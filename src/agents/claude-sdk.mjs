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

export const claudeSdkAgent = {
  id: "claude-sdk",
  label: "Claude (SDK)",
  description: "Anthropic's Claude Code, run in-process via the official Agent SDK.",

  // Share the CLI adapter's slash commands and controls (same model list, effort,
  // thinking, permission modes) so the two agents present an identical panel.
  commands: claudeAgent.commands,
  controls: claudeAgent.controls,

  // Returns a killable handle ({ killed, kill() }, no pid) matching what the
  // server's killTree/stop expects; kill() interrupts the running query.
  run({ text, controls = {}, resumeId, sessionId, cwd, gatewayUrl, onEvent, onError, onClose }) {
    const handle = { killed: false, _q: null, kill() { this.killed = true; try { this._q && this._q.interrupt && this._q.interrupt(); } catch { /* already done */ } } };

    (async () => {
      try {
        if (!sdkQuery) ({ query: sdkQuery } = await import("@anthropic-ai/claude-agent-sdk"));

        const keyword = THINKING[controls.thinking] || "";
        const prompt = keyword ? `${text}\n\n${keyword}` : text;

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

        const q = sdkQuery({ prompt, options });
        handle._q = q;
        for await (const msg of q) {
          if (handle.killed) break;
          onEvent(msg); // SDK messages share the CLI's shape; runTurn handles them
        }
      } catch (e) {
        onError && onError(String((e && (e.stack || e.message)) || e));
      } finally {
        onClose && onClose();
      }
    })();

    return handle;
  },
};
