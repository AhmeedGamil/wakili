// Files page. A full-screen overlay opened from the sidebar's Files entry. Two
// tabs — Images and Files — and within each, two sub-groups: "Attached by you"
// (source: user) and "Sent by the agent" (source: agent). Images are a thumbnail
// gallery (tap to open); files are download links. Dumb component: render(files)
// feeds it the global files registry; open() shows the current data.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createFilesPage() {
  let files = [];          // the global registry: [{ source, image, name, url }]
  let tab = "images";      // active tab: "images" | "files"

  const imgBtn = el("button", { class: "ft-tab", type: "button", onClick: () => setTab("images") }, icon("image"), el("span", { text: "Images" }));
  const fileBtn = el("button", { class: "ft-tab", type: "button", onClick: () => setTab("files") }, icon("file-text"), el("span", { text: "Files" }));
  const tabs = el("div", { class: "ft-tabs" }, imgBtn, fileBtn);
  const body = el("div", { class: "ft-body" });

  const panel = el("div", { class: "ft-panel" },
    el("div", { class: "fp-head" },
      el("strong", { text: "Files" }),
      el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
    ),
    tabs, body,
  );
  const overlay = el("div", { class: "fp-overlay ft-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  function open() { overlay.removeAttribute("hidden"); draw(); }
  function close() { overlay.setAttribute("hidden", ""); }

  function setTab(t) { tab = t; draw(); }

  // Build the body for the active tab: a sub-group per source, or an empty note.
  function draw() {
    imgBtn.classList.toggle("on", tab === "images");
    fileBtn.classList.toggle("on", tab === "files");
    body.innerHTML = "";
    const pick = (src) => files.filter((f) => f.source === src && (tab === "images" ? f.image : !f.image));
    const mine = pick("user");
    const theirs = pick("agent");
    if (!mine.length && !theirs.length) {
      body.appendChild(el("div", { class: "fp-empty", text: tab === "images" ? "No images yet." : "No files yet." }));
      return;
    }
    if (mine.length) body.appendChild(group("Attached by you", mine));
    if (theirs.length) body.appendChild(group("Sent by the agent", theirs));
  }

  function group(title, items) {
    const sec = el("div", { class: "ft-group" }, el("div", { class: "ft-group-title", text: title }));
    if (tab === "images") {
      const grid = el("div", { class: "ft-grid" });
      for (const f of items) {
        grid.appendChild(el("a", { class: "ft-imglink", href: f.url, target: "_blank", title: f.name },
          el("img", { class: "ft-thumb", src: f.url, alt: f.name })));
      }
      sec.appendChild(grid);
    } else {
      for (const f of items) {
        sec.appendChild(el("a", { class: "ft-file", href: f.url, target: "_blank", download: f.name, title: f.name }, icon("download"), el("span", { text: f.name })));
      }
    }
    return sec;
  }

  function render(list) { files = Array.isArray(list) ? list : []; if (!overlay.hasAttribute("hidden")) draw(); }

  return { open, render };
}
