// One picker. Closed, it shows the current model (e.g. "GPT-5.5"). Open, it's a
// stack of modern dropdowns: Model, then the agent's other settings. The AGENT
// is not here — it's chosen on the home page and fixed per chat. What remains
// is "Continue with <other agent>" at the bottom: it moves THIS chat to that
// agent, keeping the history, and carries the transcript over as context.

import { el, dismissFirst, backdropFor } from "./dom.js";
import { createDropdown } from "./Dropdown.js";

export function createModelPicker({ onSwitchAgent, onPickModel, onControlChange, onToggleAutoContinue }) {
  const current = el("span", { class: "pick-current", text: "Model" });
  const trigger = el("button", { class: "picker-trigger", type: "button" }, current, el("span", { class: "pick-caret", text: "▾" }));
  const pop = el("div", { class: "picker-pop", hidden: "" });
  const root = el("div", { class: "picker" }, trigger, pop);

  let open = false;
  const bd = backdropFor(pop, () => setOpen(false));
  const setOpen = (v) => { open = v; if (v) { pop.removeAttribute("hidden"); bd.show(); } else { pop.setAttribute("hidden", ""); bd.hide(); } };
  trigger.addEventListener("click", (e) => { e.stopPropagation(); setOpen(!open); });
  dismissFirst(() => open, (t) => root.contains(t), () => setOpen(false));

  function render({ agents, agentId, controls, autoContinue }) {
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

    mk({ label: "Model", options: modelOpts, value: controls.model, onChange: (v) => onPickModel(agentId, v) });
    for (const [key, c] of Object.entries(agent?.controls || {})) {
      if (key === "model") continue;
      // A control can scope its options to the selected model (e.g. Codex's
      // effort ladder: ultra only on models that take it).
      const options = (c.optionsFor && c.optionsFor[controls.model]) || c.options;
      mk({ label: c.label, options, value: controls[key] != null ? controls[key] : c.default, onChange: (v) => onControlChange(key, v) });
    }

    // "Auto continue" — a plain on/off switch, not an agent control: a turn this
    // chat loses to the usage limit is re-driven by the gateway once quota
    // returns. On by default; the switch is how a chat opts out.
    const acKnob = el("button", { class: "switch" + (autoContinue ? " on" : ""), type: "button", role: "switch", "aria-checked": autoContinue ? "true" : "false", tabindex: "-1" }, el("span", { class: "sw-knob" }));
    const acRow = el("div", { class: "switch-row" }, el("span", { class: "switch-label", text: "Auto continue" }), acKnob);
    // The whole row is the hit area — the knob alone is too small on phones.
    acRow.addEventListener("click", (e) => { e.stopPropagation(); onToggleAutoContinue && onToggleAutoContinue(!autoContinue); });
    pop.appendChild(acRow);

    // One expandable "Continue with" dropdown (like the controls above) whose
    // options are the OTHER agents; picking one hands this chat to that agent
    // in place, with the transcript attached. No preset value — an action list.
    const others = agents.filter((a) => a.id !== agentId);
    if (others.length) {
      mk({
        label: "Continue with",
        options: others.map((a) => ({ value: a.id, label: a.label, description: a.description })),
        value: "",
        onChange: (id) => { setOpen(false); onSwitchAgent(id); },
      });
    }
  }

  return { el: root, render, open: () => setOpen(true), close: () => setOpen(false), isOpen: () => open };
}
