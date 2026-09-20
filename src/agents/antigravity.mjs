import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { config, ROOT } from "../config.mjs";

// Antigravity adapter. Runs Google's Antigravity CLI (agy) headless, one print-
// mode process per turn, speaking its NDJSON protocol in both directions
// (agy >= 1.2): the prompt goes in on stdin (`--input-format stream-json`), so
// it never rides argv — no Windows 32K command-line ceiling, nothing for other
// local processes to read off the process list — and the turn comes back as
// typed events (`--output-format stream-json`): real text deltas, tool steps
// with their output, and the conversation id up front in `init`.
// Conversations resume across turns via `--conversation <id>`, which the CLI
// persists on disk (~/.gemini/antigravity-cli), surviving gateway restarts.

// The CLI binary. The release archive unpacks to ~/.antigravity/bin and is not
// put on PATH; the Docker image installs it to /usr/local/bin. WAKILI_AGY_BIN
// overrides for a differently-placed install.
function findBinary() {
  if (process.env.WAKILI_AGY_BIN) return process.env.WAKILI_AGY_BIN;
  const exe = config.isWin ? ".exe" : "";
  const candidates = [
    path.join(os.homedir(), ".antigravity", "bin", `antigravity${exe}`),
    path.join(os.homedir(), ".antigravity", "bin", `agy${exe}`),
    ...(config.isWin ? [] : ["/usr/local/bin/agy", "/usr/local/bin/antigravity"]),
  ];
  return candidates.find((p) => { try { return fs.statSync(p).isFile(); } catch { return false; } }) || null;
}
const AGY_BIN = findBinary();

/** False when the CLI isn't installed here — the registry then leaves the agent
 *  out of the manifest instead of offering a tab whose every turn would fail. */
export const antigravityAvailable = !!AGY_BIN;

// --- Model discovery ---------------------------------------------------------
// `agy models` prints one model per line as "slug<TAB>Display name" (older
// builds: the display name alone, which `--model` also accepts). Effort tiers
// are baked into the models themselves ("… Flash (High)"), so there is no
// separate effort control. Probed at startup (needs auth); until a probe
// succeeds the picker shows this static list.
const FALLBACK_MODELS = [
  { value: "gemini-3.8-flash-medium", label: "Gemini 3.8 Flash (Medium)" },
  { value: "gemini-3.8-flash-high", label: "Gemini 3.8 Flash (High)" },
  { value: "gemini-3.8-flash-low", label: "Gemini 3.8 Flash (Low)" },
  { value: "gemini-3.1-pro-high", label: "Gemini 3.1 Pro (High)" },
  { value: "gemini-3.1-pro-low", label: "Gemini 3.1 Pro (Low)" },
  { value: "claude-sonnet-4-6", label: "Claude Sonnet 4.6 (Thinking)" },
  { value: "claude-opus-4-6-thinking", label: "Claude Opus 4.6 (Thinking)" },
  { value: "gpt-oss-120b-medium", label: "GPT-OSS 120B (Medium)" },
];

function parseModels(out) {
  return out.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)
    // progress/auth chatter ("Fetching available models...", sign-in prose)
    // shares stdout on some builds — never parse it as a model
    .filter((s) => !/\.\.\.$|error|sign in|log in|https?:\/\//i.test(s))
    .map((line) => {
      const [value, label] = line.split("\t").map((s) => s.trim());
      return { value, label: label || value };
    });
}

function probeModels() {
  return new Promise((resolve) => {
    let child, out = "";
    // stdin must be closed ("ignore"): agy blocks forever on an open stdin
    // pipe in models mode.
    try { child = spawn(AGY_BIN, ["models"], { stdio: ["ignore", "pipe", "ignore"] }); }
    catch { return resolve(null); }
    const done = () => { clearTimeout(timer); const opts = parseModels(out); resolve(opts.length ? opts : null); };
    const timer = setTimeout(() => { try { child.kill(); } catch { /* gone */ } }, 30000);
    child.stdout.on("data", (d) => { out += d.toString(); });
    child.on("error", done);
    child.on("close", done);
  });
}

// The first probe after a restart can be slow or fail outright (cold token
// refresh, network flake) — and a silent fallback would hide models until the
// next restart. So retry in the background until one probe succeeds; a success
// rewrites the options in place and the manifest (fetched fresh by the UI)
// picks them up. Never awaited by the server — startup must not block on this.
async function refreshModels() {
  for (let attempt = 0; attempt < 5; attempt++) {
    const opts = await probeModels();
    if (opts) {
      antigravityAgent.controls.model.options = opts;
      // keep the default on a mid-tier model when the list still has it
      antigravityAgent.controls.model.default = (opts.find((o) => o.value === FALLBACK_MODELS[0].value) || opts[0]).value;
      return;
    }
    await new Promise((r) => { const t = setTimeout(r, 20000); t.unref?.(); });
  }
}

// --- MCP registration (send_to_user + ask_options) ----------------------------
// agy reads MCP servers from the user-level ~/.gemini/config/mcp_config.json —
// it has no per-spawn override (unlike claude's --mcp-config or codex's -c). So
// the wakili entry is MERGED into that file at startup, preserving whatever
// servers the user configured themselves. The entry carries NO secrets: the
// per-turn context (session id, gateway URL, token) reaches mcp-tools.mjs
// through the environment of the agy child, which agy's MCP servers inherit.
// A user running agy standalone sees the two tools as well; without that
// environment they register but deliveries fail politely.
const MCP_SERVER = path.join(ROOT, "src", "mcp-tools.mjs").replace(/\\/g, "/");
const MCP_CONFIG = path.join(os.homedir(), ".gemini", "config", "mcp_config.json");
const AGY_SETTINGS = path.join(os.homedir(), ".gemini", "antigravity-cli", "settings.json");
const MCP_ALLOW_RULE = "mcp(wakili/*)";

// Read-modify-write one of agy's user-level JSON files. Missing file: start
// from {}. Unparseable (agy tolerates comments and trailing commas, JSON.parse
// doesn't): leave the user's file alone rather than clobber it — the agent then
// just runs without the phone tools. `edit` returns false when nothing changed.
function mergeJson(file, edit) {
  let cfg = {};
  try {
    let raw = fs.readFileSync(file, "utf8");
    if (raw.charCodeAt(0) === 0xfeff) raw = raw.slice(1); // Windows editors save it with a BOM
    if (raw.trim()) cfg = JSON.parse(raw);
  } catch (e) {
    if (e.code !== "ENOENT") return;
  }
  if (!cfg || typeof cfg !== "object" || Array.isArray(cfg)) return;
  if (edit(cfg) === false) return;
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(cfg, null, 2));
  } catch { /* agy just won't have the phone tools */ }
}

function registerMcp() {
  mergeJson(MCP_CONFIG, (cfg) => {
    const servers = cfg.mcpServers && typeof cfg.mcpServers === "object" ? cfg.mcpServers : (cfg.mcpServers = {});
    const wanted = { command: "node", args: [MCP_SERVER] };
    if (JSON.stringify(servers.wakili) === JSON.stringify(wanted)) return false;
    servers.wakili = wanted;
  });
  // Headless agy cannot prompt, so an MCP call outside "Full access" is
  // auto-denied — and a denial ENDS the turn with an empty reply. Pre-allow
  // exactly our two phone-facing tools (same reasoning as the Claude settings
  // pre-allowing send_to_user and codex's default_tools_approval_mode).
  mergeJson(AGY_SETTINGS, (cfg) => {
    const perms = cfg.permissions && typeof cfg.permissions === "object" ? cfg.permissions : (cfg.permissions = {});
    const allow = Array.isArray(perms.allow) ? perms.allow : (perms.allow = []);
    if (allow.includes(MCP_ALLOW_RULE)) return false;
    allow.push(MCP_ALLOW_RULE);
  });
}

// agy never injects an MCP server's `instructions` into the model context, and
// it reaches MCP tools through one dispatcher tool (call_mcp_tool) whose server
// and tool names the model would otherwise have to go and discover — it spent
// its first steps listing ~/.gemini/antigravity-cli/mcp to find them. So the
// phone directive names the exact call.
const PHONE_DIRECTIVE =
  "The user chats from a phone through the Wakili gateway: they cannot open local file paths, " +
  "and a question written as plain prose gives them nothing to tap. Two tools on the MCP server " +
  "\"wakili\" cover this — call them directly with call_mcp_tool (ServerName \"wakili\"), no need to look them up first:\n" +
  "- ToolName \"send_to_user\", Arguments {\"path\": \"<absolute path>\", \"caption\": \"<optional>\"}: whenever you create, " +
  "build, generate, screenshot or otherwise end up with a file the user would want, deliver it proactively — never just print the path.\n" +
  "- ToolName \"ask_options\", Arguments {\"questions\": [{\"question\": \"…\", \"options\": [\"…\", \"…\"]}]}: whenever you need the " +
  "user to choose, confirm or decide between options. It shows tappable buttons and waits for the answer.";

// Map the phone's approval choice to agy's mode flags. Headless agy never
// prompts, and there is no hook to bridge its prompts to the phone's permission
// cards: an action the mode doesn't cover is auto-denied, which ends the turn
// (see deniedNotice). agy has no middle "commands inside the workspace" tier —
// its --sandbox flag doesn't lift the prompt either (verified on 1.2.5).
//   plan          -> read-only planning mode
//   accept-edits  -> file edits auto-approved; shell commands denied
//   full-access   -> auto-approve every tool permission request
const APPROVAL = {
  "plan": ["--mode", "plan"],
  "accept-edits": ["--mode", "accept-edits"],
  "full-access": ["--dangerously-skip-permissions"],
};

// agy step -> the Claude-shaped tool events both clients already render.
// Shell runs reuse codex's `command_execution` card (command in the header,
// output in the body); MCP calls surface the real tool name (send_to_user,
// ask_options — the gateway turns the latter into its question card, not a
// chip); everything else shows as a chip under agy's own tool name, with its
// PascalCase parameters renamed to the keys the clients' card headers read
// (file_path / path / pattern / query / url). The names stay agy's own rather
// than Read/Edit/Grep: the stream carries no file contents or diffs, and the
// clients derive line-count badges from those for Claude's tools.
const PARAM_KEYS = {
  AbsolutePath: "file_path", TargetFile: "file_path",
  DirectoryPath: "path", SearchDirectory: "path", SearchPath: "path",
  Pattern: "pattern", Query: "pattern",
  Url: "url",
};
function toolUse(step, id) {
  const info = step.tool_info || {};
  const params = info.parameters || {};
  const name = step.tool_name || info.name || "tool";
  if (name === "run_command") return { type: "tool_use", name: "command_execution", input: { command: params.CommandLine || params.command || "" }, id };
  if (name === "call_mcp_tool") {
    const tool = params.ToolName || params.tool_name || params.toolName || params.name;
    if (tool) return { type: "tool_use", name: String(tool), input: params.Arguments || params.arguments || params.args || params, id };
  }
  const input = Object.fromEntries(Object.entries(params).map(([k, v]) => [PARAM_KEYS[k] || k, v]));
  return { type: "tool_use", name, input, id };
}

// A failed tool reports its output as {"type":"TOOL_ERROR","message":…} while
// the step itself still says DONE.
function toolFailure(out) {
  if (!out.startsWith("{")) return null;
  try { const j = JSON.parse(out); return j && j.type === "TOOL_ERROR" ? String(j.message || "Tool failed") : null; }
  catch { return null; }
}

// A turn cut short by an auto-denied action "succeeds" with an empty reply —
// name what was blocked and the way out, or the phone just sees a blank turn.
function deniedNotice(denied) {
  const kinds = [...new Set(denied.map((d) => d && d.action).filter(Boolean))];
  const what = kinds.includes("command") ? "run a shell command" : `use a tool that needs the "${kinds[0] || "unknown"}" permission`;
  return `Antigravity stopped: it needed to ${what}, which this chat's Approval setting doesn't allow (it can't ask from here). Switch Approval to "Full access" and send the message again.`;
}

function toolOutput(info) {
  const out = info && (info.output ?? info.error);
  if (out == null) return "";
  return typeof out === "string" ? out : JSON.stringify(out, null, 2);
}

export const antigravityAgent = {
  id: "antigravity",
  label: "Antigravity",
  description: "Google's Antigravity CLI (agy), run headless per turn and streamed.",

  // agy expands its own slash commands/skills in print mode, but has no
  // listing we could surface without starting a session.
  commands: [],

  controls: {
    model: {
      label: "Model",
      default: FALLBACK_MODELS[0].value,
      options: FALLBACK_MODELS,
    },
    approval: {
      label: "Approval",
      default: "accept-edits",
      options: [
        { value: "plan", label: "Plan (read-only)" },
        { value: "accept-edits", label: "Edit files (no commands)" },
        { value: "full-access", label: "Full access" },
      ],
    },
  },

  run({ text, controls = {}, resumeId, sessionId, cwd, gatewayUrl, onEvent, onError, onClose }) {
    // agy never injects MCP server instructions into the model context, so the
    // phone directive rides on the first message of each conversation (same
    // approach as codex exec) — resumed turns already have it in history.
    // Same for the working directory: agy's context names its own app-data
    // folder before the workspace, and "the current folder" sent it there.
    const preamble = [PHONE_DIRECTIVE, cwd ? `Your working directory (the user's project folder) is: ${cwd}` : ""].filter(Boolean).join("\n\n");
    const prompt = resumeId ? text : `${preamble}\n\n${text}`;

    // `-p=` is print mode with an empty argv prompt: the real one arrives on
    // stdin below. (A bare `-p` would swallow the next flag as its prompt.)
    const args = ["-p=", "--input-format", "stream-json", "--output-format", "stream-json", "--print-timeout", "60m"];
    // A model saved by an older build may be gone from the list; agy hard-fails
    // on an unknown --model, so only pass one the current picker still offers.
    const models = antigravityAgent.controls.model.options;
    if (controls.model && models.some((m) => m.value === controls.model)) args.push("--model", controls.model);
    if (resumeId) args.push("--conversation", resumeId);
    args.push(...(APPROVAL[controls.approval] || APPROVAL["accept-edits"]));

    // No shell: AGY_BIN is a real executable path, so there is no cmd.exe
    // quoting to get wrong.
    const child = spawn(AGY_BIN, args, {
      cwd: cwd || undefined,
      // WAKILI_* are inherited by the MCP servers agy spawns — how
      // mcp-tools.mjs learns which session to deliver files/questions to
      // (see registerMcp above).
      env: { ...process.env, WAKILI_SESSION: sessionId, WAKILI_GATEWAY: gatewayUrl, WAKILI_TOKEN: config.token },
    });

    let conversationId = resumeId || null;
    let sawText = false, failed = false, started = false, err = "";
    const openTools = new Set(); // tool ids announced (ACTIVE) but not yet finished
    const textDelta = (t) => { sawText = true; onEvent({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: t } } }); };

    const handle = (evt) => {
      if (evt.event === "init") {
        started = true;
        if (evt.conversation_id) {
          conversationId = evt.conversation_id;
          // Announce the resume id up front: a turn stopped midway never
          // reaches `result`, and the next message must not start a blank
          // conversation.
          onEvent({ type: "system", subtype: "init", session_id: conversationId });
        }
      } else if (evt.event === "step_update" && evt.step_update) {
        const step = evt.step_update;
        if (step.step_type === "agent_response") {
          if (typeof step.thinking_delta === "string" && step.thinking_delta) onEvent({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "thinking_delta", thinking: step.thinking_delta } } });
          if (typeof step.text_delta === "string" && step.text_delta) textDelta(step.text_delta);
        } else if (step.step_type === "tool") {
          const id = `agy-${step.conversation_id || conversationId}-${step.step_index}`;
          const finished = step.state !== "ACTIVE";
          // ACTIVE announces the call; a fast tool may only ever report DONE.
          if (!openTools.has(id)) onEvent({ type: "assistant", message: { content: [toolUse(step, id)] } });
          if (!finished) { openTools.add(id); return; }
          openTools.delete(id);
          let out = toolOutput(step.tool_info);
          const failure = toolFailure(out);
          if (failure) out = failure;
          const isError = !!failure || step.state === "ERROR" || step.state === "FAILED" || !!(step.tool_info && step.tool_info.error);
          if (out || isError) onEvent({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: id, content: out, is_error: isError }] } });
        }
      } else if (evt.event === "result" && evt.result) {
        const res = evt.result;
        if (res.conversation_id) conversationId = res.conversation_id;
        // Normally the reply has already streamed as deltas; a turn answered
        // without an agent step (a print-mode slash command) only has this.
        if (!sawText && typeof res.response === "string" && res.response) textDelta(res.response);
        // Sent as reply TEXT, not an error banner: banners are live-only, and
        // this is all the turn has to show — it must survive a reopen.
        if (Array.isArray(res.denied_actions) && res.denied_actions.length) textDelta((sawText ? "\n\n" : "") + deniedNotice(res.denied_actions));
        if (res.status && res.status !== "SUCCESS") {
          failed = true;
          if (onError) onError(res.error ? String(res.error) : `Antigravity turn ended with status ${res.status}`);
        }
      }
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
        handle(evt);
      }
    });
    // stderr carries soft-deny notices and progress chatter alongside real
    // failures — only worth the phone's attention if the turn itself fails.
    child.stderr.on("data", (d) => { if (err.length < 8192) err += d.toString(); });

    let closed = false;
    const closeOnce = (code) => {
      if (closed) return;
      closed = true;
      const bad = code !== 0 && !failed;
      // A user Stop kills the tree (non-zero exit, empty stderr) — that is not
      // an error worth a banner. Only a run that died before it even opened
      // its conversation gets the bare exit code.
      if (bad && onError && (err.trim() || !started)) onError(err.trim() || `antigravity exited with code ${code}`);
      // Carry the resume id the same way Claude's result.session_id does.
      onEvent({ type: "result", session_id: conversationId, is_error: bad || failed, ...(bad || failed ? { error: err.trim() } : {}) });
      if (onClose) onClose(code);
    };
    child.on("close", closeOnce);
    // A failed spawn (missing binary, bad cwd) emits 'error', not 'close'.
    child.on("error", (e) => {
      failed = true; // reported here — closeOnce must not add a second banner
      if (onError) onError(`failed to start antigravity: ${e.message}`);
      closeOnce(-1);
    });

    // One NDJSON message = one turn; closing stdin lets agy exit once it's done.
    try {
      child.stdin.write(JSON.stringify({ event: "user", message: { role: "user", content: [{ type: "text", text: prompt }] } }) + "\n");
      child.stdin.end();
    } catch { /* surfaced via the error handler above */ }
    return child;
  },
};

if (antigravityAvailable) {
  registerMcp();
  refreshModels().catch(() => {});
}
