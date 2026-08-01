// Known projects — the folders the user works in, tracked in their own right.
//
// Projects used to have no existence of their own: the sidebar derived them from
// the cwd of existing sessions, AFTER filtering that list by the selected agent.
// So a project only existed where a chat already existed — creating a folder put
// it nowhere, and a project used from the Claude tab was invisible from Codex
// until a Codex chat happened to be started in it. A project is a property of
// the workspace, not of an agent, so it lives here instead: one registry the
// gateway owns, identical for every agent, client, and device.
//
// Registering is idempotent and happens wherever the user shows intent (creating
// a folder, pointing a session at one, starting a chat there), so the list fills
// itself and nothing has to be added by hand.

import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import { config } from "./config.mjs";

const FILE = path.join(config.runtimeDir, "projects.json");

// Windows paths are case-insensitive, so "D:\Wakili" and "d:\wakili" are one
// project; the first spelling seen is the one we display. Trailing separators
// are dropped so "D:\wakili\" can't register a twin of "D:\wakili".
const key = (p) => {
  const abs = norm(p);
  return config.isWin ? abs.toLowerCase() : abs;
};

function norm(p) {
  const s = String(p || "").trim();
  if (!s) return "";
  let abs;
  try { abs = path.resolve(s); } catch { return ""; }
  return abs.replace(/[\\/]+$/, "") || abs; // keep "C:\" / "/" intact
}

export const projectName = (p) => {
  const abs = norm(p);
  const base = path.basename(abs);
  return base || abs; // a drive root has no basename — show the root itself
};

// path-key -> { path, name, addedAt, lastUsedAt }. Loaded once; the file is a
// cache of this map, not the source of truth during a run.
const projects = new Map();
let loaded = false;

function load() {
  if (loaded) return;
  loaded = true;
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, "utf8"));
    for (const p of Array.isArray(raw) ? raw : []) {
      const k = key(p && p.path);
      if (!k) continue;
      projects.set(k, {
        path: norm(p.path),
        name: projectName(p.path),
        addedAt: p.addedAt || Date.now(),
        lastUsedAt: p.lastUsedAt || p.addedAt || Date.now(),
      });
    }
  } catch { /* no registry yet, or corrupt — start empty and rewrite on first save */ }
}

// tmp+rename so a crash mid-write can't truncate the registry (same discipline
// as the session store's pending-turn sidecars).
let saving = null;
async function save() {
  const body = JSON.stringify([...projects.values()], null, 2);
  const run = async () => {
    const tmp = FILE + ".tmp";
    await fsp.mkdir(path.dirname(FILE), { recursive: true });
    await fsp.writeFile(tmp, body);
    await fsp.rename(tmp, FILE);
  };
  // Collapse concurrent saves: writes are tiny and the body is snapshotted above,
  // so the last writer always persists the current map.
  saving = (saving || Promise.resolve()).then(run, run);
  return saving;
}

/** Every known project, most recently used first. */
export function listProjects() {
  load();
  return [...projects.values()].sort((a, b) => b.lastUsedAt - a.lastUsedAt);
}

/**
 * Register `p` (or bump its recency if already known). Returns the stored entry,
 * or null when `p` is empty — the default project (no cwd) is implicit and never
 * stored, since it isn't a folder the user chose.
 */
export function rememberProject(p) {
  load();
  const k = key(p);
  if (!k) return null;
  const now = Date.now();
  const existing = projects.get(k);
  const entry = existing
    ? { ...existing, lastUsedAt: now }
    : { path: norm(p), name: projectName(p), addedAt: now, lastUsedAt: now };
  projects.set(k, entry);
  save().catch(() => { /* the in-memory list stays correct for this run */ });
  return entry;
}

/** Drop `p` from the list. The folder itself is never touched. */
export function forgetProject(p) {
  load();
  const k = key(p);
  if (!k || !projects.has(k)) return false;
  projects.delete(k);
  save().catch(() => {});
  return true;
}

/**
 * Seed the registry from folders that sessions already point at, so upgrading
 * doesn't start with an empty list. Only adds what's missing — an existing
 * entry keeps its recency rather than being bumped by a boot scan.
 */
export function backfillProjects(cwds) {
  load();
  let added = 0;
  for (const cwd of cwds || []) {
    const k = key(cwd);
    if (!k || projects.has(k)) continue;
    projects.set(k, { path: norm(cwd), name: projectName(cwd), addedAt: Date.now(), lastUsedAt: Date.now() });
    added++;
  }
  if (added) save().catch(() => {});
  return added;
}
