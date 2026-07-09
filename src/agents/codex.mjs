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

// --- Reasoning effort ----------------------------------------------------------
// The GPT-5.6 tiers grew Codex's effort ladder: every 5.6 model takes `max`,
// and Sol + Terra additionally take `ultra` (multi-agent orchestration mode).
// Older models stop at xhigh. The control's `optionsFor` map gives the UI the
// exact list per model; plain `options` stays as the union fallback for clients
// that don't know optionsFor — run() clamps an unsupported pick down to the
// selected model's ceiling so a stale saved value can't fail the turn.
const EFFORT_LADDER = ["low", "medium", "high", "xhigh", "max", "ultra"];
const EFFORT_LABELS = { low: "Light", medium: "Medium", high: "High", xhigh: "Extra High", max: "Max", ultra: "Ultra" };

function effortCeiling(model) {
  if (/^gpt-5\.6-(sol|terra)/.test(model || "")) return "ultra";
  if (/^gpt-5\.6/.test(model || "")) return "max";
  return "xhigh";
}

function effortOptions(ceiling) {
  const cut = EFFORT_LADDER.indexOf(ceiling) + 1;
  return [{ value: "", label: "Default" }, ...EFFORT_LADDER.slice(0, cut).map((v) => ({ value: v, label: EFFORT_LABELS[v] }))];
}

function clampEffort(model, effort) {
  const idx = EFFORT_LADDER.indexOf(effort);
  if (idx === -1) return ""; // unset or unknown -> Codex default
  return EFFORT_LADDER[Math.min(idx, EFFORT_LADDER.indexOf(effortCeiling(model)))];
}

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
// typewriter still reveals it smoothly). Declares an `effort` control mapped to
// Codex's model_reasoning_effort, with a per-model ladder (see effortCeiling).
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
    effort: {
      label: "Effort",
      default: "",
      options: effortOptions("ultra"), // union fallback; run() clamps per model
      optionsFor: Object.fromEntries(CODEX_MODELS.map((m) => [m.value, effortOptions(effortCeiling(m.value))])),
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
    // `controls.reasoning` is the control's pre-rename key — sessions saved
    // before the rename still carry it.
    const effort = clampEffort(controls.model, controls.effort || controls.reasoning || "");
    if (effort) args.push("-c", `model_reasoning_effort=${effort}`);
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

    // A failed turn emits BOTH {type:"error"} and {type:"turn.failed"} with the
    // same message (e.g. "model requires a newer version of Codex") — without
    // surfacing it the phone just shows a blank reply. Report it once.
    let lastError = null;
    const reportError = (raw) => {
      const msg = errorText(raw);
      if (!msg || msg === lastError) return;
      lastError = msg;
      onError && onError(msg);
    };

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
        translate(evt, onEvent, reportError);
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

// Codex error payloads wrap the API's JSON error as a string, e.g.
// '{"type":"error","status":400,"error":{"message":"The model requires…"}}' —
// dig out the human-readable message when present, otherwise pass through.
function errorText(raw) {
  const s = String(raw ?? "").trim();
  try {
    const j = JSON.parse(s);
    return String(j?.error?.message || j?.message || s);
  } catch { return s; }
}

// Codex event -> Claude-shaped gateway event.
let toolSeq = 0; // fallback tool_use id when a codex item carries none
function translate(evt, onEvent, onError) {
  if (evt.type === "thread.started" && evt.thread_id) {
    // carry the resume id the same way Claude's `result.session_id` does
    onEvent({ type: "result", session_id: evt.thread_id });
    return;
  }
  // Fatal errors ({type:"error"}) and failed turns ({type:"turn.failed"}) both
  // carry the reason the turn produced no reply — surface it to the phone.
  if (evt.type === "error") { onError(evt.message); return; }
  if (evt.type === "turn.failed") { onError(evt.error && evt.error.message); return; }
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
