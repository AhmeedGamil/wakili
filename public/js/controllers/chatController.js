// Business logic. Orchestrates the api, the store, and the live stream. Knows
// nothing about the DOM — it mutates state (store) and emits semantic streaming
// events (emitter) that UI components subscribe to.

import { parseClaudeEvent } from "../core/streamParser.js";

// Tools gated by the permission hook — their permission card already shows the
// tool + input, so we suppress the redundant tool chip for them. AskUserQuestion
// is shown as its own interactive question card, so it's suppressed here too.
const GATED = new Set(["Bash", "Write", "Edit", "MultiEdit", "NotebookEdit", "AskUserQuestion", "mcp__remoteagent__ask_options"]);

// The user's last-chosen permission mode, remembered as a global default so new
// sessions open in the same posture (notes #5). Per-session changes still win.
const PERM_KEY = "ra-perm-mode";

// "Allow always" — a global switch. When on, an incoming permission card is
// approved automatically (the Allow button is clicked for you). Remembered
// across sessions/restarts in localStorage.
const AUTO_KEY = "ra-auto-allow";
// The last agent + controls actually used, applied as defaults for new chats so
// they keep your model/effort/thinking/mode instead of snapping back to the
// agent's built-in default (e.g. Sonnet).
const LAST_KEY = "ra-last-config";

export function createChatController({ api, store, emitter }) {
  let es = null;          // the ONE multiplexed EventSource (all sessions)
  let streamReady = false; // set once the stream connects; resyncs wait for it
  // Identifies this tab: the resync snapshot is tagged with it, so a snapshot
  // another tab requested doesn't double-render here.
  const clientId = Math.random().toString(36).slice(2) + Date.now().toString(36);
  // Between switching to a session and its snapshot arriving on the stream, the
  // session's content events are ignored — the snapshot (published into the same
  // ordered stream) already contains them; rendering both would duplicate.
  let awaitingSnapshot = false;
  let snapTimer = 0;

  const loadLastConfig = () => { try { return JSON.parse(localStorage.getItem(LAST_KEY) || "null"); } catch { return null; } };
  const saveLastConfig = () => { const s = store.get(); localStorage.setItem(LAST_KEY, JSON.stringify({ agentId: s.agentId, controls: s.controls })); };

  // Build an agent's control values from its declared defaults, then layer on the
  // last-used controls for that agent, then any explicit overrides.
  function defaultsFor(agent, extra) {
    const c = {};
    for (const [k, ctl] of Object.entries(agent?.controls || {})) c[k] = ctl.default ?? "";
    const last = loadLastConfig();
    if (last && last.agentId === agent?.id && last.controls) {
      for (const k of Object.keys(c)) if (last.controls[k] != null) c[k] = last.controls[k];
    }
    if ("permissionMode" in c) { const g = localStorage.getItem(PERM_KEY); if (g) c.permissionMode = g; }
    return Object.assign(c, extra || {});
  }

  // Guard against stale saved values: if a control's saved value (from localStorage
  // or a session's stored controls) is no longer among its current options — e.g. a
  // model id that changed to an alias — reset it to that control's default. Without
  // this the picker can't match the value and falls back to showing the agent label.
  function normalizeControls(agent, controls) {
    const out = { ...controls };
    for (const [k, ctl] of Object.entries(agent?.controls || {})) {
      if (!Array.isArray(ctl.options)) continue;
      if (ctl.options.some((o) => o.value === out[k])) continue; // already a valid option
      // Legacy heal: map an old full model id ("claude-<family>-…") back to its
      // family alias so the prior choice is preserved; else fall back to default.
      const m = typeof out[k] === "string" && out[k].match(/^claude-([a-z]+)-/);
      const alias = m && ctl.options.find((o) => o.value === m[1]);
      out[k] = alias ? alias.value : (ctl.default ?? "");
    }
    return out;
  }

  // Mark a session busy/idle in the per-session map (drives the composer + badges).
  const setBusy = (id, on) => store.set((s) => ({ busyIds: { ...s.busyIds, [id]: !!on } }));
  // Live-adjust a session's pending-cards badge (the poll reconciles it too).
  const bumpPending = (id, d) => store.set((s) => ({ sessions: s.sessions.map((x) => x.id === id ? { ...x, pending: Math.max(0, (x.pending || 0) + d) } : x) }));
  const markUnread = (id) => store.set((s) => ({ unreadIds: { ...s.unreadIds, [id]: true } }));

  // Quietly refetch a background session so switching to it later is instant
  // AND current (the cache would otherwise show a pre-turn transcript first).
  async function refreshCache(id) {
    try { const s = await api.getSession(id); if (s) sessionCache.set(id, s); } catch { /* next open fetches */ }
  }

  // Ask the server to publish `id`'s live state into the stream; gate content
  // events until it lands. The gate self-clears on failure or timeout so a lost
  // snapshot can't mute the session forever.
  function requestResync(id) {
    if (!streamReady) return; // the stream's "connected" handler resyncs on open
    awaitingSnapshot = true;
    clearTimeout(snapTimer);
    snapTimer = setTimeout(() => { awaitingSnapshot = false; }, 10000);
    api.resync(id, clientId).catch(() => { awaitingSnapshot = false; });
  }

  // The active session renders live into the chat.
  function handleActive(id, ev) {
    const gated = awaitingSnapshot; // busy bookkeeping still runs while gated
    switch (ev.kind) {
      case "turnStart": setBusy(id, true); if (!gated) emitter.emit("turnStart"); break;
      case "snapshot":
        if (ev.client && ev.client !== clientId) break; // another tab's resync
        awaitingSnapshot = false;
        clearTimeout(snapTimer);
        setBusy(id, ev.busy);
        emitter.emit("snapshot", { parts: ev.parts, busy: ev.busy });
        // Re-raise the cards still awaiting an answer (the dock dedupes by id).
        for (const c of ev.pending || []) {
          if (c.subtype === "permission_request") emitter.emit("permission", { id: c.id, tool: c.tool, input: c.input, autoAllow: store.get().autoAllow });
          else if (c.subtype === "question_request") emitter.emit("question", { id: c.id, questions: c.input?.questions || [] });
        }
        break;
      // A queued message waiting to go out means the turn was interrupted to make
      // room for it (vs. a plain stop with nothing pending).
      case "stopped": if (!gated) emitter.emit("stopped", { interrupted: !!store.get().queued[id] }); break;
      case "text": if (!gated) emitter.emit("text", ev.text); break;
      case "thinking": if (!gated) emitter.emit("thinking", ev.text); break;
      case "tools": if (!gated) ev.tools.forEach((t) => { if (!GATED.has(t.name)) emitter.emit("tool", t); }); break;
      case "tool": if (!gated) emitter.emit("tool", ev.tool); break; // gateway-issued chip (auto-approved gated tool)
      case "toolResult": if (!gated) emitter.emit("toolResult", { id: ev.id, output: ev.output, isError: ev.isError }); break;
      case "question": if (!gated) emitter.emit("question", { id: ev.id, questions: ev.questions }); break;
      case "permission": if (!gated) emitter.emit("permission", { id: ev.id, tool: ev.tool, input: ev.input, autoAllow: store.get().autoAllow }); break;
      case "requestResolved": if (!gated) emitter.emit("requestResolved", { id: ev.id }); break;
      case "file":
        store.set((s) => ({ files: { ...s.files, received: [...s.files.received, ev.file] } }));
        if (!gated) emitter.emit("file", ev.file);
        refreshFiles(); // keep the sidebar files list current
        break;
      case "error": console.warn("[agent]", ev.text); break;
      case "turnEnd":
        setBusy(id, false);
        if (ev.title) {
          store.set((s) => ({ activeSession: s.activeSession ? { ...s.activeSession, title: ev.title } : null }));
        }
        if (!gated) emitter.emit("turnEnd");
        refreshCache(id); // keep the transcript cache current for the next switch
        refreshSessions();
        refreshFiles(); // any files this turn used/produced
        flushQueued(id); // a queued message (if any) goes out now the turn is done
        break;
    }
  }

  // Background sessions don't render — they update badges, caches, and queues.
  function handleBackground(id, ev) {
    switch (ev.kind) {
      case "turnStart": setBusy(id, true); break;
      case "permission": case "question": bumpPending(id, +1); break;
      case "requestResolved": bumpPending(id, -1); break;
      case "file": refreshFiles(); break;
      case "turnEnd":
        setBusy(id, false);
        markUnread(id);      // a turn finished while you were elsewhere
        refreshCache(id);    // so switching there shows the new turn instantly
        refreshSessions();   // fresh titles + authoritative pending counts
        refreshFiles();
        flushQueued(id);     // deliver its queued message right away (no poll wait)
        break;
    }
  }

  function openStream() {
    if (es) es.close();
    streamReady = false;
    es = api.stream();
    let firstConnect = true;
    es.onmessage = (e) => {
      const raw = JSON.parse(e.data);
      const ev = parseClaudeEvent(raw);
      if (ev.kind === "connected") {
        // EventSource auto-reconnects (network blip / server restart). The first
        // "connected" is the normal open; later ones are reconnects — re-sync so
        // nothing missed while disconnected stays missing.
        streamReady = true;
        const id = store.get().activeId;
        if (firstConnect) { firstConnect = false; if (id) requestResync(id); }
        else { refreshSessions(); if (id) openSession(id); }
        return;
      }
      const sid = raw.sessionId;
      if (!sid) return;
      if (sid === store.get().activeId) handleActive(sid, ev);
      else handleBackground(sid, ev);
    };
  }

  async function loadAgents() {
    const agents = await api.agents();
    const last = loadLastConfig();
    const agent = (last && agents.find((a) => a.id === last.agentId)) || agents.find((a) => a.id === "claude") || agents[0];
    store.set({ agents, agentId: agent?.id || "claude", controls: normalizeControls(agent, defaultsFor(agent)) });
  }

  async function refreshSessions() {
    // Offline is normal here (background poll / best-effort refresh after a turn)
    // — keep the last known list instead of surfacing an error.
    let sessions;
    try { sessions = await api.listSessions(); } catch { return; }
    // Reconcile per-session busy from the server's view (keeps background badges
    // fresh). EXCEPT the active session: its busy is driven by its live stream
    // (turn_start/turn_end) and local sends, so a stale poll must not overwrite it
    // — otherwise the Stop button flickers back to Send right after you send.
    const prev = store.get().busyIds;
    const activeId = store.get().activeId;
    const busyIds = {};
    for (const s of sessions) if (s.busy) busyIds[s.id] = true;
    if (activeId && activeId in prev) busyIds[activeId] = prev[activeId];
    store.set({ sessions, busyIds });
    // A queued message for a session that finished in the background goes out now.
    for (const sid of Object.keys(store.get().queued)) if (!busyIds[sid]) flushQueued(sid);
  }

  // Transcripts from previous opens, so switching chats works instantly (and at
  // all) on a slow or dead connection; the fresh fetch reconciles over it.
  const sessionCache = new Map(); // id -> last-fetched session object
  let openSeq = 0; // guards against a slow response landing after a newer switch

  // Make `s` the on-screen session: state, history render, then a resync so the
  // in-progress turn + pending cards arrive in order on the multiplexed stream.
  function applySession(id, s) {
    store.set((st) => {
      const agentId = s.agentId || st.agentId;
      const agent = st.agents.find((a) => a.id === agentId);
      const unreadIds = { ...st.unreadIds }; delete unreadIds[id];
      return { activeId: id, activeSession: s, agentId, unreadIds, controls: normalizeControls(agent, { ...defaultsFor(agent), ...(s.controls || {}) }), files: { received: [], uploaded: [] } };
    });
    saveLastConfig(); // the session you're on becomes the default for new chats
    emitter.emit("historyLoaded", s.messages);
    requestResync(id);
  }

  async function openSession(id, attempt = 0) {
    const seq = ++openSeq;
    const cached = sessionCache.get(id);
    if (cached) applySession(id, cached); // optimistic: switch now, reconcile below
    let s = null, err = null;
    try { s = await api.getSession(id); } catch (e) { err = e; }
    if (seq !== openSeq) return; // the user has already switched elsewhere
    if (!s) {
      // A 404 means the session is gone (deleted on another device) — don't retry.
      if (err && String(err).includes("404")) { sessionCache.delete(id); refreshSessions(); return; }
      if (cached) return; // the cached view stays up; the stream reconnects on its own
      if (attempt === 0) emitter.emit("toast", "Can't connect — retrying…");
      setTimeout(() => { if (seq === openSeq) openSession(id, attempt + 1); }, 2500);
      return;
    }
    sessionCache.set(id, s);
    // Skip the re-render (and stream reopen) when the fresh copy matches what the
    // cache already put on screen — avoids a needless flicker on every switch.
    const same = cached && cached.updatedAt === s.updatedAt && (cached.messages || []).length === (s.messages || []).length;
    if (same) store.set({ activeSession: s });
    else applySession(id, s);
    refreshSessions();
  }

  // Create a new chat, optionally bound to a project folder (cwd). The folder is
  // chosen at creation time — changing it later resets the agent's resume thread.
  async function newSession(cwd) {
    const s = await api.createSession({ agentId: store.get().agentId, cwd: cwd || null });
    await refreshSessions();
    await openSession(s.id);
    emitter.emit("focusInput");
  }

  async function deleteSession(id) {
    const wasActive = store.get().activeId === id;
    sessionCache.delete(id);
    outbox.delete(id);
    sendChain.delete(id);
    store.set((s) => { const u = { ...s.unreadIds }; delete u[id]; return { unreadIds: u }; });
    await api.deleteSession(id);
    await refreshSessions();
    if (wasActive) {
      const list = store.get().sessions;
      if (list.length) await openSession(list[0].id);
      else await newSession();
    }
  }

  // ---- outbox: in-flight sends, tracked per session ----
  // Every send lives here from "user hit send" until the server accepts it, so
  // switching sessions (or a failure) can't lose it invisibly. The chat renders
  // outbox entries after history with a "Sending… / Retry / Discard" status row
  // (main.js listens to the "outbox" event and hands back a UI handle).
  let outboxSeq = 0;
  const outbox = new Map(); // sessionId -> [{ key, sessionId, text, raw, controls, agentId, status, handle }]
  const getOutbox = (id) => outbox.get(id) || [];

  function removeOutbox(entry) {
    const list = outbox.get(entry.sessionId);
    if (!list) return;
    const i = list.indexOf(entry);
    if (i !== -1) list.splice(i, 1);
    if (!list.length) outbox.delete(entry.sessionId);
  }

  function failOutbox(entry, msg) {
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
      if (!up || !up.path) return failOutbox(entry, `Couldn't upload ${a.name} — tap Retry`);
      attachments.push({ name: up.name, path: up.path, url: up.url || "" });
    }
    if (attachments.length && store.get().activeId === id) store.set((s) => ({ files: { ...s.files, uploaded: [...s.files.uploaded, ...attachments] } }));
    setBusy(id, true);
    let r = null;
    try { r = await api.send(id, entry.text, entry.controls, attachments, entry.agentId); }
    catch {
      setBusy(id, false);
      return failOutbox(entry, "Couldn't send — check your connection, then tap Retry");
    }
    // Raced another turn (it started between our check and the send): the session
    // IS busy, so keep the entry failed for a manual retry once it finishes.
    if (r && r.error === "busy") return failOutbox(entry, "The agent is mid-turn — tap Retry when it finishes");
    removeOutbox(entry);
    if (entry.handle) entry.handle.update("sent");
  }

  // FIFO per session: every send (its uploads included) waits for the previous
  // one, so a quick second message can never overtake a first one whose file is
  // still uploading — order on the wire is the order the user sent.
  const sendChain = new Map(); // sessionId -> tail promise of the send queue
  function enqueueSend(id, fn) {
    const tail = (sendChain.get(id) || Promise.resolve()).then(fn).catch(() => {});
    sendChain.set(id, tail);
    return tail;
  }

  // Deliver one turn to a specific session through its outbox.
  function deliver(id, text, raw, controls, agentId) {
    const entry = { key: ++outboxSeq, sessionId: id, text, raw: raw || [], controls, agentId, status: "sending", handle: null };
    if (!outbox.has(id)) outbox.set(id, []);
    outbox.get(id).push(entry);
    if (store.get().activeId === id) emitter.emit("outbox", entry); // render now; main.js sets entry.handle
    return enqueueSend(id, () => transmit(entry));
  }

  const retryOutbox = (entry) => { if (entry.status === "failed") enqueueSend(entry.sessionId, () => transmit(entry)); };
  const discardOutbox = (entry) => { removeOutbox(entry); if (entry.handle) entry.handle.remove(); };

  // Composer send. If the active session is busy, queue the message (Claude-Code
  // style) instead of erroring — it goes out when the turn ends or is stopped.
  // Run "!cmd" directly on the laptop shell (in the session's folder) and show
  // the command + its output in the chat. Bypasses the agent entirely — no LLM,
  // no tokens, not queued.
  async function runExec(id, command) {
    if (store.get().activeId === id) emitter.emit("userMessage", "! " + command);
    let r = null;
    try { r = await api.exec(id, command); } catch { r = null; }
    if (store.get().activeId === id) emitter.emit("exec", { output: r ? (r.output || "") : "(failed to run)", ok: !!(r && r.ok) });
  }

  // Terminal page: run a command in an explicit folder with stateful `cd`
  // tracking. Bypasses the agent AND the chat (nothing is persisted server-side).
  // Returns { ok, output, cwd } — the (possibly changed) cwd flows back to the UI.
  async function execTerm(command, cwd) {
    const id = store.get().activeId;
    if (!id) return { ok: false, output: "No active session.", cwd };
    try { return await api.term(id, command, cwd); }
    catch { return { ok: false, output: "(failed to run)", cwd }; }
  }

  async function send(text, raw) {
    const st = store.get();
    raw = raw || [];
    if ((!text && !raw.length) || !st.activeId) return;
    // "!cmd" → direct shell command, straight to the server (skips agent + queue).
    if (text && text[0] === "!" && text.slice(1).trim()) return runExec(st.activeId, text.slice(1).trim());
    if (st.busyIds[st.activeId]) {
      // Queue is a list: each queued message goes out (one per turn end) in
      // order — sending twice mid-turn no longer overwrites the first message.
      const q = { text, raw, controls: st.controls, agentId: st.agentId };
      store.set((s) => ({ queued: { ...s.queued, [st.activeId]: [...(s.queued[st.activeId] || []), q] } }));
      emitter.emit("queued", q);
      return;
    }
    await deliver(st.activeId, text, raw, st.controls, st.agentId);
  }

  // Send a session's oldest queued message (if any) now that it's free; the
  // rest stay queued and follow, one per turn end.
  function flushQueued(id) {
    const list = store.get().queued[id];
    if (!list || !list.length) return;
    const [q, ...rest] = list;
    store.set((s) => { const next = { ...s.queued }; if (rest.length) next[id] = rest; else delete next[id]; return { queued: next }; });
    deliver(id, q.text, q.raw, q.controls, q.agentId);
  }

  function cancelQueued() {
    const id = store.get().activeId;
    if (!id) return;
    store.set((s) => { const next = { ...s.queued }; delete next[id]; return { queued: next }; });
  }

  // Interrupt the active session's running turn (the queued message, if any,
  // then goes out via turn_end — "stop, and the agent sees my waiting message").
  function stopActive() {
    const id = store.get().activeId;
    if (id) api.stop(id);
  }

  function setControl(key, value) {
    if (key === "permissionMode") localStorage.setItem(PERM_KEY, value); // remember as the global default
    store.set((s) => ({ controls: { ...s.controls, [key]: value } }));
    saveLastConfig();
  }

  // Toggle the global "Allow always" switch (persisted; read on the next permission).
  function setAutoAllow(on) {
    localStorage.setItem(AUTO_KEY, on ? "1" : "");
    store.set({ autoAllow: !!on });
  }

  function setAgent(id) {
    const agent = store.get().agents.find((a) => a.id === id);
    store.set({ agentId: id, controls: defaultsFor(agent) });
    saveLastConfig();
  }

  // Pick a model from the tree: switching agent resets that agent's controls to
  // defaults; within the same agent, just change the model.
  function pickModel(agentId, model) {
    const st = store.get();
    if (agentId !== st.agentId) {
      const agent = st.agents.find((a) => a.id === agentId);
      store.set({ agentId, controls: defaultsFor(agent, { model }) });
    } else {
      store.set((s) => ({ controls: { ...s.controls, model } }));
    }
    saveLastConfig();
  }

  function answerPermission(requestId, decision, tool) {
    const id = store.get().activeId;
    if (id) api.answerPermission(id, requestId, decision, tool);
  }

  // AskUserQuestion reply: send the chosen answer text back to the parked request
  // (resolved in-turn via the hook), not as a brand-new chat message.
  function answerQuestion(requestId, answer) {
    const id = store.get().activeId;
    if (id) api.answerQuestion(id, requestId, answer);
  }

  // Project-folder picker: browse the laptop FS, then set the active session's cwd.
  const browseFolders = (path) => api.folders(path);
  const createFolder = (parent, name) => api.createFolder(parent, name);

  // The global files registry (uploads + agent-sent), shown in the sidebar.
  async function refreshFiles() { try { store.set({ allFiles: await api.files() }); } catch { /* ignore */ } }

  // Device controls (lock the laptop screen; keep it awake while you're away).
  async function refreshPower() { try { store.set({ power: await api.power() }); } catch { /* ignore */ } }
  async function lockScreen() { return api.lockScreen(); }
  async function screenOff() { return api.screenOff(); }
  async function shutdownComputer() { return api.shutdown(); }
  async function setKeepAwake(on) { try { const p = await api.keepAwake(on); store.set({ power: p }); return p; } catch { return null; } }

  // Start-at-login: the gateway registers itself with the OS's per-user
  // autostart mechanism, so this setting lives on the laptop, not the phone.
  async function refreshAutostart() { try { store.set({ autostart: await api.autostart() }); } catch { /* ignore */ } }
  async function setAutostart(on) { try { const a = await api.setAutostart(on); store.set({ autostart: a }); return a; } catch { return null; } }

  // Undo an eager upload: the user removed the attachment before sending, so
  // delete the file from the laptop and drop it from the sidebar files list.
  async function removeUpload(up) {
    try { await api.deleteUpload(up.path); } catch { /* best effort */ }
    refreshFiles();
  }
  async function setCwd(cwd) {
    const id = store.get().activeId;
    if (!id) return;
    const s = await api.setCwd(id, cwd);
    if (s && s.id) store.set({ activeSession: s });
  }

  async function init() {
    openStream(); // the single multiplexed stream, up for the whole app life
    await loadAgents();
    await refreshSessions();
    refreshFiles();
    refreshPower();
    refreshAutostart();
    const list = store.get().sessions;
    // The sidebar's refresh button stashes the open session before reloading;
    // honor it so a refresh lands back in the same chat, not the newest one.
    let resume = null;
    try { resume = sessionStorage.getItem("ra-resume-sid"); sessionStorage.removeItem("ra-resume-sid"); } catch { /* private mode */ }
    if (resume && list.some((s) => s.id === resume)) await openSession(resume);
    else if (list.length) await openSession(list[0].id);
    else await newSession();
    // Light poll as a safety net (authoritative busy/pending counts, titles);
    // the live stream handles moment-to-moment updates.
    setInterval(() => { refreshSessions().catch(() => {}); }, 5000);
    // Version-skew guard: if the API answers but the live stream never connected,
    // the running gateway predates /api/stream — replies would silently never
    // render. Say so instead of looking broken.
    setTimeout(() => {
      if (streamReady) return;
      api.agents().then(() => emitter.emit("toast", "Live updates aren't connecting — restart the gateway server to finish updating")).catch(() => {});
    }, 6000);
  }

  return { init, openSession, newSession, deleteSession, send, execTerm, stopActive, cancelQueued, setControl, setAutoAllow, setAgent, pickModel, answerPermission, answerQuestion, browseFolders, createFolder, setCwd, lockScreen, screenOff, shutdownComputer, setKeepAwake, setAutostart, removeUpload, getOutbox, retryOutbox, discardOutbox };
}
