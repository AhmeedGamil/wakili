// Settings menu. Opened from the sidebar's Settings button. One panel with a
// tab bar — Connection | Device | Other — so each section has the whole body to
// itself (no stacking, no sub-page navigation). "Other" holds the Markdown
// formatting toggle, the light/dark theme toggle, and the accent palette. Dumb
// component: getters read the current state, callbacks apply changes
// (persistence + CSS vars live in main.js); renderConnections/renderDevice fill
// the content area for their tabs.

import { el } from "./dom.js";
import { icon } from "./icons.js";

// Curated accent presets — each reads well on both light and dark backgrounds.
// Includes the Claude coral and a couple of neutral grays for a muted look.
export const PALETTE = [
  { name: "Claude", hex: "#d97757" },
  { name: "Indigo", hex: "#6d5cf0" },
  { name: "Violet", hex: "#8b5cf6" },
  { name: "Blue", hex: "#3b82f6" },
  { name: "Cyan", hex: "#06b6d4" },
  { name: "Teal", hex: "#14b8a6" },
  { name: "Green", hex: "#22c55e" },
  { name: "Lime", hex: "#84cc16" },
  { name: "Yellow", hex: "#eab308" },
  { name: "Amber", hex: "#f59e0b" },
  { name: "Orange", hex: "#f97316" },
  { name: "Red", hex: "#ef4444" },
  { name: "Rose", hex: "#f43f5e" },
  { name: "Pink", hex: "#ec4899" },
  { name: "Slate", hex: "#64748b" },
  { name: "Gray", hex: "#8b8b96" },
];

const TABS = [
  { key: "connection", label: "Connection" },
  { key: "device", label: "Device" },
  { key: "other", label: "Other" },
];

export function createAppearanceMenu({ getTheme, onSetTheme, getAccent, onSetAccent, getFormat, onToggleFormat, renderConnections, renderDevice, onShowGuide }) {
  const seg = el("div", { class: "appr-seg" });
  const swatches = el("div", { class: "swatches" });

  // Markdown formatting toggle. Turns things like **bold** into real formatting
  // (vs. raw text). State lives in localStorage via the message list's
  // setMarkdown; getFormat() reads the current value.
  const mdSwitch = el("span", { class: "md-switch" });
  const mdBtn = el("button", { class: "md-toggle", type: "button", title: "Format Markdown in messages" },
    icon("type"), el("span", { class: "md-toggle-label", text: "Format Markdown" }), mdSwitch);
  mdBtn.addEventListener("click", () => { if (onToggleFormat) onToggleFormat(!getFormat()); renderOther(); });

  // Replay the first-run tour on demand (the guide navigates the UI itself,
  // so the settings panel gets out of its way first).
  const guideBtn = el("button", { class: "set-row", type: "button", onClick: () => { close(); if (onShowGuide) onShowGuide(); } },
    icon("help"), el("span", { class: "set-row-label", text: "Show the guide again" }), icon("chevron-right", "set-row-go"));

  // The "Other" tab's content, rebuilt on open so it reflects live state.
  const otherBody = el("div", { class: "appr-body" },
    el("div", { class: "appr-label", text: "Formatting" }), mdBtn,
    el("div", { class: "appr-label", text: "Theme" }), seg,
    el("div", { class: "appr-label", text: "Accent color" }), swatches,
    el("div", { class: "appr-label", text: "Help" }), guideBtn);

  const tabsBar = el("div", { class: "set-tabs" });
  const content = el("div", { class: "set-content" });
  let curTab = "connection";

  const panel = el("div", { class: "fp-panel ep-panel set-panel" },
    el("div", { class: "fp-head" },
      el("strong", { text: "Settings" }),
      el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
    ),
    tabsBar,
    content,
  );
  const overlay = el("div", { class: "fp-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  function close() { overlay.setAttribute("hidden", ""); }

  function renderTabs() {
    tabsBar.replaceChildren(...TABS.map((t) =>
      el("button", { type: "button", class: "set-tab" + (t.key === curTab ? " on" : ""), onClick: () => showTab(t.key) },
        el("span", { text: t.label }))));
  }
  function showTab(key) {
    curTab = key;
    renderTabs();
    content.replaceChildren();
    if (key === "connection") { if (renderConnections) renderConnections(content); }
    else if (key === "device") { if (renderDevice) renderDevice(content); }
    else { renderOther(); content.appendChild(otherBody); }
  }

  function renderOther() {
    if (getFormat) mdBtn.classList.toggle("on", !!getFormat());
    const theme = getTheme();
    seg.replaceChildren(
      themeBtn("light", "Light", "sun", theme),
      themeBtn("dark", "Dark", "moon", theme),
    );
    const accent = (getAccent() || "").toLowerCase();
    swatches.replaceChildren(...PALETTE.map((c) => {
      const on = accent === c.hex.toLowerCase();
      const sw = el("button", { class: "swatch" + (on ? " on" : ""), type: "button", title: c.name, style: `background:${c.hex}`, "aria-label": c.name });
      sw.addEventListener("click", () => { onSetAccent(c.hex); renderOther(); });
      return sw;
    }));
  }

  function themeBtn(value, label, iconName, current) {
    const b = el("button", { type: "button", class: current === value ? "on" : "" }, icon(iconName), el("span", { text: label }));
    b.addEventListener("click", () => { onSetTheme(value); renderOther(); });
    return b;
  }

  function open() { showTab(curTab); overlay.removeAttribute("hidden"); }
  // openTab/close/panel let the guided tour drive this panel step by step.
  function openTab(key) { showTab(key); overlay.removeAttribute("hidden"); }
  return { open, openTab, close, panel };
}
