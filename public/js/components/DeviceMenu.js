// Device controls menu. Opened from the sidebar's "Device" button. A small list:
//   - Lock screen / Turn off screen — one-shot actions (momentary switch that
//     flips on while firing, then springs back).
//   - Keep awake — a stateful toggle reflecting the gateway's current state.
// Dumb component: callbacks do the work; getKeepAwake() reads the live state.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createDeviceMenu({ onLock, onScreenOff, onToggleKeepAwake, getKeepAwake }) {
  const list = el("div", { class: "dev-list" });
  const panel = el("div", { class: "fp-panel ep-panel" },
    el("div", { class: "fp-head" },
      el("strong", { text: "Device" }),
      el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
    ),
    list,
  );
  const overlay = el("div", { class: "fp-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  function close() { overlay.setAttribute("hidden", ""); }

  function makeSwitch(on) {
    return el("button", { class: "switch" + (on ? " on" : ""), type: "button", role: "switch", "aria-checked": String(!!on) }, el("span", { class: "sw-knob" }));
  }

  // Momentary: fire the action, flash the switch on, then reset.
  function actionRow(iconNode, label, cb) {
    const sw = makeSwitch(false);
    const row = el("div", { class: "dev-row" }, el("span", { class: "dev-left" }, iconNode, el("span", { class: "dev-lbl", text: label })), sw);
    row.addEventListener("click", async () => {
      if (sw.disabled) return;
      sw.disabled = true; sw.classList.add("on"); sw.setAttribute("aria-checked", "true");
      try { await cb(); } catch { /* ignore */ }
      setTimeout(() => { sw.classList.remove("on"); sw.setAttribute("aria-checked", "false"); sw.disabled = false; }, 700);
    });
    return row;
  }

  // Stateful: reflect current state; on toggle, adopt whatever the server reports.
  function toggleRow(iconNode, label, get, set) {
    const sw = makeSwitch(get());
    const row = el("div", { class: "dev-row" }, el("span", { class: "dev-left" }, iconNode, el("span", { class: "dev-lbl", text: label })), sw);
    row.addEventListener("click", async () => {
      if (sw.disabled) return;
      sw.disabled = true;
      const p = await set(!get());
      const on = p ? !!p.keepAwake : get();
      sw.classList.toggle("on", on); sw.setAttribute("aria-checked", String(on)); sw.disabled = false;
    });
    return row;
  }

  function open() {
    list.replaceChildren(
      actionRow(icon("lock"), "Lock screen", onLock),
      actionRow(icon("monitor-off"), "Turn off screen", onScreenOff),
      toggleRow(icon("zap"), "Keep awake", getKeepAwake, onToggleKeepAwake),
    );
    overlay.removeAttribute("hidden");
  }

  return { open };
}
