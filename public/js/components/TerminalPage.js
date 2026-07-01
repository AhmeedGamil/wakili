// Terminal page. A full-screen overlay (opened from the composer's + menu) that
// runs raw shell commands on the laptop — no leading "!" needed, unlike the chat
// box. The header shows the current folder, which live-updates as you `cd`. This
// is NOT saved as a chat; only the command history is kept, on the phone
// (localStorage, newest-first). Typing "/" recalls that history in the same menu
// style as the agent's slash-commands.

import { el } from "./dom.js";
import { icon } from "./icons.js";

const HISTORY_KEY = "ra-term-history";
const HISTORY_MAX = 200;

function loadHistory() {
  try { const a = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]"); return Array.isArray(a) ? a : []; }
  catch { return []; }
}
function saveHistory(list) {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, HISTORY_MAX))); } catch { /* ignore */ }
}

// Last path segment, for the compact "<folder> $" prompt (full path is in the bar).
function shortCwd(c) {
  if (!c) return "~";
  const parts = c.split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : c;
}

// Programs that need a real terminal (TTY). This page runs commands with the
// server's child_process.exec, which has no TTY and no keyboard stream — so these
// hang or error out (e.g. bare `claude` waits for stdin, then aborts). We catch
// the common ones and show a hint instead of running them into that wall.
const TUI_PROGRAMS = new Set(["vim", "vi", "nano", "emacs", "top", "htop", "less", "more", "ssh", "telnet", "mysql", "psql", "sqlite3", "ftp", "tmux", "screen"]);
const REPL_PROGRAMS = new Set(["python", "python3", "node", "irb", "php", "ruby"]);

function firstToken(cmd) {
  const t = cmd.trim().split(/\s+/)[0] || "";
  return t.replace(/^.*[\\/]/, "").replace(/\.exe$/i, "").toLowerCase(); // strip path + .exe
}

// A hint string if the command can't run in this non-interactive shell, else null.
// Exceptions keep the useful cases working: piped/redirected stdin, `claude -p`,
// and REPLs invoked with a script file.
function interactiveHint(command) {
  const piped = /[|<]/.test(command);            // stdin is being fed → not interactive
  const prog = firstToken(command);
  const args = command.trim().split(/\s+/).slice(1);
  const hasPrint = args.includes("-p") || args.includes("--print");
  const hasPositional = args.some((a) => !a.startsWith("-")); // a file/script/prompt arg

  if (prog === "claude") {
    if (piped || hasPrint) return null;
    return '"claude" needs a real terminal here. Run it one-shot instead:\n  claude -p "your prompt"';
  }
  if (TUI_PROGRAMS.has(prog)) {
    return `"${prog}" is an interactive program and can't run in this terminal (no TTY). Use it from a real terminal on the laptop.`;
  }
  if (REPL_PROGRAMS.has(prog)) {
    if (piped || hasPositional) return null;     // running a script, or piped input
    return `"${prog}" would open an interactive prompt this terminal can't provide. Run a script (e.g. "${prog} script.js") or pipe input instead.`;
  }
  return null;
}

export function createTerminalPage({ onRun }) {
  let cwd = "";                  // current directory (echoed back by the server)
  let history = loadHistory();   // newest-first list of command strings
  let items = [];                // history entries currently shown in the menu
  let active = 0;                // highlighted index
  let menuOpen = false;
  let running = false;

  const pathEl = el("span", { class: "term-path", text: "" });
  const head = el("div", { class: "fp-head term-head" },
    el("div", { class: "term-head-left" }, icon("terminal"), el("strong", { text: "Terminal" })),
    el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
  );
  const cwdBar = el("div", { class: "term-cwd" }, icon("folder"), pathEl);
  const out = el("div", { class: "term-out" });
  const menu = el("div", { class: "slash-menu term-menu", hidden: "" });
  const input = el("textarea", { class: "term-input", rows: "1",
    placeholder: "run a command — type / for history",
    spellcheck: "false", autocapitalize: "off", autocorrect: "off" });
  const runBtn = el("button", { class: "btn send", type: "submit", "aria-label": "Run" }, icon("arrow-up-right"));
  const bar = el("div", { class: "composer-bar term-bar" }, input, runBtn);
  const form = el("form", { class: "term-form" }, menu, bar);

  const panel = el("div", { class: "ft-panel term-panel" }, head, cwdBar, out, form);
  const overlay = el("div", { class: "fp-overlay term-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  function setCwd(c) { cwd = c || ""; pathEl.textContent = cwd || "(default folder)"; }

  function open(startCwd) {
    setCwd(startCwd);
    overlay.removeAttribute("hidden");
    setTimeout(() => input.focus(), 0);
  }
  function close() { overlay.setAttribute("hidden", ""); closeMenu(); }

  // ---- output (scrollback) ----
  function scrollDown() { out.scrollTop = out.scrollHeight; }
  function printCommand(command) {
    out.appendChild(el("div", { class: "term-line term-cmd" },
      el("span", { class: "term-prompt", text: shortCwd(cwd) + " $" }),
      el("span", { class: "term-cmd-text", text: " " + command }),
    ));
    scrollDown();
  }
  function printOutput(text, ok) {
    const t = (text || "").replace(/\s+$/, "");
    if (!t) return;
    out.appendChild(el("pre", { class: "term-line term-result" + (ok ? "" : " err"), text: t }));
    scrollDown();
  }
  // A guidance note (interactive-command hint) — softer than an error.
  function printHint(text) {
    out.appendChild(el("pre", { class: "term-line term-result term-hint", text }));
    scrollDown();
  }

  // ---- history menu (newest-first, same look as the slash commands) ----
  function remember(command) {
    history = history.filter((h) => h !== command); // dedupe, then push to front
    history.unshift(command);
    if (history.length > HISTORY_MAX) history = history.slice(0, HISTORY_MAX);
    saveHistory(history);
  }
  function closeMenu() { menuOpen = false; menu.setAttribute("hidden", ""); menu.innerHTML = ""; }
  function renderMenu() {
    menu.innerHTML = "";
    items.forEach((c, i) => {
      menu.appendChild(el("button",
        { type: "button", class: "slash-item" + (i === active ? " active" : ""),
          onMousedown: (e) => { e.preventDefault(); pick(c); } },
        el("span", { class: "slash-name", text: c }),
      ));
    });
  }
  function move(dir) {
    if (!items.length) return;
    active = (active + dir + items.length) % items.length;
    renderMenu();
    menu.children[active]?.scrollIntoView({ block: "nearest" });
  }
  function pick(c) {
    if (c == null) return;
    closeMenu();
    input.value = c;   // drop it into the box; user hits Run (or edits first)
    autoSize();
    input.focus();
  }
  function syncMenu() {
    const m = /^\/(\S*)$/.exec(input.value);   // only a bare "/token", no space yet
    if (!m) return closeMenu();
    const q = m[1].toLowerCase();
    items = history.filter((h) => h.toLowerCase().includes(q)); // already newest-first
    if (!items.length) return closeMenu();
    active = 0; menuOpen = true; menu.removeAttribute("hidden"); renderMenu();
  }

  function autoSize() {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 160) + "px";
  }

  async function run() {
    if (running) return;
    const command = input.value.trim();
    if (!command) return;
    closeMenu();
    input.value = "";
    autoSize();
    remember(command);
    printCommand(command);
    // Interactive programs can't run in a non-TTY shell — hint instead of hanging.
    const hint = interactiveHint(command);
    if (hint) { printHint(hint); input.focus(); return; }
    running = true; runBtn.disabled = true;
    let r = null;
    try { r = await onRun(command, cwd); } catch { r = null; }
    running = false; runBtn.disabled = false;
    if (r && typeof r.cwd === "string") setCwd(r.cwd);
    printOutput(r ? r.output : "(failed to run)", !!(r && r.ok));
    input.focus();
  }

  runBtn.addEventListener("mousedown", (e) => e.preventDefault());
  form.addEventListener("submit", (e) => { e.preventDefault(); run(); });
  input.addEventListener("input", () => { autoSize(); syncMenu(); });
  input.addEventListener("keydown", (e) => {
    if (menuOpen) {
      if (e.key === "ArrowDown") { e.preventDefault(); return move(1); }
      if (e.key === "ArrowUp") { e.preventDefault(); return move(-1); }
      if (e.key === "Enter" || e.key === "Tab") { e.preventDefault(); return pick(items[active]); }
      if (e.key === "Escape") { e.preventDefault(); return closeMenu(); }
      return;
    }
    // Enter runs; Shift+Enter inserts a newline.
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); run(); }
  });

  return { open, close, el: overlay };
}
