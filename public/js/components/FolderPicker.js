// Project-folder modal. A reusable, controlled overlay that browses the laptop
// filesystem (via onBrowse), can create a new folder (onCreate), and reports the
// chosen folder through a per-open callback. It owns no trigger button — callers
// open it programmatically: open(onChoose, startPath). "Use this folder" calls
// onChoose(path). Dumb component: it never calls the API directly.

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createFolderPicker({ onBrowse, onCreate }) {
  const crumb = el("div", { class: "fp-crumb" });
  const list = el("div", { class: "fp-list" });
  const newName = el("input", { class: "fp-newname", type: "text", placeholder: "New folder name…" });
  const createBtn = el("button", { class: "btn ghost", type: "button" }, icon("plus"), el("span", { text: "Create" }));
  const useBtn = el("button", { class: "btn primary", type: "button", text: "Use this folder" });
  const panel = el("div", { class: "fp-panel" },
    el("div", { class: "fp-head" },
      el("strong", { text: "Choose a project folder" }),
      el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
    ),
    crumb, list,
    el("div", { class: "fp-new" }, newName, createBtn),
    el("div", { class: "fp-foot" }, useBtn),
  );
  const overlay = el("div", { class: "fp-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  let curPath = "";          // the folder currently being browsed
  let onChoose = null;       // callback for this open() session

  function open(choose, startPath) {
    onChoose = choose || null;
    overlay.removeAttribute("hidden");
    browse(startPath || "");
  }
  function close() { overlay.setAttribute("hidden", ""); onChoose = null; }
  useBtn.addEventListener("click", () => { if (curPath && onChoose) { onChoose(curPath); close(); } });

  // Create a sub-folder in the folder being browsed, then drill into it.
  async function create() {
    const name = newName.value.trim();
    if (!name || !curPath) return;
    createBtn.disabled = true;
    let res;
    try { res = await onCreate(curPath, name); } catch { res = null; }
    createBtn.disabled = false;
    if (res && res.path) { newName.value = ""; browse(res.path); }
    else { newName.focus(); }
  }
  createBtn.addEventListener("click", create);
  newName.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); create(); } });

  async function browse(path) {
    let data;
    try { data = await onBrowse(path); } catch { data = null; }
    if (!data) { list.innerHTML = ""; list.appendChild(el("div", { class: "fp-empty", text: "Couldn't open that folder." })); return; }
    curPath = data.path || "";
    crumb.textContent = curPath || "Pick a drive";
    list.innerHTML = "";
    if (data.parent != null) list.appendChild(row("corner-up-left", "..", () => browse(data.parent)));
    if (!data.dirs.length) list.appendChild(el("div", { class: "fp-empty", text: data.error ? "Can't open this folder." : "No sub-folders." }));
    for (const d of data.dirs) list.appendChild(row("folder", d.name, () => browse(d.path)));
    useBtn.disabled = !curPath;
    createBtn.disabled = !curPath; // can only create inside a real folder
  }

  function row(iconName, name, onClick) {
    return el("button", { class: "fp-row", type: "button", onClick },
      el("span", { class: "fp-ico" }, icon(iconName)),
      el("span", { class: "fp-name", text: name }),
    );
  }

  return { open, close, isOpen: () => !overlay.hasAttribute("hidden") };
}
