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

export function createDock({ onPermission, onAnswerQuestion, onArchive }) {
  const root = el("div", { id: "dock", class: "dock", hidden: "" });
  const perms = [];     // pending permission requests, FIFO
  let permBlock = null; // the rendered permission area (front card + batch bar)

  const sync = () => { if (!root.children.length) root.setAttribute("hidden", ""); };
  const show = () => root.removeAttribute("hidden");
  const archive = (node) => onArchive && onArchive(node);
  function clear() { perms.length = 0; permBlock = null; root.innerHTML = ""; sync(); }

  // ---- permissions ----
  function addPermission(req) {
    if (req.autoAllow) { decide(req, "allow"); return; } // "Allow always" → resolve at once, no card
    perms.push(req);
    renderPerms();
  }

  // Resolve one request: tell the gateway, archive a record, drop it from the queue.
  function decide(req, decision) {
    const i = perms.indexOf(req);
    if (i !== -1) perms.splice(i, 1);
    onPermission(req.id, decision, req.tool);
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
    const note = decision === "allow_session" ? " — allowed (session)" : ok ? " — allowed" : " — denied";
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
      el("button", { class: "btn allow-session", type: "button", onClick: () => decide(req, "allow_session") }, "Always"),
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
    const questions = req.questions || [];
    const card = el("div", { class: "card ask" });
    card.appendChild(el("div", { class: "perm-head" }, icon("help"), el("span", { text: "The agent is asking…" })));
    const scroll = el("div", { class: "ask-scroll" }); // only the questions scroll
    card.appendChild(scroll);
    const norm = (o) => (typeof o === "string" ? { label: o } : (o || {}));
    const state = questions.map(() => ({ picks: new Set(), other: false, otherText: "" }));

    questions.forEach((q, i) => {
      const st = state[i];
      if (q.header) scroll.appendChild(el("div", { class: "ask-tag", text: q.header }));
      scroll.appendChild(el("div", { class: "ask-q", text: q.question || "" }));
      const opts = el("div", { class: "ask-opts" });
      const otherInput = el("input", { class: "ask-other-input", type: "text", placeholder: "Type your answer…", hidden: "" });
      const optBtns = [];

      for (const raw of q.options || []) {
        const o = norm(raw);
        const btn = el("button", { class: "ask-opt", type: "button", title: o.description || "" }, o.label);
        btn.addEventListener("click", () => {
          if (q.multiSelect) {
            btn.classList.toggle("on");
            btn.classList.contains("on") ? st.picks.add(o.label) : st.picks.delete(o.label);
          } else {
            optBtns.forEach((b) => b.classList.remove("on"));
            otherBtn.classList.remove("on"); st.other = false; otherInput.setAttribute("hidden", "");
            btn.classList.add("on");
            st.picks.clear(); st.picks.add(o.label);
            if (questions.length === 1) submit(); // single single-select → submit at once
          }
        });
        optBtns.push(btn);
        opts.appendChild(btn);
      }

      // "Other" → reveal a free-text box for this question
      const otherBtn = el("button", { class: "ask-opt ask-other", type: "button" }, icon("pencil"), el("span", { text: "Other" }));
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
      });
      otherInput.addEventListener("input", () => { st.otherText = otherInput.value; });
      opts.appendChild(otherBtn);
      scroll.appendChild(opts);
      scroll.appendChild(otherInput);
    });

    const sendBtn = el("button", { class: "btn allow ask-send", type: "button", onClick: () => submit() }, "Send answers");
    card.appendChild(sendBtn);

    const answered = (i) => state[i].picks.size > 0 || (state[i].other && state[i].otherText.trim());
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
      card.remove(); sync();
      const rec = el("div", { class: "msg ask decided allowed" }, el("div", { class: "perm-head" }, icon("check"), el("span", { text: "Answered" })));
      for (const line of lines) rec.appendChild(el("div", { class: "ask-q", text: line }));
      archive(rec);
    }

    root.appendChild(card); show();
  }

  return { el: root, addPermission, addQuestion, clear };
}
