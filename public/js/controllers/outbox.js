// Outbox: in-flight sends, tracked per session. Every send lives here from
// "user hit send" until the server accepts it, so switching sessions (or a
// failure) can't lose it invisibly. The chat renders outbox entries after
// history with a "Sending… / Retry / Discard" status row (main.js listens to
// the "outbox" event and hands back a UI handle).
//
// FIFO per session: every send (its uploads included) waits for the previous
// one, so a quick second message can never overtake a first one whose file is
// still uploading — order on the wire is the order the user sent.

export function createOutbox({ api, store, emitter, setBusy }) {
  let seq = 0;
  const bySession = new Map(); // sessionId -> [{ key, sessionId, text, raw, controls, agentId, status, handle }]
  const chains = new Map();    // sessionId -> tail promise of the send queue

  const getOutbox = (id) => bySession.get(id) || [];

  function remove(entry) {
    const list = bySession.get(entry.sessionId);
    if (!list) return;
    const i = list.indexOf(entry);
    if (i !== -1) list.splice(i, 1);
    if (!list.length) bySession.delete(entry.sessionId);
  }

  function fail(entry, msg) {
    entry.status = "failed";
    if (entry.handle) entry.handle.update("failed");
    emitter.emit("toast", msg);
  }

  // Post the turn once its attachments are on the laptop. Attachments picked in
  // the composer are usually already uploaded (eagerly, on pick) or in flight —
  // use/await that result. Anything without one (a failed eager upload, a
  // Retry) uploads here. Kept re-runnable so Retry works.
  async function transmit(entry) {
    const id = entry.sessionId;
    entry.status = "sending";
    if (entry.handle) entry.handle.update("sending");
    const attachments = [];
    for (const a of entry.raw) {
      let up = a.up || null;
      if (!up && a.promise) up = await a.promise.catch(() => null); // eager upload still in flight
      if (!up || !up.path) {
        try { up = await api.upload(a.name, a.dataBase64, id); } catch { up = null; }
        if (up && up.path) a.up = up; // remember, so another Retry won't re-upload
      }
      if (!up || !up.path) return fail(entry, `Couldn't upload ${a.name} — tap Retry`);
      attachments.push({ name: up.name, path: up.path, url: up.url || "" });
    }
    if (attachments.length && store.get().activeId === id) store.set((s) => ({ files: { ...s.files, uploaded: [...s.files.uploaded, ...attachments] } }));
    setBusy(id, true);
    let r = null;
    try { r = await api.send(id, entry.text, entry.controls, attachments, entry.agentId); }
    catch {
      setBusy(id, false);
      return fail(entry, "Couldn't send — check your connection, then tap Retry");
    }
    // Raced another turn (it started between our check and the send): the session
    // IS busy, so keep the entry failed for a manual retry once it finishes.
    if (r && r.error === "busy") return fail(entry, "The agent is mid-turn — tap Retry when it finishes");
    remove(entry);
    if (entry.handle) entry.handle.update("sent");
  }

  function enqueue(id, fn) {
    const tail = (chains.get(id) || Promise.resolve()).then(fn).catch(() => {});
    chains.set(id, tail);
    return tail;
  }

  // Deliver one turn to a specific session through its outbox.
  function deliver(id, text, raw, controls, agentId) {
    const entry = { key: ++seq, sessionId: id, text, raw: raw || [], controls, agentId, status: "sending", handle: null };
    if (!bySession.has(id)) bySession.set(id, []);
    bySession.get(id).push(entry);
    if (store.get().activeId === id) emitter.emit("outbox", entry); // render now; main.js sets entry.handle
    return enqueue(id, () => transmit(entry));
  }

  const retry = (entry) => { if (entry.status === "failed") enqueue(entry.sessionId, () => transmit(entry)); };
  const discard = (entry) => { remove(entry); if (entry.handle) entry.handle.remove(); };
  // A deleted session's pending sends and send queue go with it.
  const forget = (id) => { bySession.delete(id); chains.delete(id); };

  return { deliver, getOutbox, retry, discard, forget };
}
