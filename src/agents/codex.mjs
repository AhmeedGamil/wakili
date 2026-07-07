import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { config, ROOT } from "../config.mjs";
import { PHONE_DIRECTIVE } from "./claude.mjs";

// --- Model discovery ---------------------------------------------------------
// Codex keeps a fresh model list on disk (fetched from OpenAI, revalidated by
// etag — see fetched_at/etag in the file), so we read it instead of hardcoding.
// Only "list"-visibility entries are the models Codex offers users; hidden ones
// (e.g. "Codex Auto Review") are skipped. `priority` gives the display order.
// Falls back to a small static list if the cache is missing or unreadable. Read
// once at startup — a server restart picks up any models Codex has since fetched.
const CODEX_MODELS_CACHE = path.join(os.homedir(), ".codex", "models_cache.json");

const FALLBACK_MODELS = [
  { value: "gpt-5.5", label: "GPT-5.5" },
  { value: "gpt-5.4", label: "GPT-5.4" },
  { value: "gpt-5.4-mini", label: "GPT-5.4-Mini" },
];

// "gpt-5.5" -> "GPT-5.5" (only used if a model has no display_name).
function titleFromSlug(slug) {
  return String(slug).split("-").map((p) => (/^gpt$/i.test(p) ? "GPT" : p.charAt(0).toUpperCase() + p.slice(1))).join("-");
}

function loadCodexModels() {
  try {
    const data = JSON.parse(fs.readFileSync(CODEX_MODELS_CACHE, "utf8"));
    const models = (Array.isArray(data && data.models) ? data.models : [])
      .filter((m) => m && m.slug && m.visibility === "list")
      .sort((a, b) => (a.priority ?? 999) - (b.priority ?? 999));
    const opts = models.map((m) => ({ value: m.slug, label: m.display_name || titleFromSlug(m.slug) }));
    return opts.length ? opts : null;
  } catch { return null; }
}

const CODEX_MODELS = loadCodexModels() || FALLBACK_MODELS;

// --- Slash commands ----------------------------------------------------------
// Codex has no exec-usable command list to probe (its TUI slash commands like
// /model, /review don't apply under `codex exec`). The real analog to Claude's
// custom commands is Codex's custom prompts: markdown files in ~/.codex/prompts
// (and <cwd>/.codex/prompts), invoked as /name. We surface those, with their
// frontmatter `description` (or first body line) as the hint.
function describePrompt(file) {
  try {
    const txt = fs.readFileSync(file, "utf8");
    const fm = /^---\s*\r?\n([\s\S]*?)\r?\n---/.exec(txt);
    if (fm) {
      const d = /^\s*description:\s*(.+)$/im.exec(fm[1]);
      if (d) return d[1].trim().replace(/^["']|["']$/g, "").slice(0, 80);
    }
    const body = txt.replace(/^---[\s\S]*?---/, "").trim().split(/\r?\n/)[0] || "";
    return (body || "Custom prompt").slice(0, 80);
  } catch { return "Custom prompt"; }
}

function listCommands() {
  const out = new Map(); // project prompts override user prompts of the same name
  for (const dir of [path.join(os.homedir(), ".codex", "prompts"), path.join(process.cwd(), ".codex", "prompts")]) {
    let files;
    try { files = fs.readdirSync(dir, { recursive: true }); } catch { continue; }
    for (const rel of files) {
      const r = String(rel);
      if (!r.endsWith(".md")) continue;
      const name = path.basename(r, ".md");
      out.set(name, describePrompt(path.join(dir, r)));
    }
  }
  return [...out].map(([name, desc]) => ({ name, desc }));
}

// --- MCP registration (send_to_user + ask_options) ----------------------------
// The same MCP server the Claude adapters use, registered per spawn with `-c`
// config overrides — nothing is ever written to the developer's
// ~/.codex/config.toml, matching how the Claude CLI gets --mcp-config.
//
// Unlike Claude Code, Codex spawns MCP servers with a MINIMAL whitelisted
// environment (PATH, HOME, ...): custom env vars on the codex child are
// stripped, so the per-turn context (session id, gateway URL, token) must be
// declared explicitly via mcp_servers.<name>.env — otherwise mcp-tools.mjs
// posts to the gateway with an empty session/token and the delivery 401s.
//
// TOML values use single-quoted (literal) strings: on Windows the child runs
// through cmd.exe (shell: true), which — like Codex's own argv parsing — eats
// double quotes but passes single quotes through. An override containing
// spaces is additionally wrapped in double quotes there so it stays one argument.
const MCP_SERVER = path.join(ROOT, "src", "mcp-tools.mjs").replace(/\\/g, "/");
function mcpOverrides({ sessionId, gatewayUrl }) {
  const wrap = (v) => (config.isWin && /\s/.test(v) ? `"${v}"` : v);
  return [
    "-c", wrap("mcp_servers.wakili.command='node'"),
    "-c", wrap(`mcp_servers.wakili.args=['${MCP_SERVER}']`),
    "-c", wrap(`mcp_servers.wakili.env.WAKILI_SESSION='${sessionId}'`),
    "-c", wrap(`mcp_servers.wakili.env.WAKILI_GATEWAY='${gatewayUrl}'`),
    "-c", wrap(`mcp_servers.wakili.env.WAKILI_TOKEN='${config.token}'`),
    // Pre-approve our tools: under approval_policy=never (how every headless
    // turn runs) codex auto-DENIES tool calls that would need an approval
    // prompt ("user cancelled MCP tool call") — these two are phone-facing and
    // safe, same reasoning as the Claude settings pre-allowing send_to_user.
    "-c", wrap("mcp_servers.wakili.default_tools_approval_mode='approve'"),
  ];
}

// Map the phone's approval choice to Codex's sandbox / approval flags.
//   read-only      -> "Ask for approval": Codex can only read; risky actions are held back
//   workspace-write -> "Approve for me":  auto-approve edits/commands inside the workspace
//   full-access    -> "Full access":      no sandbox, no approvals
const APPROVAL = {
  "read-only": ["-s", "read-only", "-c", "approval_policy=never"],
  "workspace-write": ["-s", "workspace-write", "-c", "approval_policy=never"],
  "full-access": ["--dangerously-bypass-approvals-and-sandbox"],
};

// Codex adapter. Codex's `exec --json` emits whole items (no token deltas), so
// we translate its events into the same Claude-shaped events the gateway and UI
// already understand — one text_delta carrying the full reply (the client's
// typewriter still reveals it smoothly). Declares its own native control:
// `reasoning` (Codex's name), not Claude's `effort`/`thinking`.
//
// Codex gets the gateway's MCP tools (send_to_user for file delivery,
// ask_options for tappable questions) via per-spawn overrides — see
// mcpOverrides above. Tool approvals still follow Codex's own sandbox model
// (the `approval` control), not the phone's permission cards.

export const codexAgent = {
  id: "codex",
  label: "Codex",
  description: "OpenAI's Codex CLI, run by spawning it and streaming its output.",

  // Discovered fresh each manifest request so newly added custom prompts appear.
  commands: listCommands,

  controls: {
    model: {
      label: "Model",
      // Read from Codex's own model cache at startup (see loadCodexModels above).
      default: CODEX_MODELS[0].value,
      options: CODEX_MODELS,
    },
    reasoning: {
      label: "Reasoning",
      default: "",
      options: [
        { value: "", label: "Default" },
        { value: "low", label: "Light" },
        { value: "medium", label: "Medium" },
        { value: "high", label: "High" },
        { value: "xhigh", label: "Extra High" },
      ],
    },
    // Codex's approval/sandbox posture (Codex's own three modes). Applied on a
    // fresh session; a resumed session inherits the sandbox it was created with.
    approval: {
      label: "Approval",
      default: "workspace-write",
      options: [
        { value: "read-only", label: "Ask for approval" },
        { value: "workspace-write", label: "Approve for me" },
        { value: "full-access", label: "Full access" },
      ],
    },
  },

  run({ text, controls = {}, resumeId, sessionId, cwd, gatewayUrl, onEvent, onError, onClose }) {
    const args = ["exec"];
    if (resumeId) args.push("resume", resumeId);
    args.push("--json", "--skip-git-repo-check");
    // resume inherits the session's sandbox; a fresh session takes the chosen mode
    if (!resumeId) args.push(...(APPROVAL[controls.approval] || APPROVAL["workspace-write"]));
    if (controls.model) args.push("-m", controls.model);
    if (controls.reasoning) args.push("-c", `model_reasoning_effort=${controls.reasoning}`);
    args.push(...mcpOverrides({ sessionId, gatewayUrl })); // phone tools (send_to_user / ask_options), this run only
    args.push("-"); // read the prompt from stdin

    // Codex (0.139+) defers MCP tools behind its tool-search layer: the model
    // does NOT see them (or their descriptions) upfront, so unlike the Claude
    // adapters the directives can't ride on mcp-tools.mjs alone. Instead the
    // gateway note goes in ONCE per thread — the first message stays in the
    // thread's history, so resumed turns keep the instruction without it being
    // re-sent on every prompt.
    const prompt = resumeId ? text : `[Gateway note: ${PHONE_DIRECTIVE} These tools come from the "wakili" MCP server; if they are not in your active toolset, discover them with your tool search.]\n\n${text}`;

    const child = spawn("codex", args, {
      shell: config.isWin,
      cwd: cwd || undefined,
      // NOTE: codex does NOT forward these to the MCP servers it spawns (it
      // launches them with a whitelisted env) — the MCP server gets its context
      // via the mcp_servers.wakili.env overrides above. Kept on the child
      // itself for anything codex runs directly (shell commands, hooks).
      env: {
        ...process.env,
        WAKILI_SESSION: sessionId,
        WAKILI_GATEWAY: gatewayUrl,
        WAKILI_TOKEN: config.token,
      },
    });

    let buf = "";
    child.stdout.on("data", (chunk) => {
      buf += chunk.toString();
      let nl;
      while ((nl = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line) continue;
        let evt;
        try { evt = JSON.parse(line); } catch { continue; }
        translate(evt, onEvent);
      }
    });
    child.stderr.on("data", (d) => onError && onError(d.toString()));
    let closed = false;
    const closeOnce = (code) => { if (!closed) { closed = true; onClose && onClose(code); } };
    child.on("close", closeOnce);
    // A failed spawn (bad cwd, missing binary) emits 'error', not 'close'. Without
    // this handler that error is unhandled and takes down the whole gateway.
    child.on("error", (e) => {
      if (onError) onError(`failed to start codex: ${e.message}`);
      closeOnce(-1);
    });

    try {
      child.stdin.write(prompt);
      child.stdin.end();
    } catch { /* surfaced via the error handler above */ }
    return child;
  },
};

// Codex event -> Claude-shaped gateway event.
let toolSeq = 0; // fallback tool_use id when a codex item carries none
function translate(evt, onEvent) {
  if (evt.type === "thread.started" && evt.thread_id) {
    // carry the resume id the same way Claude's `result.session_id` does
    onEvent({ type: "result", session_id: evt.thread_id });
    return;
  }
  if (evt.type === "item.completed" && evt.item) {
    const item = evt.item;
    if (item.type === "agent_message" && typeof item.text === "string") {
      onEvent({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: item.text } } });
    } else if (item.type === "command_execution") {
      // Shell runs render like a Bash card (matching the Android app): the
      // command alone as the input — so it shows in the card header — and the
      // captured output delivered as a tool_result so it attaches to the card
      // body, error-tinted when the command failed.
      const id = item.id || `codex-tool-${++toolSeq}`;
      onEvent({ type: "assistant", message: { content: [{ type: "tool_use", name: item.type, input: { command: item.command || "" }, id }] } });
      const out = item.aggregated_output ?? item.output ?? "";
      const isError = item.exit_code != null && item.exit_code !== 0;
      if (out || isError) {
        onEvent({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: id, content: String(out), is_error: isError }] } });
      }
    } else if (item.type !== "reasoning") {
      // file edits, web searches, etc. -> show as a tool chip. MCP calls carry
      // the real tool name (send_to_user, ask_options) — surface that, not
      // the generic "mcp_tool_call".
      const name = item.type === "mcp_tool_call" ? (item.tool || item.type) : (item.type || "item");
      onEvent({ type: "assistant", message: { content: [{ type: "tool_use", name, input: item, id: item.id }] } });
    }
  }
}
