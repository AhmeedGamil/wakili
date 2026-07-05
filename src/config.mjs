import path from "node:path";
import os from "node:os";
import fs from "node:fs";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(__dirname, "..");

// The gateway's runtime store (sessions, token, uploads, file registry) lives
// OUTSIDE the repo, in the user's home directory. Keeping it in <repo>/data made
// it one forgotten .gitignore line away from being committed and pushed; a path
// under ~/.wakili is structurally impossible to commit by accident.
// Override with WAKILI_HOME for a custom location.
export const BASE_DIR = process.env.WAKILI_HOME
  ? path.resolve(process.env.WAKILI_HOME)
  : path.join(os.homedir(), ".wakili");

// One-time relocation from the legacy in-repo store. Copies (never deletes) so a
// failure leaves the old data intact; server.mjs removes the old copy only after
// it has re-pointed login autostart at the new location. Idempotent: once the new
// token exists we're done. Skipped entirely on a fresh install (no old store).
(function migrateFromRepo() {
  const oldData = path.join(ROOT, "data");
  if (fs.existsSync(path.join(BASE_DIR, "token.txt"))) return; // already moved
  if (!fs.existsSync(path.join(oldData, "token.txt"))) return; // fresh install
  try {
    fs.cpSync(oldData, BASE_DIR, { recursive: true });
    // Uploads are stored by ABSOLUTE path in files.json; repoint the prefix so
    // download links keep resolving once the old tree is deleted.
    const flog = path.join(BASE_DIR, "files.json");
    try {
      const arr = JSON.parse(fs.readFileSync(flog, "utf8"));
      for (const f of arr) {
        if (f && typeof f.path === "string" && f.path.startsWith(oldData)) {
          f.path = BASE_DIR + f.path.slice(oldData.length);
        }
      }
      fs.writeFileSync(flog, JSON.stringify(arr));
    } catch { /* no files.json to fix up */ }
  } catch { /* copy failed — keep using whatever the paths below resolve to */ }
})();

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
// var WAKILI_CLAUDE_ENTRYPOINT without touching code). To find another
// editor's value: open Claude Code in it and check its CLAUDE_CODE_ENTRYPOINT
// env var. Known values: "claude-vscode", "cli", "sdk-cli".
const EDITOR_ENTRYPOINT = "claude-vscode";

const port = Number(process.env.PORT) || 8730;

// Shared secret that gates the gateway once it's reachable beyond the LAN (via a
// tunnel). Order: explicit env, then a persisted file, else generate + persist
// so the token is stable across restarts. The phone gets it once via the URL.
function loadToken() {
  if (process.env.WAKILI_TOKEN) return process.env.WAKILI_TOKEN.trim();
  const tokenFile = path.join(BASE_DIR, "token.txt");
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
  claudeEntrypoint: process.env.WAKILI_CLAUDE_ENTRYPOINT || EDITOR_ENTRYPOINT,
  gatewayUrl: `http://127.0.0.1:${port}`, // the hook (a child process) calls back here
  isWin: process.platform === "win32",
  publicDir: path.join(ROOT, "public"),
  dataDir: path.join(BASE_DIR, "sessions"),
  runtimeDir: BASE_DIR,
  uploadsDir: path.join(BASE_DIR, "uploads"),
  defaultModel: "",
};
