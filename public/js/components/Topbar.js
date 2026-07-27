// Floating top controls (no header bar): a circular back button that returns to
// the home page, the model picker next to it, and a read-only chip on the right showing
// the current chat's project folder. Folder selection itself lives in the sidebar
// now — here it's display-only. Sits over the message list behind a soft top
// gradient.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createTopbar({ onMenu }) {
  const menu = el("button", { class: "icon-btn round", "aria-label": "Back to chats", onClick: onMenu }, icon("chevron-left"));
  const slot = el("div", { class: "topbar-slot" });            // left: model picker
  // right: connection dot + current folder (read-only). The dot mirrors the
  // sidebar's host dot so you always see the link state from inside a chat.
  const folderDot = el("span", { class: "conn-dot" });
  const folderName = el("span", { class: "tf-name" });
  const folder = el("div", { class: "topbar-folder", title: "" }, folderDot, folderName);
  const slotRight = el("div", { class: "topbar-slot right" }, folder);
  const root = el("header", { id: "topbar" }, menu, slot, slotRight);

  const MAX_NAME = 22; // cap the shown project name so a long folder can't blow out the bar
  function baseName(p) {
    const parts = String(p).replace(/[\\/]+$/, "").split(/[\\/]/);
    return parts[parts.length - 1] || p;
  }
  const ellipsize = (s) => (s.length > MAX_NAME ? s.slice(0, MAX_NAME - 1) + "…" : s);
  // Show the chat's folder name (truncated); empty/none → the gateway's own folder.
  function setFolder(cwd) {
    const name = cwd ? baseName(cwd) : "Default project";
    folderName.textContent = ellipsize(name);
    folder.title = cwd || "the gateway's own folder";
  }
  // Connection state → the chip's dot (green live / red down).
  function setConnected(connected) {
    folderDot.classList.toggle("off", !connected);
  }

  return {
    el: root,
    slot,
    slotRight,
    setFolder,
    setConnected,
    setTitle: () => {}, // kept for composition-root compatibility; no title shown
  };
}
