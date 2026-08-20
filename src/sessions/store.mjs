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
const turnFileFor = (id) => path.join(config.dataDir, `${id}.turn.json`);

// Fires after every successful save. The HTTP layer uses it to push cache
// invalidations to clients without coupling this module to the transport.
let onSave = null;
export function onSessionSave(fn) { onSave = fn; }

export const sessionStore = {
  // `draft` — a chat that was opened but never written in. It's a real session
  // (it owns an id from the start, so the client's streams, uploads and control
  // plumbing all work) but it stays OUT of list(), so opening "New chat" and
  // backing out doesn't leave an empty row behind. The first user message clears
  // the flag, which is the moment the chat becomes real. Handoffs and any other
  // path that starts a session WITH content pass draft: false.
  async create({ agentId = "claude", model = config.defaultModel, cwd = null, draft = false } = {}) {
    const now = Date.now();
    const session = {
      id: crypto.randomUUID(),
      draft: !!draft,
      title: "New chat",
      agentId,
      model,
      cwd, // working directory the agent runs in (null = the gateway's own dir)
      controls: {}, // per-agent native control values (model/effort/thinking/...)
      // Auto continue is ON by default: a turn killed by the usage limit should
      // pick itself back up when quota returns unless the user says otherwise.
      // Written explicitly so the value is visible in the file, but every read
      // goes through autoContinueOn() — sessions created before this default
      // have no field at all and must still read as on.
      autoContinue: true,
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
    try { await fsp.unlink(turnFileFor(id)); } catch { /* none pending */ }
  },

  // --- in-progress turn durability ------------------------------------------
  // The live turn streams into RAM (server.mjs's activeTurns); these persist it
  // to a sidecar next to the session JSON so a gateway crash mid-turn can't
  // lose text that already arrived. Sidecar writes bypass save() on purpose:
  // no updatedAt bump and no onSave broadcast, so the throttled stream of
  // writes doesn't spam every client with cache invalidations.

  async savePendingTurn(id, parts) {
    // tmp+rename so a crash mid-write leaves the previous good copy, not half a file
    const tmp = turnFileFor(id) + ".tmp";
    await fsp.writeFile(tmp, JSON.stringify(parts));
    await fsp.rename(tmp, turnFileFor(id));
  },

  async clearPendingTurn(id) {
    try { await fsp.unlink(turnFileFor(id)); } catch { /* none pending */ }
  },

  // Boot-time crash recovery: any sidecar still on disk is a turn whose gateway
  // died mid-stream — fold it into its transcript (marked interrupted) so the
  // streamed-so-far content survives, matching the invariant "anything that
  // reached the server is saved". Returns the recovered sessions' ids, so the
  // caller can re-arm auto-continue for chats a crash cut mid-task.
  async recoverPendingTurns() {
    let files = [];
    try { files = await fsp.readdir(config.dataDir); } catch { return []; }
    const recovered = [];
    for (const f of files) {
      if (!f.endsWith(".turn.json")) continue;
      const id = f.slice(0, -".turn.json".length);
      try {
        const parts = JSON.parse(await fsp.readFile(path.join(config.dataDir, f), "utf8"));
        const s = await this.get(id);
        if (s && Array.isArray(parts) && parts.length) {
          // If the crash hit between the turn-end save and the sidecar cleanup,
          // the turn is already in the transcript — don't fold it in twice.
          const last = s.messages[s.messages.length - 1];
          if (!(last && last.role === "assistant" && JSON.stringify(last.parts) === JSON.stringify(parts))) {
            s.messages.push({ role: "assistant", parts, interrupted: true });
            await this.save(s);
            recovered.push(id);
          }
        }
      } catch { /* corrupt sidecar — nothing recoverable */ }
      try { await fsp.unlink(path.join(config.dataDir, f)); } catch { /* gone */ }
    }
    return recovered;
  },

  /**
   * Lightweight list for the sidebar (no message bodies), newest first.
   * Drafts are omitted: a chat the user opened but never wrote in isn't a chat
   * yet, and listing it is what used to fill the sidebar with "New chat" rows.
   */
  async list() {
    let files = [];
    try { files = await fsp.readdir(config.dataDir); } catch { return []; }
    const out = [];
    for (const f of files) {
      if (!f.endsWith(".json") || f.endsWith(".turn.json")) continue; // skip pending-turn sidecars
      try {
        const s = JSON.parse(await fsp.readFile(path.join(config.dataDir, f), "utf8"));
        if (s.draft) continue;
        // model: what the session actually runs with — the last picked control
        // value wins over the create-time default.
        out.push({ id: s.id, title: s.title, agentId: s.agentId, model: (s.controls && s.controls.model) || s.model, cwd: s.cwd || null, updatedAt: s.updatedAt });
      } catch { /* skip corrupt */ }
    }
    return out.sort((a, b) => b.updatedAt - a.updatedAt);
  },

  /**
   * Sweep abandoned drafts — chats opened but never written in, whose client is
   * long gone. Only drafts with no messages and older than `maxAgeMs` are
   * removed, so a draft the user is sitting in right now survives a gateway
   * restart and can still take its first message.
   */
  async pruneDrafts(maxAgeMs = 24 * 60 * 60 * 1000) {
    let files = [];
    try { files = await fsp.readdir(config.dataDir); } catch { return 0; }
    const cutoff = Date.now() - maxAgeMs;
    let removed = 0;
    for (const f of files) {
      if (!f.endsWith(".json") || f.endsWith(".turn.json")) continue;
      try {
        const s = JSON.parse(await fsp.readFile(path.join(config.dataDir, f), "utf8"));
        if (!s.draft || (s.messages && s.messages.length) || s.updatedAt > cutoff) continue;
        await this.remove(s.id);
        removed++;
      } catch { /* skip corrupt */ }
    }
    return removed;
  },
};
