// Scheduled tasks page. Every pending gateway-side job across every chat, with
// create / edit / cancel — the web twin of Android's ScheduledTasksScreen.
// Reached from the sidebar's ☰ menu (under Select project).
//
// The composer's + → Schedule still exists for "schedule what I'm typing, here".
// This page is the other half: one place to see and fix tasks you already made,
// including the ones in chats you don't have open.

import { el } from "./dom.js";
import { icon } from "./icons.js";

// Epoch millis of the next occurrence of HH:MM — a time already past today
// means tomorrow. Same rule the composer's schedule popover uses, and the same
// rule the user is told about in the dialog's "Runs …" line.
function nextOccurrence(hhmm) {
  const parts = String(hhmm || "").split(":").map(Number);
  if (parts.length < 2 || parts.some(Number.isNaN)) return null;
  const at = new Date();
  at.setHours(parts[0], parts[1], 0, 0);
  if (at.getTime() <= Date.now()) at.setDate(at.getDate() + 1);
  return at.getTime();
}

const pad = (n) => String(n).padStart(2, "0");
const asHHMM = (d) => pad(d.getHours()) + ":" + pad(d.getMinutes());
const timeOf = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

function isToday(ms) {
  const a = new Date(ms), b = new Date();
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

// Same-day tasks show the time alone; later ones add the short date.
function formatTaskTime(ms) {
  return isToday(ms) ? timeOf(ms) : new Date(ms).toLocaleDateString([], { month: "short", day: "numeric" }) + " " + timeOf(ms);
}

/**
 * onLoad()                     -> [{ id, sessionId, sessionTitle, text, at, attachments }]
 * onCreate(sessionId, text, at)
 * onUpdate(jobId, text, at)
 * onCancel(jobId)
 * render(sessions) feeds the create dialog's "Run in" picker.
 */
export function createSchedulePage({ onLoad, onCreate, onUpdate, onCancel } = {}) {
  let jobs = [];
  let sessions = [];
  let loading = true;
  const cancelling = new Set(); // ids mid-cancel: row greys out until the server answers

  const body = el("div", { class: "ft-body sched-body" });
  const newBtn = el("button", { class: "btn ghost sp-new", type: "button", title: "New task", onClick: () => openDialog(null) }, icon("plus"));
  const panel = el("div", { class: "ft-panel" },
    el("div", { class: "fp-head" },
      el("strong", { text: "Scheduled tasks" }),
      el("div", { class: "sp-head-actions" },
        newBtn,
        el("button", { class: "btn ghost fp-x", type: "button", onClick: close }, icon("x")),
      ),
    ),
    body,
  );
  const overlay = el("div", { class: "fp-overlay ft-overlay", hidden: "" }, panel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  // ---- the create/edit dialog ----------------------------------------------
  // Its own overlay stacked above the page, so closing it doesn't close the
  // page behind it. `job` null = create (with chat picker), otherwise edit.
  let dialogOverlay = null;

  function closeDialog() {
    if (!dialogOverlay) return;
    dialogOverlay.remove();
    dialogOverlay = null;
  }

  function openDialog(job) {
    closeDialog();
    const editing = !!job;
    const start = editing ? new Date(job.at) : new Date(Date.now() + 5 * 60000);

    const text = el("textarea", { class: "sp-text", rows: "3", placeholder: "What should the agent do?" });
    text.value = editing ? (job.text || "") : "";
    const time = el("input", { type: "time", class: "sched-time", value: asHHMM(start) });
    const runs = el("div", { class: "sp-runs" });

    // A task can be attachments-only, so those rows don't have to keep text.
    const hasAttachments = editing && (job.attachments || []).length > 0;

    // A chat picker only on create — a task cannot move to another chat.
    let picker = null;
    if (!editing) {
      picker = el("select", { class: "sp-select" });
      for (const s of sessions.slice(0, 20)) {
        picker.appendChild(el("option", { value: s.id, text: s.title || "Untitled" }));
      }
    }

    const confirm = el("button", { class: "btn primary", type: "button" }, el("span", { text: editing ? "Save" : "Schedule" }));

    // The "Runs today/tomorrow at …" line is the honest part: a past time
    // silently means tomorrow, so say so before they commit.
    function sync() {
      const at = nextOccurrence(time.value);
      runs.textContent = at ? `Runs ${isToday(at) ? "today" : "tomorrow"} at ${timeOf(at)}` : "Pick a time";
      const ok = !!at && (text.value.trim() || hasAttachments) && (editing || !!(picker && picker.value));
      confirm.disabled = !ok;
    }
    time.addEventListener("input", sync);
    text.addEventListener("input", sync);

    confirm.onclick = async () => {
      const at = nextOccurrence(time.value);
      if (!at) return;
      const value = text.value.trim();
      closeDialog();
      if (editing) await (onUpdate && onUpdate(job.id, value, at));
      else await (onCreate && onCreate(picker.value, value, at));
      await refresh();
    };

    const card = el("div", { class: "sp-dialog" },
      el("div", { class: "fp-head" }, el("strong", { text: editing ? "Edit task" : "New task" })),
      el("div", { class: "sp-dialog-body" },
        ...(picker ? [el("div", { class: "sched-title", text: "Run in" }), picker] : []),
        el("div", { class: "sched-title", text: "Task" }),
        text,
        el("div", { class: "sched-title", text: "Run at" }),
        time,
        runs,
      ),
      el("div", { class: "sp-dialog-actions" },
        el("button", { class: "btn ghost", type: "button", onClick: closeDialog }, el("span", { text: "Cancel" })),
        confirm,
      ),
    );
    dialogOverlay = el("div", { class: "fp-overlay sp-dialog-overlay" }, card);
    document.body.appendChild(dialogOverlay);
    dialogOverlay.addEventListener("click", (e) => { if (e.target === dialogOverlay) closeDialog(); });
    sync();
    // Editing starts on the text; creating starts on the chat picker.
    (editing ? text : (picker || text)).focus();
  }

  // ---- list -----------------------------------------------------------------

  async function startCancel(job) {
    if (cancelling.has(job.id)) return;
    cancelling.add(job.id);
    draw();
    try { await (onCancel && onCancel(job.id)); } finally { cancelling.delete(job.id); }
    await refresh();
  }

  function draw() {
    body.innerHTML = "";
    if (loading) { body.appendChild(el("div", { class: "fp-empty", text: "Loading…" })); return; }
    if (!jobs.length) {
      body.appendChild(el("div", { class: "sp-empty" },
        icon("clock"),
        el("strong", { text: "No scheduled tasks" }),
        el("span", { text: "Schedule a message to run later, in any chat." }),
        el("button", { class: "btn primary", type: "button", onClick: () => openDialog(null) }, icon("plus"), el("span", { text: "New task" })),
      ));
      return;
    }
    for (const j of jobs) {
      const busy = cancelling.has(j.id);
      const head = el("div", { class: "sp-row-head" },
        icon("clock"),
        el("span", { class: "sched-when", text: formatTaskTime(j.at) }),
        el("span", { class: "sp-where", text: j.sessionTitle || "Untitled" }),
      );
      const info = el("div", { class: "sched-info" }, head);
      if (j.text) info.appendChild(el("span", { class: "sp-body-text", text: j.text }));
      const atts = j.attachments || [];
      if (atts.length) {
        info.appendChild(el("span", { class: "sched-att" }, icon("paperclip"), el("span", { text: atts.map((a) => a.name).join(", ") })));
      }
      const row = el("div", { class: "sp-row" + (busy ? " busy" : "") },
        el("button", { class: "sp-row-main", type: "button", title: "Edit task", onClick: () => { if (!busy) openDialog(j); } }, info),
        busy
          ? el("span", { class: "spinner-danger" })
          : el("button", { class: "queued-x", type: "button", title: "Cancel task", onClick: () => startCancel(j) }, icon("x")),
      );
      body.appendChild(row);
    }
  }

  async function refresh() {
    if (!onLoad) return;
    try {
      jobs = (await onLoad()) || [];
    } catch {
      jobs = [];
    }
    loading = false;
    draw();
  }

  function open() { overlay.removeAttribute("hidden"); loading = true; draw(); refresh(); }
  function close() { closeDialog(); overlay.setAttribute("hidden", ""); }

  return {
    open,
    close,
    isOpen: () => !overlay.hasAttribute("hidden"),
    dialogOpen: () => !!dialogOverlay,
    closeDialog,
    // Recent chats for the create dialog's "Run in" picker.
    render: (list) => { sessions = Array.isArray(list) ? list : []; },
  };
}
