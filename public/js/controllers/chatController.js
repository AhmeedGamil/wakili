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
  let es = null;

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

  function openStream(id) {
    if (es) es.close();
    es = api.stream(id);
    let firstConnect = true;
    es.onmessage = (e) => {
      const ev = parseClaudeEvent(JSON.parse(e.data));
      switch (ev.kind) {
        // EventSource auto-reconnects (network blip / server restart). The first
        // "connected" is the normal open; a later one is a reconnect — re-sync from
        // scratch so the replayed snapshot doesn't double-render the live turn.
        case "connected": if (firstConnect) firstConnect = false; else if (store.get().activeId === id) openSession(id); break;
        case "turnStart": setBusy(id, true); emitter.emit("turnStart"); break;
        // restore the in-progress turn after history when (re)entering a working session
        case "snapshot": setBusy(id, ev.busy); emitter.emit("snapshot", { parts: ev.parts, busy: ev.busy }); break;
        // A queued message waiting to go out means the turn was interrupted to make
        // room for it (vs. a plain stop with nothing pending).
        case "stopped": emitter.emit("stopped", { interrupted: !!store.get().queued[id] }); break;
        case "text": emitter.emit("text", ev.text); break;
        case "thinking": emitter.emit("thinking", ev.text); break;
        case "tools": ev.tools.forEach((t) => { if (!GATED.has(t.name)) emitter.emit("tool", t); }); break;
        case "tool": emitter.emit("tool", ev.tool); break; // gateway-issued chip (auto-approved gated tool)
        case "toolResult": emitter.emit("toolResult", { id: ev.id, output: ev.output, isError: ev.isError }); break;
        case "question": emitter.emit("question", { id: ev.id, questions: ev.questions }); break;
        case "permission": emitter.emit("permission", { id: ev.id, tool: ev.tool, input: ev.input, autoAllow: store.get().autoAllow }); break;
        case "file":
          store.set((s) => ({ files: { ...s.files, received: [...s.files.received, ev.file] } }));
          emitter.emit("file", ev.file);
          refreshFiles(); // keep the sidebar files list current
          break;
        case "error": console.warn("[agent]", ev.text); break;
        case "turnEnd":
          setBusy(id, false);
          if (ev.title) {
            store.set((s) => ({ activeSession: s.activeSession ? { ...s.activeSession, title: ev.title } : null }));
          }
          emitter.emit("turnEnd");
          refreshSessions();
          refreshFiles(); // any files this turn used/produced
          flushQueued(id); // a queued message (if any) goes out now the turn is done
          break;
      }
    };
  }

  async function loadAgents() {
    const agents = await api.agents();
    const last = loadLastConfig();
    const agent = (last && agents.find((a) => a.id === last.agentId)) || agents.find((a) => a.id === "claude") || agents[0];
    store.set({ agents, agentId: agent?.id || "claude", controls: normalizeControls(agent, defaultsFor(agent)) });
  }

  async function refreshSessions() {
    const sessions = await api.listSessions();
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

  async function openSession(id) {
    const s = await api.getSession(id);
    if (!s) return;
    store.set((st) => {
      const agentId = s.agentId || st.agentId;
      const agent = st.agents.find((a) => a.id === agentId);
      return { activeId: id, activeSession: s, agentId, controls: normalizeControls(agent, { ...defaultsFor(agent), ...(s.controls || {}) }), files: { received: [], uploaded: [] } };
    });
    saveLastConfig(); // the session you're on becomes the default for new chats
    emitter.emit("historyLoaded", s.messages);
    openStream(id);
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
    await api.deleteSession(id);
    await refreshSessions();
    if (wasActive) {
      const list = store.get().sessions;
      if (list.length) await openSession(list[0].id);
      else await newSession();
    }
  }

  // Deliver one turn to a specific session: upload attachments, render the user
  // bubble only if that session is on screen, mark it busy, and post the turn.
  async function deliver(id, text, raw, controls, agentId) {
    const attachments = [];
    for (const a of raw || []) {
      const up = await api.upload(a.name, a.dataBase64);
      if (up && up.path) attachments.push({ name: up.name, path: up.path });
    }
    const onScreen = store.get().activeId === id;
    if (attachments.length && onScreen) store.set((s) => ({ files: { ...s.files, uploaded: [...s.files.uploaded, ...attachments] } }));
    const tag = attachments.length ? `\nAttached: ${attachments.map((a) => a.name).join(", ")}` : "";
    if (onScreen) { emitter.emit("userMessage", text + tag); emitter.emit("turnStart"); } // show the working pulse right away (don't wait for the server's turn_start)
    setBusy(id, true);
    const r = await api.send(id, text, controls, attachments, agentId);
    if (r && r.error === "busy") setBusy(id, false);
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
      const q = { text, raw, controls: st.controls, agentId: st.agentId };
      store.set((s) => ({ queued: { ...s.queued, [st.activeId]: q } }));
      emitter.emit("queued", q);
      return;
    }
    await deliver(st.activeId, text, raw, st.controls, st.agentId);
  }

  // Send a session's queued message (if any) now that it's free.
  function flushQueued(id) {
    const q = store.get().queued[id];
    if (!q) return;
    store.set((s) => { const next = { ...s.queued }; delete next[id]; return { queued: next }; });
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
  async function setKeepAwake(on) { try { const p = await api.keepAwake(on); store.set({ power: p }); return p; } catch { return null; } }
  async function setCwd(cwd) {
    const id = store.get().activeId;
    if (!id) return;
    const s = await api.setCwd(id, cwd);
    if (s && s.id) store.set({ activeSession: s });
  }

  async function init() {
    await loadAgents();
    await refreshSessions();
    refreshFiles();
    refreshPower();
    const list = store.get().sessions;
    if (list.length) await openSession(list[0].id);
    else await newSession();
    // Light poll so background sessions' busy badges stay fresh and queued
    // messages for sessions that finished off-screen still get delivered.
    setInterval(() => { refreshSessions().catch(() => {}); }, 5000);
  }

  return { init, openSession, newSession, deleteSession, send, execTerm, stopActive, cancelQueued, setControl, setAutoAllow, setAgent, pickModel, answerPermission, answerQuestion, browseFolders, createFolder, setCwd, lockScreen, screenOff, setKeepAwake };
}
