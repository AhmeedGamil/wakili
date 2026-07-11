// Composition root. Builds the store, controller, and components, then wires
// them together: user intents -> controller, controller events -> components,
// state changes -> components. No business logic lives here.

import { api } from "./services/api.js";
import { createStore } from "./core/store.js";
import { createEmitter } from "./core/emitter.js";
import { createChatController } from "./controllers/chatController.js";
import { el } from "./components/dom.js";
import { createSidebar } from "./components/Sidebar.js";
import { createTopbar } from "./components/Topbar.js";
import { createModelPicker } from "./components/ModelPicker.js";
import { createFolderPicker } from "./components/FolderPicker.js";
import { createFilesPage } from "./components/FilesPage.js";
import { createTerminalPage } from "./components/TerminalPage.js";
import { createEndpointMenu } from "./components/EndpointMenu.js";
import { createDeviceMenu } from "./components/DeviceMenu.js";
import { createAppearanceMenu } from "./components/AppearanceMenu.js";
import { createMessageList } from "./components/MessageList.js";
import { createDock } from "./components/Dock.js";
import { createComposer } from "./components/Composer.js";
import { maybeShowGuide, showGuide } from "./components/Guide.js";

const THEME_KEY = "ra-theme";
const ACCENT_KEY = "ra-accent";

// autoAllow is per-session; applySession loads the active session's value.
const store = createStore({ sessions: [], activeId: null, activeSession: null, agents: [], agentId: "claude", controls: {}, busyIds: {}, queued: {}, unreadIds: {}, autoAllow: false, files: { received: [], uploaded: [] }, allFiles: [], power: { platform: "", keepAwake: false }, autostart: { supported: false, on: false } });
const emitter = createEmitter();
const controller = createChatController({ api, store, emitter });

// ---- components ----
// New chat: pick/create a project folder first (start browsing from the current
// chat's folder), then create the chat there. The per-project + skips the modal.
function newChatWithPicker() {
  // Keep the sidebar where it is; it only closes once a folder is actually
  // picked and the new chat opens (cancelling leaves everything untouched).
  const start = store.get().activeSession?.effectiveCwd || "";
  folderPicker.open((cwd) => { document.body.classList.remove("nav-open"); controller.newSession(cwd); }, start);
}
const sidebar = createSidebar({
  onNew: newChatWithPicker,
  // Header +: new chat in the last selected project (the open chat's folder),
  // skipping the picker. With nothing open it lands in the default project.
  onNewLast: () => { document.body.classList.remove("nav-open"); controller.newSession(store.get().activeSession?.effectiveCwd || null); },
  onNewInFolder: (cwd) => { document.body.classList.remove("nav-open"); controller.newSession(cwd); },
  onSelect: (id) => { document.body.classList.remove("nav-open"); controller.openSession(id); },
  onDelete: (id) => controller.deleteSession(id),
  onRename: (id, title) => controller.renameSession(id, title),
  onOpenFiles: () => filesPage.open(),
  onAppearance: () => appearanceMenu.open(),
});
const topbar = createTopbar({ onMenu: () => document.body.classList.toggle("nav-open") });
const picker = createModelPicker({
  onPickAgent: (id) => controller.setAgent(id),
  onPickModel: (agentId, model) => controller.pickModel(agentId, model),
  onControlChange: (key, value) => controller.setControl(key, value),
  onToggleAutoAllow: (v) => controller.setAutoAllow(v),
});
topbar.slot.appendChild(picker.el);
const folderPicker = createFolderPicker({
  onBrowse: (path) => controller.browseFolders(path),
  onCreate: (parent, name) => controller.createFolder(parent, name),
});
const filesPage = createFilesPage(); // full-screen Files view, opened from the sidebar
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
  getAccent: () => localStorage.getItem(ACCENT_KEY) || "#3b82f6",
  onSetAccent: (hex) => applyAccent(hex),
  getFormat: () => localStorage.getItem("ra-markdown") !== "0",
  onToggleFormat: (on) => messageList.setMarkdown(on),
  renderConnections: (c) => endpointMenu.render(c),
  renderDevice: (c) => deviceMenu.render(c),
  onShowGuide: () => showGuide(guideSteps(), { onEnd: () => appearanceMenu.close() }),
});

// ---- guided tour ----
// Steps navigate the real UI: the settings panel opens, tabs switch, and each
// device row is spotlit and explained on its own.
const devRow = (n) => () => appearanceMenu.panel.querySelectorAll(".dev-row")[n];
function guideSteps() {
  return [
    {
      before: () => appearanceMenu.close(),
      target: () => sidebar.el.querySelectorAll(".side-files-btn")[0],
      title: "Select a project",
      body: "Every chat runs inside a project folder on your computer. Tap here to pick (or create) the folder the agent should work in, then start chatting.",
    },
    {
      // The switch lives in the model picker's popover (tap the model name in
      // the topbar) — the drawer closes so the topbar is visible.
      before: () => { document.body.classList.remove("nav-open"); picker.open(); },
      target: () => picker.el.querySelector(".switch-row"),
      title: "Allow always",
      body: "When this is on, the agent's permission requests (running commands, editing files) are approved automatically — smooth, hands-off runs. Turn it off to review and answer each request yourself.",
    },
    {
      before: () => { picker.close(); appearanceMenu.openTab("connection"); },
      target: () => appearanceMenu.panel.querySelector(".set-content"),
      title: "Connection",
      body: "These are the ways your phone can reach this computer — same Wi-Fi, Tailscale, or Cloudflare. Tap one to switch whenever your network changes.",
    },
    {
      // The row exists only inside the phone app (added via the native bridge),
      // so a browser tour skips this step.
      target: () => appearanceMenu.panel.querySelector(".ep-add"),
      optional: true,
      title: "Add or change the host",
      body: "Opens the hosts page over your session: select another saved computer, scan a QR to add one, or remove one — without losing where you are.",
    },
    {
      before: () => appearanceMenu.openTab("device"),
      target: devRow(0),
      title: "Lock screen",
      body: "Locks your computer's screen remotely, so no one at the desk can see or touch your session.",
    },
    {
      target: devRow(1),
      title: "Turn off screen",
      body: "Switches the computer's display off — the machine keeps running, the screen just goes dark.",
    },
    {
      target: devRow(2),
      title: "Keep awake",
      body: "Stops the computer from going to sleep while you work remotely — turn it on before stepping away.",
    },
    {
      target: devRow(3),
      optional: true, // hidden on OSes the gateway can't register itself on
      title: "Start with computer",
      body: "Launches the gateway automatically when the computer starts, so the app always has something to connect to.",
    },
    {
      target: () => appearanceMenu.panel.querySelector(".dev-danger"),
      title: "Shut down computer",
      body: "Powers the computer off completely. It takes two taps — the first arms the row (\"Tap again to confirm\"), the second shuts down — so a stray touch can't turn your machine off.",
    },
  ];
}

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
  onOpenTerminal: () => terminalPage.open(store.get().activeSession?.effectiveCwd || ""),
  // Eager attachment uploads: start on pick (progress ring on the card), undo
  // on remove — the upload belongs to whichever session the file was picked in.
  onUpload: (a, onProgress) => api.uploadProgress(a.name, a.dataBase64, store.get().activeId || "", onProgress),
  onRemoveUpload: (up) => controller.removeUpload(up),
});

const backdrop = el("div", { id: "backdrop", onClick: () => document.body.classList.remove("nav-open") });
const main = el("main", { id: "main" }, topbar.el, messageList.el, dock.el, composer.el);
document.getElementById("app").append(sidebar.el, main, backdrop);

// Running inside the native app shell (its WebView tags the user agent): the
// shell already pads for the system bars, so trim our own safe-area padding.
// Old installs still say ZogagApp — keep matching both.
const IS_NATIVE = /WakiliApp|ZogagApp/i.test(navigator.userAgent);
if (IS_NATIVE) document.documentElement.classList.add("native-app");

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
  { isOpen: () => picker.isOpen() || composer.menusOpen() || sidebar.menusOpen(),
    close: () => {
      if (picker.isOpen()) return picker.close();
      if (composer.menusOpen()) return composer.closeMenus();
      sidebar.closeMenus();
    } },
  // Full-screen / modal overlays.
  { isOpen: () => folderPicker.isOpen(), close: () => folderPicker.close() },
  { isOpen: () => filesPage.isOpen(), close: () => filesPage.close() },
  // Terminal's own layers close before the page itself: close-tab confirm,
  // then the "/" history menu, then the whole terminal.
  { isOpen: () => terminalPage.confirmOpen(), close: () => terminalPage.hideConfirm() },
  { isOpen: () => terminalPage.menuOpen(), close: () => terminalPage.closeMenu() },
  { isOpen: () => !!terminalPage.el && !terminalPage.el.hasAttribute("hidden"), close: () => terminalPage.close() },
  { isOpen: () => appearanceMenu.isOpen(), close: () => appearanceMenu.close() },
  // The sidebar drawer, sitting under any of the above.
  { isOpen: () => document.body.classList.contains("nav-open"),
    close: () => document.body.classList.remove("nav-open") },
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
  localStorage.setItem(THEME_KEY, t);
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
    localStorage.setItem(ACCENT_KEY, hex);
  } else {
    document.body.style.removeProperty("--accent");
    document.body.style.removeProperty("--accent-ink");
    localStorage.removeItem(ACCENT_KEY);
  }
}

// ---- per-session UI state (drafts, attachments, scroll) ----
// Saved when you switch away, restored when you come back — each chat keeps its
// own half-typed message, pending attachments, and reading position.
const uiState = new Map(); // sessionId -> { draft, pending, scrollTop }
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
emitter.on("historyLoaded", (msgs) => {
  dock.clear();
  messageList.renderHistory(msgs);
  // In-flight/failed sends for this session render after its history.
  for (const entry of controller.getOutbox(store.get().activeId)) renderOutboxEntry(entry);
  // Outbox rows belong above the live-turn boundary: a snapshot REPLACES
  // everything below the boundary, and an unsent message must survive that.
  messageList.markLive();
  // Restore where you were scrolled to (falls back to the bottom).
  const st = uiState.get(store.get().activeId);
  if (st && st.scrollTop != null) messageList.el.scrollTop = st.scrollTop;
});
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
// Agent handoff: a new chat was opened to continue with a different agent.
// Attach the exported transcript (already on the laptop — status "done" skips
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
    if (uiSid) uiState.set(uiSid, { ...composer.getState(), scrollTop: messageList.el.scrollTop });
    uiSid = s.activeId;
    composer.setState(uiState.get(uiSid));
  }
  sidebar.render({ sessions: s.sessions, activeId: s.activeId, busyIds: s.busyIds, files: s.allFiles, unreadIds: s.unreadIds, agents: s.agents, power: s.power });
  filesPage.render(s.allFiles);
  composer.setBusy(!!s.busyIds[s.activeId]);   // only the active session's busy gates the composer
  composer.setQueued(s.queued[s.activeId]);    // its pending (queued) message, if any
  topbar.setFolder(s.activeSession ? s.activeSession.effectiveCwd : "");
  // re-render the picker only when its inputs actually change (avoids churn)
  const key = s.agents.length + "|" + s.agentId + "|" + JSON.stringify(s.controls) + "|" + s.autoAllow;
  if (key !== lastPick && s.agents.length) {
    lastPick = key;
    picker.render({ agents: s.agents, agentId: s.agentId, controls: s.controls, autoAllow: s.autoAllow });
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
  applyTheme(localStorage.getItem(THEME_KEY) || "dark");
  applyAccent(localStorage.getItem(ACCENT_KEY) || ""); // restore saved accent (empty = theme default)
  if (!api.hasToken()) return showGate();
  try { await controller.init(); }
  catch (e) { if (String(e.message || e).includes("unauthorized")) showGate(); else throw e; }
  // First visit only: a short tour of the essentials.
  maybeShowGuide(guideSteps(), { onEnd: () => appearanceMenu.close() });
}
boot();
