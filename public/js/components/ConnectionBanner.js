// Small "Reconnecting…" pill — shown only on the home page (CSS gates it to
// body.nav-open / desktop), floating top-center. Renders nothing while
// connected. No escalation: one quiet line, driven by the single connection
// state that also feeds the sidebar host dot and the chat chip dot.

import { el } from "./dom.js";

export function createConnectionBanner() {
  const root = el("div", { class: "conn-banner", hidden: "" },
    el("span", { class: "spinner" }),
    el("span", { class: "conn-banner-text", text: "Reconnecting…" }));

  function set(connected) {
    if (connected) root.setAttribute("hidden", "");
    else root.removeAttribute("hidden");
  }

  return { el: root, set };
}
