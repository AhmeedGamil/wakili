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

const THEME_KEY = "ra-theme";
const ACCENT_KEY = "ra-accent";

const store = createStore({ sessions: [], activeId: null, activeSession: null, agents: [], agentId: "claude", controls: {}, busyIds: {}, queued: {}, autoAllow: !!localStorage.getItem("ra-auto-allow"), files: { received: [], uploaded: [] }, allFiles: [], power: { platform: "", keepAwake: false } });
const emitter = createEmitter();
const controller = createChatController({ api, store, emitter });

// ---- components ----
// New chat: pick/create a project folder first (start browsing from the current
// chat's folder), then create the chat there. The per-project + skips the modal.
function newChatWithPicker() {
  document.body.classList.remove("nav-open");
  const start = store.get().activeSession?.effectiveCwd || "";
  folderPicker.open((cwd) => controller.newSession(cwd), start);
}
const sidebar = createSidebar({
  onNew: newChatWithPicker,
  onNewInFolder: (cwd) => { document.body.classList.remove("nav-open"); controller.newSession(cwd); },
  onSelect: (id) => { document.body.classList.remove("nav-open"); controller.openSession(id); },
  onDelete: (id) => controller.deleteSession(id),
  onOpenFiles: () => filesPage.open(),
  onConnections: () => endpointMenu.open(),
  onDeviceMenu: () => deviceMenu.open(),
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
const deviceMenu = createDeviceMenu({ // lock screen / turn off screen / keep awake
  onLock: () => controller.lockScreen(),
  onScreenOff: () => controller.screenOff(),
  onToggleKeepAwake: (on) => controller.setKeepAwake(on),
  getKeepAwake: () => store.get().power.keepAwake,
});
const appearanceMenu = createAppearanceMenu({ // markdown formatting + theme + accent color
  getTheme: () => document.body.dataset.theme,
  onSetTheme: (t) => applyTheme(t),
  getAccent: () => localStorage.getItem(ACCENT_KEY) || "#6d5cf0",
  onSetAccent: (hex) => applyAccent(hex),
  getFormat: () => localStorage.getItem("ra-markdown") !== "0",
  onToggleFormat: (on) => messageList.setMarkdown(on),
});

const messageList = createMessageList();
const dock = createDock({
  onPermission: (id, decision, tool) => controller.answerPermission(id, decision, tool),
  onAnswerQuestion: (id, answer) => controller.answerQuestion(id, answer),
  onArchive: (node) => messageList.addRecord(node),
});
const composer = createComposer({
  onSend: (t, attachments) => controller.send(t, attachments),
  onStop: () => controller.stopActive(),
  onCancelQueued: () => controller.cancelQueued(),
  onOpenTerminal: () => terminalPage.open(store.get().activeSession?.effectiveCwd || ""),
});

const backdrop = el("div", { id: "backdrop", onClick: () => document.body.classList.remove("nav-open") });
const main = el("main", { id: "main" }, topbar.el, messageList.el, dock.el, composer.el);
document.getElementById("app").append(sidebar.el, main, backdrop);

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

// ---- streaming events -> components ----
emitter.on("userMessage", (t) => messageList.userMessage(t));
emitter.on("historyLoaded", (msgs) => { dock.clear(); messageList.renderHistory(msgs); });
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
emitter.on("file", (f) => messageList.addFile(f));
emitter.on("turnEnd", () => messageList.endTurn());
emitter.on("focusInput", () => composer.focus());

// ---- state -> components ----
let lastPick = "";
store.subscribe((s) => {
  sidebar.render({ sessions: s.sessions, activeId: s.activeId, busyIds: s.busyIds, files: s.allFiles, power: s.power });
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
      el("div", { class: "lg-title", text: "Remote Agent" }),
      el("div", { class: "lg-sub", text: "Open this page from the link or QR code in your laptop's terminal — it carries your access token." }),
    )));
}

async function boot() {
  applyTheme(localStorage.getItem(THEME_KEY) || "dark");
  applyAccent(localStorage.getItem(ACCENT_KEY) || ""); // restore saved accent (empty = theme default)
  if (!api.hasToken()) return showGate();
  try { await controller.init(); }
  catch (e) { if (String(e.message || e).includes("unauthorized")) showGate(); else throw e; }
}
boot();
