// Files page. A full-screen overlay opened from the sidebar's Files entry. Two
// tabs — Images and Files — and within each, ONE list sorted newest-first (the
// registry is append-ordered, so reversed = latest first) with a source filter:
// All (default) / By you (source: user) / By agent (source: agent). Images are a
// thumbnail gallery (tap to open); files are download links. Dumb component:
// render(files) feeds it the global files registry; open() shows the current data.

import { el } from "./dom.js";
import { icon } from "./icons.js";
import { openImage, downloadFile } from "./media.js";

export function createFilesPage() {
  let files = [];          // the global registry: [{ source, image, name, url }]
  let tab = "images";      // active tab: "images" | "files"
  let who = "all";         // source filter: "all" | "user" | "agent"

  const imgBtn = el("button", { class: "ft-tab", type: "button", onClick: () => setTab("images") }, icon("image"), el("span", { text: "Images" }));
  const fileBtn = el("button", { class: "ft-tab", type: "button", onClick: () => setTab("files") }, icon("file-text"), el("span", { text: "Files" }));
  const tabs = el("div", { class: "ft-tabs" }, imgBtn, fileBtn);
  const whoChip = (label, v) => el("button", { class: "ft-chip", type: "button", "data-who": v, onClick: () => { who = v; draw(); } }, el("span", { text: label }));
  const chips = el("div", { class: "ft-chips" }, whoChip("All", "all"), whoChip("By you", "user"), whoChip("By agent", "agent"));
  const body = el("div", { class: "ft-body" });

  const panel = el("div", { class: "ft-panel" },
    el("div", { class: "fp-head" },
      el("strong", { text: "Files" }),
      el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
    ),
    tabs, chips, body,
  );
  const overlay = el("div", { class: "fp-overlay ft-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  function open() { overlay.removeAttribute("hidden"); draw(); }
  function close() { overlay.setAttribute("hidden", ""); }

  function setTab(t) { tab = t; draw(); }

  // Build the body for the active tab: one newest-first list, filtered by source.
  function draw() {
    imgBtn.classList.toggle("on", tab === "images");
    fileBtn.classList.toggle("on", tab === "files");
    for (const c of chips.children) c.classList.toggle("on", c.dataset.who === who);
    body.innerHTML = "";
    const items = files
      .filter((f) => (tab === "images" ? f.image : !f.image) && (who === "all" || f.source === who))
      .slice().reverse(); // registry is append-ordered → reversed = latest first
    if (!items.length) {
      body.appendChild(el("div", { class: "fp-empty", text: tab === "images" ? "No images yet." : "No files yet." }));
      return;
    }
    if (tab === "images") {
      const grid = el("div", { class: "ft-grid" });
      for (const f of items) {
        grid.appendChild(el("a", { class: "ft-imglink", title: f.name, onClick: () => openImage(f.url, f.name) },
          el("img", { class: "ft-thumb", src: f.url, alt: f.name })));
      }
      body.appendChild(grid);
    } else {
      for (const f of items) {
        body.appendChild(el("a", { class: "ft-file", title: f.name, onClick: () => downloadFile(f.url, f.name) }, icon("download"), el("span", { text: f.name })));
      }
    }
  }

  function render(list) { files = Array.isArray(list) ? list : []; if (!overlay.hasAttribute("hidden")) draw(); }

  return { open, render, close, isOpen: () => !overlay.hasAttribute("hidden") };
}
