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
    const opts = models.map((m) => ({
      value: m.slug,
      label: m.display_name || titleFromSlug(m.slug),
      // The catalog declares each model's efforts (supported_reasoning_levels)
      // with descriptions — the source of truth for the Effort control.
      efforts: Array.isArray(m.supported_reasoning_levels)
        ? m.supported_reasoning_levels.filter((l) => l && l.effort).map((l) => ({ effort: l.effort, description: l.description || "" }))
        : null,
    }));
    return opts.length ? opts : null;
  } catch { return null; }
}

const CODEX_MODELS = loadCodexModels() || FALLBACK_MODELS;

// --- Reasoning effort ----------------------------------------------------------
// Each model's effort list comes straight from Codex's model catalog (see
// loadCodexModels): e.g. Terra takes max + ultra, Luna stops at max, gpt-5.5 at
// xhigh. The control's `optionsFor` map gives the UI the exact list per model;
// plain `options` is the union fallback for clients that don't know optionsFor —
// run() clamps an unsupported pick down to the nearest level the selected model
// takes, so a stale saved value can't fail the turn. The ladder is only the
// clamping order plus the heuristic for models the catalog didn't describe.
const EFFORT_LADDER = ["low", "medium", "high", "xhigh", "max", "ultra"];
const EFFORT_LABELS = { low: "Light", medium: "Medium", high: "High", xhigh: "Extra High", max: "Max", ultra: "Ultra" };

function supportedEfforts(model) {
  const m = CODEX_MODELS.find((x) => x.value === model);
  if (m && m.efforts && m.efforts.length) return m.efforts;
  // Catalog didn't say (static fallback list / unknown model): 5.6 models take
  // max, Sol and Terra also ultra, everything else stops at xhigh.
  const ceiling = /^gpt-5\.6-(sol|terra)/.test(model || "") ? "ultra" : /^gpt-5\.6/.test(model || "") ? "max" : "xhigh";
  return EFFORT_LADDER.slice(0, EFFORT_LADDER.indexOf(ceiling) + 1).map((e) => ({ effort: e, description: "" }));
}

function effortOptions(model) {
  return [
    { value: "", label: "Default" },
    ...supportedEfforts(model).map((l) => ({ value: l.effort, label: EFFORT_LABELS[l.effort] || titleFromSlug(l.effort), description: l.description || undefined })),
  ];
}

function clampEffort(model, effort) {
  if (!effort) return ""; // unset -> Codex default
  const supported = supportedEfforts(model).map((l) => l.effort);
  if (supported.includes(effort)) return effort;
  for (let i = EFFORT_LADDER.indexOf(effort); i >= 0; i--) {
    if (supported.includes(EFFORT_LADDER[i])) return EFFORT_LADDER[i];
  }
  return "";
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

// The same three choices as app-server sandbox policies (per-turn overrides).
const SANDBOX_POLICY = {
  "read-only": { type: "readOnly" },
  "workspace-write": { type: "workspaceWrite" },
  "full-access": { type: "dangerFullAccess" },
};

// --- Warm sessions (app-server) -------------------------------------------------
// Instead of one `codex exec` process per TURN, a single long-lived
// `codex app-server` process (the JSON-RPC engine behind the Codex desktop app)
// hosts EVERY codex session as a thread. Each turn is a `turn/start` call; the
// process persists between turns, so per-message spawn cost disappears — and
// unlike exec, the protocol streams agent-message deltas (real typewriter) and
// takes model/effort/sandbox as per-turn overrides. The server is killed after
// config.warmTtlMs with no active turns; threads live on disk, so the next
// message just resumes. If app-server can't start (older CLI, warm disabled),
// run() falls back to the classic per-turn exec path below.
let server = null; // singleton { child, rpc, threads, sessions, ... } or null

function startServer() {
  const child = spawn("codex", ["app-server"], { shell: config.isWin, env: process.env });
  const srv = {
    child,
    alive: true,
    nextId: 0,
    pending: new Map(),  // rpc id -> { resolve, reject, timer }
    threads: new Map(),  // threadId -> { turn, sawDelta, turnId }
    sessions: new Map(), // wakili sessionId -> threadId (warm binding)
    active: 0,           // in-flight turns; 0 arms the idle kill timer
    idleTimer: null,
    ready: null,
  };
  const write = (obj) => { try { child.stdin.write(JSON.stringify(obj) + "\n"); } catch { /* dying; rpc timeouts handle it */ } };
  srv.rpc = (method, params, timeoutMs = 60000) => new Promise((resolve, reject) => {
    const id = ++srv.nextId;
    const timer = setTimeout(() => { srv.pending.delete(id); reject(new Error(`codex app-server: ${method} timed out`)); }, timeoutMs);
    timer.unref?.();
    srv.pending.set(id, { resolve, reject, timer });
    write({ jsonrpc: "2.0", id, method, params });
  });

  let buf = "";
  child.stdout.on("data", (chunk) => {
    buf += chunk.toString();
    let nl;
    while ((nl = buf.indexOf("\n")) !== -1) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      let msg;
      try { msg = JSON.parse(line); } catch { continue; }
      if (msg.id != null && msg.method) {
        // Server->client request (an approval prompt). approval_policy=never
        // should prevent these; decline anything that slips through so no
        // turn can hang waiting on a reply we'd never send.
        write({ jsonrpc: "2.0", id: msg.id, result: { decision: "denied" } });
      } else if (msg.id != null) {
        const p = srv.pending.get(msg.id);
        if (p) {
          srv.pending.delete(msg.id);
          clearTimeout(p.timer);
          msg.error ? p.reject(new Error(msg.error.message || JSON.stringify(msg.error))) : p.resolve(msg.result);
        }
      } else if (msg.method) {
        routeNotification(srv, msg.method, msg.params || {});
      }
    }
  });
  // stderr is the server's own logging (model-cache refreshes etc.), not turn
  // errors — those arrive as `error` notifications. Don't spam the phone.
  child.stderr.on("data", () => {});

  const die = () => {
    if (!srv.alive) return;
    srv.alive = false;
    clearTimeout(srv.idleTimer);
    for (const p of srv.pending.values()) { clearTimeout(p.timer); p.reject(new Error("codex app-server exited")); }
    srv.pending.clear();
    for (const [threadId, st] of srv.threads) {
      if (st.turn) { const t = st.turn; st.turn = null; if (t.onError) t.onError("codex app-server exited"); t.onEvent({ type: "result", session_id: threadId }); if (t.onClose) t.onClose(-1); }
    }
    srv.threads.clear();
    srv.sessions.clear();
    if (server === srv) server = null;
  };
  child.on("close", die);
  child.on("error", die);

  srv.ready = (async () => {
    await srv.rpc("initialize", { clientInfo: { name: "wakili", title: "Wakili", version: "0.1.0" } }, 15000);
    write({ jsonrpc: "2.0", method: "initialized", params: {} });
    return srv;
  })();
  return srv;
}

function getServer() {
  if (!server || !server.alive) server = startServer();
  return server;
}

function killServer(srv) {
  srv.alive = false; // stop reuse immediately; die() finishes the bookkeeping
  if (config.isWin && srv.child.pid) {
    try { spawn("taskkill", ["/pid", String(srv.child.pid), "/t", "/f"], { stdio: "ignore" }); return; } catch { /* fall through */ }
  }
  try { srv.child.kill(); } catch { /* already gone */ }
}

// Called on gateway shutdown so the app-server doesn't outlive it.
export function closeWarmCodex() {
  if (server && server.alive) killServer(server);
  server = null;
}

const armIdle = (srv) => {
  clearTimeout(srv.idleTimer);
  srv.idleTimer = setTimeout(() => { if (srv.active === 0) killServer(srv); }, config.warmTtlMs);
  srv.idleTimer.unref?.();
};

// "commandExecution" -> "command_execution": v2 item types are camelCase, but
// both phone clients already render the exec path's snake_case names.
const snakeCase = (s) => String(s).replace(/[A-Z]/g, (c) => "_" + c.toLowerCase());

function endTurn(srv, threadId, code) {
  const st = srv.threads.get(threadId);
  if (!st || !st.turn) return;
  const t = st.turn;
  st.turn = null;
  st.sawDelta.clear();
  srv.active = Math.max(0, srv.active - 1);
  if (srv.active === 0) armIdle(srv);
  t.onEvent({ type: "result", session_id: threadId }); // the session's resume id
  if (t.onClose) t.onClose(code);
}

// app-server notification -> Claude-shaped gateway events (same shapes the exec
// path's translate() produces, so the web and Android clients need no changes).
function routeNotification(srv, method, params) {
  const st = params.threadId ? srv.threads.get(params.threadId) : null;
  if (!st) return;
  const turn = st.turn;
  if (method === "turn/started") {
    st.turnId = params.turn && params.turn.id;
  } else if (method === "item/agentMessage/delta") {
    st.sawDelta.add(params.itemId);
    if (turn) turn.onEvent({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: params.delta } } });
  } else if (method === "item/completed") {
    const item = params.item;
    if (!item || !turn) return;
    if (item.type === "agentMessage") {
      // Already streamed via deltas; only emit whole if no delta ever arrived.
      if (!st.sawDelta.has(item.id) && typeof item.text === "string") {
        turn.onEvent({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: item.text } } });
      }
    } else if (item.type === "commandExecution") {
      const id = item.id || `codex-tool-${++toolSeq}`;
      turn.onEvent({ type: "assistant", message: { content: [{ type: "tool_use", name: "command_execution", input: { command: item.command || "" }, id }] } });
      const out = item.aggregatedOutput ?? "";
      const isError = item.exitCode != null && item.exitCode !== 0;
      if (out || isError) {
        turn.onEvent({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: id, content: String(out), is_error: isError }] } });
      }
    } else if (item.type !== "reasoning" && item.type !== "userMessage") {
      const name = item.type === "mcpToolCall" ? (item.tool || "mcp_tool_call") : snakeCase(item.type || "item");
      turn.onEvent({ type: "assistant", message: { content: [{ type: "tool_use", name, input: item, id: item.id }] } });
    }
  } else if (method === "error") {
    // willRetry errors are transient reconnect chatter; the terminal failure
    // arrives with willRetry=false (and again on turn/completed's status).
    if (!params.willRetry && turn && turn.onError) {
      turn.onError(errorText(params.error && params.error.message ? params.error.message : params.error));
    }
  } else if (method === "turn/completed") {
    const t = params.turn || {};
    if (t.status === "failed" && t.error && t.error.message && turn && turn.onError) turn.onError(errorText(t.error.message));
    endTurn(srv, params.threadId, t.status === "failed" ? 1 : 0);
  }
}

// Codex adapter. Primary path: the warm app-server above (true streaming
// deltas, per-turn model/effort/sandbox overrides). Fallback path: per-turn
// `exec --json`, which emits whole items (no token deltas) that translate()
// turns into one text_delta carrying the full reply. Both produce the same
// Claude-shaped events the gateway and UI already understand. Declares an
// `effort` control mapped to Codex's model_reasoning_effort, with a per-model
// ladder (see effortCeiling).
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
      options: CODEX_MODELS.map(({ value, label }) => ({ value, label })),
    },
    effort: {
      label: "Effort",
      default: "",
      // Union of every model's levels — the fallback for clients that don't
      // know optionsFor; run() clamps per model at spawn.
      options: [
        { value: "", label: "Default" },
        ...EFFORT_LADDER.filter((e) => CODEX_MODELS.some((m) => supportedEfforts(m.value).some((l) => l.effort === e)))
          .map((e) => ({ value: e, label: EFFORT_LABELS[e] || titleFromSlug(e) })),
      ],
      optionsFor: Object.fromEntries(CODEX_MODELS.map((m) => [m.value, effortOptions(m.value)])),
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

  run(opts) {
    if (!config.warmTtlMs) return runExec(opts); // warm sessions disabled -> classic per-turn exec
    const { text, controls = {}, resumeId, sessionId, cwd, gatewayUrl, onEvent, onError, onClose } = opts;

    // Returned before the async work below settles. kill() interrupts the
    // running turn (turn/interrupt) — never the shared server, which hosts
    // other sessions' threads too. If we fell back to exec, kill its tree.
    const handle = {
      killed: false, _srv: null, _threadId: null, _fallback: null,
      kill() {
        this.killed = true;
        if (this._fallback) {
          if (config.isWin && this._fallback.pid) { try { spawn("taskkill", ["/pid", String(this._fallback.pid), "/t", "/f"], { stdio: "ignore" }); return; } catch { /* fall through */ } }
          try { this._fallback.kill(); } catch { /* already gone */ }
          return;
        }
        const srv = this._srv, threadId = this._threadId;
        if (srv && srv.alive && threadId) {
          const st = srv.threads.get(threadId);
          if (st && st.turnId) srv.rpc("turn/interrupt", { threadId, turnId: st.turnId }, 10000).catch(() => {});
        }
      },
    };

    (async () => {
      let srv;
      try { srv = await getServer().ready; }
      catch {
        // app-server unavailable (older CLI?) -> classic per-turn exec
        handle._fallback = runExec(opts);
        if (handle.killed) handle.kill();
        return;
      }
      try {
        // Resolve this session's thread: reuse the warm binding when it matches
        // the caller's resume id; otherwise resume from disk / start fresh.
        let threadId = srv.sessions.get(sessionId);
        if (threadId !== resumeId) threadId = null; // stale binding (agent switch, restart) — never continue the wrong thread
        const threadConfig = {
          // Registers the gateway's MCP tools per thread, replacing the exec
          // path's -c TOML-string overrides with plain JSON (no shell quoting).
          mcp_servers: {
            wakili: {
              command: "node",
              args: [MCP_SERVER],
              env: { WAKILI_SESSION: sessionId, WAKILI_GATEWAY: gatewayUrl, WAKILI_TOKEN: config.token },
              default_tools_approval_mode: "approve",
            },
          },
        };
        if (!threadId && resumeId) {
          await srv.rpc("thread/resume", { threadId: resumeId, cwd: cwd || undefined, approvalPolicy: "never", config: threadConfig });
          threadId = resumeId;
        } else if (!threadId) {
          const r = await srv.rpc("thread/start", { model: controls.model || undefined, approvalPolicy: "never", cwd: cwd || undefined, config: threadConfig });
          threadId = r && r.thread && r.thread.id;
          if (!threadId) throw new Error("codex app-server: thread/start returned no thread id");
        }
        srv.sessions.set(sessionId, threadId);

        const st = srv.threads.get(threadId) || { turn: null, sawDelta: new Set(), turnId: null };
        srv.threads.set(threadId, st);
        st.turn = { onEvent, onError, onClose };
        handle._srv = srv;
        handle._threadId = threadId;
        clearTimeout(srv.idleTimer);
        srv.active++;

        // Same first-message gateway note as the exec path (see comment there).
        const prompt = resumeId ? text : `[Gateway note: ${PHONE_DIRECTIVE} These tools come from the "wakili" MCP server; if they are not in your active toolset, discover them with your tool search.]\n\n${text}`;
        const effort = clampEffort(controls.model, controls.effort || controls.reasoning || "");
        await srv.rpc("turn/start", {
          threadId,
          input: [{ type: "text", text: prompt }],
          model: controls.model || undefined,
          effort: effort || undefined,
          // Unlike exec (where sandbox is fixed at thread birth), the approval
          // choice applies per turn — including on resumed sessions.
          sandboxPolicy: SANDBOX_POLICY[controls.approval] || SANDBOX_POLICY["workspace-write"],
          approvalPolicy: "never",
        });
        if (handle.killed) handle.kill(); // stop arrived while the turn was starting
      } catch (e) {
        const srvErr = String((e && e.message) || e);
        const st = srv.threads.get(handle._threadId);
        if (st && st.turn) {
          if (st.turn.onError) st.turn.onError(srvErr);
          endTurn(srv, handle._threadId, -1);
        } else {
          if (onError) onError(srvErr);
          if (onClose) onClose(-1);
        }
      }
    })();

    return handle;
  },
};

// Classic per-turn exec path: used when warm sessions are disabled
// (WAKILI_WARM_TTL_MS=0) or the installed CLI has no usable app-server.
function runExec({ text, controls = {}, resumeId, sessionId, cwd, gatewayUrl, onEvent, onError, onClose }) {
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
}

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
