// Device controls. Rendered as a sub-page inside Settings. A small list:
//   - Lock screen / Turn off screen — one-shot actions (no toggle; the row shows
//     "Started…" while firing, then "Finished" / "Failed").
//   - Keep awake — a stateful toggle reflecting the gateway's current state.
// Dumb component: callbacks do the work; getKeepAwake() reads the live state.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createDeviceMenu({ onLock, onScreenOff, onShutdown, onToggleKeepAwake, getKeepAwake, onToggleAutostart, getAutostart }) {
  function makeSwitch(on) {
    return el("button", { class: "switch" + (on ? " on" : ""), type: "button", role: "switch", "aria-checked": String(!!on) }, el("span", { class: "sw-knob" }));
  }

  // One-shot: no toggle — clicking anywhere on the row fires the action once.
  // The right side reports progress: "Started…" while running, then a brief
  // "Finished" (or "Failed") before returning to the idle chevron.
  function actionRow(iconNode, label, cb) {
    const status = el("span", { class: "dev-status" }, icon("chevron-right"));
    const row = el("div", { class: "dev-row" }, el("span", { class: "dev-left" }, iconNode, el("span", { class: "dev-lbl", text: label })), status);
    let busy = false;
    row.addEventListener("click", async () => {
      if (busy) return;
      busy = true;
      status.replaceChildren(el("span", { class: "dev-note run", text: "Started…" }));
      let ok = true;
      try { await cb(); } catch { ok = false; }
      status.replaceChildren(ok
        ? el("span", { class: "dev-note ok" }, icon("check"), el("span", { text: "Finished" }))
        : el("span", { class: "dev-note err" }, icon("x"), el("span", { text: "Failed" })));
      setTimeout(() => { status.replaceChildren(icon("chevron-right")); busy = false; }, 1800);
    });
    return row;
  }

  // Destructive one-shot: the first tap only ARMS the row ("Tap again to
  // confirm", auto-disarms after a few seconds), the second tap fires. A stray
  // touch can't shut the computer down.
  function confirmRow(iconNode, label, cb) {
    const status = el("span", { class: "dev-status" }, icon("chevron-right"));
    const row = el("div", { class: "dev-row dev-danger" }, el("span", { class: "dev-left" }, iconNode, el("span", { class: "dev-lbl", text: label })), status);
    let armed = false, busy = false, disarmTimer = 0;
    const disarm = () => { armed = false; row.classList.remove("armed"); status.replaceChildren(icon("chevron-right")); };
    row.addEventListener("click", async () => {
      if (busy) return;
      if (!armed) {
        armed = true;
        row.classList.add("armed");
        status.replaceChildren(el("span", { class: "dev-note err", text: "Tap again to confirm" }));
        clearTimeout(disarmTimer);
        disarmTimer = setTimeout(disarm, 4000);
        return;
      }
      clearTimeout(disarmTimer);
      armed = false; busy = true;
      row.classList.remove("armed");
      status.replaceChildren(el("span", { class: "dev-note run", text: "Started…" }));
      let ok = true;
      try { const r = await cb(); ok = !r || r.ok !== false; } catch { ok = false; }
      status.replaceChildren(ok
        ? el("span", { class: "dev-note ok" }, icon("check"), el("span", { text: "Shutting down" }))
        : el("span", { class: "dev-note err" }, icon("x"), el("span", { text: "Failed" })));
      setTimeout(() => { status.replaceChildren(icon("chevron-right")); busy = false; }, 4000);
    });
    return row;
  }

  // Stateful: reflect current state; on toggle, adopt whatever the server
  // reports (pick reads the new on/off out of the server's response shape).
  function toggleRow(iconNode, label, get, set, pick) {
    const sw = makeSwitch(get());
    const row = el("div", { class: "dev-row" }, el("span", { class: "dev-left" }, iconNode, el("span", { class: "dev-lbl", text: label })), sw);
    row.addEventListener("click", async () => {
      if (sw.disabled) return;
      sw.disabled = true;
      const p = await set(!get());
      const on = p ? !!pick(p) : get();
      sw.classList.toggle("on", on); sw.setAttribute("aria-checked", String(on)); sw.disabled = false;
    });
    return row;
  }

  // Fill `container` with the device rows (the settings panel owns the chrome).
  function render(container) {
    const rows = [
      actionRow(icon("lock"), "Lock screen", onLock),
      actionRow(icon("monitor-off"), "Turn off screen", onScreenOff),
      toggleRow(icon("zap"), "Keep awake", getKeepAwake, onToggleKeepAwake, (p) => p.keepAwake),
    ];
    // Start-at-login (hidden on OSes the gateway can't register itself on).
    const auto = getAutostart ? getAutostart() : null;
    if (auto && auto.supported) rows.push(toggleRow(icon("power"), "Start with computer", () => !!auto.on, onToggleAutostart, (p) => p.on));
    // Destructive, so it sits last and takes two taps.
    rows.push(confirmRow(icon("power-off"), "Shut down computer", onShutdown));
    container.replaceChildren(el("div", { class: "dev-list" }, ...rows));
  }

  return { render };
}
