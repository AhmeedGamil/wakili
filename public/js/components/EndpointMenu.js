// Connection switcher. The page is served by the gateway, and the same gateway
// is reachable on several URLs (LAN, Tailscale, Cloudflare). This overlay lists
// them and, on pick, simply navigates the browser there — same server, same
// sessions, same token, just a different network path. The current connection
// is marked and not clickable.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createEndpointMenu({ fetchEndpoints }) {
  const list = el("div", { class: "ep-list" });
  const panel = el("div", { class: "fp-panel ep-panel" },
    el("div", { class: "fp-head" },
      el("strong", { text: "Connection" }),
      el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
    ),
    list,
  );
  const overlay = el("div", { class: "fp-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  function close() { overlay.setAttribute("hidden", ""); }

  async function open() {
    overlay.removeAttribute("hidden");
    list.innerHTML = "";
    list.appendChild(el("div", { class: "fp-empty", text: "Loading…" }));
    let eps = [];
    try { eps = await fetchEndpoints(); } catch { eps = null; }
    list.innerHTML = "";
    if (!eps || !eps.length) { list.appendChild(el("div", { class: "fp-empty", text: "No connections reported." })); return; }
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
  }

  return { open };
}
