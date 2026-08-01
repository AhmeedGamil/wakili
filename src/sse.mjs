// Observer / pub-sub hub. Each session is a channel; SSE responses subscribe to
// their session and receive every event published for it. Keeps the agent layer
// and the transport (HTTP/SSE) decoupled — the runner just calls publish().

import { recordPublish } from "./recorder.mjs";

const channels = new Map(); // sessionId -> Set<ServerResponse>
const everyone = new Set(); // multiplexed subscribers: every session's events, tagged with sessionId

export function subscribe(sessionId, res) {
  if (!channels.has(sessionId)) channels.set(sessionId, new Set());
  channels.get(sessionId).add(res);
  return () => {
    const set = channels.get(sessionId);
    if (!set) return;
    set.delete(res);
    if (set.size === 0) channels.delete(sessionId);
  };
}

// One stream for ALL sessions (the client keeps a single EventSource and routes
// by sessionId — browsers cap concurrent connections, so one-per-session won't scale).
export function subscribeAll(res) {
  everyone.add(res);
  return () => everyone.delete(res);
}

// Monotonic ordering stamp carried by every event that leaves the gateway.
// Clients use it to tell whether a snapshot is NEWER than the state they have
// already applied: two resyncs can be answered out of order (HTTP guarantees no
// ordering), and today the later-arriving — possibly older — snapshot wins,
// which is what rewinds a live turn and leaves a hole in it.
//
// Seeded from the wall clock rather than 0 so a gateway RESTART always issues
// higher numbers than the run before it; a counter starting over at 0 would
// make every connected client treat the new run's events as stale. Scaled by
// 1000 so the counter can never outpace the clock it was seeded from — that
// would take a sustained million events per second — while staying far inside
// Number.MAX_SAFE_INTEGER for the next couple of centuries.
let seq = Date.now() * 1000;

export function publish(sessionId, event) {
  // One stamp per publish, shared by both lines below: the same event must
  // carry the same seq on the per-session channel and on the multiplexed one.
  const s = ++seq;
  const set = channels.get(sessionId);
  if (set) {
    const line = `data: ${JSON.stringify({ ...event, seq: s })}\n\n`;
    for (const res of set) res.write(line);
  }
  if (everyone.size) {
    const line = `data: ${JSON.stringify({ ...event, sessionId, seq: s })}\n\n`;
    for (const res of everyone) res.write(line);
  }
  recordPublish(sessionId, event, s, (set ? set.size : 0) + everyone.size);
  return s;
}
