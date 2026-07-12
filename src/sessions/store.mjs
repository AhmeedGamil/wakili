import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { config } from "../config.mjs";

// Repository pattern. The rest of the app talks to this interface, never to the
// filesystem directly — so swapping JSON files for SQLite later is a one-file
// change. A session owns: its display transcript (what the UI renders) and the
// agent's resume id (Claude's session_id) for context continuity.

fs.mkdirSync(config.dataDir, { recursive: true });
const fileFor = (id) => path.join(config.dataDir, `${id}.json`);

// Fires after every successful save. The HTTP layer uses it to push cache
// invalidations to clients without coupling this module to the transport.
let onSave = null;
export function onSessionSave(fn) { onSave = fn; }

export const sessionStore = {
  async create({ agentId = "claude", model = config.defaultModel, cwd = null } = {}) {
    const now = Date.now();
    const session = {
      id: crypto.randomUUID(),
      title: "New chat",
      agentId,
      model,
      cwd, // working directory the agent runs in (null = the gateway's own dir)
      controls: {}, // per-agent native control values (model/effort/thinking/...)
      allowedTools: [], // tools the user chose to auto-approve for this session
      resumeId: null, // agent-native session id for --resume
      createdAt: now,
      updatedAt: now,
      messages: [], // [{ role: "user"|"assistant", text }]
    };
    return this.save(session);
  },

  async get(id) {
    try {
      return JSON.parse(await fsp.readFile(fileFor(id), "utf8"));
    } catch {
      return null;
    }
  },

  async save(session) {
    session.updatedAt = Date.now();
    await fsp.writeFile(fileFor(session.id), JSON.stringify(session, null, 2));
    if (onSave) { try { onSave(session); } catch { /* observers must not break persistence */ } }
    return session;
  },

  async remove(id) {
    try { await fsp.unlink(fileFor(id)); } catch { /* already gone */ }
  },

  /** Lightweight list for the sidebar (no message bodies), newest first. */
  async list() {
    let files = [];
    try { files = await fsp.readdir(config.dataDir); } catch { return []; }
    const out = [];
    for (const f of files) {
      if (!f.endsWith(".json")) continue;
      try {
        const s = JSON.parse(await fsp.readFile(path.join(config.dataDir, f), "utf8"));
        // model: what the session actually runs with — the last picked control
        // value wins over the create-time default.
        out.push({ id: s.id, title: s.title, agentId: s.agentId, model: (s.controls && s.controls.model) || s.model, cwd: s.cwd || null, updatedAt: s.updatedAt });
      } catch { /* skip corrupt */ }
    }
    return out.sort((a, b) => b.updatedAt - a.updatedAt);
  },
};
