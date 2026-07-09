#!/usr/bin/env node
// One-command Cloudflare: launches the gateway and the CF bridge together, so
// you don't have to juggle two terminals. The gateway runs in its normal (auto)
// mode — LAN + Tailscale still work — and the bridge adds the public, unbuffered
// Cloudflare path (and registers its URL so the in-app switcher can offer it).
//
//   node cf.mjs        (or: npm run cloudflare)
//
// Ctrl+C stops both. On Windows we taskkill the tree so cloudflared doesn't
// linger (a hard kill skips the children's own cleanup).

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const node = process.execPath;
// Resolve siblings next to THIS file, not the caller's cwd — a globally
// installed `wakili-cloudflare` runs from any directory.
const here = path.dirname(fileURLToPath(import.meta.url));
const procs = [];
let shuttingDown = false;

function killProc(p) {
  if (!p || p.killed) return;
  if (process.platform === "win32" && p.pid) {
    try { spawn("taskkill", ["/pid", String(p.pid), "/t", "/f"], { stdio: "ignore" }); return; } catch { /* fall through */ }
  }
  try { p.kill(); } catch { /* already gone */ }
}

function shutdown(code) {
  if (shuttingDown) return;
  shuttingDown = true;
  for (const p of procs) killProc(p);
  process.exit(code ?? 0);
}

function launch(args) {
  const p = spawn(node, args, { stdio: "inherit" });
  procs.push(p);
  p.on("exit", (code) => { if (!shuttingDown) shutdown(code ?? 0); }); // one dies → take the rest down
  return p;
}

process.on("SIGINT", () => shutdown(0));
process.on("SIGTERM", () => shutdown(0));

console.log("Starting gateway + Cloudflare bridge…  (Ctrl+C to stop both)\n");
launch([path.join(here, "server.mjs")]);
setTimeout(() => launch([path.join(here, "cf-bridge.mjs")]), 1200); // let the gateway bind first
