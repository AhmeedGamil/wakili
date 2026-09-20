// Business logic. Orchestrates the api, the store, and the live stream. Knows
// nothing about the DOM — it mutates state (store) and emits semantic streaming
// events (emitter) that UI components subscribe to.

import { parseClaudeEvent } from "../core/streamParser.js";
import { recorder } from "../core/recorder.js";
import { KEYS, setPref, takeSession } from "../core/prefs.js";
import { loadLastConfig, saveLastConfig as persistLastConfig, rememberPermMode, defaultsFor, normalizeControls } from "./agentConfig.js";
import { createSessionCache } from "./sessionCache.js";
import { createOutbox } from "./outbox.js";
import { createDeviceController } from "./device.js";

// Tools gated by the permission hook — their permission card already shows the
// tool + input, so we suppress the redundant tool chip for them. AskUserQuestion
// is shown as its own interactive question card, so it's suppressed here too.
const GATED = new Set(["Bash", "Write", "Edit", "MultiEdit", "NotebookEdit", "AskUserQuestion", "mcp__wakili__ask_options", "ask_options"]);

// Blanket auto-approval of permission cards is gone from the UI. Clear the two
// old client-side switches on sight so nobody is left silently auto-allowing
// from a setting nothing renders any more.
setPref("ra-auto-allow", null);
setPref("ra-auto-allow-sessions", null);

export function createChatController({ api, store, emitter }) {
  let es = null;          // the ONE multiplexed EventSource (all sessions)
  let streamReady = false; // set once the stream connects; resyncs wait for it
  // Identifies this tab: the resync snapshot is tagged with it, so a snapshot
  // another tab requested doesn't double-render here.
  const clientId = Math.random().toString(36).slice(2) + Date.now().toString(36);
  // An anomaly uploads this tab's ring to the gateway, so one /api/debug/stream
  // read shows the phone's and the laptop's view of the same turn side by side.
  recorder.setUploader(api.debugReport);
  // There is no render gate. Switching into a session used to mute its content
  // events until the resync snapshot came back — up to 10s on a bad link, which
  // is what made a turn appear frozen at the moment you switched. The gate only
  // ever existed to stop a delta being drawn twice (once live, once from the
  // snapshot), and renderSnapshot has always been a clearLive() + full redraw,
  // so it was already idempotent. With the seq guard refusing out-of-order
  // snapshots, the stream's own ordering is enough: deltas draw immediately,
  // and a snapshot that lands after them wipes and redraws the same content.

  const saveLastConfig = () => { const s = store.get(); persistLastConfig(s.agentId, s.controls); };

  // Mark a session busy/idle in the per-session map (drives the composer + badges).
  const setBusy = (id, on) => store.set((s) => ({ busyIds: { ...s.busyIds, [id]: !!on } }));
  // Live-adjust a session's pending-cards badge (the poll reconciles it too).
  const bumpPending = (id, d) => store.set((s) => ({ sessions: s.sessions.map((x) => x.id === id ? { ...x, pending: Math.max(0, (x.pending || 0) + d) } : x) }));
  const markUnread = (id) => store.set((s) => ({ unreadIds: { ...s.unreadIds, [id]: true } }));

  // Local mirror of the server's activeTurns: every session's in-progress turn,
  // assembled from the multiplexed stream — INCLUDING background sessions, whose
  // content events were previously thrown away. Switching back to a working
  // session paints from this buffer instantly (zero round trips, like the VS
  // Code extension); the resync snapshot stays authoritative and replaces it.
  // Only these interactive tools stay out of parts (they render as their own
  // card via the gateway's question/permission flow) — mirrors the server's
  // skip set, NOT the wider GATED display set: Bash/Write/… DO belong here.
  const QA_TOOLS = new Set(["AskUserQuestion", "mcp__wakili__ask_options", "ask_options"]);
  const liveParts = new Map(); // sessionId -> parts[] (same shape the server assembles)

  // The gateway's ordering stamp (src/sse.mjs) for the newest event folded into
  // each session's buffer — a high-water mark, so it never moves backwards.
  const appliedSeq = new Map(); // sessionId -> seq

  /**
   * Flag a snapshot stamped BEFORE state we have already folded in. Two resyncs
   * can be answered in the opposite order (HTTP guarantees no ordering), and
   * adopting the older one rewinds the turn — deltas then append onto that
   * rewound base and the middle of the answer is gone for good. A newer
   * snapshot contains everything an older one had, so dropping the older one
   * can never lose information.
   *
   * Decided ONCE here, before routing, so the buffer and the render path can
   * never disagree about which snapshots they took.
   */
  function markStale(id, ev) {
    if (ev.kind !== "snapshot" || ev.seq == null) return;
    const applied = appliedSeq.get(id);
    if (applied == null || ev.seq >= applied) return;
    ev.stale = true;
    recorder.anomaly("stale-snapshot", {
      sessionId: id, snapshotSeq: ev.seq, appliedSeq: applied,
      parts: (ev.parts || []).length, client: ev.client || "",
    });
  }

  function accumulate(id, ev) {
    switch (ev.kind) {
      case "turnStart": liveParts.set(id, []); break;
      // Any snapshot is server truth for the whole turn — adopt it even when it
      // was another tab's resync (the render path filters by client; we don't).
      case "snapshot": {
        if (ev.stale) break; // older than what we hold — see markStale
        liveParts.set(id, (ev.parts || []).map((p) => ({ ...p })));
        break;
      }
      case "turnEnd": liveParts.delete(id); break; // the turn lives in the transcript now
      case "text": case "thinking": {
        const parts = liveParts.get(id) || [];
        if (!liveParts.has(id)) liveParts.set(id, parts);
        const type = ev.kind;
        const l = parts[parts.length - 1];
        if (l && l.type === type) l.text += ev.text;
        else parts.push({ type, text: ev.text });
        break;
      }
      case "tools": {
        const parts = liveParts.get(id) || [];
        if (!liveParts.has(id)) liveParts.set(id, parts);
        for (const t of ev.tools) if (!QA_TOOLS.has(t.name)) parts.push({ type: "tool", name: t.name, input: t.input, id: t.id });
        break;
      }
      // NOT "tool": the gateway chip re-announces a tool_use already pushed by
      // its assistant event — buffering it too would duplicate the card.
      case "toolResult": {
        const part = (liveParts.get(id) || []).find((p) => p.type === "tool" && p.id === ev.id);
        if (part) { part.output = ev.output; part.isError = ev.isError; }
        break;
      }
    }
    // Advance the session's watermark past everything we've now seen. Kept
    // outside the switch on purpose: lifecycle events don't change parts, but
    // they DO prove the stream has moved past that point, which is exactly what
    // makes a later-arriving older snapshot recognisable as stale. Monotonic —
    // a stale event must never drag the mark backwards, or the next stale one
    // after it would look fresh.
    if (ev.seq != null) {
      const applied = appliedSeq.get(id);
      if (applied == null || ev.seq > applied) appliedSeq.set(id, ev.seq);
    }
    recorder.event(id, ev);
  }

  // Ask the server to publish `id`'s live state into the stream.
  // A failed POST RETRIES (bounded backoff): the snapshot is the only full-state
  // re-delivery after a gap, so giving up on one flaky request left everything
  // streamed while away invisible until turn end — and a missed turn_end stuck
  // the composer on Stop.
  function requestResync(id, attempt = 0) {
    if (!streamReady) return; // the stream's "connected" handler resyncs on open
    recorder.note("resync-request", { sessionId: id, attempt, appliedSeq: appliedSeq.get(id) });
    api.resync(id, clientId).catch(() => {
      if (attempt < 5) setTimeout(() => { if (store.get().activeId === id) requestResync(id, attempt + 1); }, 1000 * 2 ** attempt);
    });
  }

  // The active session renders live into the chat.
  function handleActive(id, ev) {
    switch (ev.kind) {
      case "turnStart": setBusy(id, true); emitter.emit("turnStart"); break;
      case "snapshot":
        if (ev.client && ev.client !== clientId) break; // another tab's resync
        if (ev.stale) break; // older than what's on screen — see markStale
        // busy comes from the same instant as the parts, so they move together.
        setBusy(id, ev.busy);
        emitter.emit("snapshot", { parts: ev.parts, busy: ev.busy });
        // Re-raise the cards still awaiting an answer (the dock dedupes by id).
        for (const c of ev.pending || []) {
          if (c.subtype === "permission_request") emitter.emit("permission", { id: c.id, tool: c.tool, input: c.input });
          else if (c.subtype === "question_request") emitter.emit("question", { id: c.id, questions: c.input?.questions || [] });
        }
        break;
      // A queued message waiting to go out means the turn was interrupted to make
      // room for it (vs. a plain stop with nothing pending).
      case "stopped": emitter.emit("stopped", { interrupted: !!store.get().queued[id] }); break;
      case "text": emitter.emit("text", ev.text); break;
      case "thinking": emitter.emit("thinking", ev.text); break;
      case "tools": ev.tools.forEach((t) => { if (!GATED.has(t.name)) emitter.emit("tool", t); }); break;
      case "tool": emitter.emit("tool", ev.tool); break; // gateway-issued chip (auto-approved gated tool)
      case "toolResult": emitter.emit("toolResult", { id: ev.id, output: ev.output, isError: ev.isError }); break;
      case "question": emitter.emit("question", { id: ev.id, questions: ev.questions }); break;
      case "permission": emitter.emit("permission", { id: ev.id, tool: ev.tool, input: ev.input }); break;
      case "requestResolved": emitter.emit("requestResolved", { id: ev.id }); break;
      case "file":
        store.set((s) => ({ files: { ...s.files, received: [...s.files.received, ev.file] } }));
        emitter.emit("file", ev.file);
        refreshFiles(); // keep the sidebar files list current
        break;
      case "error": console.warn("[agent]", ev.text); break;
      // The gateway saved this session (user message persisted, rename from
      // another device…) — refresh our copy; the delta fetch keeps it cheap.
      case "sessionUpdated":
        if (ev.title) store.set((s) => ({
          sessions: s.sessions.map((x) => x.id === id ? { ...x, title: ev.title, updatedAt: ev.updatedAt } : x),
          activeSession: s.activeSession ? { ...s.activeSession, title: ev.title } : null,
        }));
        sessionCache.refresh(id);
        break;
      case "turnEnd":
        setBusy(id, false);
        if (ev.title) {
          store.set((s) => ({ activeSession: s.activeSession ? { ...s.activeSession, title: ev.title } : null }));
        }
        emitter.emit("turnEnd");
        sessionCache.refresh(id); // keep the transcript cache current for the next switch
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
      // Push invalidation: refetch only when we hold a stale copy — never
      // download transcripts for sessions that were never opened.
      case "sessionUpdated": {
        if (ev.title) store.set((s) => ({ sessions: s.sessions.map((x) => x.id === id ? { ...x, title: ev.title, updatedAt: ev.updatedAt } : x) }));
        const c = sessionCache.get(id);
        if (c && c.updatedAt !== ev.updatedAt) sessionCache.refresh(id);
        break;
      }
      case "turnEnd":
        setBusy(id, false);
        markUnread(id);      // a turn finished while you were elsewhere
        sessionCache.refresh(id);    // so switching there shows the new turn instantly
        refreshSessions();   // fresh titles + authoritative pending counts
        refreshFiles();
        flushQueued(id);     // deliver its queued message right away (no poll wait)
        break;
    }
  }

  // Watchdog against a SILENTLY dead stream (phone backgrounded, wifi→cellular
  // switch): the server sends a data ping every 15s, so a long all-quiet gap
  // means the socket is gone even though EventSource never fired an error —
  // reopen as a reconnect so the repair path (buffers dropped, session
  // refetched, resync) runs. EventSource's own auto-reconnect only covers
  // errors it can SEE.
  let lastEventAt = Date.now();
  let watchdog = 0;
  function openStream(asReconnect) {
    if (es) es.close();
    streamReady = false;
    lastEventAt = Date.now();
    clearInterval(watchdog);
    watchdog = setInterval(() => {
      if (streamReady && Date.now() - lastEventAt > 40000) openStream(true);
    }, 10000);
    es = api.stream();
    // EventSource drops fire onerror (it auto-retries); reflect that as the
    // connection state feeding the host dot + the floating banner. A fresh
    // "connected" event below flips it back.
    es.onerror = () => { streamReady = false; store.set({ connected: false }); };
    let firstConnect = !asReconnect;
    es.onmessage = (e) => {
      lastEventAt = Date.now();
      const raw = JSON.parse(e.data);
      const ev = parseClaudeEvent(raw);
      if (ev.kind === "connected") {
        // EventSource auto-reconnects (network blip / server restart). The first
        // "connected" is the normal open; later ones are reconnects — re-sync so
        // nothing missed while disconnected stays missing.
        streamReady = true;
        store.set({ connected: true });
        // One switch for all three sides: the gateway's WAKILI_DEBUG_STREAM.
        recorder.enable(ev.debug);
        recorder.note("connected", { reconnect: !firstConnect });
        const id = store.get().activeId;
        if (firstConnect) { firstConnect = false; if (id) requestResync(id); }
        else {
          // Reconnect: events published while disconnected are gone (the hub
          // doesn't replay), so every live buffer may have a gap — drop them
          // all. The openSession fetch + resync snapshot rebuild the active
          // one; background buffers restart from their next turnStart/snapshot.
          liveParts.clear();
          // The watermarks describe buffers that no longer exist; a stale one
          // would reject the very snapshot sent to repair the gap.
          appliedSeq.clear();
          refreshSessions();
          if (id) openSession(id);
        }
        return;
      }
      const sid = raw.sessionId;
      if (!sid) return;
      // Decide staleness before anything consumes the event, so the buffer and
      // the render path below can never take different views of it.
      markStale(sid, ev);
      // Feed the live-turn buffer for EVERY session before routing: background
      // sessions accumulate silently, the active one stays mirrored even while
      // the snapshot gate is dropping its render events.
      accumulate(sid, ev);
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
    // — keep the last known list instead of surfacing an error. Before the FIRST
    // successful load there's no list to keep, so flag the failure: the sidebar
    // shows an error state instead of an eternal spinner.
    let sessions;
    try { sessions = await api.listSessions(); }
    catch {
      if (store.get().sessionsState !== "ready") store.set({ sessionsState: "error" });
      return;
    }
    // Reconcile per-session busy from the server's view (keeps background badges
    // fresh). EXCEPT the active session: its busy is driven by its live stream
    // (turn_start/turn_end) and local sends, so a stale poll must not overwrite it
    // — otherwise the Stop button flickers back to Send right after you send.
    const prev = store.get().busyIds;
    const activeId = store.get().activeId;
    const busyIds = {};
    for (const s of sessions) if (s.busy) busyIds[s.id] = true;
    if (activeId && activeId in prev) busyIds[activeId] = prev[activeId];
    store.set({ sessions, busyIds, sessionsState: "ready" });
    // A queued message for a session that finished in the background goes out now.
    for (const sid of Object.keys(store.get().queued)) if (!busyIds[sid]) flushQueued(sid);
  }

  const sessionCache = createSessionCache(api);
  const outbox = createOutbox({ api, store, emitter, setBusy });
  const device = createDeviceController({ api, store });
  let openSeq = 0; // guards against a slow response landing after a newer switch

  // Make `s` the on-screen session: state, history render, then a resync so the
  // in-progress turn + pending cards arrive in order on the multiplexed stream.
  // `fresh` (optional) is the raw fetch result carrying the server's current
  // live-turn parts + busy — fresher than anything stored on `s`.
  function applySession(id, s, fresh) {
    store.set((st) => {
      const agentId = s.agentId || st.agentId;
      const agent = st.agents.find((a) => a.id === agentId);
      const unreadIds = { ...st.unreadIds }; delete unreadIds[id];
      return { activeId: id, activeSession: s, agentId, unreadIds, autoContinue: s.autoContinue !== false, controls: normalizeControls(agent, { ...defaultsFor(agent), ...(s.controls || {}) }), files: { received: [], uploaded: [] } };
    });
    saveLastConfig(); // the session you're on becomes the default for new chats
    emitter.emit("historyLoaded", s.messages);
    livePaint(id, s, fresh);
    requestResync(id);
  }

  // Paint the freshest view of `id`'s in-progress turn, best source first: the
  // parts a just-completed fetch carried (server truth from an HTTP round trip
  // that JUST succeeded — trustworthy even when the stream is silently dead
  // and the local buffer is frozen at whatever streamed before it died), else
  // the local live buffer (covers the optimistic cached apply and fetch
  // failures), else the cached copy's. The resync snapshot still REPLACES
  // whatever paints here when it lands: it's published into the ordered
  // stream, so it can't miss or duplicate deltas. Also adopts the fetch's
  // busy as truth — a turn_end missed while offline otherwise leaves the
  // composer stuck on Stop forever.
  function livePaint(id, s, fresh) {
    const buf = liveParts.get(id);
    const parts = (fresh && fresh.parts && fresh.parts.length) ? fresh.parts : ((buf && buf.length) ? buf : (s && s.parts) || []);
    let busyNow;
    if (fresh && fresh.busy != null) { busyNow = !!fresh.busy; setBusy(id, busyNow); }
    else { const b = store.get().busyIds[id]; busyNow = b != null ? !!b : !!(s && s.busy); }
    if (parts.length || busyNow) emitter.emit("snapshot", { parts, busy: busyNow });
  }

  async function openSession(id, attempt = 0) {
    const seq = ++openSeq;
    recorder.note("open-session", { sessionId: id, attempt, cached: !!sessionCache.get(id), buffered: (liveParts.get(id) || []).length });
    const cached = sessionCache.get(id);
    if (cached) applySession(id, cached); // optimistic: switch now, reconcile below
    // First-time open (no cached transcript): the pane would sit empty while the
    // fetch runs — show a spinner so the wait reads as loading, not as a blank
    // chat. Retries keep the error card up instead of flashing back to a spinner.
    else if (attempt === 0) { store.set({ activeId: id }); emitter.emit("sessionLoading", { id }); }
    let r = null, err = null;
    try { r = await sessionCache.fetch(id); } catch (e) { err = e; }
    if (seq !== openSeq) return; // the user has already switched elsewhere
    if (!r || !r.session) {
      // A 404 means the session is gone (deleted on another device) — don't retry.
      if (err && String(err).includes("404")) { sessionCache.delete(id); refreshSessions(); return; }
      // With a cached view up, retry QUIETLY behind it (no error pane): this
      // fetch — and the resync a successful applySession re-triggers — is the
      // only re-delivery path for a turn streamed while disconnected; giving
      // up here left it invisible until turn end.
      if (!cached) {
        if (attempt === 0) emitter.emit("toast", "Can't connect — retrying…");
        emitter.emit("sessionError", { id }); // error state in the pane; retries continue behind it
      }
      setTimeout(() => { if (seq === openSeq) openSession(id, attempt + 1); }, 2500);
      return;
    }
    // Unchanged on the server means the cached render already on screen is
    // current — skip the re-render (and stream reopen) to avoid a needless
    // flicker. The live turn still needs painting: mid-turn nothing bumps
    // updatedAt, so a busy session ALWAYS lands here when a cache exists.
    if (r.unchanged) { store.set({ activeSession: r.session }); livePaint(id, r.session, r); }
    else applySession(id, r.session, r);
    refreshSessions();
  }

  // Create a new chat, optionally bound to a project folder (cwd) and an agent
  // (the home page's selected tab; omitted = the active session's agent). The
  // folder is chosen at creation time — changing it later resets the resume thread.
  async function newSession(cwd, agentId) {
    const s = await api.createSession({ agentId: agentId || store.get().agentId, cwd: cwd || null });
    if (cwd) refreshProjects(); // first chat in a folder also registers it
    await refreshSessions();
    await openSession(s.id);
    // Pin the defaults this chat opened with — otherwise an untouched new chat
    // recomputes them from "last used" on every revisit and can drift.
    persistControls();
    emitter.emit("focusInput");
  }

  async function renameSession(id, title) {
    try { await api.renameSession(id, title); } catch { return emitter.emit("toast", "Couldn't rename — check the connection"); }
    // Reflect the new title immediately (open chat header + list); the follow-up
    // refreshSessions() stays authoritative.
    store.set((s) => ({
      activeSession: s.activeSession && s.activeSession.id === id ? { ...s.activeSession, title } : s.activeSession,
      sessions: s.sessions.map((x) => (x.id === id ? { ...x, title } : x)),
    }));
    refreshSessions();
  }

  async function deleteSession(id) {
    const wasActive = store.get().activeId === id;
    sessionCache.delete(id);
    liveParts.delete(id);
    outbox.forget(id);
    store.set((s) => { const u = { ...s.unreadIds }; delete u[id]; return { unreadIds: u }; });
    try { await api.deleteSession(id); }
    catch { emitter.emit("toast", "Couldn't delete the chat — check the connection"); return; }
    // The row goes NOW — waiting for the list refetch left the sidebar's
    // deleting spinner up even though the delete was already done. The
    // refresh below keeps the list authoritative.
    store.set((s) => ({ sessions: s.sessions.filter((x) => x.id !== id) }));
    await refreshSessions();
    if (wasActive) {
      const list = store.get().sessions;
      if (list.length) await openSession(list[0].id);
      else await newSession();
    }
  }

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
    await outbox.deliver(st.activeId, text, raw, st.controls, st.agentId);
  }

  // Send a session's oldest queued message (if any) now that it's free; the
  // rest stay queued and follow, one per turn end.
  function flushQueued(id) {
    const list = store.get().queued[id];
    if (!list || !list.length) return;
    const [q, ...rest] = list;
    store.set((s) => { const next = { ...s.queued }; if (rest.length) next[id] = rest; else delete next[id]; return { queued: next }; });
    outbox.deliver(id, q.text, q.raw, q.controls, q.agentId);
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

  // Persist the ACTIVE session's picker state (agent + controls) the moment it
  // changes — not just at send time. Without this, a choice made without
  // sending afterwards silently reverts: the in-memory copy dies on the next
  // session switch, and defaultsFor falls back to whatever chat was opened
  // last. The gateway also reads the saved permissionMode per permission card,
  // so a mid-turn mode flip counts from the very next card. Fire-and-forget:
  // if it doesn't land, the send-time persist still applies.
  // The agent is NOT ours to change as a side effect. The gateway resets the
  // session's resume thread the moment agentId differs (the PATCH handler nulls
  // resumeId — same semantics as runTurn), so a stray agentId here silently
  // destroys the native thread behind something as small as a permission-mode
  // tweak. st.agentId is the CLIENT's currently-selected agent and drifts from
  // the open session's own agent (home-page tabs, last-used restored at boot),
  // so it is the wrong thing to send. Default to the session's own agent; only
  // a caller deliberately switching passes one in.
  function persistControls(opts) {
    const st = store.get();
    if (!st.activeId) return;
    const own = st.activeSession && st.activeSession.id === st.activeId ? st.activeSession.agentId : null;
    const agentId = (opts && opts.agentId) || own || st.agentId;
    api.saveControls(st.activeId, agentId, st.controls).catch(() => {});
  }

  // Does the open chat already have messages? Deciding this from client state
  // alone is what let an in-place switch through on a chat that had history:
  // activeSession can be absent or stale (opened from cache, fetch still in
  // flight, restored session on boot). "unknown" is never treated as "empty" —
  // an unnecessary handoff is recoverable, a destroyed resume thread is not.
  async function historyState(id) {
    const st = store.get();
    const cur = st.activeSession;
    if (cur && cur.id === id) return { known: true, session: cur };
    let r = null;
    try { r = await sessionCache.fetch(id); } catch { /* unknown below */ }
    return r && r.session ? { known: true, session: r.session } : { known: false, session: null };
  }

  function setControl(key, value) {
    if (key === "permissionMode") rememberPermMode(value); // remember as the global default
    store.set((s) => ({ controls: { ...s.controls, [key]: value } }));
    saveLastConfig();
    persistControls();
  }

  // "Auto continue" — per-chat, ON by default and persisted on the GATEWAY: a
  // turn this chat loses to the usage limit is re-driven once quota returns,
  // whether or not this client is still around to watch.
  function setAutoContinue(on) {
    const id = store.get().activeId;
    if (!id) return;
    store.set({ autoContinue: !!on });
    api.setAutoContinue(id, on).catch(() => {
      // The gateway never heard the change — reflect reality, not the wish.
      if (store.get().activeId === id) store.set({ autoContinue: !on });
    });
  }

  // ---- scheduled tasks (gateway-side, survive restarts) ----

  // Schedule the composer text (+ any attached files) for `at` (epoch ms). The
  // gateway also flips the chat's autoContinue on, so an unattended task
  // finishes through quota walls. Files are already uploaded (eagerly, on
  // pick); any still in flight are awaited here so the job carries real paths.
  async function scheduleTask(text, at, raw) {
    const id = store.get().activeId;
    raw = raw || [];
    if (!id || (!text && !raw.length)) return false;
    let attachments = [];
    try {
      attachments = await outbox.resolveUploads(id, raw);
    } catch (e) {
      emitter.emit("toast", `Couldn't upload ${e.message} — retry it, then schedule again`);
      return false;
    }
    try {
      const r = await api.schedule(id, text, at, attachments);
      if (r && r.session) store.set({ activeSession: r.session, autoContinue: true });
      emitter.emit("toast", "Scheduled for " + new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }));
      return true;
    } catch {
      emitter.emit("toast", "Couldn't schedule — check the connection");
      return false;
    }
  }

  async function loadScheduled() {
    const id = store.get().activeId;
    if (!id) return [];
    try { return (await api.scheduledJobs(id)).jobs || []; } catch { return []; }
  }

  async function cancelScheduled(jobId) {
    try { await api.cancelScheduled(jobId); } catch { /* row stays; retry by reopening */ }
    sessionCache.refresh(store.get().activeId);
  }

  // ---- Scheduled tasks page: every chat's jobs, not just the open one ----
  // These differ from the composer's three above by not assuming activeId — the
  // page can create, edit and cancel tasks in chats that aren't even open, so
  // each one refreshes the session LIST (which carries the "N scheduled" count)
  // rather than just the active session's cache.

  async function loadAllScheduled() {
    try { return (await api.allScheduledJobs()).jobs || []; } catch { return []; }
  }

  // Create in an explicitly chosen chat. No attachments: the page has no
  // upload surface, so attachments stay the composer's job.
  async function createScheduled(sessionId, text, at) {
    if (!sessionId || !text) return false;
    try {
      const r = await api.schedule(sessionId, text, at, []);
      // Scheduling flips autoContinue on server-side; mirror that locally when
      // the target happens to be the chat currently on screen.
      if (r && r.session && sessionId === store.get().activeId) {
        store.set({ activeSession: r.session, autoContinue: true });
      }
      emitter.emit("toast", "Scheduled for " + new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }));
      refreshSessions();
      return true;
    } catch {
      emitter.emit("toast", "Couldn't schedule — check the connection");
      return false;
    }
  }

  async function updateScheduled(jobId, text, at) {
    try {
      await api.updateScheduled(jobId, text, at);
      emitter.emit("toast", "Task updated");
      refreshSessions();
      return true;
    } catch {
      emitter.emit("toast", "Couldn't update the task — check the connection");
      return false;
    }
  }

  // Cancel from the page: the job may belong to any chat, so refresh the list.
  async function cancelScheduledAnywhere(jobId) {
    try { await api.cancelScheduled(jobId); }
    catch { return emitter.emit("toast", "Couldn't cancel — check the connection"); }
    refreshSessions();
  }

  // Switching agent happens IN PLACE: you stay in the same chat and its history
  // stays on screen. What can't come along is the native thread — resume ids are
  // per-agent, so the gateway starts the new agent fresh. To bridge that, the
  // server exports the transcript so far and we attach it to the composer with a
  // suggested first message; the new agent reads it for context on its first
  // turn. Nothing sends until the user hits Send.
  async function continueWithAgent(cur, agentId, model) {
    const agent = store.get().agents.find((a) => a.id === agentId);
    const label = agent ? agent.label : agentId;
    emitter.emit("toast", `Switching to ${label}…`);
    let r = null;
    try { r = await api.switchAgent(cur.id, agentId); } catch { /* toast below */ }
    if (!r || !r.session) return emitter.emit("toast", "Couldn't switch agent — check the connection");
    // The stored agent and controls just changed underneath the cache.
    sessionCache.delete(cur.id);
    await openSession(cur.id); // same chat — re-applied with the new agent's defaults
    if (model) store.set((s) => ({ controls: { ...s.controls, model } }));
    saveLastConfig();
    persistControls({ agentId });
    await refreshSessions();
    if (r.file) {
      emitter.emit("handoff", { file: r.file });
      emitter.emit("toast", `Now chatting with ${label} — the transcript is attached, press Send to continue`);
    } else {
      emitter.emit("toast", `Now chatting with ${label}`);
    }
  }

  // An empty chat just switches in place, as before.
  async function setAgent(id) {
    const st = store.get();
    if (st.activeId) {
      const h = await historyState(st.activeId);
      if (!h.known) return emitter.emit("toast", "Can't switch agent — this chat isn't loaded yet");
      if ((h.session.messages || []).length && id !== h.session.agentId) return continueWithAgent(h.session, id);
    }
    const agent = store.get().agents.find((a) => a.id === id);
    store.set({ agentId: id, controls: defaultsFor(agent) });
    saveLastConfig();
    persistControls({ agentId: id }); // deliberate switch — this one may move the session
  }

  // Pick a model from the tree: switching agent on a non-empty chat goes through
  // the handoff flow (carrying the picked model); on an empty chat it resets that
  // agent's controls to defaults; within the same agent, just change the model.
  async function pickModel(agentId, model) {
    const st = store.get();
    let switching = false;
    if (agentId !== st.agentId) {
      if (st.activeId) {
        const h = await historyState(st.activeId);
        if (!h.known) return emitter.emit("toast", "Can't switch agent — this chat isn't loaded yet");
        if ((h.session.messages || []).length && agentId !== h.session.agentId) {
          return continueWithAgent(h.session, agentId, model);
        }
      }
      switching = true;
      const agent = store.get().agents.find((a) => a.id === agentId);
      store.set({ agentId, controls: defaultsFor(agent, { model }) });
    } else {
      store.set((s) => ({ controls: { ...s.controls, model } }));
    }
    saveLastConfig();
    persistControls(switching ? { agentId } : undefined);
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
  // Creating a folder registers it as a project server-side — pull the new list
  // so it appears under every agent immediately, before any chat exists in it.
  const createFolder = async (parent, name) => {
    const r = await api.createFolder(parent, name);
    refreshProjects();
    return r;
  };

  // The global files registry (uploads + agent-sent), shown in the sidebar.
  async function refreshFiles() { try { store.set({ allFiles: await api.files() }); } catch { /* ignore */ } }

  // Known projects — gateway-owned and agent-independent, so the sidebar can
  // show every project under every agent tab instead of only the folders that
  // the selected agent already has chats in.
  async function refreshProjects() { try { store.set({ projects: await api.projects() }); } catch { /* keep the last list */ } }

  // Remove a project from the list only — the folder and its chats stay put, so
  // this is safe to offer without a confirmation.
  async function forgetProject(path) {
    try { await api.forgetProject(path); } catch { return emitter.emit("toast", "Couldn't remove — check the connection"); }
    refreshProjects();
  }

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
    refreshProjects(); // the gateway just registered this folder — show it everywhere
  }

  async function init() {
    openStream(); // the single multiplexed stream, up for the whole app life
    // Coming back to the foreground (or back online) is the moment a phone
    // discovers its socket died. Give queued events a beat to drain, then: a
    // stale stream reopens as a reconnect (full repair); a healthy one just
    // revalidates the active session's cache (~60 bytes when unchanged).
    // The files registry is refreshed either way: it only ever updates from
    // live `file`/`turnEnd` events, so a stream that died while backgrounded
    // left it frozen at the last snapshot — with no poll to repair it, the
    // Files page kept showing a stale gallery until a full reload.
    const nudge = () => setTimeout(() => {
      if (!es) return;
      if (Date.now() - lastEventAt > 20000) openStream(true);
      else { const id = store.get().activeId; if (id) sessionCache.refresh(id); }
      refreshFiles();
    }, 1000);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) nudge(); });
    window.addEventListener("online", nudge);
    await loadAgents();
    await refreshSessions();
    refreshFiles();
    refreshProjects();
    device.refreshPower();
    device.refreshAutostart();
    const list = store.get().sessions;
    // The sidebar's pull-to-refresh stashes the open session before reloading;
    // honor it so a refresh lands back in the same chat, not the newest one.
    const resume = takeSession(KEYS.resumeSid);
    if (resume && list.some((s) => s.id === resume)) await openSession(resume);
    else if (list.length) await openSession(list[0].id);
    // An empty list because the load FAILED isn't "no chats yet" — the sidebar
    // shows its error state with a retry; creating a session would just throw
    // into the same dead connection.
    else if (store.get().sessionsState === "ready") await newSession();
    // Slow poll as a safety net only (authoritative busy/pending counts after a
    // missed event); session_updated pushes on the stream do the real-time work.
    setInterval(() => { refreshSessions().catch(() => {}); }, 60000);
    // Version-skew guard: if the API answers but the live stream never connected,
    // the running gateway predates /api/stream — replies would silently never
    // render. Say so instead of looking broken.
    setTimeout(() => {
      if (streamReady) return;
      api.agents().then(() => emitter.emit("toast", "Live updates aren't connecting — restart the gateway server to finish updating")).catch(() => {});
    }, 6000);
  }

  return {
    init, openSession, newSession, renameSession, deleteSession, refreshSessions,
    send, execTerm, stopActive, cancelQueued, setControl, setAutoContinue,
    scheduleTask, loadScheduled, cancelScheduled, setAgent,
    loadAllScheduled, createScheduled, updateScheduled, cancelScheduledAnywhere,
    pickModel, answerPermission, answerQuestion, browseFolders, createFolder,
    setCwd, removeUpload, refreshFiles, refreshProjects, forgetProject,
    // Device controls (Settings → Device rows render through these).
    refreshDevice: device.refreshDevice,
    lockScreen: device.lockScreen, screenOff: device.screenOff,
    shutdownComputer: device.shutdownComputer, setKeepAwake: device.setKeepAwake,
    setAutostart: device.setAutostart,
    // Outbox handles for main.js's status rows.
    getOutbox: outbox.getOutbox, retryOutbox: outbox.retry, discardOutbox: outbox.discard,
  };
}
