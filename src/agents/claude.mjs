import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { config, ROOT } from "../config.mjs";

// --- Slash commands ----------------------------------------------------------
// Source of truth = the `slash_commands` array Claude reports in its system/init
// event (the exact list the CLI shows). We grab it once with a cheap probe that
// sends "/cost" (a synthetic, zero-model-cost command) and reads init. Custom
// commands in ~/.claude/commands or <cwd>/.claude/commands are merged in with
// their frontmatter descriptions. Note: interactive built-ins (/clear, /model,
// /compact …) are advertised here but reply "isn't available" under `-p`; the
// info ones (/context /cost /usage) and skills/custom commands do work.

// Descriptions for the commands worth labelling; the rest show name-only.
const KNOWN_DESC = {
  context: "Show context window / token usage",
  cost: "Show session cost & weekly limits",
  usage: "Show plan usage limits",
  "usage-credits": "Show usage credits",
  "extra-usage": "Extra usage settings",
  review: "Review a pull request",
  "security-review": "Security review of pending changes",
  "code-review": "Review the current diff",
  simplify: "Simplify the changed code",
  init: "Generate a CLAUDE.md for this project",
  compact: "Summarize & compact the conversation",
  clear: "Clear the conversation",
  "deep-research": "Multi-source research report",
  verify: "Verify a change by running it",
  debug: "Debug an issue",
  run: "Launch & drive the app",
};

let probed = [];   // names from init's slash_commands (filled by the probe below)

function probeSlashCommands() {
  return new Promise((resolve) => {
    let child, names = [], buf = "";
    try {
      child = spawn("claude", ["-p", "--output-format", "stream-json", "--verbose"], { shell: config.isWin, env: process.env });
    } catch { return resolve([]); }
    const done = (v) => { clearTimeout(timer); resolve(v); };
    const timer = setTimeout(() => { try { child.kill(); } catch {} done(names); }, 8000);
    child.stdout.on("data", (c) => {
      buf += c.toString();
      let nl;
      while ((nl = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, nl).trim(); buf = buf.slice(nl + 1);
        if (!line) continue;
        try {
          const ev = JSON.parse(line);
          if (ev.type === "system" && ev.subtype === "init" && Array.isArray(ev.slash_commands)) names = ev.slash_commands;
        } catch { /* ignore non-JSON */ }
      }
    });
    child.on("error", () => done(names));
    child.on("close", () => done(names));
    child.stdin.write("/cost");   // synthetic: emits init then exits, ~0 model cost
    child.stdin.end();
  });
}

// Probe once at startup; server awaits this before listening (see commandsReady).
export const commandsReady = probeSlashCommands().then((list) => { probed = list; }).catch(() => {});

function describeCommand(file) {
  try {
    const txt = fs.readFileSync(file, "utf8");
    const fm = /^---\s*\r?\n([\s\S]*?)\r?\n---/.exec(txt);
    if (fm) {
      const d = /^\s*description:\s*(.+)$/im.exec(fm[1]);
      if (d) return d[1].trim().replace(/^["']|["']$/g, "").slice(0, 80);
    }
    const body = txt.replace(/^---[\s\S]*?---/, "").trim().split(/\r?\n/)[0] || "";
    return (body || "Custom command").slice(0, 80);
  } catch { return "Custom command"; }
}

// Project commands override user commands of the same name (last write wins).
function customCommands() {
  const out = new Map();
  for (const dir of [path.join(os.homedir(), ".claude", "commands"), path.join(process.cwd(), ".claude", "commands")]) {
    let files;
    try { files = fs.readdirSync(dir, { recursive: true }); } catch { continue; }
    for (const rel of files) {
      const r = String(rel);
      if (!r.endsWith(".md")) continue;
      const name = path.basename(r, ".md");
      out.set(name, describeCommand(path.join(dir, r)));
    }
  }
  return out; // Map<name, desc>
}

function listCommands() {
  const names = new Set(probed);
  for (const n of ["context", "cost", "usage"]) names.add(n); // verified-working; ensure present even if probe missed them
  const custom = customCommands();
  for (const n of custom.keys()) names.add(n);
  return [...names].map((name) => ({ name, desc: custom.get(name) || KNOWN_DESC[name] || "" }));
}

// --- Model discovery ---------------------------------------------------------
// The CLI has no "list models" command, and its /v1/models API needs a key we
// don't have (we run on subscription auth). But `--model <alias>` accepts the
// family aliases opus/sonnet/fable/haiku, each resolving server-side to the
// LATEST model of that family. So we pass the alias as the option value (always
// current, even between releases) and probe each one once at startup purely to
// label it with the concrete version the alias currently maps to.
//
// The probe reuses the /cost synthetic (≈0 model cost): we spawn `--model
// <alias>`, read the resolved `model` off the init event, then kill the child.
const MODEL_FAMILIES = [
  { alias: "opus", family: "Opus" },
  { alias: "sonnet", family: "Sonnet" },
  { alias: "fable", family: "Fable" },
  { alias: "haiku", family: "Haiku" },
];

// "claude-sonnet-5[1m]" -> "Sonnet 5"; "claude-haiku-4-5-20251001" -> "Haiku 4.5".
function labelForModel(family, resolved) {
  if (!resolved) return `${family} (latest)`;
  const ver = resolved
    .replace(/\[.*?\]/g, "")       // drop [1m] context tag
    .replace(/^claude-/, "")        // drop claude- prefix
    .replace(/-\d{8}$/, "")         // drop -20251001 date suffix
    .split("-")
    .filter((p) => /^\d+$/.test(p)) // keep numeric version parts
    .join(".");
  return ver ? `${family} ${ver}` : `${family} (latest)`;
}

// Resolve one alias to its concrete model id via the init event (null on failure).
function probeModel(alias) {
  return new Promise((resolve) => {
    let child, buf = "";
    try {
      child = spawn("claude", ["-p", "--model", alias, "--output-format", "stream-json", "--verbose"], { shell: config.isWin, env: process.env });
    } catch { return resolve(null); }
    const done = (v) => { clearTimeout(timer); try { child.kill(); } catch {} resolve(v); };
    const timer = setTimeout(() => done(null), 8000);
    child.stdout.on("data", (c) => {
      buf += c.toString();
      let nl;
      while ((nl = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, nl).trim(); buf = buf.slice(nl + 1);
        if (!line) continue;
        try {
          const ev = JSON.parse(line);
          if (ev.type === "system" && ev.subtype === "init" && ev.model) return done(ev.model);
        } catch { /* ignore non-JSON */ }
      }
    });
    child.on("error", () => done(null));
    child.on("close", () => done(null));
    child.stdin.write("/cost"); // synthetic: emits init then exits, ~0 model cost
    child.stdin.end();
  });
}

// Probe all families in parallel and rewrite the model options in place. Options
// keep the ALIAS as their value (so runtime always gets the latest); only the
// label reflects the probed version. The static list below is the fallback if a
// probe times out or the CLI is unavailable.
async function discoverModels() {
  const resolved = await Promise.all(MODEL_FAMILIES.map((f) => probeModel(f.alias)));
  const opts = MODEL_FAMILIES.map((f, i) => ({ value: f.alias, label: labelForModel(f.family, resolved[i]) }));
  if (opts.length) claudeAgent.controls.model.options = opts;
}

// Claude adapter. Declares its own native controls (the manifest the UI renders)
// and runs one streaming turn. Gated tools route through a PreToolUse hook so
// the phone can approve/deny them.

// --- Generate the settings file that registers our permission hook. Static
//     (per-session info travels via env), written once at startup. ---
const HOOK = path.join(ROOT, "src", "permission-hook.mjs").replace(/\\/g, "/");
const MCP_SERVER = path.join(ROOT, "src", "mcp-tools.mjs").replace(/\\/g, "/");
const SETTINGS_PATH = path.join(config.runtimeDir, "claude-settings.json");
const MCP_CONFIG_PATH = path.join(config.runtimeDir, "mcp-config.json");
fs.mkdirSync(config.runtimeDir, { recursive: true });

// Permission hook gates mutating tools; the send_to_user MCP tool is pre-allowed
// (delivering a file to the user shouldn't require approval). AskUserQuestion is
// also routed through the hook: headless `-p` can't pause for input, so the hook
// forwards the question to the phone and feeds the chosen answer back in-turn.
fs.writeFileSync(
  SETTINGS_PATH,
  JSON.stringify({
    permissions: { allow: ["mcp__wakili__send_to_user"] },
    hooks: {
      PreToolUse: [
        {
          matcher: "Bash|Write|Edit|MultiEdit|NotebookEdit|AskUserQuestion",
          hooks: [{ type: "command", command: `node "${HOOK}"` }],
        },
      ],
    },
  }, null, 2)
);

// Our MCP server provides the agent's file-delivery tool.
fs.writeFileSync(
  MCP_CONFIG_PATH,
  JSON.stringify({ mcpServers: { wakili: { command: "node", args: [MCP_SERVER] } } }, null, 2)
);

// Maps the bridge's thinking levels to Claude's keyword triggers (appended to
// the prompt; Claude turns them into a thinking-token budget internally).
export const THINKING = { off: "", think: "think", think_hard: "think hard", ultrathink: "ultrathink" };

// Phone directives. Their PRIMARY delivery channel is the MCP tool descriptions
// in mcp-tools.mjs (seen by both Claude adapters upfront, invisible in the
// transcript); the SDK adapter additionally appends PHONE_DIRECTIVE to its
// system prompt as reinforcement, and the CLI adapter injects nothing. Codex
// defers MCP tools behind its tool-search layer (descriptions aren't visible
// until the model searches), so its adapter prepends PHONE_DIRECTIVE to the
// FIRST message of each thread — see codex.mjs.
export const ASK_DIRECTIVE =
  "The user is on a phone. Whenever you need them to make a choice, a decision, a confirmation, " +
  "or to pick between options, you MUST call the ask_options tool (mcp__wakili__ask_options) " +
  "with your question(s) and their options — never ask by writing the question as plain text. That " +
  "tool shows tappable buttons and pauses the turn until they answer, then returns their choice. Only " +
  "ask in plain prose for genuinely open-ended input that has no discrete options.";

export const SEND_DIRECTIVE =
  "The user is on a phone and cannot open files by path — a filesystem path in your reply is useless " +
  "to them. Whenever you create, build, generate, screenshot, or otherwise end up with a file the user " +
  "would want (an image, screenshot, APK/build artifact, PDF, log, or any generated file), you MUST " +
  "call the send_to_user tool (mcp__wakili__send_to_user) with its absolute path to deliver it to " +
  "their phone. Do this proactively as soon as the file exists — do not wait to be asked, and never " +
  "just print the path and stop.";

// Both phone directives combined — used only by the SDK adapter's in-process
// systemPrompt `append` (no shell in the way, so multi-line is safe there).
export const PHONE_DIRECTIVE = ASK_DIRECTIVE + "\n\n" + SEND_DIRECTIVE;

// --- Warm sessions -------------------------------------------------------------
// One CLI process per SESSION, not per turn. The process is spawned with
// --input-format stream-json and kept alive between turns; each new message is
// written to its stdin as a stream-json user event, skipping the CLI's ~1-2s
// boot on every message. Because model/effort/mode/cwd are spawn-time flags,
// the process is recycled when any of them change; it's also killed after
// config.warmTtlMs idle — the next message just cold-starts with --resume, so
// nothing is lost. Reuse additionally requires the caller's resumeId to be the
// thread this process is already on (guards agent switches, which null it).
const warm = new Map(); // sessionId -> { child, key, threadId, turn, idleTimer, alive }

function killWarm(entry) {
  clearTimeout(entry.idleTimer);
  entry.alive = false;
  // Same reasoning as the server's killTree: under shell:true a plain kill()
  // only takes down the cmd.exe wrapper — kill the whole tree on Windows.
  if (config.isWin && entry.child.pid) {
    try { spawn("taskkill", ["/pid", String(entry.child.pid), "/t", "/f"], { stdio: "ignore" }); return; } catch { /* fall through */ }
  }
  try { entry.child.kill(); } catch { /* already gone */ }
}

// Called on gateway shutdown so warm processes don't outlive it.
export function closeWarmClaude() {
  for (const e of warm.values()) killWarm(e);
  warm.clear();
}

export const claudeAgent = {
  id: "claude",
  label: "Claude",
  description: "Anthropic's Claude Code, run by spawning the CLI and streaming its output.",

  // Discovered fresh on each manifest request so newly added custom commands appear.
  commands: listCommands,

  controls: {
    model: {
      label: "Model",
      // Value is the family alias (always resolves to the latest of that family
      // at call time); labels are refreshed at startup by discoverModels(). This
      // static list is the fallback used until the probe returns / if it fails.
      default: "sonnet",
      options: [
        { value: "opus", label: "Opus (latest)" },
        { value: "sonnet", label: "Sonnet (latest)" },
        { value: "fable", label: "Fable (latest)" },
        { value: "haiku", label: "Haiku (latest)" },
      ],
    },
    effort: {
      label: "Effort",
      default: "",
      options: [
        { value: "", label: "Auto" },
        { value: "low", label: "Low" },
        { value: "medium", label: "Medium" },
        { value: "high", label: "High" },
        { value: "xhigh", label: "X-High" },
        { value: "max", label: "Max" },
      ],
    },
    thinking: {
      label: "Thinking",
      default: "off",
      options: [
        { value: "off", label: "Off" },
        { value: "think", label: "Think" },
        { value: "think_hard", label: "Think hard" },
        { value: "ultrathink", label: "Ultrathink" },
      ],
    },
    // Claude's operating mode. Maps to --permission-mode; also drives the
    // gateway's auto-approve (acceptEdits auto-allows edits, bypass allows all).
    permissionMode: {
      label: "Mode",
      default: "default",
      options: [
        { value: "default", label: "Ask before edit" },
        { value: "acceptEdits", label: "Edit automatically" },
        { value: "plan", label: "Plan mode" },
        { value: "bypassPermissions", label: "Auto mode" },
      ],
    },
  },

  run({ text, controls = {}, resumeId, sessionId, cwd, gatewayUrl, onEvent, onError, onClose }) {
    // Appending the thinking keyword to a slash command ("/context\n\nthink")
    // stops the CLI from recognizing it — the whole thing would go to the model
    // as plain text. Slash commands ship exactly as typed.
    const isSlash = /^\//.test((text || "").trim());
    const keyword = THINKING[controls.thinking] || "";
    const prompt = keyword && !isSlash ? `${text}\n\n${keyword}` : text;
    // The turn's message in stream-json input form (one JSON object per line).
    const userMsg = JSON.stringify({ type: "user", message: { role: "user", content: [{ type: "text", text: prompt }] } }) + "\n";

    // Reuse the session's warm process when its spawn-time settings still match
    // and it's sitting on the thread the caller wants to continue.
    const key = JSON.stringify([controls.model || "", controls.effort || "", controls.permissionMode || "default", cwd || ""]);
    const existing = warm.get(sessionId);
    if (existing) {
      if (existing.alive && !existing.turn && existing.key === key && resumeId === existing.threadId) {
        clearTimeout(existing.idleTimer);
        existing.turn = { onEvent, onError, onClose };
        try { existing.child.stdin.write(userMsg); return existing.child; }
        catch { /* dead pipe — recycle below */ }
      }
      killWarm(existing);
      warm.delete(sessionId);
    }

    const args = [
      "-p",
      "--input-format", "stream-json", // stdin stays open; one process serves the whole session
      "--output-format", "stream-json",
      "--verbose",
      "--include-partial-messages",
      "--permission-mode", controls.permissionMode || "default",
      "--settings", SETTINGS_PATH,
      "--mcp-config", MCP_CONFIG_PATH,
      "--strict-mcp-config",
      // Force questions through the reliable ask_options MCP tool by disabling
      // the built-in AskUserQuestion (whose headless answer-injection is a
      // fragile deny-reason hack). The "you MUST use ask_options / send_to_user"
      // phone directives reach the model via the MCP tool descriptions in
      // mcp-tools.mjs — no --append-system-prompt needed (which was Windows-
      // hostile anyway: cmd.exe word-splits multi-line args under shell:true).
      "--disallowed-tools", "AskUserQuestion",
    ];
    if (controls.model) args.push("--model", controls.model);
    if (controls.effort) args.push("--effort", controls.effort);
    if (resumeId) args.push("--resume", resumeId);

    const child = spawn("claude", args, {
      shell: config.isWin,
      cwd: cwd || undefined,
      env: {
        ...process.env,
        // Tag these headless turns with an interactive entrypoint so they appear
        // in the native Claude Code resume list (the picker hides the default
        // "sdk-cli" entrypoint). Configurable — see config.claudeEntrypoint.
        CLAUDE_CODE_ENTRYPOINT: config.claudeEntrypoint,
        WAKILI_SESSION: sessionId,
        WAKILI_GATEWAY: gatewayUrl,
        WAKILI_TOKEN: config.token,
      },
    });

    const entry = { child, key, threadId: resumeId || null, turn: { onEvent, onError, onClose }, idleTimer: null, alive: true };
    warm.set(sessionId, entry);

    // A turn ends on the CLI's `result` event (the process stays alive waiting
    // for the next stdin message) or on process death, whichever comes first.
    const endTurn = (code) => {
      const t = entry.turn;
      entry.turn = null;
      if (entry.alive) {
        entry.idleTimer = setTimeout(() => { killWarm(entry); if (warm.get(sessionId) === entry) warm.delete(sessionId); }, config.warmTtlMs);
        entry.idleTimer.unref?.();
      }
      if (t && t.onClose) t.onClose(code);
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
        if (evt.type === "result" && evt.session_id) entry.threadId = evt.session_id; // the thread this process now carries
        const t = entry.turn;
        if (t) t.onEvent(evt);
        if (evt.type === "result") endTurn(0);
      }
    });
    child.stderr.on("data", (d) => { const t = entry.turn; if (t && t.onError) t.onError(d.toString()); });
    child.on("close", (code) => {
      entry.alive = false;
      clearTimeout(entry.idleTimer);
      if (warm.get(sessionId) === entry) warm.delete(sessionId);
      if (entry.turn) endTurn(code); // died mid-turn (crash or user stop)
    });
    // A failed spawn (bad cwd, missing binary) emits 'error', not 'close'. Without
    // this handler that error is unhandled and takes down the whole gateway.
    child.on("error", (e) => {
      entry.alive = false;
      if (warm.get(sessionId) === entry) warm.delete(sessionId);
      const t = entry.turn;
      if (t && t.onError) t.onError(`failed to start claude: ${e.message}`);
      if (entry.turn) endTurn(-1);
    });

    // stdin writes can also throw if the process never started.
    try { child.stdin.write(userMsg); } catch { /* surfaced via the error handler above */ }
    return child;
  },
};

// Probe model families once at startup (parallel); server awaits this before
// serving the manifest so the picker shows concrete version labels.
export const modelsReady = discoverModels().catch(() => {});
