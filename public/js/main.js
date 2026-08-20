// Composition root. Builds the store, controller, and components, then wires
// them together: user intents -> controller, controller events -> components,
// state changes -> components. No business logic lives here.

import { api } from "./services/api.js";
import { KEYS, getPref, setPref } from "./core/prefs.js";
import { IS_NATIVE } from "./core/env.js";
import { createStore } from "./core/store.js";
import { createEmitter } from "./core/emitter.js";
import { createChatController } from "./controllers/chatController.js";
import { el } from "./components/dom.js";
import { createSidebar } from "./components/Sidebar.js";
import { createTopbar } from "./components/Topbar.js";
import { createModelPicker } from "./components/ModelPicker.js";
import { createFolderPicker } from "./components/FolderPicker.js";
import { createFilesPage } from "./components/FilesPage.js";
import { createSchedulePage } from "./components/SchedulePage.js";
import { createTerminalPage } from "./components/TerminalPage.js";
import { createEndpointMenu } from "./components/EndpointMenu.js";
import { createDeviceMenu } from "./components/DeviceMenu.js";
import { createAppearanceMenu } from "./components/AppearanceMenu.js";
import { createMessageList } from "./components/MessageList.js";
import { mediaMenuLayer } from "./components/media.js";
import { createDock } from "./components/Dock.js";
import { createComposer } from "./components/Composer.js";
import { initDrawerSwipe } from "./components/drawerSwipe.js";
import { showGuide } from "./components/Guide.js";
import { createGuideSteps } from "./components/guideSteps.js";
import { createConnectionBanner } from "./components/ConnectionBanner.js";

// autoContinue is per-session and defaults ON; applySession loads the active
// session's value.
const store = createStore({ sessions: [], sessionsState: "loading", activeId: null, activeSession: null, agents: [], agentId: "claude", projects: [], controls: {}, busyIds: {}, queued: {}, unreadIds: {}, autoContinue: true, connected: true, files: { received: [], uploaded: [] }, allFiles: [], power: { platform: "", keepAwake: false }, autostart: { supported: false, on: false } });
const emitter = createEmitter();
const controller = createChatController({ api, store, emitter });

// ---- components ----
// New chat: pick/create a project folder first (start browsing from the current
// chat's folder), then create the chat there. The per-project + skips the modal.
// Creating a chat is a round trip, and on a slow link the tap looked ignored
// until the chat suddenly appeared. Every entry point goes through here so the
// sidebar shows a spinner for exactly as long as the create is in flight.
function startChat(cwd) {
  document.body.classList.remove("nav-open");
  sidebar.setCreating(true);
  Promise.resolve(controller.newSession(cwd, sidebar.currentAgent()))
    .finally(() => sidebar.setCreating(false));
}
function newChatWithPicker() {
  // Keep the sidebar where it is; it only closes once a folder is actually
  // picked and the new chat opens (cancelling leaves everything untouched).
  const start = store.get().activeSession?.effectiveCwd || "";
  folderPicker.open((cwd) => startChat(cwd), start);
}
// New chats are born under the home page's selected agent tab — the chat page
// itself has no agent picker anymore.
const sidebar = createSidebar({
  onNew: newChatWithPicker,
  // Header +: new chat in the last selected project (the open chat's folder),
  // skipping the picker. With nothing open it lands in the default project.
  onNewLast: () => startChat(store.get().activeSession?.effectiveCwd || null),
  onNewInFolder: (cwd) => startChat(cwd),
  onSelect: (id) => { document.body.classList.remove("nav-open"); controller.openSession(id); },
  onDelete: (id) => controller.deleteSession(id),
  onRename: (id, title) => controller.renameSession(id, title),
  onOpenFiles: () => filesPage.open(),
  onOpenSchedule: () => schedulePage.open(),
  onAppearance: () => appearanceMenu.open(),
  onOpenConnect: () => appearanceMenu.openTab("connection"),
  onRetrySessions: () => { store.set({ sessionsState: "loading" }); controller.refreshSessions(); },
});
const topbar = createTopbar({ onMenu: () => document.body.classList.toggle("nav-open") });
const picker = createModelPicker({
  // "Continue with X": switches this chat's agent in place — same chat, same
  // history — attaching the exported transcript so the new agent has context.
  onSwitchAgent: (id) => controller.setAgent(id),
  onPickModel: (agentId, model) => controller.pickModel(agentId, model),
  onControlChange: (key, value) => controller.setControl(key, value),
  onToggleAutoContinue: (v) => controller.setAutoContinue(v),
});
const folderPicker = createFolderPicker({
  onBrowse: (path) => controller.browseFolders(path),
  onCreate: (parent, name) => controller.createFolder(parent, name),
});
// Full-screen Files view, opened from the sidebar. Long-press → Delete removes
// the file from the gateway's registry, then the list refreshes from source.
const filesPage = createFilesPage({
  onDelete: async (f) => {
    try { await api.deleteFile(f.token); }
    catch (e) {
      // A 404 here isn't "file already gone" — it's a gateway from before the
      // delete endpoint existed answering "no such route". Say that.
      const old = String(e && e.message || e).includes("404");
      return emitter.emit("toast", old ? "The wakili server needs a restart to enable deleting" : "Couldn't delete — check the connection");
    }
    emitter.emit("toast", `Deleted "${f.name}"`);
    // Drop the row NOW — waiting for the refetch left the spinner up for
    // seconds on slow links even though the delete was already done. The
    // background refresh keeps the list authoritative.
    store.set((s) => ({ allFiles: (s.allFiles || []).filter((x) => x.token !== f.token) }));
    api.files().then((all) => store.set({ allFiles: all })).catch(() => { /* next refresh catches up */ });
  },
});
// Scheduled tasks across every chat, opened from the sidebar's ☰ menu. The
// composer's + → Schedule still handles "schedule what I'm typing, here"; this
// page is where tasks already made get edited or cancelled.
const schedulePage = createSchedulePage({
  onLoad: () => controller.loadAllScheduled(),
  onCreate: (sessionId, text, at) => controller.createScheduled(sessionId, text, at),
  onUpdate: (jobId, text, at) => controller.updateScheduled(jobId, text, at),
  onCancel: (jobId) => controller.cancelScheduledAnywhere(jobId),
});
const terminalPage = createTerminalPage({ // full-screen shell, opened from the composer's + menu
  onRun: (command, cwd) => controller.execTerm(command, cwd),
});
const endpointMenu = createEndpointMenu({ fetchEndpoints: () => api.endpoints() }); // connection switcher
const deviceMenu = createDeviceMenu({ // lock screen / turn off screen / keep awake / start at login / shut down
  onLock: () => controller.lockScreen(),
  onScreenOff: () => controller.screenOff(),
  onShutdown: () => controller.shutdownComputer(),
  onToggleKeepAwake: (on) => controller.setKeepAwake(on),
  getKeepAwake: () => store.get().power.keepAwake,
  onToggleAutostart: (on) => controller.setAutostart(on),
  getAutostart: () => store.get().autostart,
});
const appearanceMenu = createAppearanceMenu({ // connection/device rows + formatting + theme + accent
  getTheme: () => document.body.dataset.theme,
  onSetTheme: (t) => applyTheme(t),
  getAccent: () => getPref(KEYS.accent) || "#3b82f6",
  onSetAccent: (hex) => applyAccent(hex),
  getFormat: () => getPref(KEYS.markdown, "1") !== "0",
  onToggleFormat: (on) => messageList.setMarkdown(on),
  renderConnections: (c) => endpointMenu.render(c),
  renderDevice: (c) => {
    deviceMenu.render(c);
    // Paint the snapshot we have, then correct it with the gateway's live
    // state: keep-awake and start-at-login can both change on the laptop (or
    // from the phone) while this tab sits on a value read at boot.
    // The repaint must not land on a tab the user has since left: the panel
    // reuses ONE content node, so check our own rows are still the ones in it.
    controller.refreshDevice().then(() => { if (c.querySelector(".dev-list")) deviceMenu.render(c); });
  },
  onShowGuide: () => showGuide(guideSteps(), { onEnd: () => appearanceMenu.close() }),
});

// ---- guided tour ----
// The steps' content lives in guideSteps.js; each run reads the live UI.
const guideSteps = createGuideSteps({ sidebar, picker, appearanceMenu });

const messageList = createMessageList();
const dock = createDock({
  onPermission: (id, decision, tool) => controller.answerPermission(id, decision, tool),
  onAnswerQuestion: (id, answer) => controller.answerQuestion(id, answer),
  onArchive: (node) => messageList.addRecord(node),
  onActiveChange: (active) => composer.setBlocked(active), // block sending while a card awaits an answer
});
const composer = createComposer({
  onSend: (t, attachments) => controller.send(t, attachments),
  onStop: () => controller.stopActive(),
  onCancelQueued: () => controller.cancelQueued(),
  // Scheduled tasks: gateway-side one-shots (see src/schedule.mjs).
  onSchedule: (t, at, raw) => controller.scheduleTask(t, at, raw),
  onLoadScheduled: () => controller.loadScheduled(),
  onCancelScheduled: (id) => controller.cancelScheduled(id),
  onOpenTerminal: () => terminalPage.open(store.get().activeSession?.effectiveCwd || ""),
  // Eager attachment uploads: start on pick (progress ring on the card), undo
  // on remove — the upload belongs to whichever session the file was picked in.
  onUpload: (a, onProgress) => api.uploadProgress(a.name, a.dataBase64, store.get().activeId || "", onProgress),
  onRemoveUpload: (up) => controller.removeUpload(up),
});
// The model picker sits with the composer now (not the topbar), next to the
// other per-turn controls; its popover opens upward from there.
composer.pickerSlot.appendChild(picker.el);

const backdrop = el("div", { id: "backdrop", onClick: () => document.body.classList.remove("nav-open") });
const main = el("main", { id: "main" }, topbar.el, messageList.el, dock.el, composer.el);
// The connection status lives at the end of the sidebar's host row — the host
// it describes — instead of floating over the app.
const connBanner = createConnectionBanner();
sidebar.connSlot.appendChild(connBanner.el);
document.getElementById("app").append(sidebar.el, main, backdrop);

// The dock (permission/question cards) sits between the transcript and the
// composer: a card appearing AFTER the transcript pinned to the bottom shrinks
// the pane and leaves the view a card-height above the end — and an agent
// waiting on that card streams nothing further that would re-pin. Follow dock
// size changes instead (repin no-ops unless the view is stuck to the bottom).
new ResizeObserver(() => messageList.repin()).observe(dock.el);

// Inside the native app shell, the shell already pads for the system bars —
// trim our own safe-area padding.
if (IS_NATIVE) document.documentElement.classList.add("native-app");

// Swipe the drawer open/closed with a finger — app shell only; browsers keep
// the tap-to-toggle drawer. Held back while a sidebar popover is up so a drag
// on the popover can't carry the drawer (and its menus) off-screen with it.
if (IS_NATIVE) initDrawerSwipe({ sidebar: sidebar.el, backdrop, main, blocked: () => sidebar.menusOpen() });

// Links inside rendered messages (markdown) point elsewhere on the web, but
// target="_blank" doesn't open in a WebView — hand external links to the native
// shell so they open in the system browser. Desktop keeps the normal new tab.
if (IS_NATIVE) {
  document.addEventListener("click", (e) => {
    const a = e.target.closest && e.target.closest("a[href]");
    if (!a) return;
    const href = a.getAttribute("href") || "";
    if (!/^(https?:|mailto:|tel:)/i.test(href)) return; // leave in-app "/" and "#" links alone
    e.preventDefault();
    try { window.ReactNativeWebView.postMessage(JSON.stringify({ wakiliOpenUrl: { url: href } })); } catch { /* not in shell */ }
  }, true);
}

// ---- Android system back button ----
// The native shell forwards each back press here before falling back to WebView
// history / exiting. We close the top-most open surface (one per press) and
// return true; when nothing is open we return false and the shell takes over.
// Ordered top-most (most transient / highest on screen) first.
const backLayers = [
  // Centered confirm dialog (delete chat) — the top-most modal; back cancels
  // it, same as tapping outside. The dialog is created ad-hoc and removed on
  // close, so existence is the open check.
  { isOpen: () => !!document.querySelector(".confirm-overlay"),
    close: () => document.querySelector(".confirm-overlay")?.remove() },
  // Guided tour: back skips it (same as its Skip button).
  { isOpen: () => !!document.querySelector(".guide-overlay"),
    close: () => document.querySelector(".guide-overlay .btn.ghost")?.click() },
  // Full-screen image viewer.
  { isOpen: () => !!document.querySelector(".lb-overlay:not([hidden])"),
    close: () => document.querySelector(".lb-overlay:not([hidden])")?.setAttribute("hidden", "") },
  // Transient popovers (also dismiss on an outside tap; back closes them too):
  // topbar model picker, composer's slash / + menus, sidebar's filter popover
  // and long-press context menu. Always through each component's own close so
  // the popover's backdrop shield is removed with it.
  { isOpen: () => picker.isOpen() || composer.menusOpen() || sidebar.menusOpen() || mediaMenuLayer.isOpen(),
    close: () => {
      if (picker.isOpen()) return picker.close();
      if (composer.menusOpen()) return composer.closeMenus();
      if (mediaMenuLayer.isOpen()) return mediaMenuLayer.close();
      sidebar.closeMenus();
    } },
  // Full-screen / modal overlays.
  { isOpen: () => folderPicker.isOpen(), close: () => folderPicker.close() },
  { isOpen: () => filesPage.isOpen(), close: () => filesPage.close() },
  // The create/edit dialog closes before the page under it.
  { isOpen: () => schedulePage.dialogOpen(), close: () => schedulePage.closeDialog() },
  { isOpen: () => schedulePage.isOpen(), close: () => schedulePage.close() },
  // Terminal's own layers close before the page itself: close-tab confirm,
  // then the "/" history menu, then the whole terminal.
  { isOpen: () => terminalPage.confirmOpen(), close: () => terminalPage.hideConfirm() },
  { isOpen: () => terminalPage.menuOpen(), close: () => terminalPage.closeMenu() },
  { isOpen: () => !!terminalPage.el && !terminalPage.el.hasAttribute("hidden"), close: () => terminalPage.close() },
  { isOpen: () => appearanceMenu.isOpen(), close: () => appearanceMenu.close() },
  // Page navigation, under all of the above: back in a chat returns to the
  // home page; back on the home page falls through to the shell (app exits).
  { isOpen: () => !document.body.classList.contains("nav-open"),
    close: () => document.body.classList.add("nav-open") },
];
window.__wakiliBack = () => {
  for (const layer of backLayers) {
    if (layer.isOpen()) { layer.close(); return true; }
  }
  return false;
};

// ---- theme + accent ----
function applyTheme(t) {
  document.body.dataset.theme = t;
  setPref(KEYS.theme, t);
}

// Pick readable ink (text/icons on the accent) from the accent's luminance.
function accentInk(hex) {
  const c = hex.replace("#", "");
  const r = parseInt(c.slice(0, 2), 16), g = parseInt(c.slice(2, 4), 16), b = parseInt(c.slice(4, 6), 16);
  const L = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  return L > 0.6 ? "#1d1d22" : "#fff";
}
// Override --accent on <body> (beats the per-theme default) and persist it.
// Falsy hex clears the override, falling back to the theme's built-in accent.
function applyAccent(hex) {
  if (hex) {
    document.body.style.setProperty("--accent", hex);
    document.body.style.setProperty("--accent-ink", accentInk(hex));
  } else {
    document.body.style.removeProperty("--accent");
    document.body.style.removeProperty("--accent-ink");
  }
  setPref(KEYS.accent, hex || null);
}

// ---- per-session UI state (drafts, attachments) ----
// Saved when you switch away, restored when you come back — each chat keeps its
// own half-typed message and pending attachments. Reading position is NOT
// saved: opening a chat always lands at the end of the transcript (a restored
// mid-history position shifts as images above it finish loading — not worth it).
const uiState = new Map(); // sessionId -> { draft, pending }
let uiSid = null;

// Render an outbox entry (an in-flight/failed send) and hand its UI handle back
// to the controller so upload/send progress can update it.
function renderOutboxEntry(entry) {
  entry.handle = messageList.addOutbox({
    text: entry.text,
    attachments: (entry.raw || []).map((a) => ({ name: a.name, url: a.isImg ? a.dataUrl : "", image: !!a.isImg })),
    status: entry.status,
    onRetry: () => controller.retryOutbox(entry),
    onDiscard: () => controller.discardOutbox(entry),
  });
}

// ---- streaming events -> components ----
emitter.on("userMessage", (t) => messageList.userMessage(t));
emitter.on("outbox", (entry) => renderOutboxEntry(entry));
let renderedSid = null; // which session's transcript the pane currently shows
emitter.on("historyLoaded", (msgs) => {
  const sid = store.get().activeId;
  // Opening or switching to a chat always lands at the end of the transcript.
  // The one exception is the fresh fetch reconciling over the optimistic
  // cached render of the SAME session: that re-render keeps the current
  // position, so scrolling right after a chat opens is never yanked back down.
  const anchor = sid === renderedSid ? messageList.getAnchor() : { bottom: true };
  renderedSid = sid;
  dock.clear();
  messageList.renderHistory(msgs);
  // In-flight/failed sends for this session render after its history.
  for (const entry of controller.getOutbox(sid)) renderOutboxEntry(entry);
  // Outbox rows belong above the live-turn boundary: a snapshot REPLACES
  // everything below the boundary, and an unsent message must survive that.
  messageList.markLive();
  messageList.restoreAnchor(anchor);
});
// First-time session open: spinner while the transcript fetch runs; error card
// with Retry when it fails with nothing cached. historyLoaded replaces both.
emitter.on("sessionLoading", () => { dock.clear(); messageList.showLoading(); });
emitter.on("sessionError", ({ id }) => messageList.showError("Couldn't load this chat — check the connection.", () => controller.openSession(id)));
emitter.on("turnStart", () => messageList.startAssistant());
emitter.on("snapshot", (s) => messageList.renderSnapshot(s.parts, s.busy));
emitter.on("stopped", (info) => messageList.addStopped(info && info.interrupted));
emitter.on("text", (t) => messageList.feedText(t));
emitter.on("thinking", (t) => messageList.feedThink(t));
emitter.on("tool", (t) => messageList.addTool(t));
emitter.on("toolResult", (r) => messageList.addToolResult(r));
emitter.on("exec", (e) => messageList.addExec(e));
emitter.on("permission", (req) => dock.addPermission(req));
emitter.on("question", (q) => dock.addQuestion(q));
emitter.on("requestResolved", (r) => dock.remove(r.id)); // answered elsewhere / timed out
emitter.on("file", (f) => messageList.addFile(f));
emitter.on("turnEnd", () => messageList.endTurn());
emitter.on("focusInput", () => composer.focus());
// Agent switched on this chat. The new agent starts a fresh native thread, so
// attach the exported transcript (already on the laptop — status "done" skips
// the upload) and suggest a first message; nothing sends until the user does.
emitter.on("handoff", ({ file }) => {
  composer.setState({
    draft: "Read the attached transcript of my previous conversation, then continue from where it left off.",
    pending: [{ name: file.name, isImg: false, status: "done", up: file }],
  });
  composer.focus();
});

// Transient notice (connection trouble etc.) — one reusable element, auto-hides.
let toastEl = null, toastTimer = 0;
emitter.on("toast", (text) => {
  if (!toastEl) { toastEl = el("div", { class: "toast", hidden: "" }); document.body.appendChild(toastEl); }
  toastEl.textContent = text;
  toastEl.removeAttribute("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.setAttribute("hidden", ""), 4000);
});
// Let leaf components (image viewer / downloads) raise a toast without the emitter.
window.__wakiliToast = (text) => emitter.emit("toast", text);

// ---- state -> components ----
let lastPick = "";
store.subscribe((s) => {
  // Session switch: bank the outgoing session's composer state + scroll position
  // (this fires before the new session's history renders), then restore the
  // incoming session's draft and attachments.
  if (s.activeId !== uiSid) {
    if (uiSid) uiState.set(uiSid, composer.getState());
    uiSid = s.activeId;
    composer.setState(uiState.get(uiSid));
  }
  sidebar.render({ sessions: s.sessions, activeId: s.activeId, busyIds: s.busyIds, files: s.allFiles, unreadIds: s.unreadIds, agents: s.agents, projects: s.projects, power: s.power, sessionsState: s.sessionsState, connected: s.connected });
  topbar.setConnected(s.connected);
  connBanner.set(s.connected);
  filesPage.render(s.allFiles);
  schedulePage.render(s.sessions); // the create dialog's "Run in" list
  composer.setBusy(!!s.busyIds[s.activeId]);   // only the active session's busy gates the composer
  composer.setQueued(s.queued[s.activeId]);    // its pending (queued) message, if any
  composer.setScheduled(s.activeSession ? (s.activeSession.scheduled || 0) : 0);
  topbar.setFolder(s.activeSession ? s.activeSession.effectiveCwd : "");
  // re-render the picker only when its inputs actually change (avoids churn)
  const key = s.agents.length + "|" + s.agentId + "|" + JSON.stringify(s.controls) + "|" + s.autoContinue;
  if (key !== lastPick && s.agents.length) {
    lastPick = key;
    picker.render({ agents: s.agents, agentId: s.agentId, controls: s.controls, autoContinue: s.autoContinue });
    const agent = s.agents.find((a) => a.id === s.agentId);
    composer.setCommands(agent?.commands || []);   // slash menu = the active agent's real commands
  }
});

// ---- boot ----
// Access is token-only: the QR/link from the laptop carries ?t=<token>, saved on
// first open. With no valid token there's nothing to type — show a notice that
// points back to the laptop's link/QR.
function showGate() {
  document.body.appendChild(el("div", { class: "lg-overlay" },
    el("div", { class: "lg-form" },
      el("div", { class: "lg-title", text: "Wakili" }),
      el("div", { class: "lg-sub", text: "Open this page from the link or QR code shown in your computer's terminal — it carries your access token." }),
    )));
}

async function boot() {
  applyTheme(getPref(KEYS.theme) || "dark");
  applyAccent(getPref(KEYS.accent)); // restore saved accent (empty = theme default)
  // The app opens on the HOME page (agent tabs + chats). init() still preloads
  // the newest chat behind it, so tapping a session lands instantly.
  document.body.classList.add("nav-open");
  if (!api.hasToken()) return showGate();
  // Show the connected computer's own NAME (os.hostname) under the logo, not the
  // raw host:port / network address. Fired independently of init() so it lands
  // even if the live stream is slow; best-effort — host:port stays if it fails.
  api.host().then((h) => { if (h && h.hostname) sidebar.setHost(h.hostname); })
    .catch(() => { /* keep host:port */ });
  try { await controller.init(); }
  catch (e) { if (String(e.message || e).includes("unauthorized")) showGate(); else throw e; }
}
boot();
