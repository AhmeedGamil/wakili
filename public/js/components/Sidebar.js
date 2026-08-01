// Sidebar — on phones this is the HOME PAGE (the app opens here, full-width;
// opening a chat slides it away). A floating brand (logo + name) over one
// scrolling column — the agent tabs (Claude / Codex…) that pick which agent
// new chats use AND filter the session list, the "New chat" split button
// (tap = last project, folder half = pick a project), the Files row, then a
// "Sessions" header with a filter menu and the session list; Settings sits
// alone in a pinned footer under the scroll. Pulling the
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

export function createSidebar({ onNew, onNewLast, onNewInFolder, onSelect, onDelete, onRename, onOpenFiles, onAppearance, onOpenConnect, onRetrySessions, onLoadHost, onAdoptHost }) {
  let view = getPref(KEYS.sessionView) === "all" ? "all" : "project";
  let showModel = getPref(KEYS.sessionModel) === "1";
  let tabAgent = getPref(KEYS.homeAgent) || "claude"; // the selected agent tab — new chats use it, the list filters by it
  // The list opens at the latest few and grows on demand, so a long history
  // never costs a wall of rows before the chat you actually want.
  const PAGE = 5;
  let ownShown = PAGE;
  // "From the host" section state (lazily loaded — see renderHostSection).
  const host = { open: getPref(KEYS.hostOpen) === "1", rows: [], cursor: "", total: 0, loading: false, loaded: false, error: false, adopting: new Set() };
  let last = { sessions: [], activeId: null, busyIds: {}, unreadIds: {}, agents: [], projects: [], sessionsState: "loading" }; // cached for re-render on toggle

  // Agent tabs: the agent is chosen HERE, on the home page — the chat page
  // never shows an agent picker. Segmented buttons from the agents manifest;
  // the choice persists and also filters the session list below.
  const tabs = el("div", { class: "agent-tabs" });
  tabs.hidden = true;
  function renderTabs() {
    const agents = last.agents;
    tabs.hidden = agents.length < 2;
    if (tabs.hidden) return;
    if (!agents.some((a) => a.id === tabAgent)) tabAgent = agents[0].id;
    tabs.innerHTML = "";
    for (const a of agents) {
      tabs.appendChild(el("button", {
        class: a.id === tabAgent ? "on" : "", type: "button", text: a.label,
        onClick: () => {
          if (a.id === tabAgent) return;
          tabAgent = a.id; setPref(KEYS.homeAgent, tabAgent);
          // A different agent is a different list: start both back at page one
          // rather than showing this agent's chats under the other's paging.
          ownShown = PAGE;
          host.rows = []; host.cursor = ""; host.total = 0; host.loaded = false;
          renderTabs(); renderSessions();
          if (host.open) loadHost(true);
        },
      }));
    }
  }


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
  // Creating a chat is a round trip: on a slow link the button used to sit there
  // looking untouched until the chat suddenly opened. The tap answers at once —
  // the + becomes a spinner and the button stops taking clicks until it lands.
  const newIco = el("span", { class: "side-new-ico" }, icon("plus"));
  const newBtn = el("button", { class: "side-new side-new-solo", type: "button", onClick: onNewLast },
    newIco, el("span", { text: "New chat" }));
  // Driven by main.js around the create call, so every entry point that starts a
  // chat (this button, the picker, a group's +) shows the same busy state.
  function setCreating(on) {
    newBtn.disabled = !!on;
    root.classList.toggle("creating", !!on);
    newIco.innerHTML = "";
    newIco.appendChild(on ? el("span", { class: "spinner" }) : icon("plus"));
  }

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
  // Select project / Files / Settings are collapsed into the ☰ menu in the
  // brand row (New chat is the one action kept out, below). One popover recipe
  // (.sess-pop), dropped under the ☰ button, dismissed via its own backdrop.
  const brandFilesCount = el("span", { class: "side-files-count", text: "0" });
  const menu = el("div", { class: "sess-pop side-menu" },
    el("button", { type: "button", onClick: () => { closeMenu(); onNew && onNew(); } },
      icon("folder"), el("span", { class: "sess-opt", text: "Select project" })),
    el("button", { type: "button", onClick: () => { closeMenu(); onOpenFiles && onOpenFiles(); } },
      icon("file-text"), el("span", { class: "sess-opt", text: "Files" }), brandFilesCount),
    el("button", { type: "button", onClick: () => { closeMenu(); onAppearance && onAppearance(); } },
      icon("settings"), el("span", { class: "sess-opt", text: "Settings" })));
  menu.hidden = true;
  const menuBd = backdropFor(menu, () => closeMenu());
  function openMenu() { menu.hidden = false; menuBd.show(); }
  function closeMenu() { menu.hidden = true; menuBd.hide(); }
  const menuBtn = el("button", { class: "brand-menu", type: "button", title: "Menu", onClick: () => { menu.hidden ? openMenu() : closeMenu(); } }, icon("menu"));

  // ● host — the connected computer + live/down dot, under the logo; tapping it
  // opens the connection switcher. One connection-state source feeds this dot,
  // the chat chip's dot, and the floating banner.
  const hostDot = el("span", { class: "conn-dot" });
  // The connected computer's NAME (the current endpoint's label), set once the
  // endpoints load; the raw host:port only until then.
  const hostName = el("span", { class: "side-host-name", text: location.host });
  const setHost = (name) => { if (name) hostName.textContent = name; };
  const hostRow = el("button", { class: "side-host", type: "button", title: "Switch connection", onClick: () => onOpenConnect && onOpenConnect() }, hostDot, hostName);
  // The reconnect pill rides the END of the host row (main.js mounts it here)
  // rather than floating over the page: the status belongs to the host it
  // describes, and nothing below has to leave a gap for it.
  const connSlot = el("div", { class: "side-host-slot" });

  // The floating brand: [logo Wakili …… ☰] over the host row.
  const brand = el("div", { class: "side-brand" },
    el("div", { class: "brand-row" },
      el("span", { class: "brand-logo", "aria-hidden": "true" }),
      el("span", { class: "brand-name", text: "Wakili" }),
      el("div", { class: "brand-menu-wrap" }, menuBtn, menu)),
    el("div", { class: "side-host-row" }, hostRow, connSlot));
  dismissFirst(() => !menu.hidden, (t) => brand.contains(t), closeMenu);

  // Everything below the brand scrolls as one column; the brand floats over it
  // (absolute, with a fade — see .side-brand) so content slides out under it.
  const scroll = el("div", { class: "side-scroll" }, tabs, newBtn, el("div", { class: "side-sep" }), sessHead, list);

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
    brand,
    pull,
    scroll,
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

  function render({ sessions, activeId, busyIds = {}, files = [], unreadIds = {}, agents = [], projects = [], sessionsState = "ready", connected = true }) {
    last = { sessions: sessions || [], activeId, busyIds, unreadIds, agents: agents || [], projects: projects || [], sessionsState };
    brandFilesCount.textContent = String(files.length);
    hostDot.classList.toggle("off", !connected);
    renderTabs();
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
    const { activeId, busyIds, unreadIds, sessionsState } = last;
    // Only the selected agent's chats — the tab is the top of the hierarchy.
    // Sessions from before agents were recorded count as Claude's.
    const sessions = tabs.hidden ? last.sessions
      : last.sessions.filter((s) => (s.agentId || "claude") === tabAgent);
    list.innerHTML = "";
    // Before the first successful list load there are no rows to show — say
    // what's happening (spinner) or what went wrong (note + retry) instead of
    // presenting an empty list that reads as "no chats".
    if (!last.sessions.length && sessionsState === "loading") {
      list.appendChild(el("div", { class: "sess-note" }, el("span", { class: "spinner" })));
      return;
    }
    if (!last.sessions.length && sessionsState === "error") {
      list.appendChild(el("div", { class: "sess-note" },
        el("span", { text: "Can't load sessions — check the connection." }),
        el("button", { class: "btn", type: "button", onClick: () => onRetrySessions && onRetrySessions() }, "Try again")));
      return;
    }
    // The list loaded but this agent has nothing yet. In "By latest" there's
    // genuinely nothing to draw — point at New chat. "By project" still has the
    // project list to show (every project, for every agent), and each group's +
    // starts the first chat there, so it falls through.
    if (!sessions.length && (view === "all" || !last.projects.length)) {
      const label = last.agents.find((a) => a.id === tabAgent)?.label || "";
      list.appendChild(el("div", { class: "sess-note" },
        el("span", { text: `No ${label} chats yet — start one with New chat.` })));
      return;
    }
    // Show the latest few and let the user ask for more, in BOTH views: the cap
    // is applied to the flat newest-first list before grouping, so "the latest
    // N" means the same thing either way.
    const shown = sessions.slice(0, ownShown);
    const moreOwn = sessions.length - shown.length;

    if (view === "all") {
      // Flat, newest-first (server order), each row tagged "folder · time-ago".
      for (const s of shown) {
        const folder = s.cwd ? baseName(s.cwd) : "Default project";
        const ago = timeAgo(s.updatedAt);
        list.appendChild(sessionRow(s, activeId, busyIds, unreadIds, ago ? folder + " · " + ago : folder));
      }
      if (moreOwn > 0) list.appendChild(loadMoreBtn(moreOwn, () => { ownShown += PAGE; renderSessions(); }));
      renderHostSection();
      return;
    }
    // By project: group by folder. The header collapses/expands its group
    // (persisted per device); only the + starts a chat there — a tap that
    // creates a session is too heavy a meaning for a whole header row.
    //
    // Groups come from the gateway's project registry FIRST, not from the
    // sessions on screen: a project belongs to the workspace, not to an agent,
    // so every agent tab lists every project — including ones this agent has no
    // chats in yet, and folders just created that have no chats at all. Sessions
    // are then bucketed into those groups; any cwd not in the registry (an older
    // session, or a folder since forgotten) still gets a group of its own so no
    // chat can become unreachable.
    const groups = new Map(); // key -> { cwd, label, items: [] }
    const groupKey = (cwd) => (cwd ? String(cwd).replace(/[\\/]+$/, "").toLowerCase() : "");
    const ensure = (cwd, label) => {
      const k = groupKey(cwd);
      if (!groups.has(k)) groups.set(k, { cwd: cwd || null, label: label || (cwd ? baseName(cwd) : "Default project"), items: [] });
      return groups.get(k);
    };
    for (const s of shown) ensure(s.cwd);
    for (const pr of last.projects) ensure(pr.path, pr.name);
    // Projects with chats stay ordered by recency (the server sorts sessions
    // newest first, so insertion order already carries it); empty ones sink to
    // the bottom rather than pushing active work down.
    const ordered = [...groups.values()].sort((a, b) => (b.items.length ? 1 : 0) - (a.items.length ? 1 : 0));
    for (const group of ordered) {
      const label = group.label;
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
      if (!closed) {
        for (const s of group.items) list.appendChild(sessionRow(s, activeId, busyIds, unreadIds));
        // A registered project this agent hasn't been used in yet. Say so, so an
        // empty group reads as "nothing here yet" and not as a broken list.
        if (!group.items.length) list.appendChild(el("div", { class: "group-empty", text: "No chats yet — tap + to start one." }));
      }
    }
    if (moreOwn > 0) list.appendChild(loadMoreBtn(moreOwn, () => { ownShown += PAGE; renderSessions(); }));
    renderHostSection();
  }

  // "Load more (N)" — one recipe for both lists.
  function loadMoreBtn(remaining, onClick, busy = false) {
    return el("button", { class: "sess-more", type: "button", disabled: busy ? "" : null, onClick },
      busy ? el("span", { class: "spinner" }) : null,
      el("span", { text: busy ? "Loading…" : `Load more (${remaining})` }));
  }

  // ---- From the host --------------------------------------------------------
  // Chats started in a terminal on the host machine. They live in their OWN
  // section under your chats, never merged into them: work you do at the desk
  // must never reorder the list you were just using on the phone. Collapsed by
  // default and loaded only when opened — the scan is the gateway's to pay for,
  // and only when asked.
  function renderHostSection() {
    if (!onLoadHost) return;
    const open = host.open;
    const head = el("div", { class: "group-head host-head" + (open ? "" : " closed"), onClick: toggleHost },
      icon("chevron-right", "group-chev"),
      icon("monitor", "host-ico"),
      el("span", { class: "group-label", text: "From the host" }),
      host.total ? el("span", { class: "group-count", text: String(host.total) }) : null);
    list.appendChild(head);
    if (!open) return;
    if (host.error) {
      list.appendChild(el("div", { class: "group-empty" },
        el("span", { text: "Couldn't read the host's sessions. " }),
        el("button", { class: "linkish", type: "button", onClick: () => loadHost(true) }, "Try again")));
      return;
    }
    for (const s of host.rows) list.appendChild(hostRowEl(s));
    if (host.loading) list.appendChild(el("div", { class: "group-empty" }, el("span", { class: "spinner" })));
    else if (!host.rows.length) list.appendChild(el("div", { class: "group-empty", text: "No chats started on the host in your projects." }));
    else if (host.cursor) list.appendChild(loadMoreBtn(Math.max(0, host.total - host.rows.length), () => loadHost(), false));
  }

  // A host row reads like a chat row but says where it came from, and tapping it
  // ADOPTS it — the chat becomes yours and moves up into the list above.
  function hostRowEl(s) {
    const busy = host.adopting.has(s.id);
    const folder = s.cwd ? baseName(s.cwd) : "Default project";
    const ago = timeAgo(s.updatedAt);
    const row = el("div", { class: "session host-row" + (busy ? " busy" : ""), title: "Continue this host chat here",
      onClick: () => { if (!busy) adopt(s); } },
      el("div", { class: "s-main" },
        el("div", { class: "s-title", text: s.title }),
        el("span", { class: "s-badge", text: ago ? folder + " · " + ago : folder })),
      busy ? el("span", { class: "spinner" }) : icon("corner-up-left", "host-adopt"));
    return row;
  }

  async function adopt(s) {
    host.adopting.add(s.id);
    renderSessions();
    try {
      await onAdoptHost(s.id);
      // It's a Wakili chat now — drop it from this section rather than waiting
      // for a refetch to notice.
      host.rows = host.rows.filter((r) => r.id !== s.id);
      host.total = Math.max(0, host.total - 1);
    } catch { /* the row stays; the user can try again */ }
    host.adopting.delete(s.id);
    renderSessions();
  }

  function toggleHost() {
    host.open = !host.open;
    setPref(KEYS.hostOpen, host.open ? "1" : "0");
    if (host.open && !host.loaded) loadHost();
    else renderSessions();
  }

  // Fetch the next page (or restart from scratch after a tab switch / retry).
  async function loadHost(reset = false) {
    if (host.loading) return;
    if (reset) { host.rows = []; host.cursor = ""; host.total = 0; }
    host.loading = true;
    host.error = false;
    renderSessions();
    try {
      const page = await onLoadHost({ cursor: host.cursor || "", agentId: tabs.hidden ? "" : tabAgent, limit: PAGE });
      host.rows = host.rows.concat(page.sessions || []);
      host.cursor = page.nextCursor || "";
      host.total = page.total || host.rows.length;
      host.loaded = true;
    } catch {
      host.error = true;
    }
    host.loading = false;
    renderSessions();
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
  const menusOpen = () => !ctx.hidden || !pop.hidden || !menu.hidden;
  const closeMenus = () => { if (!ctx.hidden) closeCtx(); else if (!pop.hidden) closePop(); else closeMenu(); };
  return { el: root, render, menusOpen, closeMenus, setHost, setCreating, connSlot, currentAgent: () => tabAgent };
}
