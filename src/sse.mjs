// Observer / pub-sub hub. Each session is a channel; SSE responses subscribe to
// their session and receive every event published for it. Keeps the agent layer
// and the transport (HTTP/SSE) decoupled — the runner just calls publish().

const channels = new Map(); // sessionId -> Set<ServerResponse>

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

export function publish(sessionId, event) {
  const set = channels.get(sessionId);
  if (!set) return;
  const line = `data: ${JSON.stringify(event)}\n\n`;
  for (const res of set) res.write(line);
}
