// Stream flight recorder — diagnostics for "a chunk went missing" / "the turn
// took a while to appear". OFF unless WAKILI_DEBUG_STREAM=1.
//
// Deliberately NOT a logger. Logging one line per token would be the worst
// possible instrument here: console writes to a file or TTY are synchronous on
// Windows, so they would stall the very event loop that fans out the SSE
// writes — we'd be measuring the logger, not the bug. Instead:
//
//   - Lifecycle events (turn start/end, snapshots, resyncs, client reports) go
//     into a fixed-size ring buffer in memory. ~20 entries per turn.
//   - Content deltas are COUNTED, never stored. One integer add per delta.
//
// Nothing is ever printed. The buffer is read on demand via GET /api/debug/stream,
// and clients POST their own recorder dumps to /api/debug/report when they spot
// an ordering anomaly — so one request shows both sides of the same turn.

const enabled = process.env.WAKILI_DEBUG_STREAM === "1";

const MAX_ENTRIES = 400;
const MAX_CLIENT_REPORTS = 20;

const ring = [];           // lifecycle entries, oldest first
const counters = new Map(); // sessionId -> { deltas, chars, lastSeq, turns }
const clientReports = [];   // dumps uploaded by web/Android clients

export const recorderEnabled = enabled;

function push(entry) {
  ring.push({ at: Date.now(), ...entry });
  if (ring.length > MAX_ENTRIES) ring.splice(0, ring.length - MAX_ENTRIES);
}

function counterFor(sessionId) {
  let c = counters.get(sessionId);
  if (!c) { c = { deltas: 0, chars: 0, lastSeq: 0, turns: 0 }; counters.set(sessionId, c); }
  return c;
}

/** Generic hook for the HTTP side (resync requests, session fetches). */
export function record(kind, fields) {
  if (!enabled) return;
  push({ kind, ...fields });
}

/**
 * Called from publish() for every event that leaves the gateway. Content
 * deltas only bump counters; everything else is worth a ring entry.
 */
export function recordPublish(sessionId, event, seq, subscribers) {
  if (!enabled) return;
  const c = counterFor(sessionId);
  c.lastSeq = seq;

  // Content deltas: the high-frequency path. Count, don't store.
  if (event.type === "stream_event") {
    const d = event.event?.delta;
    const text = d?.text ?? d?.thinking;
    if (typeof text === "string") { c.deltas++; c.chars += text.length; }
    return;
  }
  if (event.type !== "_gateway") return; // assistant/user/result envelopes: not lifecycle

  const sub = event.subtype;
  if (sub === "ping") return; // keepalive noise
  if (sub === "turn_start") { c.turns++; c.deltas = 0; c.chars = 0; }

  const entry = { kind: "publish", sessionId, subtype: sub, seq, subscribers };
  // The two fields that actually matter when a turn comes back wrong.
  if (sub === "snapshot") {
    entry.parts = (event.parts || []).length;
    entry.busy = !!event.busy;
    entry.client = event.client || "";
  }
  if (sub === "turn_end") { entry.deltas = c.deltas; entry.chars = c.chars; }
  push(entry);
}

/** A client noticed something (usually an out-of-order snapshot) and sent its ring. */
export function recordClientReport(body) {
  if (!enabled || !body || typeof body !== "object") return;
  clientReports.push({ at: Date.now(), ...body });
  if (clientReports.length > MAX_CLIENT_REPORTS) {
    clientReports.splice(0, clientReports.length - MAX_CLIENT_REPORTS);
  }
}

/** Everything the recorder holds, for GET /api/debug/stream. */
export function dump() {
  return {
    enabled,
    now: Date.now(),
    entries: ring,
    counters: Object.fromEntries(counters),
    clientReports,
  };
}
