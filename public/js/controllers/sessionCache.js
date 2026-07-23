// Transcripts from previous opens, so switching chats works instantly (and at
// all) on a slow or dead connection; the fresh fetch reconciles over it. RAM
// only — nothing here survives a reload. The cache never lies: everything it
// holds is either fresh from the server or explicitly reconciled against it.

export function createSessionCache(api) {
  const cache = new Map(); // id -> last-fetched session object

  // Fetch `id` reconciled against the copy we already hold. Sends since/after
  // so the server can answer { unchanged } (~60 bytes) or a messages delta
  // instead of the full transcript; the merged result replaces the cache
  // entry. Returns { unchanged, session, parts, busy } — parts/busy are the
  // server's CURRENT live-turn state (fresher than anything cached), so the
  // caller can paint an in-progress turn straight from this response; session
  // is null only when the server had nothing.
  async function fetch(id) {
    const cached = cache.get(id);
    const r = await api.getSession(id, cached ? { since: cached.updatedAt, after: (cached.messages || []).length } : undefined);
    if (!r) return { session: null };
    if (r.unchanged) return { unchanged: true, session: cached, parts: r.parts, busy: r.busy };
    let s = r;
    if (r.delta) {
      // Merge: the server sent only the messages past our cursor.
      const { delta, after, messages, ...meta } = r;
      s = { ...cached, ...meta, messages: [...(cached.messages || []).slice(0, after), ...messages] };
    }
    cache.set(id, s);
    return { session: s, parts: r.parts, busy: r.busy };
  }

  // Quietly refetch a background session so switching to it later is instant
  // AND current (the cache would otherwise show a pre-turn transcript first).
  async function refresh(id) {
    try { await fetch(id); } catch { /* next open fetches */ }
  }

  return { get: (id) => cache.get(id), delete: (id) => cache.delete(id), fetch, refresh };
}
