// Connection switcher. The page is served by the gateway, and the same gateway
// is reachable on several URLs (LAN, Tailscale, Cloudflare). Rendered as a
// sub-page inside Settings: it lists them and, on pick, simply navigates the
// browser there — same server, same sessions, same token, just a different
// network path. The current connection is marked and not clickable.
//
// Inside the phone app a second section appears: "Saved computers" — OTHER
// gateways the phone has saved (each with its own token). That list lives in
// the native shell's storage, so the row just asks it (postMessage bridge) to
// open its computers page over the session. A regular browser has no bridge →
// no section.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createEndpointMenu({ fetchEndpoints }) {
  // Rendered as one more row in the same list, so it aligns with the endpoint
  // rows above it; the accent color marks it as an action rather than a place.
  function phoneSection(list) {
    if (!window.ReactNativeWebView) return;
    list.appendChild(el("button", {
      class: "ep-row ep-add", type: "button",
      onClick: () => window.ReactNativeWebView.postMessage(JSON.stringify({ wakiliNetworks: true })),
    }, icon("plus"), el("span", { class: "ep-label", text: "Add or change the host" })));
  }

  // Fill `container` with the endpoint list (the settings panel owns the chrome).
  async function render(container) {
    const list = el("div", { class: "ep-list" });
    container.replaceChildren(list);
    list.appendChild(el("div", { class: "fp-empty", text: "Loading…" }));
    let eps = [];
    try { eps = await fetchEndpoints(); } catch { eps = null; }
    list.innerHTML = "";
    if (!eps || !eps.length) {
      list.appendChild(el("div", { class: "fp-empty", text: "No connections reported." }));
      phoneSection(list);
      return;
    }
    const here = location.host;
    for (const ep of eps) {
      let epHost = ep.host;
      try { epHost = epHost || new URL(ep.url).host; } catch { /* keep as-is */ }
      const current = epHost === here;
      const row = el("button", { class: "ep-row" + (current ? " current" : ""), type: "button", disabled: current ? "" : null },
        el("span", { class: "ep-label", text: ep.label }),
        el("span", { class: "ep-host", text: epHost }),
        current ? el("span", { class: "ep-now" }, icon("check"), el("span", { text: "current" })) : icon("arrow-up-right", "ep-go"),
      );
      if (!current) row.addEventListener("click", () => { location.href = ep.url; });
      list.appendChild(row);
    }
    phoneSection(list);
  }

  return { render };
}
