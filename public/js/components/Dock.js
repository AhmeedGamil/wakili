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
import { createQuestionCard } from "./QuestionCard.js";

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
  // "Allow always" never reaches here — the gateway approves those at the
  // permission hook (no card is published), so it works with the app asleep.
  function addPermission(req) {
    if (perms.some((r) => r.id === req.id)) return; // stream replay of a card already up
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
    const note = decision === "allow_session" ? " — allowed (session)"
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
      el("button", { class: "btn allow-session", type: "button", onClick: () => decide(req, "allow_session") }, "Session"),
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
  // The card itself lives in QuestionCard.js; this wires its one submit into
  // the gateway answer + an "Answered" record for the scrolling history.
  function addQuestion(req) {
    if (asks.has(req.id)) return; // stream replay of a card already up
    const card = createQuestionCard(req, (lines) => {
      onAnswerQuestion(req.id, lines.join("\n"));
      asks.delete(req.id);
      card.remove(); sync();
      const rec = el("div", { class: "msg ask decided allowed" }, el("div", { class: "perm-head" }, icon("check"), el("span", { text: "Answered" })));
      for (const line of lines) rec.appendChild(el("div", { class: "ask-q", text: line }));
      archive(rec);
    });
    asks.set(req.id, card);
    root.appendChild(card); show();
  }

  return { el: root, addPermission, addQuestion, remove, clear };
}
