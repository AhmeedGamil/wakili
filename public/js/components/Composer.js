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

import { el, dismissFirst, backdropFor } from "./dom.js";
import { icon } from "./icons.js";
import { createSlashMenu } from "./slashMenu.js";
import { isImageName } from "./media.js";
import { IS_NATIVE } from "../core/env.js";

export function createComposer({ onSend, onStop, onCancelQueued, onOpenTerminal, onUpload, onRemoveUpload, commands = [] }) {
  // [{ name, dataBase64, dataUrl, isImg, status, progress, up, promise }]
  // status: "uploading" | "done" | "failed"; up = { path, name, url } once done.
  let pending = [];
  let busy = false; // active session has an in-flight turn (send button becomes Stop)
  let blocked = false; // a permission / question card is up — sending is disabled until answered

  // The box is a contenteditable div, NOT a textarea: Android keyboards commit
  // clipboard-panel images through the IME's rich-content channel, which plain
  // text controls categorically refuse — only an editable region can receive
  // them (the long-press Paste menu works on both; the Gboard image chip only
  // works here). A `value` accessor mimics the textarea so the rest of this
  // file and the shared slash menu keep reading/writing input.value untouched.
  const input = el("div", { id: "input", contenteditable: "true", "data-placeholder": "type / for command" });
  Object.defineProperty(input, "value", {
    get: () => input.innerText.replace(/\u00A0/g, " ").replace(/\n$/, ""),
    set: (v) => { input.textContent = v == null ? "" : String(v); },
  });
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

  // A contenteditable auto-grows natively — ZERO JS measurement on the typing
  // path. The multiline class is driven by a ResizeObserver, which fires after
  // layout only when the box's size actually changed (no forced reflow).
  {
    let line = 0;
    new ResizeObserver((entries) => {
      const h = entries[entries.length - 1].contentRect.height;
      if (!line) {
        const cs = getComputedStyle(input);
        line = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.4;
      }
      bar.classList.toggle("multiline", Math.round(h / line) > 1);
    }).observe(input);
  }
  const autoSize = () => {}; // sizing is native; kept for the call sites

  // Is there something to send (typed text or a pending attachment)?
  const hasContent = () => input.value.trim().length > 0 || pending.length > 0;
  // The button is Stop (■) ONLY while the agent works AND the box is empty;
  // the moment you type (or while idle) it becomes Send (↑) so you can send/queue.
  let lastStop = null; // rebuild the button's icon only when its state flips —
                       // this runs per keystroke, and most keystrokes change nothing
  function refreshButton() {
    // While a card is up, force plain Send appearance and disable it entirely.
    const stop = !blocked && busy && !hasContent();
    if (stop !== lastStop) {
      lastStop = stop;
      send.classList.toggle("stop", stop);
      send.replaceChildren(icon(stop ? "square" : "arrow-up"));
      send.setAttribute("aria-label", stop ? "Stop" : "Send");
    }
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
      const card = el("div", { class: "att-card" + (a.isImg ? " img" : "") });
      if (a.isImg) {
        // The image itself is the preview; keep generated/pasted filenames out
        // of visible text, tooltips, and image fallback text.
        card.appendChild(el("img", { src: a.dataUrl, alt: "" }));
      } else {
        card.title = a.name;
        card.append(icon("paperclip", "att-ico"), el("div", { class: "att-name", text: a.name }));
      }
      const overlay = attOverlay(a);
      if (overlay) card.appendChild(overlay);
      card.appendChild(remove);
      chips.appendChild(card);
    });
    refreshButton(); // attachments count as content
  }

  // ---- slash menu (shared component; this wires the agent's commands in) ----
  const slash = createSlashMenu({
    menu, input,
    filter: (q) => commands.filter((c) => c.name.toLowerCase().startsWith(q) || c.desc.toLowerCase().includes(q)),
    render: (c) => [el("span", { class: "slash-name", text: "/" + c.name }), el("span", { class: "slash-desc", text: c.desc })],
    onPick: (c) => {
      if (!c) return;
      slash.close();
      input.value = "/" + c.name + " ";   // fill it in; user adds args or hits Enter
      autoSize();
      input.focus();
    },
  });

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
    // Drop the mobile keyboard — but only AFTER the optimistic message has
    // painted (double rAF: the second callback runs after the first frame is
    // on screen). Blurring synchronously dismissed the keyboard in the same
    // beat as the send work; in the native shell that dismissal resizes the
    // WebView (KeyboardAvoidingView), stacking a full-page relayout onto the
    // tap — the "Send lags in big sessions" hitch.
    requestAnimationFrame(() => requestAnimationFrame(() => input.blur()));
  }

  // ---- + (add) menu: attach Images / Files, or open the Terminal page ----
  const addBd = backdropFor(addMenu, closeAddMenu);
  function closeAddMenu() { addMenu.setAttribute("hidden", ""); addBd.hide(); }
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
    addBd.show();
  }
  attachBtn.onclick = () => { if (addMenu.hasAttribute("hidden")) openAddMenu(); else closeAddMenu(); };
  // One file (picked or pasted) → pending item: read to base64 (the data URL
  // doubles as the image preview), then eager-upload while the user types.
  const addFile = (file, name) => {
    const isImg = /^image\//.test(file.type) || isImageName(file.name);
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result);
      const a = { name: name || file.name, dataBase64: dataUrl.split(",")[1], dataUrl, isImg };
      pending.push(a);
      startUpload(a); // eager: the transfer runs while the user types
      renderChips();
    };
    reader.readAsDataURL(file);
  };
  const readPicked = (inp) => {
    for (const file of inp.files) addFile(file);
    inp.value = "";
  };
  fileInput.onchange = () => readPicked(fileInput);
  imgInput.onchange = () => readPicked(imgInput);
  // Pasting an image attaches it exactly like a picked file; plain-text pastes
  // are left to the browser. Clipboard images are often nameless — stamp one so
  // the chip and the upload have something to show. Returns whether it took
  // anything, so the caller knows to cancel the default insertion.
  const takeImages = (dt) => {
    const imgs = [...((dt && dt.items) || [])].filter((it) => it.kind === "file" && /^image\//.test(it.type));
    if (!imgs.length) return false;
    for (const it of imgs) {
      const f = it.getAsFile();
      if (!f) continue;
      const ext = (f.type.split("/")[1] || "png").replace("jpeg", "jpg");
      addFile(f, f.name || `pasted-${Date.now()}.${ext}`);
    }
    return true;
  };
  // Two delivery channels for the same gesture: the long-press Paste menu (and
  // Ctrl+V) raises a real "paste" event; an image tapped in the KEYBOARD's
  // clipboard panel commits through the IME instead — no paste event at all,
  // only a beforeinput carrying the file in its dataTransfer. Handle both.
  // (Canceling a handled paste means no beforeinput follows — never both.)
  // Every paste is intercepted: the box is contenteditable, and letting the
  // default run would insert rich HTML — text goes back in as plain text.
  input.addEventListener("paste", (e) => {
    if (blocked) return; // same gate as the + menu while a card awaits an answer
    e.preventDefault();
    if (takeImages(e.clipboardData)) return;
    const txt = e.clipboardData ? e.clipboardData.getData("text/plain") : "";
    if (txt) document.execCommand("insertText", false, txt);
  });
  input.addEventListener("beforeinput", (e) => {
    if (blocked) return;
    if (e.dataTransfer && takeImages(e.dataTransfer)) e.preventDefault();
  });
  // One image data URL → pending attachment (the entry point for swept IME
  // images and the shell-assisted clipboard paste).
  const addDataUrl = (dataUrl, name) => {
    if (!/^data:image\//.test(dataUrl || "")) return false;
    const a = { name: name || `pasted-${Date.now()}.png`, dataBase64: dataUrl.split(",")[1], dataUrl, isImg: true };
    pending.push(a);
    startUpload(a);
    renderChips();
    return true;
  };
  // Belt and braces: some WebView versions commit an IME image straight into
  // the editable as a literal <img> without a cancelable beforeinput. Sweep
  // any such node into a real attachment (data URLs only — there's nothing
  // fetchable behind other schemes), and never leave images sitting in the box.
  const sweepImgs = () => {
    for (const im of [...input.querySelectorAll("img")]) {
      const src = im.getAttribute("src") || "";
      im.remove();
      addDataUrl(src);
    }
  };

  // Keep focus on the textarea when the button is tapped — otherwise the first tap
  // just blurs the input (dismissing the keyboard) and you'd need a second tap.
  send.addEventListener("mousedown", (e) => e.preventDefault());
  // Stop only when working with an empty box; otherwise it's a normal Send button
  // and the form submit handles it (sending, or queueing if the agent is busy).
  send.addEventListener("click", (e) => { if (blocked) { e.preventDefault(); return; } if (busy && !hasContent()) { e.preventDefault(); onStop && onStop(); } });

  root.addEventListener("submit", (e) => { e.preventDefault(); submit(); });
  input.addEventListener("input", () => {
    sweepImgs();
    // A fully-deleted contenteditable keeps a stray <br>, which defeats the
    // :empty placeholder — drop the husk so "empty" is truly empty.
    if (!input.textContent) input.replaceChildren();
    autoSize(); slash.sync(); refreshButton(); closeAddMenu();
  });
  // The slash menu owns the keys while it's open. Otherwise Enter just inserts
  // a newline (default) — only the Send button sends.
  input.addEventListener("keydown", (e) => { slash.keydown(e); });
  document.addEventListener("click", (e) => {
    if (!root.contains(e.target) && slash.isOpen()) slash.close();
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
    // Native back button: a transient menu (slash commands or the + menu) is
    // its own dismissible layer — report whether one is open and close it.
    menusOpen: () => slash.isOpen() || !addMenu.hasAttribute("hidden"),
    closeMenus: () => { if (slash.isOpen()) slash.close(); else closeAddMenu(); },
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
    setCommands: (list) => { commands = Array.isArray(list) ? list : []; if (slash.isOpen()) slash.sync(); },
    // Per-session state: the draft text and pending attachments are saved on
    // session switch and restored when you come back — nothing leaks across chats.
    getState: () => ({ draft: input.value, pending: pending.slice() }),
    setState: (st) => {
      pending = (st && st.pending) ? st.pending.slice() : [];
      input.value = (st && st.draft) || "";
      slash.close(); closeAddMenu();
      renderChips(); // also refreshes the send button
      autoSize();
    },
  };
}
