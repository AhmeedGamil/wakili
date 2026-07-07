// Reusable message list. Renders stored history and the live assistant turn as
// an ordered sequence of segments (thoughts / text / tool / permission / file)
// appended in arrival order, so nothing ever jumps above a tool or a permission
// card. Text streams with the smooth typewriter. A neutral "working" pulse shows
// while the model is busy; the "Thinking…" label appears only when real
// reasoning actually streams in.

import { el } from "./dom.js";
import { createTypewriter } from "../core/typewriter.js";
import { renderMarkdown } from "../core/markdown.js";
import { renderTool, attachOutput } from "./toolCard.js";
import { icon } from "./icons.js";
import { openImage, downloadFile } from "./media.js";

const MD_KEY = "ra-markdown"; // "1" = format markdown, "0" = raw text

export function createMessageList() {
  const root = el("div", { id: "messages", class: "messages" });

  // Whether message text is rendered as formatted Markdown or plain text. The
  // toggle lives in the sidebar; this is the shared source of truth, remembered
  // in localStorage (default on).
  let mdOn = localStorage.getItem(MD_KEY) !== "0";

  // Write model/user text into a node, remembering the raw source on `__raw` so
  // flipping the toggle can re-render every existing message in place.
  function writeText(node, text) {
    node.__raw = text == null ? "" : text;
    if (mdOn) node.innerHTML = renderMarkdown(node.__raw);
    else node.textContent = node.__raw;
    return node;
  }

  // Stick-to-bottom: only auto-scroll while the user is already parked near the
  // bottom. If they scroll up to read earlier content, streaming leaves them be;
  // scrolling back down within the threshold re-enables sticking.
  const STICK_THRESHOLD = 80; // px from the bottom that still counts as "at bottom"
  let stick = true;
  root.addEventListener("scroll", () => {
    const distance = root.scrollHeight - root.scrollTop - root.clientHeight;
    stick = distance <= STICK_THRESHOLD;
  });
  const scroll = () => { if (stick) root.scrollTop = root.scrollHeight; };
  // Note: we deliberately do NOT re-pin to the bottom on pane resize. Tapping the
  // composer opens the mobile keyboard, which shrinks the viewport (interactive-
  // widget=resizes-content); a resize-triggered scroll made the whole chat jump
  // upward on focus. Leaving scrollTop where it is keeps the content anchored.
  // New messages and streaming still stick to the bottom via scroll() directly.
  const tw = createTypewriter(scroll);
  // Streaming text paints through the same writeText path, so live deltas are
  // formatted (or not) consistently with the stored history and re-render on toggle.
  tw.setRenderer((node, full) => writeText(node, full));

  let workingEl = null;   // neutral processing pulse — ONE element per turn, kept
                          // as the last child and shown/hidden via the "off" class
                          // (visibility toggle) so its space is reserved and the
                          // chat never shifts when it blinks in and out mid-turn.
  let textEl = null;      // current open answer block
  let thinkEl = null;     // current open thoughts body
  let thinkSummary = null;
  let thinkLabel = null;  // the text span inside the thoughts summary (icon stays)
  // Live tool cards still waiting for their output, in arrival order. A tool_result
  // is matched to its card by tool id when known, else FIFO (tools in `-p` run
  // sequentially, so the oldest unfilled card is the right one).
  let awaiting = [];

  // Every insertion into the list goes through here so the persistent working
  // pulse stays pinned below whatever just arrived (appendChild MOVES it).
  function append(node) {
    root.appendChild(node);
    if (workingEl) root.appendChild(workingEl);
  }

  function reset() { tw.reset(); removeWorking(); textEl = thinkEl = thinkSummary = thinkLabel = null; awaiting = []; }

  // Register a freshly-rendered tool card so a later tool_result can fill it.
  function registerToolCard(node, id) {
    const card = node && node.querySelector ? node.querySelector(".tool-card") : null;
    if (card) awaiting.push({ card, id });
  }

  // One sent attachment as its own right-aligned card: images show just the
  // picture (tap to open full size); other files show an icon + the name.
  function attachmentCard(a) {
    const isImg = a.image || /\.(png|jpe?g|gif|webp|svg|bmp|heic)$/i.test(a.name || "");
    const src = a.url || "";
    if (isImg && src) {
      return el("div", { class: "msg user" },
        el("a", { class: "att-msg img", onClick: () => openImage(src, a.name) }, el("img", { src, alt: a.name || "image" })));
    }
    const card = el("div", { class: "att-msg doc" }, icon("paperclip"), el("span", { class: "att-msg-name", text: a.name || "file" }));
    return el("div", { class: "msg user" }, src ? el("a", { class: "att-msg-link", onClick: () => downloadFile(src, a.name) }, card) : card);
  }

  // Accepts a plain string (text only) or { text, attachments }. Attachments
  // each render as an independent card, then the text as its own bubble.
  function userMessage(m) {
    const { text, attachments } = typeof m === "string" ? { text: m, attachments: [] } : { text: m.text || "", attachments: m.attachments || [] };
    for (const a of attachments) append(attachmentCard(a));
    if (text) append(el("div", { class: "msg user" }, writeText(el("div", { class: "bubble" }), text)));
    stick = true; // sending a message always jumps to the bottom
    scroll();
  }
  function assistantMessage(text) {
    append(el("div", { class: "msg assistant" }, writeText(el("div", { class: "text" }), text)));
    scroll();
  }
  // An in-flight (or failed) send from the outbox: the message visuals plus a
  // status row — "Sending…" while in flight, Retry/Discard once failed, removed
  // when the server accepts it. Returns a handle the controller drives.
  function addOutbox({ text, attachments, status, onRetry, onDiscard }) {
    const nodes = [];
    const add = (n) => { nodes.push(n); append(n); };
    for (const a of attachments || []) add(attachmentCard(a));
    if (text) add(el("div", { class: "msg user" }, writeText(el("div", { class: "bubble" }), text)));
    const label = el("span", { class: "ob-label" });
    const retryBtn = el("button", { class: "ob-btn", type: "button", onClick: () => onRetry && onRetry() }, "Retry");
    const discardBtn = el("button", { class: "ob-btn", type: "button", onClick: () => onDiscard && onDiscard() }, "Discard");
    add(el("div", { class: "msg user outbox" }, el("div", { class: "ob-row" }, label, retryBtn, discardBtn)));
    const row = nodes[nodes.length - 1];
    // "Sending…" only matters while an upload is in flight — a text-only message
    // just appears. A failure surfaces "Not sent" + Retry/Discard and drops the
    // optimistic pulse below (no turn will start).
    const hasFiles = (attachments || []).length > 0;
    const update = (st) => {
      if (st === "sent") { row.remove(); return; }
      const failed = st === "failed";
      if (failed) removeWorking(); // no turn will start — drop it, don't hold space
      row.hidden = !failed && !hasFiles;
      label.textContent = failed ? "Not sent" : "Sending…";
      row.classList.toggle("failed", failed);
      retryBtn.hidden = discardBtn.hidden = !failed;
    };
    update(status || "sending");
    // Optimistic "working" pulse: show it the instant the message is on screen,
    // so the busy indicator appears together with your message instead of lagging
    // until the server's turn_start (the agent spawn adds a beat). The real
    // turn_start re-calls showWorking (idempotent) and streaming clears it; a
    // failed send clears it via update() above.
    if ((status || "sending") !== "failed") showWorking();
    stick = true;
    scroll();
    return { update, remove: () => nodes.forEach((n) => n.remove()) };
  }

  // static renderers for stored history parts
  function staticTool(name, input, output, isError) {
    const node = el("div", { class: "msg assistant" }, renderTool(name, input, { output, isError }));
    append(node);
    return node;
  }
  function staticThink(text) {
    append(el("details", { class: "thoughts" }, el("summary", {}, icon("bulb"), el("span", { text: "Thoughts" })), writeText(el("div", { class: "think-body" }), text)));
  }
  // History replay of an agent-sent file. When the stored part carries a url
  // (new sends do), render it just like the live addFile: a thumbnail for images,
  // a download link otherwise. Older parts without a url fall back to a name card.
  function staticFile(p) {
    // Direct append (no `segment`, which would show the live "working" pulse).
    if (p.url) { append(fileCard({ name: p.name || "file", url: p.url, caption: p.caption })); return; }
    append(el("div", { class: "msg assistant" }, el("div", { class: "tool" }, icon("paperclip"), el("span", { text: "sent: " + (p.name || "file") }))));
  }
  // `live` marks an in-progress turn (snapshot replay): tool cards without output
  // yet are registered so a pending result can still attach when it arrives.
  function renderParts(parts, live = false) {
    for (const p of parts) {
      if (p.type === "text") assistantMessage(p.text);
      else if (p.type === "tool") {
        const node = staticTool(p.name, p.input, p.output, p.isError);
        if (live && p.output == null) registerToolCard(node, p.id);
      }
      else if (p.type === "thinking") staticThink(p.text);
      else if (p.type === "file") staticFile(p);
    }
  }

  function renderHistory(messages) {
    root.innerHTML = "";
    reset();
    for (const m of messages) {
      if (m.role === "user") userMessage({ text: m.text, attachments: m.attachments || [] });
      else if (m.parts) renderParts(m.parts);
      else if (m.text) assistantMessage(m.text);
    }
  }

  // Restore the in-progress turn (sent on (re)entering a working session): append
  // what streamed so far after the history, then resume the "working" pulse so the
  // next live delta continues below it.
  function renderSnapshot(parts, busy) {
    renderParts(parts || [], true);
    if (busy) showWorking();
    scroll();
  }

  // show/hide toggle the "off" class on ONE persistent element (space stays
  // reserved → no layout shift); remove is for turn-terminal moments only.
  function showWorking() {
    if (!workingEl) {
      workingEl = el("div", { class: "msg assistant working" },
        el("div", { class: "ti-dots", html: "<span></span><span></span><span></span>" }));
      root.appendChild(workingEl);
    }
    workingEl.classList.remove("off");
    scroll();
  }
  function hideWorking() { if (workingEl) workingEl.classList.add("off"); }
  function removeWorking() { if (workingEl) { workingEl.remove(); workingEl = null; } }

  // close the open text/think blocks so the next segment appends below them
  function closeSegments() {
    tw.flush();
    if (thinkSummary) { if (thinkLabel) thinkLabel.textContent = "Thoughts"; thinkSummary = null; thinkLabel = null; }
    textEl = null;
    thinkEl = null;
  }

  // a non-text segment (tool/permission/file) just arrived: end current blocks,
  // drop it in order above the pulse, and make sure the pulse is visible again
  // (the model keeps working)
  function segment(node) {
    closeSegments();
    append(node);
    showWorking();
    scroll();
  }

  function startAssistant() { closeSegments(); showWorking(); }

  function feedText(t) {
    hideWorking();
    if (thinkLabel) thinkLabel.textContent = "Thoughts"; // reasoning done, answering
    if (!textEl) {
      textEl = el("div", { class: "text" });
      append(el("div", { class: "msg assistant" }, textEl));
    }
    tw.feed(textEl, t);
  }

  function feedThink(t) {
    hideWorking(); // the streaming thoughts block is itself the indicator
    if (!thinkEl) {
      thinkLabel = el("span", { text: "Thinking…" });
      thinkSummary = el("summary", {}, icon("bulb"), thinkLabel);
      const body = el("div", { class: "think-body" });
      append(el("details", { class: "thoughts", open: "" }, thinkSummary, body));
      thinkEl = body;
    }
    tw.feed(thinkEl, t);
  }

  function addTool(tool) {
    const node = el("div", { class: "msg assistant" }, renderTool(tool.name, tool.input));
    registerToolCard(node, tool.id);
    segment(node);
  }

  // A tool's output arrived: attach it to the matching live card. Prefer an id
  // match; fall back to the oldest card still missing output.
  function addToolResult({ id, output, isError }) {
    let i = id != null ? awaiting.findIndex((a) => a.id === id) : -1;
    if (i === -1) i = 0; // FIFO fallback (sequential execution → oldest is the one)
    const entry = awaiting[i];
    if (!entry) return;
    awaiting.splice(i, 1);
    attachOutput(entry.card, output, isError);
    scroll();
  }

  // Append an already-built node (e.g. a resolved permission/question card the
  // dock hands back) into the scrolling history, keeping the working pulse below.
  // If it carries a tool card (an approved/denied Bash/Edit), register it so its
  // output can still attach.
  function addRecord(node) { registerToolCard(node); segment(node); }

  // Build an agent-sent file node: a thumbnail for images (tap to open full
  // size), a download link otherwise. Shared by the live path (addFile) and
  // history replay (staticFile) so both render identically.
  function fileCard(file) {
    const isImg = /\.(png|jpe?g|gif|webp|svg)$/i.test(file.name);
    const card = el("div", { class: "file-card" });
    if (isImg) {
      card.appendChild(el("a", { class: "file-img-link", onClick: () => openImage(file.url, file.name) }, el("img", { class: "file-img", src: file.url, alt: file.name })));
    } else {
      card.appendChild(el("a", { class: "file-link", onClick: () => downloadFile(file.url, file.name) }, icon("download"), el("span", { class: "file-name", text: file.name })));
    }
    if (file.caption) card.appendChild(el("div", { class: "file-cap", text: file.caption }));
    return el("div", { class: "msg assistant" }, card);
  }
  function addFile(file) { segment(fileCard(file)); }

  // The turn ended early: drop the pulse and leave a small marker. "Interrupted"
  // when a queued message is about to go out; plain "Stopped" otherwise.
  function addStopped(interrupted) {
    closeSegments();
    removeWorking(); // turn is over — release the reserved space
    const label = interrupted ? "Interrupted" : "Stopped";
    append(el("div", { class: "msg assistant" }, el("div", { class: "stopped-note" }, icon("square"), el("span", { text: label }))));
    scroll();
  }

  function endTurn() { tw.flush(); reset(); } // reset removes the pulse

  // Flip Markdown formatting on/off and re-render every message already on screen
  // from its stored raw source (`__raw`), including any mid-stream text.
  function setMarkdown(on) {
    mdOn = !!on;
    localStorage.setItem(MD_KEY, mdOn ? "1" : "0");
    root.querySelectorAll(".bubble, .text, .think-body").forEach((n) => {
      if (n.__raw != null) writeText(n, n.__raw);
    });
    scroll();
  }

  // Output of a "!cmd" direct shell command (no agent) — a terminal-style block.
  function addExec({ output, ok }) {
    closeSegments(); removeWorking(); // standalone shell output, no turn running
    append(el("div", { class: "msg assistant" },
      el("div", { class: "tool-card open" },
        el("div", { class: "tool-head" }, icon("terminal", "tool-ico"), el("span", { class: "tool-title", text: "Output" })),
        el("div", { class: "tool-body diff" }, el("pre", { class: "diff-out" + (ok ? "" : " err"), text: output || "(no output)" })))));
    scroll();
  }

  return { el: root, userMessage, addOutbox, renderHistory, renderSnapshot, startAssistant, feedText, feedThink, addTool, addToolResult, addRecord, endTurn, addStopped, addFile, addExec, setMarkdown };
}
