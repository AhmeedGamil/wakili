// Observer / pub-sub hub. Each session is a channel; SSE responses subscribe to
// their session and receive every event published for it. Keeps the agent layer
// and the transport (HTTP/SSE) decoupled — the runner just calls publish().

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

export function publish(sessionId, event) {
  const set = channels.get(sessionId);
  if (set) {
    const line = `data: ${JSON.stringify(event)}\n\n`;
    for (const res of set) res.write(line);
  }
  if (everyone.size) {
    const line = `data: ${JSON.stringify({ ...event, sessionId })}\n\n`;
    for (const res of everyone) res.write(line);
  }
}
