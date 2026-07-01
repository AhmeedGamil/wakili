// Pending-permission registry. When the agent's PreToolUse hook asks for a
// decision, we park a promise here keyed by id; the phone's answer (or a
// timeout) resolves it. Decouples the hook's HTTP call from the phone's reply.
//
// The parked value is opaque: for gated tools it's "allow"/"deny"; for an
// AskUserQuestion prompt it's the user's answer text. Callers interpret it.

const pending = new Map(); // id -> resolve fn
let seq = 0;

export function createPermission() {
  return `perm_${Date.now()}_${++seq}`;
}

export function waitPermission(id, timeoutMs = 120000) {
  return new Promise((resolve) => {
    const done = (decision) => {
      if (!pending.has(id)) return;
      pending.delete(id);
      clearTimeout(timer);
      resolve(decision);
    };
    const timer = setTimeout(() => done("deny"), timeoutMs);
    pending.set(id, done);
  });
}

// Resolve a parked request with an opaque value. The caller decides what it
// means (allow/deny for tools, answer text for questions); kept generic so the
// same registry serves both flows.
export function resolvePermission(id, value) {
  const done = pending.get(id);
  if (done) done(value);
}
