// Docked interactive cards. Anything that needs an answer before the agent can
// continue — a permission request or an AskUserQuestion — appears here, pinned
// just above the composer (not buried in the scrolling log). Once answered, the
// card is removed and a compact record is handed back (onArchive) to drop into
// the scrolling history, so the conversation keeps a trace of what was decided.
//
// Permissions are QUEUED: parallel/batched tool calls can raise several at once,
// so we show only the front card (keeping its pinned-button layout intact) with a
// "+N more" hint, and offer an Allow all / Deny all bar to clear a whole batch.

import { el } from "./dom.js";
import { icon } from "./icons.js";
import { isExpandable, diffBody, toolCard } from "./toolCard.js";

export function createDock({ onPermission, onAnswerQuestion, onArchive, onActiveChange }) {
  const root = el("div", { id: "dock", class: "dock", hidden: "" });
  const perms = [];     // pending permission requests, FIFO
  let permBlock = null; // the rendered permission area (front card + batch bar)
  const asks = new Map(); // question request id -> its card (dedupe + external removal)

  // Tell the composer whenever a card appears/disappears, so it can block sending
  // while an answer is pending. Only fire on real transitions.
  let wasActive = false;
  function notifyActive() {
    const active = root.children.length > 0;
    if (active !== wasActive) { wasActive = active; onActiveChange && onActiveChange(active); }
  }
  const sync = () => { if (!root.children.length) root.setAttribute("hidden", ""); notifyActive(); };
  const show = () => { root.removeAttribute("hidden"); notifyActive(); };
  const archive = (node) => onArchive && onArchive(node);
  function clear() { perms.length = 0; permBlock = null; asks.clear(); root.innerHTML = ""; sync(); }

  // Drop a card whose request was resolved elsewhere (another tab) or timed out
  // server-side — no decision to archive, it just stops being answerable.
  function remove(id) {
    const i = perms.findIndex((r) => r.id === id);
    if (i !== -1) { perms.splice(i, 1); renderPerms(); }
    const card = asks.get(id);
    if (card) { asks.delete(id); card.remove(); sync(); }
  }

  // ---- permissions ----
  function addPermission(req) {
    if (perms.some((r) => r.id === req.id)) return; // stream replay of a card already up
    if (req.autoAllow) { decide(req, "allow_auto"); return; } // "Allow always" → resolve at once, no card
    perms.push(req);
    renderPerms();
  }

  // Resolve one request: tell the gateway, archive a record, drop it from the queue.
  function decide(req, decision) {
    const i = perms.indexOf(req);
    if (i !== -1) perms.splice(i, 1);
    // "allow_auto" is a display-only variant (the global "Allow always" switch
    // answered, not the user) — the gateway only understands allow/deny.
    onPermission(req.id, decision === "allow_auto" ? "allow" : decision, req.tool);
    archive(permRecord(req, decision));
    renderPerms();
  }

  function decideAll(decision) {
    for (const req of perms.splice(0)) { onPermission(req.id, decision, req.tool); archive(permRecord(req, decision)); }
    renderPerms();
  }

  // Compact, expandable record dropped into the scrolling history.
  function permRecord(req, decision) {
    const ok = decision !== "deny";
    const note = decision === "allow_session" ? " — allowed (session)"
      : decision === "allow_auto" ? " — allowed (always)"
      : ok ? " — allowed" : " — denied";
    const rec = el("div", { class: "msg perm decided " + (ok ? "allowed" : "denied") },
      el("div", { class: "perm-head" }, icon(ok ? "check" : "x"), el("span", { text: req.tool + note })));
    if (isExpandable(req.tool) && req.input && typeof req.input === "object") rec.appendChild(toolCard(req.tool, req.input));
    return rec;
  }

  function permCard(req) {
    const hasDiff = isExpandable(req.tool) && req.input && typeof req.input === "object";
    const summary = typeof req.input === "object" ? JSON.stringify(req.input) : String(req.input ?? "");
    const more = perms.length - 1;
    const head = el("div", { class: "perm-head" }, icon("lock"), el("span", { text: "Allow " + req.tool + " ?" + (more > 0 ? `  ·  +${more} more` : "") }));
    const body = hasDiff ? diffBody(req.tool, req.input) : el("div", { class: "perm-body", text: summary });
    if (hasDiff) body.classList.add("perm-body");
    const actions = el("div", { class: "perm-actions" },
      el("button", { class: "btn deny", type: "button", onClick: () => decide(req, "deny") }, "Deny"),
      el("button", { class: "btn allow", type: "button", onClick: () => decide(req, "allow") }, "Allow once"),
      el("button", { class: "btn allow-session", type: "button", onClick: () => decide(req, "allow_session") }, "This session"),
    );
    return el("div", { class: "card perm" }, head, el("div", { class: "perm-scroll" }, body), actions);
  }

  // Re-render the permission area: only the front card, plus a batch bar if 2+.
  function renderPerms() {
    if (permBlock) { permBlock.remove(); permBlock = null; }
    if (!perms.length) { sync(); return; }
    const block = el("div", { class: "perm-block" });
    if (perms.length > 1) {
      block.appendChild(el("div", { class: "perm-batch" },
        el("span", { class: "perm-batch-label", text: perms.length + " permissions pending" }),
        el("button", { class: "btn allow", type: "button", onClick: () => decideAll("allow") }, "Allow all"),
        el("button", { class: "btn deny", type: "button", onClick: () => decideAll("deny") }, "Deny all"),
      ));
    }
    block.appendChild(permCard(perms[0]));
    permBlock = block;
    root.insertBefore(block, root.firstChild); // permissions sit above any question card
    show();
  }

  // ---- multiple-choice questions (ask_options MCP tool, or AskUserQuestion) ----
  // `req` = { id, questions:[{ header, question, multiSelect, options:[str | {label,description}] }] }.
  // Every question also gets an "Other" choice that reveals a free-text box.
  function addQuestion(req) {
    if (asks.has(req.id)) return; // stream replay of a card already up
    const questions = req.questions || [];
    const multi = questions.length > 1;
    const card = el("div", { class: "card ask" });
    card.appendChild(el("div", { class: "perm-head" }, icon("help"), el("span", { text: "The agent is asking…" })));

    const norm = (o) => (typeof o === "string" ? { label: o } : (o || {}));
    const state = questions.map(() => ({ picks: new Set(), other: false, otherText: "" }));
    const answered = (i) => state[i].picks.size > 0 || (state[i].other && state[i].otherText.trim());

    // Tabs (one per question) + a pane that shows only the active question. A single
    // question skips the tab bar. Each option renders as a radio (single-select) or a
    // checkbox (multiSelect), driven by the question's own multiSelect flag.
    const tabsBar = el("div", { class: "ask-tabs" });
    const pane = el("div", { class: "ask-pane" });
    const panes = [];   // content node per question
    const tabs = [];    // tab button per question
    let activeIdx = 0;

    const sendBtn = el("button", { class: "btn allow ask-send", type: "button", onClick: () => submit() }, "Send answers");

    function refresh() {
      tabs.forEach((t, i) => { t.classList.toggle("on", i === activeIdx); t.classList.toggle("done", !!answered(i)); });
      sendBtn.disabled = !state.every((_, i) => answered(i));
    }
    function showTab(i) {
      activeIdx = i;
      panes.forEach((p, j) => { p.hidden = j !== i; });
      refresh();
    }

    questions.forEach((q, i) => {
      const st = state[i];
      const content = el("div", { class: "ask-pane-content", hidden: "" });
      if (q.header && !multi) content.appendChild(el("div", { class: "ask-tag", text: q.header }));
      content.appendChild(el("div", { class: "ask-q", text: q.question || "" }));
      const opts = el("div", { class: "ask-opts " + (q.multiSelect ? "multi" : "single") });
      const otherInput = el("input", { class: "ask-other-input", type: "text", placeholder: "Type your answer…", hidden: "" });
      const optBtns = [];
      const mark = () => el("span", { class: "ask-mark" }); // radio circle / checkbox square (via CSS)

      for (const raw of q.options || []) {
        const o = norm(raw);
        const btn = el("button", { class: "ask-opt", type: "button", title: o.description || "" }, mark(), el("span", { class: "ask-opt-label", text: o.label }));
        btn.addEventListener("click", () => {
          if (q.multiSelect) {
            btn.classList.toggle("on");
            btn.classList.contains("on") ? st.picks.add(o.label) : st.picks.delete(o.label);
          } else {
            optBtns.forEach((b) => b.classList.remove("on"));
            otherBtn.classList.remove("on"); st.other = false; otherInput.setAttribute("hidden", "");
            btn.classList.add("on");
            st.picks.clear(); st.picks.add(o.label);
          }
          refresh();
          if (!multi && !q.multiSelect) submit(); // lone single-select → submit at once
        });
        optBtns.push(btn);
        opts.appendChild(btn);
      }

      // "Other" → reveal a free-text box for this question
      const otherBtn = el("button", { class: "ask-opt ask-other", type: "button" }, mark(), icon("pencil"), el("span", { class: "ask-opt-label", text: "Other" }));
      otherBtn.addEventListener("click", () => {
        if (q.multiSelect) {
          otherBtn.classList.toggle("on");
          st.other = otherBtn.classList.contains("on");
        } else {
          optBtns.forEach((b) => b.classList.remove("on"));
          st.picks.clear();
          otherBtn.classList.add("on");
          st.other = true;
        }
        if (st.other) { otherInput.removeAttribute("hidden"); otherInput.focus(); } else { otherInput.setAttribute("hidden", ""); }
        refresh();
      });
      otherInput.addEventListener("input", () => { st.otherText = otherInput.value; refresh(); });
      opts.appendChild(otherBtn);
      content.appendChild(opts);
      content.appendChild(otherInput);
      panes.push(content);
      pane.appendChild(content);

      if (multi) {
        const tab = el("button", { class: "ask-tab", type: "button", text: q.header || `Q${i + 1}` });
        tab.addEventListener("click", () => showTab(i));
        tabs.push(tab);
        tabsBar.appendChild(tab);
      }
    });

    if (multi) card.appendChild(tabsBar);
    card.appendChild(pane);
    card.appendChild(sendBtn);

    let done = false;
    function submit() {
      if (done) return;
      if (!state.every((_, i) => answered(i))) return; // every question needs an answer
      done = true;
      const lines = questions.map((q, i) => {
        const parts = [...state[i].picks];
        if (state[i].other && state[i].otherText.trim()) parts.push(state[i].otherText.trim());
        return `${q.header || q.question || `Q${i + 1}`}: ${parts.join(", ")}`;
      });
      onAnswerQuestion(req.id, lines.join("\n"));
      asks.delete(req.id);
      card.remove(); sync();
      const rec = el("div", { class: "msg ask decided allowed" }, el("div", { class: "perm-head" }, icon("check"), el("span", { text: "Answered" })));
      for (const line of lines) rec.appendChild(el("div", { class: "ask-q", text: line }));
      archive(rec);
    }

    showTab(0);
    asks.set(req.id, card);
    root.appendChild(card); show();
  }

  return { el: root, addPermission, addQuestion, remove, clear };
}
