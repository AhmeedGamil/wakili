// One picker. Closed, it shows the current model (e.g. "GPT-5.5"). Open, it's a
// stack of modern dropdowns: Agent, Model (for that agent), then the agent's
// other settings. Switching agent just updates the Model dropdown in place.

import { el, dismissFirst } from "./dom.js";
import { createDropdown } from "./Dropdown.js";

export function createModelPicker({ onPickAgent, onPickModel, onControlChange, onToggleAutoAllow }) {
  const current = el("span", { class: "pick-current", text: "Model" });
  const trigger = el("button", { class: "picker-trigger", type: "button" }, current, el("span", { class: "pick-caret", text: "▾" }));
  const pop = el("div", { class: "picker-pop", hidden: "" });
  const root = el("div", { class: "picker" }, trigger, pop);

  let open = false;
  const setOpen = (v) => { open = v; v ? pop.removeAttribute("hidden") : pop.setAttribute("hidden", ""); };
  trigger.addEventListener("click", (e) => { e.stopPropagation(); setOpen(!open); });
  dismissFirst(() => open, (t) => root.contains(t), () => setOpen(false));

  function render({ agents, agentId, controls, autoAllow }) {
    const agent = agents.find((a) => a.id === agentId) || agents[0];
    const modelOpts = agent?.controls?.model?.options || [];
    const curM = modelOpts.find((o) => o.value === controls.model);
    current.textContent = curM ? curM.label : (agent?.label || "Model");

    pop.innerHTML = "";
    const dds = [];
    const mk = (opts) => {
      const dd = createDropdown({ ...opts, onOpen: () => dds.forEach((d) => d !== dd && d.close()) });
      dds.push(dd);
      pop.appendChild(dd.el);
    };

    mk({ label: "Agent", options: agents.map((a) => ({ value: a.id, label: a.label, description: a.description })), value: agentId, onChange: (v) => onPickAgent(v) });
    mk({ label: "Model", options: modelOpts, value: controls.model, onChange: (v) => onPickModel(agentId, v) });
    for (const [key, c] of Object.entries(agent?.controls || {})) {
      if (key === "model") continue;
      mk({ label: c.label, options: c.options, value: controls[key] != null ? controls[key] : c.default, onChange: (v) => onControlChange(key, v) });
    }

    // "Allow always" — a plain on/off switch (not an agent control; it governs the
    // gateway's permission cards). When on, incoming permissions auto-approve.
    const knob = el("button", { class: "switch" + (autoAllow ? " on" : ""), type: "button", role: "switch", "aria-checked": autoAllow ? "true" : "false", tabindex: "-1" }, el("span", { class: "switch-dot" }));
    const swRow = el("div", { class: "switch-row" }, el("span", { class: "switch-label", text: "Allow always" }), knob);
    // The whole row is the hit area — the knob alone is too small on phones.
    swRow.addEventListener("click", (e) => { e.stopPropagation(); onToggleAutoAllow(!autoAllow); });
    pop.appendChild(swRow);
  }

  return { el: root, render, open: () => setOpen(true), close: () => setOpen(false), isOpen: () => open };
}
