// Sidebar: logo, new-chat button, a compact Files entry (opens the full Files
// page), a "Sessions" header with a filter menu, the session list, and the
// footer menus. The session list renders one of two ways, picked in the filter
// popover and remembered in localStorage:
//   - "By project": sessions grouped under their folder, each group with its own
//     + to start a chat already assigned to that folder.
//   - "By latest": one flat, newest-first list with a folder badge per row.
// Long-pressing (or right-clicking) a session row opens a small Rename/Delete
// menu; Delete asks for confirmation before it reports the intent.
// Pure UI — it renders from data and reports user intent through callbacks.

import { el, dismissFirst } from "./dom.js";
import { icon } from "./icons.js";

const VIEW_KEY = "ra-session-view"; // "project" | "all"
const MODEL_KEY = "ra-session-model"; // "1" = show each session's model in the list

export function createSidebar({ onNew, onNewLast, onNewInFolder, onSelect, onDelete, onRename, onOpenFiles, onAppearance }) {
  let view = localStorage.getItem(VIEW_KEY) === "all" ? "all" : "project";
  let showModel = localStorage.getItem(MODEL_KEY) === "1";
  let last = { sessions: [], activeId: null, busyIds: {}, unreadIds: {}, agents: [] }; // cached for re-render on toggle

  const filesBtn = el("button", { class: "side-files-btn", type: "button", onClick: onOpenFiles },
    el("span", { class: "sf-left" }, icon("file-text"), el("span", { text: "Files" })),
    el("span", { class: "side-files-count", text: "0" }));

  // "Sessions" header: title + a filter button whose popover picks how the
  // list is grouped (by project / one flat list) and what each row shows.
  const viewItem = (label, v, ico) =>
    el("button", { type: "button", "data-view": v, onClick: () => { setView(v); pop.hidden = true; } },
      icon(ico), el("span", { class: "sess-opt", text: label }), icon("check", "sess-check"));
  const modelItem = el("button", { type: "button", onClick: () => {
    showModel = !showModel;
    localStorage.setItem(MODEL_KEY, showModel ? "1" : "0");
    renderToggle();
    renderSessions();
  } }, icon("bot"), el("span", { class: "sess-opt", text: "Show model" }), icon("check", "sess-check"));
  const pop = el("div", { class: "sess-pop" },
    viewItem("By project", "project", "folder"),
    viewItem("By latest", "all", "menu"),
    el("div", { class: "sess-sep" }),
    modelItem);
  pop.hidden = true;
  const filterBtn = el("button", { class: "sess-filter", type: "button", title: "Group sessions", onClick: () => { pop.hidden = !pop.hidden; } }, icon("filter"));
  const refreshBtn = el("button", { class: "sess-filter", type: "button", title: "Refresh", onClick: () => {
    if (refreshBtn.classList.contains("spin")) return; // already reloading
    // Immediate feedback: spin the icon while the page reload kicks in (there's
    // a brief blank moment before it takes over).
    refreshBtn.classList.add("spin");
    // Come back to the same chat after the reload — a refresh shouldn't dump
    // you into the first session like a cold start would.
    try { if (last.activeId) sessionStorage.setItem("ra-resume-sid", last.activeId); } catch { /* private mode */ }
    location.reload();
  } }, icon("refresh"));
  const sessHead = el("div", { class: "sess-head" }, el("span", { class: "sess-title", text: "Sessions" }), refreshBtn, filterBtn, pop);
  dismissFirst(() => !pop.hidden, (t) => sessHead.contains(t), () => { pop.hidden = true; });

  const list = el("nav", { class: "session-list" });

  // "New session" row (same style as Files/Settings): one tap starts a new
  // chat in the last selected project, no picker.
  const newSessionBtn = el("button", { class: "side-files-btn", type: "button", onClick: onNewLast },
    el("span", { class: "sf-left" }, icon("plus"), el("span", { text: "New session" })));

  // Long-press (touch) or right-click menu on a session row: Rename / Delete.
  // Delete opens a centered confirmation dialog. The menu is attached to <body>
  // and fixed-positioned so the sidebar's drawer transform and the list's own
  // scrolling can't misplace it.
  const ctx = el("div", { class: "sess-pop sess-ctx" });
  ctx.hidden = true;
  document.body.appendChild(ctx);
  let ctxAt = { x: 0, y: 0 };
  const closeCtx = () => { ctx.hidden = true; };
  function placeCtx() {
    ctx.style.left = Math.max(8, Math.min(ctxAt.x, window.innerWidth - ctx.offsetWidth - 8)) + "px";
    ctx.style.top = Math.max(8, Math.min(ctxAt.y, window.innerHeight - ctx.offsetHeight - 8)) + "px";
  }
  function openCtx(s, x, y) {
    ctxAt = { x, y };
    ctxMenu(s);
    ctx.hidden = false;
    placeCtx();
  }
  function ctxMenu(s) {
    ctx.innerHTML = "";
    ctx.append(
      el("button", { type: "button", onClick: () => ctxRename(s) }, icon("pencil"), el("span", { class: "sess-opt", text: "Rename" })),
      el("button", { class: "ctx-danger", type: "button", onClick: () => ctxConfirm(s) }, icon("trash"), el("span", { class: "sess-opt", text: "Delete" })));
  }
  function ctxRename(s) {
    const input = el("input", { class: "sess-ctx-input", type: "text", value: s.title || "New chat" });
    const save = () => { const t = input.value.trim(); closeCtx(); if (t && t !== (s.title || "New chat")) onRename(s.id, t); };
    ctx.innerHTML = "";
    ctx.append(input, el("div", { class: "sess-ctx-row" },
      el("button", { class: "btn", type: "button", onClick: closeCtx }, "Cancel"),
      el("button", { class: "btn", type: "button", onClick: save }, "Save")));
    placeCtx();
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") save(); else if (e.key === "Escape") closeCtx(); });
    input.focus();
    input.select();
  }
  // Delete confirmation: a proper centered dialog (not the little popover) —
  // destructive actions deserve space and an obvious, deliberate choice.
  function ctxConfirm(s) {
    closeCtx();
    const overlay = el("div", { class: "confirm-overlay", onClick: (e) => { if (e.target === overlay) overlay.remove(); } },
      el("div", { class: "confirm-panel" },
        el("div", { class: "confirm-title", text: "Delete chat?" }),
        el("div", { class: "confirm-msg", text: `"${s.title || "New chat"}" and its history will be permanently deleted.` }),
        el("div", { class: "confirm-row" },
          el("button", { class: "btn", type: "button", onClick: () => overlay.remove() }, "Cancel"),
          el("button", { class: "btn danger", type: "button", onClick: () => { overlay.remove(); onDelete(s.id); } }, "Delete"))));
    document.body.appendChild(overlay);
  }
  dismissFirst(() => !ctx.hidden, (t) => ctx.contains(t), closeCtx);
  // Settings (connection, device, theme, accent, formatting) live in a page
  // opened by this button — it sits right under Files, in the same style.
  const settingsBtn = el("button", { class: "side-files-btn", type: "button", onClick: () => onAppearance && onAppearance() },
    el("span", { class: "sf-left" }, icon("settings"), el("span", { text: "Settings" })));

  const selectBtn = el("button", { class: "side-files-btn", type: "button", onClick: onNew },
    el("span", { class: "sf-left" }, icon("folder"), el("span", { text: "Select project" })));

  const root = el("aside", { id: "sidebar" },
    el("div", { class: "side-brand" },
      el("span", { class: "brand-logo", "aria-hidden": "true" }),
      el("span", { class: "brand-name", text: "Wakili" })),
    selectBtn,
    filesBtn,
    settingsBtn,
    newSessionBtn,
    sessHead,
    list,
  );

  function setView(v) {
    if (v === view) return;
    view = v;
    localStorage.setItem(VIEW_KEY, v);
    renderToggle();
    renderSessions();
  }
  function renderToggle() {
    for (const b of pop.querySelectorAll("[data-view]")) b.classList.toggle("on", b.dataset.view === view);
    modelItem.classList.toggle("on", showModel);
  }

  function render({ sessions, activeId, busyIds = {}, files = [], unreadIds = {}, agents = [] }) {
    last = { sessions: sessions || [], activeId, busyIds, unreadIds, agents: agents || [] };
    filesBtn.querySelector(".side-files-count").textContent = String(files.length);
    renderToggle();
    renderSessions();
  }

  // Human model name ("Opus 4.8") for a session: resolve the stored value
  // against the agent's model options; unknown values show as-is, and a session
  // with no model recorded shows nothing (never the agent's name).
  function modelLabel(s) {
    if (!s.model) return null;
    const agent = last.agents.find((a) => a.id === s.agentId);
    const opts = agent?.controls?.model?.options || [];
    const opt = opts.find((o) => o.value === s.model);
    return opt ? opt.label : s.model;
  }

  function sessionRow(s, activeId, busyIds, unreadIds, badge) {
    // Status, most-urgent first: waiting for your answer (permission/question),
    // then working, then "finished while you were away", else nothing. No
    // placeholder when there's no status — an empty flex child would still eat
    // a row gap and push the model pill off the right edge.
    let flag = null;
    if (s.pending > 0) flag = el("span", { class: "s-pending", title: "Waiting for your answer" }, icon("lock"));
    else if (busyIds[s.id] || s.busy) flag = el("span", { class: "s-busy", title: "Working…" });
    else if (unreadIds[s.id]) flag = el("span", { class: "s-unread", title: "New reply" });
    const title = el("span", { class: "s-title", text: s.title || "New chat" });
    const main = badge
      ? el("div", { class: "s-main" }, title, el("span", { class: "s-badge", text: badge }))
      : title;
    const label = showModel ? modelLabel(s) : null;
    const model = label ? el("span", { class: "s-model", text: label }) : null;
    // `held` marks a long-press so the row tap that produced it doesn't also
    // select the session. touchstart/mousedown reset it, so a stale flag can
    // never swallow a later, ordinary tap.
    let holdTimer = 0, held = false;
    const row = el("div",
      { class: "session" + (s.id === activeId ? " active" : ""), onClick: () => { if (held) { held = false; return; } onSelect(s.id); } },
      main, model, flag);
    const armHold = (x, y) => { held = false; clearTimeout(holdTimer); holdTimer = setTimeout(() => { held = true; openCtx(s, x, y); }, 500); };
    row.addEventListener("touchstart", (e) => { const t = e.touches[0]; armHold(t.clientX, t.clientY); }, { passive: true });
    row.addEventListener("touchmove", () => clearTimeout(holdTimer), { passive: true });
    row.addEventListener("touchcancel", () => clearTimeout(holdTimer), { passive: true });
    // preventDefault on touchend stops the browser's synthetic click — without
    // it the tap that opened the menu would immediately count as an outside
    // click and dismiss it.
    row.addEventListener("touchend", (e) => { clearTimeout(holdTimer); if (held) e.preventDefault(); }, { passive: false });
    row.addEventListener("mousedown", () => { held = false; });
    row.addEventListener("contextmenu", (e) => { e.preventDefault(); held = true; openCtx(s, e.clientX, e.clientY); });
    return row;
  }

  function renderSessions() {
    const { sessions, activeId, busyIds, unreadIds } = last;
    list.innerHTML = "";
    if (view === "all") {
      // Flat, newest-first (server order), each row tagged with its folder.
      for (const s of sessions) list.appendChild(sessionRow(s, activeId, busyIds, unreadIds, s.cwd ? baseName(s.cwd) : "Default project"));
      return;
    }
    // By project: group by folder, each group with a + to start a chat there.
    const groups = new Map(); // label -> { cwd, items: [] }
    for (const s of sessions) {
      const label = s.cwd ? baseName(s.cwd) : "Default project";
      if (!groups.has(label)) groups.set(label, { cwd: s.cwd || null, items: [] });
      groups.get(label).items.push(s);
    }
    for (const [label, group] of groups) {
      // The whole header is the "new chat in this project" target — a full-width
      // row beats hunting for the little +, which stays as a visual hint.
      list.appendChild(el("div", {
        class: "group-head", title: "New chat in " + (group.cwd || "the default project"),
        onClick: () => onNewInFolder(group.cwd),
      }, icon("folder", "group-ico"), el("span", { class: "group-label", text: label }), el("span", { class: "group-add" }, icon("plus"))));
      for (const s of group.items) list.appendChild(sessionRow(s, activeId, busyIds, unreadIds));
    }
  }

  function baseName(p) {
    const parts = String(p).replace(/[\\/]+$/, "").split(/[\\/]/);
    return parts[parts.length - 1] || p;
  }

  return { el: root, render };
}
