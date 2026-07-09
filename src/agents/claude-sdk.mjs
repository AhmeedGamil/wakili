// Alternative Claude adapter built on the official Agent SDK
// (@anthropic-ai/claude-agent-sdk) instead of spawning the `claude` CLI and
// parsing its stdout. Registered ALONGSIDE the CLI adapter (id "claude"), so the
// phone's agent picker can choose either "Claude" (CLI) or "Claude (SDK)".
//
// Why it's cleaner than the CLI wrapper:
//   - Permissions run in-process (a PreToolUse hook callback for gated tools,
//     `canUseTool` for the rest) instead of a hook subprocess — but we still
//     route every decision to the SAME gateway endpoint (/internal/permission),
//     so all the existing mode / remembered-tool / phone-card logic is reused
//     untouched.
//   - The SDK yields messages in the same shape the CLI streams (assistant /
//     user / stream_event / result), so runTurn's event handling works as-is —
//     we just forward each message to onEvent.
//
// The SDK is imported lazily inside run(): if the package isn't installed the
// CLI adapter still works and only a "Claude (SDK)" turn fails (gracefully).

import http from "node:http";
import path from "node:path";
import { config, ROOT } from "../config.mjs";
import { claudeAgent, THINKING, PHONE_DIRECTIVE } from "./claude.mjs";

const MCP_SERVER = path.join(ROOT, "src", "mcp-tools.mjs").replace(/\\/g, "/");

// Ask the gateway's own permission endpoint (same one the CLI hook calls), so the
// SDK path reuses every permission behaviour: auto-approve modes, remembered
// tools, and the phone's allow/deny card. Denies on any error/timeout.
function askPermission(gatewayUrl, sessionId, tool, input) {
  return new Promise((resolve) => {
    let u;
    try { u = new URL(gatewayUrl + "/internal/permission"); }
    catch { return resolve({ decision: "deny", reason: null }); }
    const payload = JSON.stringify({ sessionId, tool, input });
    const req = http.request(
      {
        hostname: u.hostname, port: u.port, path: u.pathname, method: "POST",
        headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload), "x-auth-token": config.token },
      },
      (res) => {
        let b = "";
        res.on("data", (c) => (b += c));
        res.on("end", () => {
          try { const j = JSON.parse(b); resolve({ decision: j.decision === "allow" ? "allow" : "deny", reason: j.reason || null }); }
          catch { resolve({ decision: "deny", reason: null }); }
        });
      }
    );
    req.on("error", () => resolve({ decision: "deny", reason: null }));
    req.setTimeout(125000, () => { req.destroy(); resolve({ decision: "deny", reason: null }); });
    req.write(payload);
    req.end();
  });
}

let sdkQuery = null; // cached SDK query() after first lazy import

// --- Warm sessions -------------------------------------------------------------
// One SDK query per SESSION, not per turn. The query is started in streaming-
// input mode (its prompt is an async iterable that stays pending between turns)
// and kept alive; each new message is pushed into the channel, skipping the
// SDK's per-turn startup. Recycled when spawn-time options change (model /
// effort / permission mode / cwd) and closed after config.warmTtlMs idle — the
// next message cold-starts with resume, losing nothing. Reuse also requires the
// caller's resumeId to be the thread the query is already on (guards agent
// switches, which null it).
const warm = new Map(); // sessionId -> { channel, q, key, threadId, turn, idleTimer, alive, handle }

// Async-iterable push channel — the streaming-input prompt. push() delivers the
// next user message; close() ends the stream, letting the query finish cleanly.
function messageChannel() {
  const queue = [];
  let notify = null, done = false;
  const wake = () => { if (notify) { const n = notify; notify = null; n(); } };
  return {
    push(m) { queue.push(m); wake(); },
    close() { done = true; wake(); },
    async *[Symbol.asyncIterator]() {
      while (true) {
        while (queue.length) yield queue.shift();
        if (done) return;
        await new Promise((r) => { notify = r; });
      }
    },
  };
}

function userMessage(text) {
  return { type: "user", message: { role: "user", content: [{ type: "text", text }] }, parent_tool_use_id: null };
}

function disposeWarm(entry) {
  clearTimeout(entry.idleTimer);
  entry.alive = false;
  entry.channel.close();
  try { entry.q && entry.q.interrupt && entry.q.interrupt(); } catch { /* already done */ }
}

// Called on gateway shutdown so warm queries don't outlive it.
export function closeWarmSdk() {
  for (const e of warm.values()) disposeWarm(e);
  warm.clear();
}

export const claudeSdkAgent = {
  id: "claude-sdk",
  label: "Claude (SDK)",
  description: "Anthropic's Claude Code, run in-process via the official Agent SDK.",

  // Share the CLI adapter's slash commands and controls (same model list, effort,
  // thinking, permission modes) so the two agents present an identical panel.
  commands: claudeAgent.commands,
  controls: claudeAgent.controls,

  // Returns a killable handle ({ killed, kill() }, no pid) matching what the
  // server's killTree/stop expects; kill() discards the whole warm session
  // (matching the CLI adapter, where stop kills the process) — the turn's
  // onClose fires as the reader unwinds, and the next message resumes cold.
  run({ text, controls = {}, resumeId, sessionId, cwd, gatewayUrl, onEvent, onError, onClose }) {
    const keyword = THINKING[controls.thinking] || "";
    const prompt = keyword ? `${text}\n\n${keyword}` : text;

    // Reuse the session's warm query when its spawn-time options still match
    // and it's sitting on the thread the caller wants to continue.
    const key = JSON.stringify([controls.model || "", controls.effort || "", controls.permissionMode === "plan" ? "plan" : "default", cwd || ""]);
    const existing = warm.get(sessionId);
    if (existing) {
      if (existing.alive && !existing.turn && existing.key === key && resumeId === existing.threadId) {
        clearTimeout(existing.idleTimer);
        existing.turn = { onEvent, onError, onClose };
        existing.channel.push(userMessage(prompt));
        return existing.handle;
      }
      disposeWarm(existing);
      warm.delete(sessionId);
    }

    const channel = messageChannel();
    const entry = { channel, q: null, key, threadId: resumeId || null, turn: { onEvent, onError, onClose }, idleTimer: null, alive: true, handle: null };
    entry.handle = {
      killed: false,
      kill() { this.killed = true; if (warm.get(sessionId) === entry) warm.delete(sessionId); disposeWarm(entry); },
    };
    warm.set(sessionId, entry);

    // A turn ends on the SDK's `result` message (the query stays open waiting
    // for the next push) or when the stream unwinds, whichever comes first.
    const endTurn = () => {
      const t = entry.turn;
      entry.turn = null;
      if (entry.alive) {
        entry.idleTimer = setTimeout(() => { if (warm.get(sessionId) === entry) warm.delete(sessionId); disposeWarm(entry); }, config.warmTtlMs);
        entry.idleTimer.unref?.();
      }
      if (t && t.onClose) t.onClose();
    };

    (async () => {
      try {
        if (!sdkQuery) ({ query: sdkQuery } = await import("@anthropic-ai/claude-agent-sdk"));

        const options = {
          cwd: cwd || undefined,
          includePartialMessages: true, // emit stream_event deltas (smooth typewriter)
          // Plan mode changes the agent loop; every other mode is enforced centrally
          // by /internal/permission via canUseTool, so we leave the SDK in "default".
          permissionMode: controls.permissionMode === "plan" ? "plan" : "default",
          disallowedTools: ["AskUserQuestion"], // force questions through the ask_options MCP tool
          // Gated tools go through a hook, not canUseTool, for the same reason the
          // CLI adapter uses one: the SDK loads the user's settings allowlists
          // (settingSources defaults to all), and a tool those rules allow never
          // reaches canUseTool — the gateway would neither show a card nor publish
          // the compensating tool chip, leaving the action invisible in the chat
          // until the session is reopened. A hook fires unconditionally.
          hooks: {
            PreToolUse: [{
              matcher: "Bash|Write|Edit|MultiEdit|NotebookEdit",
              // Seconds. Must outlive askPermission's 125s wait, or an unanswered
              // card would be cut off before the gateway's own timeout decides.
              timeout: 130,
              hooks: [async (hookInput) => {
                const { decision, reason } = await askPermission(gatewayUrl, sessionId, hookInput.tool_name, hookInput.tool_input);
                return {
                  hookSpecificOutput: {
                    hookEventName: "PreToolUse",
                    permissionDecision: decision,
                    permissionDecisionReason: reason || (decision === "allow" ? "Approved on phone" : "Denied on phone (or timed out)"),
                  },
                };
              }],
            }],
          },
          systemPrompt: { type: "preset", preset: "claude_code", append: PHONE_DIRECTIVE },
          // Reuse the existing stdio MCP server (send_to_user + ask_options); it
          // needs the per-turn session/gateway/token via env, like the CLI path.
          mcpServers: {
            wakili: {
              type: "stdio", command: "node", args: [MCP_SERVER],
              env: { ...process.env, WAKILI_SESSION: sessionId, WAKILI_GATEWAY: gatewayUrl, WAKILI_TOKEN: config.token },
            },
          },
          canUseTool: async (toolName, input) => {
            // Our own MCP tools handle their phone interaction internally — never gate them.
            if (toolName.startsWith("mcp__wakili__")) return { behavior: "allow", updatedInput: input };
            const { decision, reason } = await askPermission(gatewayUrl, sessionId, toolName, input);
            if (decision === "allow") return { behavior: "allow", updatedInput: input };
            return { behavior: "deny", message: reason || "Denied on phone (or timed out)" };
          },
        };
        if (controls.model) options.model = controls.model;
        if (controls.effort) options.effort = controls.effort;
        if (resumeId) options.resume = resumeId;

        channel.push(userMessage(prompt)); // the turn that started this query
        const q = sdkQuery({ prompt: channel, options });
        entry.q = q;
        for await (const msg of q) {
          if (!entry.alive) break;
          if (msg.type === "result" && msg.session_id) entry.threadId = msg.session_id; // the thread this query now carries
          const t = entry.turn;
          if (t) t.onEvent(msg); // SDK messages share the CLI's shape; runTurn handles them
          if (msg.type === "result") endTurn(); // turn done; query stays open for the next push
        }
      } catch (e) {
        const t = entry.turn;
        if (t && t.onError) t.onError(String((e && (e.stack || e.message)) || e));
      } finally {
        entry.alive = false;
        clearTimeout(entry.idleTimer);
        if (warm.get(sessionId) === entry) warm.delete(sessionId);
        if (entry.turn) endTurn(); // stream ended mid-turn (interrupt or crash)
      }
    })();

    return entry.handle;
  },
};
