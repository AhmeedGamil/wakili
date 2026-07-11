// Persistent session cache (IndexedDB). The controller's in-memory Map stays
// the hot layer; this mirrors it to disk so a reload (or a dead connection)
// paints the last known transcripts instantly. Everything here is best-effort:
// any failure (private mode, quota, corrupt DB) degrades to "no cache", never
// to a broken app — hence the swallowed errors throughout.

const DB_NAME = "wakili";
const SESSIONS = "sessions"; // full session objects, keyed by id
const KV = "kv";             // small singletons (the sidebar list)

let dbPromise = null;
function open() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    let req;
    try { req = indexedDB.open(DB_NAME, 1); } catch (e) { return reject(e); }
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(SESSIONS)) db.createObjectStore(SESSIONS, { keyPath: "id" });
      if (!db.objectStoreNames.contains(KV)) db.createObjectStore(KV);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

// One-shot transaction helper; resolves with the request's result.
async function tx(storeName, mode, run) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction(storeName, mode);
    const req = run(t.objectStore(storeName));
    t.oncomplete = () => resolve(req && req.result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

export const sessionDb = {
  /** All persisted sessions, for hydrating the in-memory cache on startup. */
  async allSessions() {
    try { return (await tx(SESSIONS, "readonly", (s) => s.getAll())) || []; }
    catch { return []; }
  },

  /** Write-through one session. `parts` is transient in-progress turn state —
   *  strip it so a reload can't resurrect a long-dead half-turn. */
  async putSession(session) {
    if (!session || !session.id) return;
    const { parts, ...rest } = session;
    try { await tx(SESSIONS, "readwrite", (s) => s.put(rest)); } catch { /* best effort */ }
  },

  async removeSession(id) {
    try { await tx(SESSIONS, "readwrite", (s) => s.delete(id)); } catch { /* best effort */ }
  },

  /** The sidebar list (summaries). Busy/pending flags are live server state —
   *  drop them so a reload doesn't show stale badges. */
  async putList(list) {
    const clean = (list || []).map(({ busy, pending, ...s }) => s);
    try { await tx(KV, "readwrite", (s) => s.put(clean, "sessionList")); } catch { /* best effort */ }
  },

  async getList() {
    try { return (await tx(KV, "readonly", (s) => s.get("sessionList"))) || null; }
    catch { return null; }
  },

  /** Drop sessions that no longer exist on the server (deleted elsewhere). */
  async prune(keepIds) {
    const keep = new Set(keepIds);
    try {
      const all = await this.allSessions();
      for (const s of all) if (!keep.has(s.id)) await this.removeSession(s.id);
    } catch { /* best effort */ }
  },
};
