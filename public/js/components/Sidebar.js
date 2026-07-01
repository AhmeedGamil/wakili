// Sidebar: new-chat button, a compact Files entry (opens the full Files page),
// a session-view toggle, the session list, and a theme toggle. The session list
// renders one of two ways, switched by the inline toggle and remembered in
// localStorage:
//   - "By project": sessions grouped under their folder, each group with its own
//     + to start a chat already assigned to that folder.
//   - "All chats": one flat, newest-first list with a folder badge per row.
// Pure UI — it renders from data and reports user intent through callbacks.

import { el } from "./dom.js";
import { icon } from "./icons.js";

const VIEW_KEY = "ra-session-view"; // "project" | "all"

export function createSidebar({ onNew, onNewInFolder, onSelect, onDelete, onOpenFiles, onConnections, onDeviceMenu, onAppearance }) {
  let view = localStorage.getItem(VIEW_KEY) === "all" ? "all" : "project";
  let last = { sessions: [], activeId: null, busyIds: {} }; // cached for re-render on toggle

  const filesBtn = el("button", { class: "side-files-btn", type: "button", onClick: onOpenFiles },
    el("span", { class: "sf-left" }, icon("file-text"), el("span", { text: "Files" })),
    el("span", { class: "side-files-count", text: "0" }));

  const projTab = el("button", { class: "sv-tab", type: "button", text: "By project", onClick: () => setView("project") });
  const allTab = el("button", { class: "sv-tab", type: "button", text: "All chats", onClick: () => setView("all") });
  const toggle = el("div", { class: "sv-toggle" }, projTab, allTab);

  const list = el("nav", { class: "session-list" });
  // Settings (theme, accent color, markdown formatting) live in a menu opened by
  // this button — it sits in the footer next to Device.
  const apprBtn = el("button", { class: "btn ghost", title: "Settings", onClick: () => onAppearance && onAppearance() }, icon("settings"), el("span", { text: "Settings" }));
  const connBtn = el("button", { class: "btn ghost", title: "Connection", onClick: () => onConnections && onConnections() }, icon("wifi"), el("span", { text: "Connection" }));

  // Device controls (lock screen, turn off screen, keep awake) live in a menu
  // opened by this button — it sits in the footer next to Connection.
  const deviceBtn = el("button", { class: "btn ghost", title: "Device controls", onClick: () => onDeviceMenu && onDeviceMenu() }, icon("power"), el("span", { text: "Device" }));

  const root = el("aside", { id: "sidebar" },
    el("div", { class: "side-head" }, el("button", { class: "btn primary", onClick: onNew }, icon("folder"), el("span", { text: "Select project" }))),
    filesBtn,
    toggle,
    list,
    el("div", { class: "side-foot" }, connBtn, deviceBtn, apprBtn),
  );

  function setView(v) {
    if (v === view) return;
    view = v;
    localStorage.setItem(VIEW_KEY, v);
    renderToggle();
    renderSessions();
  }
  function renderToggle() {
    projTab.classList.toggle("on", view === "project");
    allTab.classList.toggle("on", view === "all");
  }

  function render({ sessions, activeId, busyIds = {}, files = [] }) {
    last = { sessions: sessions || [], activeId, busyIds };
    filesBtn.querySelector(".side-files-count").textContent = String(files.length);
    renderToggle();
    renderSessions();
  }

  function sessionRow(s, activeId, busyIds, badge) {
    const busy = (busyIds[s.id] || s.busy) ? el("span", { class: "s-busy", title: "Working…" }) : el("span");
    const title = el("span", { class: "s-title", text: s.title || "New chat" });
    const main = badge
      ? el("div", { class: "s-main" }, title, el("span", { class: "s-badge", text: badge }))
      : title;
    return el("div",
      { class: "session" + (s.id === activeId ? " active" : ""), onClick: () => onSelect(s.id) },
      main, busy);
  }

  function renderSessions() {
    const { sessions, activeId, busyIds } = last;
    list.innerHTML = "";
    if (view === "all") {
      // Flat, newest-first (server order), each row tagged with its folder.
      for (const s of sessions) list.appendChild(sessionRow(s, activeId, busyIds, s.cwd ? baseName(s.cwd) : "Default project"));
      return;
    }
    // By project: group by folder, each group with a + to start a chat there.
    const groups = new Map(); // label -> { cwd, items: [] }
    for (const s of sessions) {
      const label = s.cwd ? baseName(s.cwd) : "Default project";
      if (!groups.has(label)) groups.set(label, { cwd: s.cwd || null, items: [] });
      groups.get(label).items.push(s);
    }
    for (const [label, group] of groups) {
      const add = el("button", { class: "group-add", title: "New chat in this project", onClick: (e) => { e.stopPropagation(); onNewInFolder(group.cwd); } }, icon("plus"));
      list.appendChild(el("div", { class: "group-head", title: group.cwd || "the gateway's own folder" },
        el("span", { class: "group-label", text: label }), add));
      for (const s of group.items) list.appendChild(sessionRow(s, activeId, busyIds));
    }
  }

  function baseName(p) {
    const parts = String(p).replace(/[\\/]+$/, "").split(/[\\/]/);
    return parts[parts.length - 1] || p;
  }

  return { el: root, render };
}
