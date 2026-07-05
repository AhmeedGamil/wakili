// Reusable composer: attach button, auto-growing textarea, send button.
// Attachments upload EAGERLY: the moment a file is picked it starts uploading
// (progress ring on its card) so the transfer runs while the user types; send
// is never gated on it — the outbox waits for in-flight uploads. Removing a
// card cancels/undoes its upload. Emits (text, attachments) via onSend.
//
// Slash commands: when the text is a single "/token" (no space yet), a menu of
// the agent's real commands pops above the bar — like the Claude CLI. ↑/↓ move,
// Enter/Tab or tap selects. Picking one drops "/name " into the box so you can add
// args (or just press Enter) — the command is then sent to the agent like any turn.
// The command list comes from the backend per agent; setCommands() swaps it.

import { el, dismissFirst } from "./dom.js";
import { icon } from "./icons.js";

export function createComposer({ onSend, onStop, onCancelQueued, onOpenTerminal, onUpload, onRemoveUpload, commands = [] }) {
  // [{ name, dataBase64, dataUrl, isImg, status, progress, up, promise }]
  // status: "uploading" | "done" | "failed"; up = { path, name, url } once done.
  let pending = [];
  let items = [];   // commands currently shown in the menu
  let active = 0;   // highlighted index
  let menuOpen = false;
  let busy = false; // active session has an in-flight turn (send button becomes Stop)
  let blocked = false; // a permission / question card is up — sending is disabled until answered

  const input = el("textarea", { id: "input", rows: "1", placeholder: "type / for command" });
  const send = el("button", { class: "btn send", type: "submit", "aria-label": "Send" }, icon("arrow-up"));
  const fileInput = el("input", { type: "file", multiple: "", style: "display:none" });
  const imgInput = el("input", { type: "file", multiple: "", accept: "image/*", style: "display:none" });
  const attachBtn = el("button", { class: "btn attach", type: "button", title: "Attach files", "aria-label": "Attach" }, icon("plus"));
  const chips = el("div", { class: "chips" });
  const menu = el("div", { class: "slash-menu", hidden: "" });
  const addMenu = el("div", { class: "add-menu", hidden: "" }); // + button: Add files / Terminal
  const queued = el("div", { class: "queued", hidden: "" }); // pending message shown while busy

  // The add-menu lives inside the bar so it can float anchored above the + button
  // (absolutely positioned — it takes no layout space and covers nothing else).
  const bar = el("div", { class: "composer-bar" }, addMenu, attachBtn, input, send);
  const root = el("form", { id: "composer", class: "composer" }, menu, queued, chips, bar, fileInput, imgInput);

  function autoSize() {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 180) + "px";
    // Buttons sit centered on a single line, but drop to the bottom once the
    // text grows past two lines (so they don't float in the middle of a tall box).
    const cs = getComputedStyle(input);
    let line = parseFloat(cs.lineHeight);
    if (!line) line = parseFloat(cs.fontSize) * 1.4;
    const padV = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom);
    const lines = Math.round((input.scrollHeight - padV) / line);
    bar.classList.toggle("multiline", lines > 1);
  }

  // Is there something to send (typed text or a pending attachment)?
  const hasContent = () => input.value.trim().length > 0 || pending.length > 0;
  // The button is Stop (■) ONLY while the agent works AND the box is empty;
  // the moment you type (or while idle) it becomes Send (↑) so you can send/queue.
  function refreshButton() {
    // While a card is up, force plain Send appearance and disable it entirely.
    const stop = !blocked && busy && !hasContent();
    send.classList.toggle("stop", stop);
    send.replaceChildren(icon(stop ? "square" : "arrow-up"));
    send.setAttribute("aria-label", stop ? "Stop" : "Send");
    send.disabled = blocked;
    // The + menu is also parked while a card awaits an answer — attaching files
    // or opening the terminal mid-permission would race the pending decision.
    attachBtn.disabled = blocked;
    if (blocked) closeAddMenu();
    root.classList.toggle("blocked", blocked);
  }
  // Eager upload: start the transfer the moment the file is picked, so it runs
  // while the user types. The result lands on the item (a.up); the outbox uses
  // it — or awaits a.promise — instead of re-uploading at send time. Without an
  // onUpload prop the item is left alone and uploads at send, as before.
  function startUpload(a) {
    if (!onUpload) { a.status = "done"; return; }
    a.status = "uploading"; a.progress = 0; a.up = null;
    const t = onUpload(a, (p) => { a.progress = p; if (a._ring) a._ring.style.setProperty("--p", String(p)); });
    a._abort = t && t.abort;
    a.promise = (t ? t.promise : Promise.reject(new Error("no uploader")))
      .then((up) => { a.up = up && up.path ? up : null; a.status = a.up ? "done" : "failed"; renderChips(); return a.up; })
      .catch(() => { a.status = "failed"; renderChips(); return null; });
  }

  // The card's status layer: a progress ring while uploading, tap-to-retry when
  // the upload failed, nothing once it's done (the ring simply disappears).
  function attOverlay(a) {
    if (a.status === "uploading") {
      a._ring = el("div", { class: "att-ring" });
      a._ring.style.setProperty("--p", String(a.progress || 0));
      return el("div", { class: "att-overlay" }, a._ring);
    }
    if (a.status === "failed") {
      return el("div", { class: "att-overlay" },
        el("button", { class: "att-retry", type: "button", title: "Upload failed — tap to retry",
          onClick: (e) => { e.preventDefault(); startUpload(a); renderChips(); } }, "↻"));
    }
    return null;
  }

  // Pending attachments preview: 1:1 cards flowing from the top-left. Images fill
  // the card (no name); files show an icon top-left + a 2-line-max name at the
  // bottom. The × at the top-right removes the attachment (cancelling or undoing
  // its upload so nothing is left behind on the laptop).
  function renderChips() {
    chips.innerHTML = "";
    pending.forEach((a, i) => {
      const remove = el("button", { class: "att-x", type: "button", "aria-label": "Remove", onClick: () => {
        if (a.status === "uploading" && a._abort) { try { a._abort(); } catch { /* already settled */ } }
        if (a.up && onRemoveUpload) onRemoveUpload(a.up);
        pending.splice(i, 1); renderChips();
      } }, icon("x"));
      const card = el("div", { class: "att-card" + (a.isImg ? " img" : ""), title: a.name });
      if (a.isImg) card.appendChild(el("img", { src: a.dataUrl, alt: a.name }));
      else card.append(icon("paperclip", "att-ico"), el("div", { class: "att-name", text: a.name }));
      const overlay = attOverlay(a);
      if (overlay) card.appendChild(overlay);
      card.appendChild(remove);
      chips.appendChild(card);
    });
    refreshButton(); // attachments count as content
  }

  // ---- slash menu ----
  function closeMenu() { menuOpen = false; menu.setAttribute("hidden", ""); menu.innerHTML = ""; }
  function renderMenu() {
    menu.innerHTML = "";
    items.forEach((c, i) => {
      menu.appendChild(el("button",
        { type: "button", class: "slash-item" + (i === active ? " active" : ""),
          onMousedown: (e) => { e.preventDefault(); run(c); } },
        el("span", { class: "slash-name", text: "/" + c.name }),
        el("span", { class: "slash-desc", text: c.desc }),
      ));
    });
  }
  function move(dir) {
    if (!items.length) return;
    active = (active + dir + items.length) % items.length;
    renderMenu();
    menu.children[active]?.scrollIntoView({ block: "nearest" });
  }
  function run(c) {
    if (!c) return;
    closeMenu();
    input.value = "/" + c.name + " ";   // fill it in; user adds args or hits Enter
    autoSize();
    input.focus();
  }
  function syncMenu() {
    const m = /^\/(\S*)$/.exec(input.value);   // only a bare "/token", no args yet
    if (!m || !commands.length) return closeMenu();
    const q = m[1].toLowerCase();
    items = commands.filter((c) => c.name.toLowerCase().startsWith(q) || c.desc.toLowerCase().includes(q));
    if (!items.length) return closeMenu();
    active = 0; menuOpen = true; menu.removeAttribute("hidden"); renderMenu();
  }

  function submit() {
    if (blocked) return; // answer the pending permission / question first
    const t = input.value.trim();
    if (!t && !pending.length) return;
    onSend(t, pending.slice());
    pending = [];
    renderChips();
    input.value = "";
    autoSize();
    refreshButton(); // box is empty again → back to Stop if still busy
    input.blur();    // drop the mobile keyboard once the message is on its way
  }

  // ---- + (add) menu: attach Images / Files, or open the Terminal page ----
  function closeAddMenu() { addMenu.setAttribute("hidden", ""); }
  function openAddMenu() {
    addMenu.innerHTML = "";
    addMenu.append(
      el("button", { type: "button", class: "add-item",
        onMousedown: (e) => { e.preventDefault(); closeAddMenu(); imgInput.click(); } },
        icon("image"), el("span", { text: "Images" })),
      el("button", { type: "button", class: "add-item",
        onMousedown: (e) => { e.preventDefault(); closeAddMenu(); fileInput.click(); } },
        icon("paperclip"), el("span", { text: "Files" })),
      el("button", { type: "button", class: "add-item",
        onMousedown: (e) => { e.preventDefault(); closeAddMenu(); onOpenTerminal && onOpenTerminal(); } },
        icon("terminal"), el("span", { text: "Terminal" })),
    );
    addMenu.removeAttribute("hidden");
  }
  attachBtn.onclick = () => { if (addMenu.hasAttribute("hidden")) openAddMenu(); else closeAddMenu(); };
  // Read picked files to base64; the full data URL doubles as the image preview.
  const readPicked = (inp) => {
    for (const file of inp.files) {
      const isImg = /^image\//.test(file.type) || /\.(png|jpe?g|gif|webp|svg|bmp|heic)$/i.test(file.name);
      const reader = new FileReader();
      reader.onload = () => {
        const dataUrl = String(reader.result);
        const a = { name: file.name, dataBase64: dataUrl.split(",")[1], dataUrl, isImg };
        pending.push(a);
        startUpload(a); // eager: the transfer runs while the user types
        renderChips();
      };
      reader.readAsDataURL(file);
    }
    inp.value = "";
  };
  fileInput.onchange = () => readPicked(fileInput);
  imgInput.onchange = () => readPicked(imgInput);

  // Keep focus on the textarea when the button is tapped — otherwise the first tap
  // just blurs the input (dismissing the keyboard) and you'd need a second tap.
  send.addEventListener("mousedown", (e) => e.preventDefault());
  // Stop only when working with an empty box; otherwise it's a normal Send button
  // and the form submit handles it (sending, or queueing if the agent is busy).
  send.addEventListener("click", (e) => { if (blocked) { e.preventDefault(); return; } if (busy && !hasContent()) { e.preventDefault(); onStop && onStop(); } });

  root.addEventListener("submit", (e) => { e.preventDefault(); submit(); });
  input.addEventListener("input", () => { autoSize(); syncMenu(); refreshButton(); closeAddMenu(); });
  input.addEventListener("keydown", (e) => {
    // Slash-command menu still uses the keys while it's open.
    if (menuOpen) {
      if (e.key === "ArrowDown") { e.preventDefault(); return move(1); }
      if (e.key === "ArrowUp") { e.preventDefault(); return move(-1); }
      if (e.key === "Enter" || e.key === "Tab") { e.preventDefault(); return run(items[active]); }
      if (e.key === "Escape") { e.preventDefault(); return closeMenu(); }
    }
    // Otherwise Enter just inserts a newline (default) — only the Send button sends.
  });
  document.addEventListener("click", (e) => {
    if (!root.contains(e.target) && menuOpen) closeMenu();
  });
  // The + menu closes on a tap outside itself, and that tap does nothing else —
  // dismissing is its own gesture (same as the sidebar filter and model picker).
  dismissFirst(
    () => !addMenu.hasAttribute("hidden"),
    (t) => addMenu.contains(t) || attachBtn.contains(t),
    closeAddMenu,
  );

  return {
    el: root,
    // Busy → Stop when the box is empty, Send when there's text to queue.
    setBusy: (b) => { busy = !!b; refreshButton(); },
    // Block sending while a permission / question card awaits an answer.
    setBlocked: (b) => { blocked = !!b; refreshButton(); },
    // Show/clear the "queued" chip for the active session's pending messages
    // (a list now — the first is shown, the rest counted; × cancels them all).
    setQueued: (list) => {
      queued.innerHTML = "";
      const arr = Array.isArray(list) ? list : (list ? [list] : []);
      const q = arr[0];
      if (q && (q.text || (q.raw && q.raw.length))) {
        let label = q.text || (q.raw || []).map((a) => a.name).join(", ");
        if (arr.length > 1) label += `  (+${arr.length - 1} more)`;
        queued.append(
          icon("clock"),
          el("span", { class: "queued-label", text: "Queued: " + label }),
          el("button", { class: "queued-x", type: "button", title: "Cancel", onClick: () => onCancelQueued && onCancelQueued() }, icon("x")),
        );
        queued.removeAttribute("hidden");
      } else {
        queued.setAttribute("hidden", "");
      }
    },
    focus: () => input.focus(),
    setCommands: (list) => { commands = Array.isArray(list) ? list : []; if (menuOpen) syncMenu(); },
    // Per-session state: the draft text and pending attachments are saved on
    // session switch and restored when you come back — nothing leaks across chats.
    getState: () => ({ draft: input.value, pending: pending.slice() }),
    setState: (st) => {
      pending = (st && st.pending) ? st.pending.slice() : [];
      input.value = (st && st.draft) || "";
      closeMenu(); closeAddMenu();
      renderChips(); // also refreshes the send button
      autoSize();
    },
  };
}
