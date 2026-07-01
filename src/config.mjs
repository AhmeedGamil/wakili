import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(__dirname, "..");

// Minimal .env loader (no dependency): read ROOT/.env and populate process.env
// for any keys not already set, so real shell env always wins. Runs before the
// settings below are read. KEY=VALUE per line; # comments and blank lines skipped;
// surrounding quotes stripped; an optional leading "export " is ignored.
(function loadDotEnv() {
  let txt;
  try { txt = fs.readFileSync(path.join(ROOT, ".env"), "utf8"); } catch { return; }
  for (const raw of txt.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim().replace(/^export\s+/, "");
    if (!key || key in process.env) continue;
    let val = line.slice(eq + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) val = val.slice(1, -1);
    process.env[key] = val;
  }
})();

// ───────────────────────────────────────────────────────────────────────────
//  SETTING: which editor's Claude Code session list your phone chats show up in
// ───────────────────────────────────────────────────────────────────────────
// Phone turns run headless; Claude would tag them "sdk-cli" and HIDE them from
// the resume list. We override the tag so they appear automatically (with auto
// titles). "claude-vscode" is verified to show in BOTH the VS Code window AND
// the terminal `claude --resume`, so it's the safe universal default.
//
// To target a different editor, change EDITOR_ENTRYPOINT below (or set the env
// var REMOTE_AGENT_CLAUDE_ENTRYPOINT without touching code). To find another
// editor's value: open Claude Code in it and check its CLAUDE_CODE_ENTRYPOINT
// env var. Known values: "claude-vscode", "cli", "sdk-cli".
const EDITOR_ENTRYPOINT = "claude-vscode";

const port = Number(process.env.PORT) || 8730;

// Shared secret that gates the gateway once it's reachable beyond the LAN (via a
// tunnel). Order: explicit env, then a persisted file, else generate + persist
// so the token is stable across restarts. The phone gets it once via the URL.
function loadToken() {
  if (process.env.REMOTE_AGENT_TOKEN) return process.env.REMOTE_AGENT_TOKEN.trim();
  const tokenFile = path.join(ROOT, "data", "token.txt");
  try {
    const t = fs.readFileSync(tokenFile, "utf8").trim();
    if (t) return t;
  } catch { /* not created yet */ }
  const t = crypto.randomBytes(24).toString("base64url");
  fs.mkdirSync(path.dirname(tokenFile), { recursive: true });
  fs.writeFileSync(tokenFile, t);
  return t;
}

export const config = {
  port,
  token: loadToken(),
  // Resolved entrypoint tag (see EDITOR_ENTRYPOINT above). Env override wins, so
  // you can switch editors per-launch without editing code.
  claudeEntrypoint: process.env.REMOTE_AGENT_CLAUDE_ENTRYPOINT || EDITOR_ENTRYPOINT,
  gatewayUrl: `http://127.0.0.1:${port}`, // the hook (a child process) calls back here
  isWin: process.platform === "win32",
  publicDir: path.join(ROOT, "public"),
  dataDir: path.join(ROOT, "data", "sessions"),
  runtimeDir: path.join(ROOT, "data"),
  uploadsDir: path.join(ROOT, "data", "uploads"),
  defaultModel: "",
};
