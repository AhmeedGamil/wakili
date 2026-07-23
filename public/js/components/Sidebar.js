// Sidebar: a floating brand (logo + name) over one scrolling column — the
// "New chat" split button (tap = last project, folder half = pick a project),
// the Files row, then a "Sessions" header with a filter menu and the session
// list; Settings sits alone in a pinned footer under the scroll. Pulling the
// column down from the top reveals a refresh chip under the brand
// (pull-to-refresh). The session list renders one of two ways, picked in the
// filter popover and remembered in localStorage:
//   - "By project": sessions grouped under their folder; the header collapses/
//     expands the group (remembered per device), its + starts a chat there.
//   - "By latest": one flat, newest-first list, each row with a
//     "folder · time-ago" line under the title.
// Long-pressing (or right-clicking) a session row opens a small Rename/Delete
// menu; Delete asks for confirmation before it reports the intent.
// Pure UI — it renders from data and reports user intent through callbacks.

import { el, dismissFirst, backdropFor } from "./dom.js";
import { icon } from "./icons.js";
import { KEYS, getPref, setPref, getJSON, setJSON, putSession } from "../core/prefs.js";

export function createSidebar({ onNew, onNewLast, onNewInFolder, onSelect, onDelete, onRename, onOpenFiles, onAppearance, onRetrySessions }) {
  let view = getPref(KEYS.sessionView) === "all" ? "all" : "project";
  let showModel = getPref(KEYS.sessionModel) === "1";
  let last = { sessions: [], activeId: null, busyIds: {}, unreadIds: {}, agents: [], sessionsState: "loading" }; // cached for re-render on toggle

  const filesBtn = el("button", { class: "side-files-btn", type: "button", onClick: onOpenFiles },
    el("span", { class: "sf-left" }, icon("file-text"), el("span", { text: "Files" })),
    el("span", { class: "side-files-count", text: "0" }));

  // "Sessions" header: title + a filter button whose popover picks how the
  // list is grouped (by project / one flat list) and what each row shows.
  const viewItem = (label, v, ico) =>
    el("button", { type: "button", "data-view": v, onClick: () => { setView(v); closePop(); } },
      icon(ico), el("span", { class: "sess-opt", text: label }), icon("check", "sess-check"));
  const modelItem = el("button", { type: "button", onClick: () => {
    showModel = !showModel;
    setPref(KEYS.sessionModel, showModel ? "1" : "0");
    renderToggle();
    renderSessions();
  } }, icon("bot"), el("span", { class: "sess-opt", text: "Show model" }), icon("check", "sess-check"));
  const pop = el("div", { class: "sess-pop" },
    viewItem("By project", "project", "folder"),
    viewItem("By latest", "all", "menu"),
    el("div", { class: "sess-sep" }),
    modelItem);
  pop.hidden = true;
  const popBd = backdropFor(pop, () => closePop());
  function openPop() { pop.hidden = false; popBd.show(); }
  function closePop() { pop.hidden = true; popBd.hide(); }
  const filterBtn = el("button", { class: "sess-filter", type: "button", title: "Group sessions", onClick: () => { pop.hidden ? openPop() : closePop(); } }, icon("filter"));
  const sessHead = el("div", { class: "sess-head" }, el("span", { class: "sess-title", text: "Sessions" }), filterBtn, pop);
  dismissFirst(() => !pop.hidden, (t) => sessHead.contains(t), closePop);

  const list = el("nav", { class: "session-list" });

  // "New chat": THE primary action, so it sits first and reads accent. A split
  // button — the wide half starts a chat in the last selected project (no
  // picker), the folder half opens the picker for "somewhere else".
  const newBtn = el("div", { class: "side-new" },
    el("button", { class: "side-new-main", type: "button", onClick: onNewLast },
      icon("plus"), el("span", { text: "New chat" })),
    el("button", { class: "side-new-pick", type: "button", title: "New chat in a project…", onClick: onNew },
      icon("folder")));

  // Long-press (touch) or right-click menu on a session row: Rename / Delete.
  // Delete opens a centered confirmation dialog. The menu is attached to <body>
  // and fixed-positioned so the sidebar's drawer transform and the list's own
  // scrolling can't misplace it.
  const ctx = el("div", { class: "sess-pop sess-ctx" });
  ctx.hidden = true;
  document.body.appendChild(ctx);
  let ctxAt = { x: 0, y: 0 };
  const ctxBd = backdropFor(ctx, () => closeCtx());
  const closeCtx = () => { ctx.hidden = true; ctxBd.hide(); };
  function placeCtx() {
    ctx.style.left = Math.max(8, Math.min(ctxAt.x, window.innerWidth - ctx.offsetWidth - 8)) + "px";
    ctx.style.top = Math.max(8, Math.min(ctxAt.y, window.innerHeight - ctx.offsetHeight - 8)) + "px";
  }
  function openCtx(s, x, y) {
    ctxAt = { x, y };
    ctxMenu(s);
    ctx.hidden = false;
    ctxBd.show();
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
          el("button", { class: "btn danger", type: "button", onClick: () => { overlay.remove(); startDelete(s); } }, "Delete"))));
    document.body.appendChild(overlay);
  }
  // Confirmed delete: red spinner on the row until the server answers (the row
  // usually disappears from the refreshed list first; the finally is for
  // failures, where the row stays and the spinner must come off).
  const deletingIds = new Set();
  async function startDelete(s) {
    if (deletingIds.has(s.id)) return;
    deletingIds.add(s.id);
    renderSessions();
    try { await onDelete(s.id); } catch { /* failure toast comes from the controller */ }
    finally { deletingIds.delete(s.id); renderSessions(); }
  }
  dismissFirst(() => !ctx.hidden, (t) => ctx.contains(t), closeCtx);
  // Settings (connection, device, theme, accent, formatting) live in a page
  // opened by this button — pinned in a footer under the scroll, out of the
  // content's way, like every settings gear ends up.
  const settingsBtn = el("button", { class: "side-files-btn", type: "button", onClick: () => onAppearance && onAppearance() },
    el("span", { class: "sf-left" }, icon("settings"), el("span", { text: "Settings" })));
  const foot = el("div", { class: "side-foot" }, settingsBtn);

  // Everything below the brand scrolls as one column; the brand floats over it
  // (absolute, with a fade — see .side-brand) so content slides out under it.
  // A hairline under Files separates the action rows from the sessions area.
  const scroll = el("div", { class: "side-scroll" }, newBtn, filesBtn, el("div", { class: "side-sep" }), sessHead, list);

  // Pull-to-refresh (replaces the old refresh button): dragging the
  // scrolled-to-top sidebar down slides a spinner chip out from under the
  // brand; releasing past the threshold reloads. JS only feeds --pull (the
  // damped drag distance, a plain number) — the slide/fade/rotate live in CSS.
  const pull = el("div", { class: "side-pull" }, el("span", { class: "side-pull-ico" }, icon("refresh")));
  const PULL_GO = 58; // release past this (damped px) → refresh
  let pullFrom = -1, pullAt = 0, reloading = false;
  const setPull = (d) => { pullAt = d; pull.style.setProperty("--pull", String(d)); pull.classList.toggle("ready", d >= PULL_GO); };
  function endPull() {
    if (pullFrom < 0 || reloading) return;
    pullFrom = -1;
    if (pullAt >= PULL_GO) {
      // Same as the old refresh button: keep spinning while the reload kicks
      // in, and stash the open chat so the reload comes back to it.
      reloading = true;
      pull.classList.add("run");
      if (last.activeId) putSession(KEYS.resumeSid, last.activeId);
      location.reload();
    } else { pull.classList.add("settle"); setPull(0); }
  }
  scroll.addEventListener("touchstart", (e) => { pull.classList.remove("settle"); pullFrom = scroll.scrollTop <= 0 ? e.touches[0].clientY : -1; }, { passive: true });
  scroll.addEventListener("touchmove", (e) => {
    if (pullFrom < 0 || reloading) return;
    const dy = e.touches[0].clientY - pullFrom;
    // Not a pull (finger going up, or the list isn't at the top): keep the
    // anchor at the finger so a later downward drag measures from there.
    if (dy <= 0 || scroll.scrollTop > 0) { if (pullAt) setPull(0); pullFrom = e.touches[0].clientY; return; }
    e.preventDefault(); // the pull owns this gesture — no page scroll/refresh underneath
    setPull(dy * 0.5);  // damped: the chip trails the finger
  }, { passive: false });
  scroll.addEventListener("touchend", endPull);
  scroll.addEventListener("touchcancel", endPull);

  const root = el("aside", { id: "sidebar" },
    el("div", { class: "side-brand" },
      el("span", { class: "brand-logo", "aria-hidden": "true" }),
      el("span", { class: "brand-name", text: "Wakili" })),
    pull,
    scroll,
    foot,
  );

  function setView(v) {
    if (v === view) return;
    view = v;
    setPref(KEYS.sessionView, v);
    renderToggle();
    renderSessions();
  }
  function renderToggle() {
    for (const b of pop.querySelectorAll("[data-view]")) b.classList.toggle("on", b.dataset.view === view);
    modelItem.classList.toggle("on", showModel);
  }

  function render({ sessions, activeId, busyIds = {}, files = [], unreadIds = {}, agents = [], sessionsState = "ready" }) {
    last = { sessions: sessions || [], activeId, busyIds, unreadIds, agents: agents || [], sessionsState };
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
    // Deletion owns the leading slot. The ordinary status flag stays at the
    // trailing edge: waiting for an answer, working, unread, or nothing. No
    // placeholder when there's no status — an empty flex child would still eat
    // a row gap and push the model pill off the right edge.
    const deleting = deletingIds.has(s.id);
    const deletingSpinner = deleting ? el("span", { class: "spinner-danger", title: "Deleting…" }) : null;
    let flag = null;
    if (!deleting && s.pending > 0) flag = el("span", { class: "s-pending", title: "Waiting for your answer" }, icon("lock"));
    else if (!deleting && (busyIds[s.id] || s.busy)) flag = el("span", { class: "s-busy", title: "Working…" });
    else if (!deleting && unreadIds[s.id]) flag = el("span", { class: "s-unread", title: "New reply" });
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
      deletingSpinner, main, model, flag);
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
    const { sessions, activeId, busyIds, unreadIds, sessionsState } = last;
    list.innerHTML = "";
    // Before the first successful list load there are no rows to show — say
    // what's happening (spinner) or what went wrong (note + retry) instead of
    // presenting an empty list that reads as "no chats".
    if (!sessions.length && sessionsState === "loading") {
      list.appendChild(el("div", { class: "sess-note" }, el("span", { class: "spinner" })));
      return;
    }
    if (!sessions.length && sessionsState === "error") {
      list.appendChild(el("div", { class: "sess-note" },
        el("span", { text: "Can't load sessions — check the connection." }),
        el("button", { class: "btn", type: "button", onClick: () => onRetrySessions && onRetrySessions() }, "Try again")));
      return;
    }
    if (view === "all") {
      // Flat, newest-first (server order), each row tagged "folder · time-ago".
      for (const s of sessions) {
        const folder = s.cwd ? baseName(s.cwd) : "Default project";
        const ago = timeAgo(s.updatedAt);
        list.appendChild(sessionRow(s, activeId, busyIds, unreadIds, ago ? folder + " · " + ago : folder));
      }
      return;
    }
    // By project: group by folder. The header collapses/expands its group
    // (persisted per device); only the + starts a chat there — a tap that
    // creates a session is too heavy a meaning for a whole header row.
    const groups = new Map(); // label -> { cwd, items: [] }
    for (const s of sessions) {
      const label = s.cwd ? baseName(s.cwd) : "Default project";
      if (!groups.has(label)) groups.set(label, { cwd: s.cwd || null, items: [] });
      groups.get(label).items.push(s);
    }
    for (const [label, group] of groups) {
      const key = group.cwd || "";
      const closed = collapsed.has(key);
      list.appendChild(el("div", {
        class: "group-head" + (closed ? " closed" : ""),
        onClick: () => toggleGroup(key),
      },
        icon("chevron-right", "group-chev"),
        el("span", { class: "group-label", text: label }),
        closed ? el("span", { class: "group-count", text: String(group.items.length) }) : null,
        el("button", {
          class: "group-add", type: "button", title: "New chat in " + (group.cwd || "the default project"),
          onClick: (e) => { e.stopPropagation(); onNewInFolder(group.cwd); },
        }, icon("plus"))));
      if (!closed) for (const s of group.items) list.appendChild(sessionRow(s, activeId, busyIds, unreadIds));
    }
  }

  // Collapsed project groups, keyed by cwd ("" = default project); survives
  // reloads so the sidebar opens the way it was left.
  const collapsed = new Set(getJSON(KEYS.sideGroups, []));
  function toggleGroup(key) {
    collapsed.has(key) ? collapsed.delete(key) : collapsed.add(key);
    setJSON(KEYS.sideGroups, [...collapsed]);
    renderSessions();
  }

  // "now" / "5m" / "3h" / "2d", then a short date — enough to place a chat in
  // time at a glance without reading like a log line.
  function timeAgo(t) {
    if (!t) return "";
    const sec = Math.max(0, (Date.now() - t) / 1000);
    if (sec < 60) return "now";
    if (sec < 3600) return Math.floor(sec / 60) + "m";
    if (sec < 86400) return Math.floor(sec / 3600) + "h";
    if (sec < 604800) return Math.floor(sec / 86400) + "d";
    return new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }

  function baseName(p) {
    const parts = String(p).replace(/[\\/]+$/, "").split(/[\\/]/);
    return parts[parts.length - 1] || p;
  }

  // Native back button: the filter popover and the long-press context menu are
  // transient layers — report whether one is open and close it (top-most first)
  // through the real close paths so the backdrop goes away with the menu.
  const menusOpen = () => !ctx.hidden || !pop.hidden;
  const closeMenus = () => { if (!ctx.hidden) closeCtx(); else closePop(); };
  return { el: root, render, menusOpen, closeMenus };
}
