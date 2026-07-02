// Modern custom dropdown (replaces native <select>). A labeled head showing the
// current value; tapping it expands an inline menu of options. onOpen lets a
// parent close sibling dropdowns so only one is open at a time.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createDropdown({ label, options, value, onChange, onOpen }) {
  const valEl = el("span", { class: "dd-value" });
  const head = el("button", { class: "dd-head", type: "button" },
    el("span", { class: "dd-label", text: label }),
    valEl,
    el("span", { class: "dd-caret", text: "▾" }),
  );
  const menu = el("div", { class: "dd-menu", hidden: "" });
  const root = el("div", { class: "dd" }, head, menu);

  let open = false;
  function setOpen(v) {
    open = v;
    v ? menu.removeAttribute("hidden") : menu.setAttribute("hidden", "");
    head.classList.toggle("open", v);
    if (v && onOpen) onOpen();
  }
  head.addEventListener("click", (e) => { e.stopPropagation(); setOpen(!open); });

  const cur = options.find((o) => o.value === value);
  valEl.textContent = cur ? cur.label : "";

  for (const o of options) {
    const active = o.value === value;
    // With a description, show label over a muted sub-line; otherwise just the label.
    const main = o.description
      ? el("span", { class: "dd-opt-main" }, el("span", { class: "dd-opt-label", text: o.label }), el("span", { class: "dd-opt-desc", text: o.description }))
      : el("span", { text: o.label });
    menu.appendChild(el("button",
      { class: "dd-opt" + (active ? " active" : ""), type: "button", onClick: (e) => { e.stopPropagation(); setOpen(false); onChange(o.value); } },
      main,
      active ? icon("check", "dd-check") : null,
    ));
  }

  return { el: root, close: () => setOpen(false) };
}
