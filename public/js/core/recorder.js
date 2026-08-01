// Client flight recorder — the browser half of src/recorder.mjs.
//
// Same rule as the server's: content deltas are COUNTED, never logged. One
// console.log per token would retain every logged object in the devtools
// console (a leak) and cost 10-50x more with devtools open — we would end up
// measuring the logger instead of the bug. Lifecycle events go into a small
// ring buffer in memory; nothing is written anywhere until asked.
//
// Enabled by the gateway (the `connected` event carries `debug`), so the single
// switch for all three sides is WAKILI_DEBUG_STREAM=1 on the laptop. Read it
// from the console with __wakiliDump(); anomalies also upload themselves so the
// laptop's /api/debug/stream shows both sides of the same turn.

const MAX_ENTRIES = 300;
const UPLOAD_COOLDOWN_MS = 30000;

let enabled = false;
let uploader = null;      // set by the controller (api.debugReport)
let lastUploadAt = 0;
const ring = [];
const counters = new Map(); // sessionId -> { deltas, chars, lastSeq, turns }

// A stable id for this tab, so an uploaded report says who sent it.
const clientId = Math.random().toString(36).slice(2) + Date.now().toString(36);

function push(entry) {
  ring.push({ at: Date.now(), ...entry });
  if (ring.length > MAX_ENTRIES) ring.splice(0, ring.length - MAX_ENTRIES);
}

function counterFor(sessionId) {
  let c = counters.get(sessionId);
  if (!c) { c = { deltas: 0, chars: 0, lastSeq: 0, turns: 0 }; counters.set(sessionId, c); }
  return c;
}

export const recorder = {
  get enabled() { return enabled; },
  // The gateway decides; a local override is still possible from the console.
  enable(on) { enabled = !!on; },
  setUploader(fn) { uploader = fn; },

  /**
   * One parsed stream event, as the controller sees it. `seq` is the gateway's
   * ordering stamp (absent on gateways that predate it).
   */
  event(sessionId, ev) {
    if (!enabled) return;
    const c = counterFor(sessionId);
    if (ev.seq != null) c.lastSeq = ev.seq;
    if (ev.kind === "text" || ev.kind === "thinking") {
      c.deltas++;
      c.chars += (ev.text || "").length;
      return; // high-frequency path: counted, not stored
    }
    if (ev.kind === "turnStart") { c.turns++; c.deltas = 0; c.chars = 0; }
    const entry = { kind: "event", sessionId, ev: ev.kind, seq: ev.seq };
    if (ev.kind === "snapshot") { entry.parts = (ev.parts || []).length; entry.busy = !!ev.busy; entry.client = ev.client || ""; }
    if (ev.kind === "turnEnd") { entry.deltas = c.deltas; entry.chars = c.chars; }
    push(entry);
  },

  /** Anything worth a ring entry that isn't a stream event (switches, resyncs). */
  note(kind, fields) {
    if (!enabled) return;
    push({ kind, ...fields });
  },

  /**
   * Something arrived that shouldn't have — currently only an out-of-order
   * snapshot. Recorded, then uploaded (rate-limited) so the laptop's recorder
   * holds this tab's ring next to its own copy of the same turn.
   */
  anomaly(kind, fields) {
    if (!enabled) return;
    push({ kind: "ANOMALY:" + kind, ...fields });
    const now = Date.now();
    if (!uploader || now - lastUploadAt < UPLOAD_COOLDOWN_MS) return;
    lastUploadAt = now;
    try { const p = uploader(this.dump()); if (p && p.catch) p.catch(() => { /* diagnostics are best effort */ }); }
    catch { /* never let the recorder break the stream */ }
  },

  dump() {
    return { source: "web", clientId, ua: navigator.userAgent, now: Date.now(), entries: ring, counters: Object.fromEntries(counters) };
  },
};

// Console access: __wakiliDump() prints the ring, __wakiliDump(true) also
// uploads it to the gateway even without an anomaly.
if (typeof window !== "undefined") {
  window.__wakiliDump = (upload) => {
    const d = recorder.dump();
    if (upload && uploader) { try { uploader(d); } catch { /* best effort */ } }
    return d;
  };
}
