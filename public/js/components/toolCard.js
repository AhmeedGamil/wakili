// Shared renderers for tool activity. An edit/bash tool becomes a collapsible
// card whose body shows the actual change (red/green diff, or the command);
// read-only tools fall back to a one-line chip. Used by both the message list
// (live + history) and the dock (permission cards show the change inline).

import { el } from "./dom.js";
import { icon } from "./icons.js";

export const EDIT_TOOLS = new Set(["Edit", "Write", "MultiEdit", "NotebookEdit"]);
// Only these have something worth expanding into a diff/command view.
export const isExpandable = (name) => EDIT_TOOLS.has(name) || name === "Bash";

// The path a tool acts on, if any. Only on an object input — a string/truncated
// input (e.g. malformed) yields "", so the header safely falls back to name-only.
const fileOf = (input) => (input && typeof input === "object" && (input.file_path || input.notebook_path || input.path)) || "";

// A "(lines a-b)" suffix for a partial Read, so the header shows the slice read.
function lineRange(name, input) {
  if (name !== "Read" || !input || typeof input !== "object") return "";
  const o = input.offset, l = input.limit;
  if (o != null && l != null) return ` (lines ${o}-${o + l})`;
  if (o != null) return ` (from line ${o})`;
  if (l != null) return ` (first ${l} lines)`;
  return "";
}

// The expanded body: a removed/added diff for edits, the command for Bash,
// pretty-printed JSON for anything else.
export function diffBody(name, input) {
  const wrap = el("div", { class: "diff" });
  const pre = (cls, text) => wrap.appendChild(el("pre", { class: "diff-" + cls, text: text == null ? "" : String(text) }));
  if (name === "Write") pre("add", input.content);
  else if (name === "Edit") { if (input.old_string) pre("del", input.old_string); if (input.new_string) pre("add", input.new_string); }
  else if (name === "MultiEdit") { for (const e of input.edits || []) { if (e.old_string) pre("del", e.old_string); if (e.new_string) pre("add", e.new_string); } }
  else if (name === "NotebookEdit") pre("add", input.new_source);
  else if (name === "Bash") pre("cmd", input.command); // description shows in the header
  else pre("cmd", typeof input === "object" ? JSON.stringify(input, null, 2) : input);
  return wrap;
}

// Header: a per-tool icon + the tool and the full path it touches (+ line range
// for Read). Bash has no path, so it shows its description instead (or just
// "Bash" when there's none). The title wraps so a long path shows in full.
function headIcon(name) {
  if (name === "Bash") return "terminal";
  if (EDIT_TOOLS.has(name)) return "pencil";
  if (name === "Read") return "file-text";
  if (name === "Grep") return "search";
  if (name === "Glob") return "folder";
  if (name === "Task" || name === "Agent") return "bot";
  return "wrench";
}
function headLabel(name, input) {
  const obj = input && typeof input === "object" ? input : null;
  if (name === "Bash") {
    const d = obj ? obj.description : "";
    return d ? "Bash · " + d : "Bash";
  }
  // Grep/Glob are about the pattern, not a single file — surface it in the header.
  if (name === "Grep" && obj && obj.pattern) return 'Grep "' + obj.pattern + '"' + (obj.path ? " (in " + obj.path + ")" : "");
  if (name === "Glob" && obj && obj.pattern) return 'Glob pattern: "' + obj.pattern + '"';
  const f = fileOf(input);
  return name + (f ? " · " + f + lineRange(name, input) : "");
}

// A short "what happened" summary shown at the right of the header. Edit/Write
// come straight from the input and are known immediately; Read/Grep/Glob need
// the tool's output, which on the live path arrives later (see attachOutput).
function lineCount(s) { return s == null || s === "" ? 0 : String(s).split("\n").length; }

// An edit removes one block and inserts another; report the NET line change.
// Equal in/out reads as "Modified"; otherwise it grew or shrank by |added-removed|.
function editDelta(added, removed) {
  const d = added - removed;
  if (d === 0) return "Modified";
  const n = Math.abs(d);
  return (d > 0 ? "Added " : "Removed ") + n + (n === 1 ? " line" : " lines");
}

function inputBadge(name, input) {
  if (!input || typeof input !== "object") return "";
  if (name === "Write") return lineCount(input.content) + " lines";
  if (name === "NotebookEdit") return lineCount(input.new_source) + " lines";
  if (name === "Edit") return editDelta(lineCount(input.new_string), lineCount(input.old_string));
  if (name === "MultiEdit") {
    const edits = input.edits || [];
    if (!edits.length) return "";
    let added = 0, removed = 0;
    for (const e of edits) { added += lineCount(e.new_string); removed += lineCount(e.old_string); }
    return editDelta(added, removed);
  }
  return "";
}

function outputBadge(name, output) {
  if (output == null || output === "") return "";
  if (name === "Read") return lineCount(output) + " lines";
  if (name === "Grep") return lineCount(output) + " lines of output";
  if (name === "Glob") return String(output).split("\n").filter((l) => l.trim()).length + " matches";
  return "";
}

function badgeText(name, input, output) {
  return inputBadge(name, input) || outputBadge(name, output);
}

// The captured output of a tool (Bash stdout, Read contents, …), shown below the
// command/diff inside the same card. Errors get a red tint.
function outputPre(output, isError) {
  const text = output == null || output === "" ? "(no output)" : String(output);
  return el("pre", { class: "diff-out" + (isError ? " err" : ""), text });
}

// A clickable, collapsible card; click the header to reveal the change/output.
// `output` (if known up-front, e.g. from history) is appended under the input.
export function toolCard(name, input, { open = false, output = null, isError = false } = {}) {
  const body = diffBody(name, input);
  body.classList.add("tool-body");
  if (output != null) body.appendChild(outputPre(output, isError));
  if (!open) body.setAttribute("hidden", "");
  const head = el("div", { class: "tool-head" },
    icon("chevron-right", "tool-caret"),
    icon(headIcon(name), "tool-ico"),
    el("span", { class: "tool-title", text: headLabel(name, input) }));
  // A "what happened" summary sits under the card, aligned to the start. Empty
  // badges are hidden via CSS. data-tool lets attachOutput fill an output-based
  // badge later (Read/Grep/Glob), whose result isn't known at render time.
  const badge = el("div", { class: "tool-badge", text: badgeText(name, input, output) });
  const card = el("div", { class: "tool-card" + (open ? " open" : ""), "data-tool": name }, head, body);
  head.addEventListener("click", () => {
    const hidden = body.hasAttribute("hidden");
    if (hidden) body.removeAttribute("hidden"); else body.setAttribute("hidden", "");
    card.classList.toggle("open", hidden);
  });
  // The "what happened" summary sits BELOW the card, outside its border.
  return el("div", { class: "tool-wrap" }, card, badge);
}

// Append a tool's output to an already-rendered card (the live path) and reveal
// it, so the result shows up the moment the command finishes. No-op if the node
// isn't a tool card or already has output.
export function attachOutput(cardEl, output, isError) {
  if (!cardEl || !cardEl.classList || !cardEl.classList.contains("tool-card")) return false;
  const body = cardEl.querySelector(".tool-body");
  if (!body || body.querySelector(".diff-out")) return false;
  body.appendChild(outputPre(output, isError));
  body.removeAttribute("hidden");
  cardEl.classList.add("open");
  // Fill the header badge from the output when it wasn't known from the input
  // (Read/Grep/Glob). Edit/Write already set theirs, so leave a filled badge be.
  // The badge now lives outside the card (as a sibling in .tool-wrap).
  const badge = cardEl.parentElement ? cardEl.parentElement.querySelector(".tool-badge") : null;
  if (badge && !badge.textContent) {
    const b = outputBadge(cardEl.getAttribute("data-tool"), output);
    if (b) badge.textContent = b;
  }
  return true;
}

// One-line chip for read-only tools (Read/Grep/…), kept for callers that want a
// compact form; the message list now uses cards so output has somewhere to land.
export function toolChip(name, input) {
  const s = (input && typeof input === "object") ? JSON.stringify(input) : String(input == null ? "" : input);
  return el("div", { class: "tool" }, icon("wrench"), el("span", { text: name + "  " + s.slice(0, 120) }));
}

// Pick the right node for a tool. Always a card now (even read-only tools) so the
// tool's output can be shown under it. `input` may be a full object (gated tools,
// history) or a truncated string (live read-only tools); diffBody renders both.
// `opts` may carry { output, isError, open }.
export function renderTool(name, input, opts) {
  return toolCard(name, input, opts);
}
