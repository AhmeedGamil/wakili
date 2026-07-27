// Floating connection banner — mounted once at the app root so it hovers
// top-center over EVERY screen (home page, chat, files…). Renders nothing while
// connected; on a drop it slides in as a quiet "Reconnecting…" pill and, if the
// outage persists, escalates to a danger-toned "server may be down" hint. One
// connection-state source feeds this, the sidebar host dot, and the chat chip
// dot — they can never disagree.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createConnectionBanner({ persistentAfterMs = 30000 } = {}) {
  const label = el("span", { class: "conn-banner-text", text: "Reconnecting…" });
  const root = el("div", { class: "conn-banner", hidden: "" }, el("span", { class: "spinner" }), label);
  let escTimer = 0, connected = true;

  function set(isConnected) {
    if (isConnected === connected) return;
    connected = isConnected;
    clearTimeout(escTimer);
    root.classList.remove("escalated");
    if (connected) {
      root.setAttribute("hidden", "");
      label.textContent = "Reconnecting…";
      return;
    }
    // Down: show the muted pill, then escalate if it stays down.
    root.removeAttribute("hidden");
    escTimer = setTimeout(() => {
      root.classList.add("escalated");
      label.textContent = "Can't reach your computer — is the gateway running?";
    }, persistentAfterMs);
  }

  return { el: root, set };
}
