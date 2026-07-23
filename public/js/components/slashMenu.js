// Shared "/" popup menu — the composer's slash-commands and the terminal's
// history recall are the same interaction, defined once. Watches a textarea:
// while its value is a single "/token" (no space yet) the menu lists matching
// items above the bar — ↑/↓ move, Enter/Tab or a tap picks, Escape closes.
//
// The owner drives it: call sync() from the input event, and give keydown()
// first refusal in the keydown handler (it returns true while the menu is
// open, so the owner skips its own key handling — e.g. Enter-to-run).

import { el } from "./dom.js";

// `filter(q)` returns the items matching a lowercased query; `render(item)`
// returns the child nodes for the item's button; `onPick(item)` applies it.
export function createSlashMenu({ menu, input, filter, render, onPick }) {
  let items = [];  // items currently shown
  let active = 0;  // highlighted index
  let open = false;

  function close() { open = false; menu.setAttribute("hidden", ""); menu.innerHTML = ""; }

  function paint() {
    menu.innerHTML = "";
    items.forEach((c, i) => {
      menu.appendChild(el("button",
        { type: "button", class: "slash-item" + (i === active ? " active" : ""),
          onMousedown: (e) => { e.preventDefault(); onPick(c); } },
        ...render(c)));
    });
  }

  function move(dir) {
    if (!items.length) return;
    active = (active + dir + items.length) % items.length;
    paint();
    menu.children[active]?.scrollIntoView({ block: "nearest" });
  }

  // Re-derive the menu from the input's current value.
  function sync() {
    const m = /^\/(\S*)$/.exec(input.value); // only a bare "/token", no args yet
    if (!m) return close();
    items = filter(m[1].toLowerCase());
    if (!items.length) return close();
    active = 0; open = true; menu.removeAttribute("hidden"); paint();
  }

  // The menu owns the keys while it's open. Returns true when open (the owner
  // should stop) — unhandled keys still type into the box and re-sync via input.
  function keydown(e) {
    if (!open) return false;
    if (e.key === "ArrowDown") { e.preventDefault(); move(1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); move(-1); }
    else if (e.key === "Enter" || e.key === "Tab") { e.preventDefault(); onPick(items[active]); }
    else if (e.key === "Escape") { e.preventDefault(); close(); }
    return true;
  }

  return { isOpen: () => open, close, sync, keydown };
}
