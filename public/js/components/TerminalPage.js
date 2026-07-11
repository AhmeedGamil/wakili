// Terminal page. A full-screen overlay (opened from the composer's + menu) that
// runs raw shell commands on the laptop — no leading "!" needed, unlike the chat
// box. Multiple terminals live side by side in tabs: + opens another, × closes
// one after an inline confirmation, and each keeps its own folder and scrollback.
// Leaving the page (even by accident) never clears anything — every terminal is
// exactly as you left it when you come back. This is NOT saved as a chat; only
// the command history is kept, on the phone (localStorage, newest-first). Typing
// "/" recalls that history in the same menu style as the agent's slash-commands.

import { el } from "./dom.js";
import { icon } from "./icons.js";

const HISTORY_KEY = "ra-term-history";
const HISTORY_MAX = 200;

// Running inside the native app shell (its WebView tags the user agent) — the
// same check main.js makes; old installs still say ZogagApp, keep matching both.
const IS_NATIVE = /WakiliApp|ZogagApp/i.test(navigator.userAgent);

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
    return `"${prog}" is an interactive program and can't run in this terminal (no TTY). Use it from a real terminal on your computer.`;
  }
  if (REPL_PROGRAMS.has(prog)) {
    if (piped || hasPositional) return null;     // running a script, or piped input
    return `"${prog}" would open an interactive prompt this terminal can't provide. Run a script (e.g. "${prog} script.js") or pipe input instead.`;
  }
  return null;
}

export function createTerminalPage({ onRun }) {
  let history = loadHistory();   // newest-first list of command strings
  let items = [];                // history entries currently shown in the menu
  let active = 0;                // highlighted index
  let menuOpen = false;
  let running = false;
  let terms = [];                // [{ id, label, cwd, out }] — one entry per tab
  let cur = -1;                  // index of the visible terminal
  let counter = 0;               // ever-increasing tab number

  const pathEl = el("span", { class: "term-path", text: "" });
  const head = el("div", { class: "fp-head term-head" },
    // Inside the native app shell the header shows a back arrow (matching the
    // Kotlin app — leaving loses nothing, terminals keep their scrollback);
    // desktop/browser keeps the usual ×. Same UA tag main.js uses for IS_NATIVE.
    IS_NATIVE
      ? el("button", { class: "head-x", type: "button", title: "Back", "aria-label": "Back", onClick: close }, icon("corner-up-left"))
      : el("button", { class: "head-x", type: "button", title: "Close", "aria-label": "Close", onClick: close }, icon("x")),
    el("div", { class: "term-head-left" }, icon("terminal"), el("strong", { text: "Terminal" })),
  );
  const tabsBar = el("div", { class: "term-tabs" });
  const confirmBox = el("div", { class: "term-confirm" });
  confirmBox.hidden = true;
  const cwdBar = el("div", { class: "term-cwd" }, icon("folder"), pathEl);
  const outWrap = el("div", { class: "term-outwrap" });
  const menu = el("div", { class: "slash-menu term-menu", hidden: "" });
  const input = el("textarea", { class: "term-input", rows: "1",
    placeholder: "type / for history",
    spellcheck: "false", autocapitalize: "off", autocorrect: "off" });
  const runBtn = el("button", { class: "btn send", type: "submit", "aria-label": "Run" }, icon("arrow-up-right"));
  const bar = el("div", { class: "composer-bar term-bar" }, input, runBtn);
  const form = el("form", { class: "term-form" }, menu, bar);

  const panel = el("div", { class: "ft-panel term-panel" }, head, tabsBar, cwdBar, confirmBox, outWrap, form);
  const overlay = el("div", { class: "fp-overlay term-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  // ---- tabs ----
  function addTerm(startCwd) {
    counter += 1;
    const t = { id: counter, label: "Term " + counter, cwd: startCwd || "", out: el("div", { class: "term-out" }) };
    outWrap.appendChild(t.out);
    terms.push(t);
    setCur(terms.length - 1);
  }
  function setCur(i) {
    cur = i;
    terms.forEach((t, k) => { t.out.hidden = k !== cur; });
    const t = terms[cur];
    pathEl.textContent = t && t.cwd ? t.cwd : "(default folder)";
    renderTabs();
  }
  function renderTabs() {
    tabsBar.innerHTML = "";
    terms.forEach((t, i) => {
      const x = el("span", { class: "term-tab-x", title: "Close " + t.label,
        onClick: (e) => { e.stopPropagation(); askClose(i); } }, icon("x"));
      tabsBar.appendChild(el("button",
        { type: "button", class: "term-tab" + (i === cur ? " on" : ""), onClick: () => { hideConfirm(); setCur(i); input.focus(); } },
        el("span", { class: "term-tab-label", text: t.label }), x));
    });
    tabsBar.appendChild(el("button", { type: "button", class: "term-tab term-tab-add", title: "New terminal",
      onClick: () => { hideConfirm(); addTerm(terms[cur] ? terms[cur].cwd : ""); input.focus(); } }, icon("plus")));
  }
  // Closing throws away that tab's scrollback, so it asks first — inline, not a
  // browser dialog (those don't render in every WebView).
  function askClose(i) {
    const t = terms[i];
    confirmBox.replaceChildren(
      el("span", { class: "term-confirm-text", text: `Close ${t.label}? Its output will be lost.` }),
      el("button", { class: "btn danger", type: "button", onClick: () => { hideConfirm(); closeTerm(i); } }, "Close"),
      el("button", { class: "btn ghost", type: "button", onClick: hideConfirm }, "Cancel"),
    );
    confirmBox.hidden = false;
  }
  function hideConfirm() { confirmBox.hidden = true; }
  function closeTerm(i) {
    const [t] = terms.splice(i, 1);
    if (t) t.out.remove();
    if (!terms.length) { close(); return; }        // last tab closed → leave the page
    setCur(Math.min(i, terms.length - 1));
    input.focus();
  }

  function open(startCwd) {
    // Returning finds everything as it was — unless the caller comes from a
    // different project than the visible tab. Then surface a terminal for THAT
    // project: reuse a tab already sitting in its folder, else add a fresh one.
    if (!terms.length) addTerm(startCwd);
    else if (startCwd && terms[cur] && terms[cur].cwd !== startCwd) {
      const i = terms.findIndex((t) => t.cwd === startCwd);
      if (i >= 0) setCur(i);
      else addTerm(startCwd);
    }
    overlay.removeAttribute("hidden");
    setTimeout(() => input.focus(), 0);
  }
  function close() { overlay.setAttribute("hidden", ""); closeMenu(); hideConfirm(); }

  // ---- output (scrollback) ----
  function scrollDown(t) { t.out.scrollTop = t.out.scrollHeight; }
  function printCommand(t, command) {
    t.out.appendChild(el("div", { class: "term-line term-cmd" },
      el("span", { class: "term-prompt", text: shortCwd(t.cwd) + " $" }),
      el("span", { class: "term-cmd-text", text: " " + command }),
    ));
    scrollDown(t);
  }
  function printOutput(t, text, ok) {
    const s = (text || "").replace(/\s+$/, "");
    if (!s) return;
    t.out.appendChild(el("pre", { class: "term-line term-result" + (ok ? "" : " err"), text: s }));
    scrollDown(t);
  }
  // A guidance note (interactive-command hint) — softer than an error.
  function printHint(t, text) {
    t.out.appendChild(el("pre", { class: "term-line term-result term-hint", text }));
    scrollDown(t);
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
    // Same behaviour as the chat composer: once the text grows past one line,
    // the Run button hugs the bottom instead of floating vertically centered.
    const cs = getComputedStyle(input);
    let line = parseFloat(cs.lineHeight);
    if (!line) line = parseFloat(cs.fontSize) * 1.4;
    const padV = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom);
    const lines = Math.round((input.scrollHeight - padV) / line);
    bar.classList.toggle("multiline", lines > 1);
  }

  async function run() {
    if (running) return;
    const t = terms[cur];                     // pin the tab: output lands here even if the user switches away
    if (!t) return;
    const command = input.value.trim();
    if (!command) return;
    closeMenu();
    input.value = "";
    autoSize();
    remember(command);
    printCommand(t, command);
    // Interactive programs can't run in a non-TTY shell — hint instead of hanging.
    const hint = interactiveHint(command);
    if (hint) { printHint(t, hint); input.focus(); return; }
    running = true; runBtn.disabled = true;
    let r = null;
    try { r = await onRun(command, t.cwd); } catch { r = null; }
    running = false; runBtn.disabled = false;
    if (r && typeof r.cwd === "string") {
      t.cwd = r.cwd;
      if (terms[cur] === t) pathEl.textContent = t.cwd || "(default folder)";
    }
    printOutput(t, r ? r.output : "(failed to run)", !!(r && r.ok));
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

  return {
    open, close, el: overlay,
    // Inner dismissible layers, so the native back button peels them off before
    // leaving the whole page: the close-tab confirmation, then the history menu.
    confirmOpen: () => !confirmBox.hidden,
    hideConfirm,
    menuOpen: () => menuOpen,
    closeMenu,
  };
}
