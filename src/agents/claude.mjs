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
    permissions: { allow: ["mcp__remoteagent__send_to_user"] },
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
  JSON.stringify({ mcpServers: { remoteagent: { command: "node", args: [MCP_SERVER] } } }, null, 2)
);

// Maps the bridge's thinking levels to Claude's keyword triggers (appended to
// the prompt; Claude turns them into a thinking-token budget internally).
export const THINKING = { off: "", think: "think", think_hard: "think hard", ultrathink: "ultrathink" };

// The model chats over a phone, so any question it wants the user to answer must
// go through the ask_options MCP tool (renders tappable buttons + pauses the turn
// for the reply). Left to itself the model tends to ask in plain prose, which the
// phone can't turn into an interactive card — so we instruct it explicitly.
export const ASK_DIRECTIVE =
  "The user is on a phone. Whenever you need them to make a choice, a decision, a confirmation, " +
  "or to pick between options, you MUST call the ask_options tool (mcp__remoteagent__ask_options) " +
  "with your question(s) and their options — never ask by writing the question as plain text. That " +
  "tool shows tappable buttons and pauses the turn until they answer, then returns their choice. Only " +
  "ask in plain prose for genuinely open-ended input that has no discrete options.";

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
    const args = [
      "-p",
      "--output-format", "stream-json",
      "--verbose",
      "--include-partial-messages",
      "--permission-mode", controls.permissionMode || "default",
      "--settings", SETTINGS_PATH,
      "--mcp-config", MCP_CONFIG_PATH,
      "--strict-mcp-config",
      // Force questions through the reliable ask_options MCP tool: instruct the
      // model to use it, and disable the built-in AskUserQuestion (whose headless
      // answer-injection is a fragile deny-reason hack).
      "--append-system-prompt", ASK_DIRECTIVE,
      "--disallowed-tools", "AskUserQuestion",
    ];
    if (controls.model) args.push("--model", controls.model);
    if (controls.effort) args.push("--effort", controls.effort);
    if (resumeId) args.push("--resume", resumeId);

    // Appending the thinking keyword to a slash command ("/context\n\nthink")
    // stops the CLI from recognizing it — the whole thing would go to the model
    // as plain text. Slash commands ship exactly as typed.
    const isSlash = /^\//.test((text || "").trim());
    const keyword = THINKING[controls.thinking] || "";
    const prompt = keyword && !isSlash ? `${text}\n\n${keyword}` : text;

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
        onEvent(evt);
      }
    });
    child.stderr.on("data", (d) => onError && onError(d.toString()));
    let closed = false;
    const closeOnce = (code) => { if (!closed) { closed = true; onClose && onClose(code); } };
    child.on("close", closeOnce);
    // A failed spawn (bad cwd, missing binary) emits 'error', not 'close'. Without
    // this handler that error is unhandled and takes down the whole gateway.
    child.on("error", (e) => {
      if (onError) onError(`failed to start claude: ${e.message}`);
      closeOnce(-1);
    });

    // stdin writes can also throw if the process never started.
    try {
      child.stdin.write(prompt);
      child.stdin.end();
    } catch { /* surfaced via the error handler above */ }
    return child;
  },
};

// Probe model families once at startup (parallel); server awaits this before
// serving the manifest so the picker shows concrete version labels.
export const modelsReady = discoverModels().catch(() => {});
