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
  const tw = createTypewriter(scroll);
  // Streaming text paints through the same writeText path, so live deltas are
  // formatted (or not) consistently with the stored history and re-render on toggle.
  tw.setRenderer((node, full) => writeText(node, full));

  let workingEl = null;   // neutral processing pulse
  let textEl = null;      // current open answer block
  let thinkEl = null;     // current open thoughts body
  let thinkSummary = null;
  let thinkLabel = null;  // the text span inside the thoughts summary (icon stays)
  // Live tool cards still waiting for their output, in arrival order. A tool_result
  // is matched to its card by tool id when known, else FIFO (tools in `-p` run
  // sequentially, so the oldest unfilled card is the right one).
  let awaiting = [];

  function reset() { tw.reset(); workingEl = textEl = thinkEl = thinkSummary = thinkLabel = null; awaiting = []; }

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
        el("a", { class: "att-msg img", href: src, target: "_blank" }, el("img", { src, alt: a.name || "image" })));
    }
    const card = el("div", { class: "att-msg doc" }, icon("paperclip"), el("span", { class: "att-msg-name", text: a.name || "file" }));
    return el("div", { class: "msg user" }, src ? el("a", { class: "att-msg-link", href: src, target: "_blank", download: a.name || "" }, card) : card);
  }

  // Accepts a plain string (text only) or { text, attachments }. Attachments
  // each render as an independent card, then the text as its own bubble.
  function userMessage(m) {
    const { text, attachments } = typeof m === "string" ? { text: m, attachments: [] } : { text: m.text || "", attachments: m.attachments || [] };
    for (const a of attachments) root.appendChild(attachmentCard(a));
    if (text) root.appendChild(el("div", { class: "msg user" }, writeText(el("div", { class: "bubble" }), text)));
    stick = true; // sending a message always jumps to the bottom
    scroll();
  }
  function assistantMessage(text) {
    root.appendChild(el("div", { class: "msg assistant" }, writeText(el("div", { class: "text" }), text)));
    scroll();
  }
  // An in-flight (or failed) send from the outbox: the message visuals plus a
  // status row — "Sending…" while in flight, Retry/Discard once failed, removed
  // when the server accepts it. Returns a handle the controller drives.
  function addOutbox({ text, attachments, status, onRetry, onDiscard }) {
    const nodes = [];
    const add = (n) => { nodes.push(n); root.appendChild(n); };
    for (const a of attachments || []) add(attachmentCard(a));
    if (text) add(el("div", { class: "msg user" }, writeText(el("div", { class: "bubble" }), text)));
    const label = el("span", { class: "ob-label" });
    const retryBtn = el("button", { class: "ob-btn", type: "button", onClick: () => onRetry && onRetry() }, "Retry");
    const discardBtn = el("button", { class: "ob-btn", type: "button", onClick: () => onDiscard && onDiscard() }, "Discard");
    add(el("div", { class: "msg user outbox" }, el("div", { class: "ob-row" }, label, retryBtn, discardBtn)));
    const row = nodes[nodes.length - 1];
    const update = (st) => {
      if (st === "sent") { row.remove(); return; }
      const failed = st === "failed";
      label.textContent = failed ? "Not sent" : "Sending…";
      row.classList.toggle("failed", failed);
      retryBtn.hidden = discardBtn.hidden = !failed;
    };
    update(status || "sending");
    stick = true;
    scroll();
    return { update, remove: () => nodes.forEach((n) => n.remove()) };
  }

  // static renderers for stored history parts
  function staticTool(name, input, output, isError) {
    const node = el("div", { class: "msg assistant" }, renderTool(name, input, { output, isError }));
    root.appendChild(node);
    return node;
  }
  function staticThink(text) {
    root.appendChild(el("details", { class: "thoughts" }, el("summary", {}, icon("bulb"), el("span", { text: "Thoughts" })), writeText(el("div", { class: "think-body" }), text)));
  }
  function staticFile(p) {
    root.appendChild(el("div", { class: "msg assistant" }, el("div", { class: "tool" }, icon("paperclip"), el("span", { text: "sent: " + (p.name || "file") }))));
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

  function showWorking() {
    if (workingEl) return;
    workingEl = el("div", { class: "msg assistant" },
      el("div", { class: "ti-dots", html: "<span></span><span></span><span></span>" }));
    root.appendChild(workingEl);
    scroll();
  }
  function hideWorking() { if (workingEl) { workingEl.remove(); workingEl = null; } }

  // close the open text/think blocks so the next segment appends below them
  function closeSegments() {
    tw.flush();
    if (thinkSummary) { if (thinkLabel) thinkLabel.textContent = "Thoughts"; thinkSummary = null; thinkLabel = null; }
    textEl = null;
    thinkEl = null;
  }

  // a non-text segment (tool/permission/file) just arrived: end current blocks,
  // drop it in order, then show the pulse again (the model keeps working)
  function segment(node) {
    closeSegments();
    hideWorking();
    root.appendChild(node);
    showWorking();
    scroll();
  }

  function startAssistant() { closeSegments(); showWorking(); }

  function feedText(t) {
    hideWorking();
    if (thinkLabel) thinkLabel.textContent = "Thoughts"; // reasoning done, answering
    if (!textEl) {
      textEl = el("div", { class: "text" });
      root.appendChild(el("div", { class: "msg assistant" }, textEl));
    }
    tw.feed(textEl, t);
  }

  function feedThink(t) {
    hideWorking(); // the streaming thoughts block is itself the indicator
    if (!thinkEl) {
      thinkLabel = el("span", { text: "Thinking…" });
      thinkSummary = el("summary", {}, icon("bulb"), thinkLabel);
      const body = el("div", { class: "think-body" });
      root.appendChild(el("details", { class: "thoughts", open: "" }, thinkSummary, body));
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

  function addFile(file) {
    const isImg = /\.(png|jpe?g|gif|webp|svg)$/i.test(file.name);
    const card = el("div", { class: "file-card" });
    if (isImg) {
      // Images show as just the thumbnail (click to open full size) — no filename,
      // since a long name overflows the image card.
      card.appendChild(el("a", { href: file.url, target: "_blank" }, el("img", { class: "file-img", src: file.url, alt: file.name })));
    } else {
      // Non-image files keep a download link; the name truncates with an ellipsis.
      card.appendChild(el("a", { class: "file-link", href: file.url, target: "_blank", download: file.name }, icon("download"), el("span", { class: "file-name", text: file.name })));
    }
    if (file.caption) card.appendChild(el("div", { class: "file-cap", text: file.caption }));
    segment(el("div", { class: "msg assistant" }, card));
  }

  // The turn ended early: drop the pulse and leave a small marker. "Interrupted"
  // when a queued message is about to go out; plain "Stopped" otherwise.
  function addStopped(interrupted) {
    closeSegments();
    hideWorking();
    const label = interrupted ? "Interrupted" : "Stopped";
    root.appendChild(el("div", { class: "msg assistant" }, el("div", { class: "stopped-note" }, icon("square"), el("span", { text: label }))));
    scroll();
  }

  function endTurn() { tw.flush(); hideWorking(); reset(); }

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
    closeSegments(); hideWorking();
    root.appendChild(el("div", { class: "msg assistant" },
      el("div", { class: "tool-card open" },
        el("div", { class: "tool-head" }, icon("terminal", "tool-ico"), el("span", { class: "tool-title", text: "Output" })),
        el("div", { class: "tool-body diff" }, el("pre", { class: "diff-out" + (ok ? "" : " err"), text: output || "(no output)" })))));
    scroll();
  }

  return { el: root, userMessage, addOutbox, renderHistory, renderSnapshot, startAssistant, feedText, feedThink, addTool, addToolResult, addRecord, endTurn, addStopped, addFile, addExec, setMarkdown };
}
