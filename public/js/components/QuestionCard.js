// The interactive multiple-choice card for agent questions (the ask_options
// MCP tool, or AskUserQuestion). `req` = { id, questions:[{ header, question,
// multiSelect, options:[str | {label,description}] }] }. Every question also
// gets an "Other" choice that reveals a free-text box.
//
// Pure card: builds the DOM and collects answers; `onSubmit(lines)` fires
// exactly once, with one "Header: answer" line per question. The dock owns
// what happens next (sending, archiving a record, removing the card).

import { el } from "./dom.js";
import { icon } from "./icons.js";

export function createQuestionCard(req, onSubmit) {
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

  // Answers are never sent by a tap alone. The button reads "Next" while an
  // unanswered question remains beyond the active one (needed for multiSelect,
  // which can't auto-advance); on the last unanswered question — or a single
  // question — it becomes "Send answers", enabled once everything is answered.
  const sendBtn = el("button", { class: "btn allow ask-send", type: "button", onClick: () => advance() }, "Send answers");

  function refresh() {
    tabs.forEach((t, i) => { t.classList.toggle("on", i === activeIdx); t.classList.toggle("done", !!answered(i)); });
    const all = state.every((_, i) => answered(i));
    if (multi) {
      const rest = state.some((_, i) => i !== activeIdx && !answered(i)); // unanswered beyond the active tab
      sendBtn.textContent = rest ? "Next" : "Send answers";
      sendBtn.disabled = !rest && !all; // "Send answers" waits for the active question too
    } else {
      sendBtn.disabled = !all;
    }
  }
  function advance() {
    if (state.every((_, i) => answered(i))) return submit();
    // jump to the next unanswered question, searching forward from the active tab
    for (let k = 1; k <= questions.length; k++) {
      const j = (activeIdx + k) % questions.length;
      if (!answered(j)) return showTab(j);
    }
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
        // A pick never sends by itself. A mid-list single-select answer steps
        // to the next unanswered question; the last one waits for "Send answers".
        if (multi && !q.multiSelect && !state.every((_, k) => answered(k))) advance();
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
    onSubmit(lines);
  }

  showTab(0);
  return card;
}
