// Observable state container. Single source of truth for discrete app state
// (sessions, active session, model, busy). No DOM, no business logic.

export function createStore(initial) {
  let state = { ...initial };
  const listeners = new Set();
  return {
    get: () => state,
    set(patch) {
      const next = typeof patch === "function" ? patch(state) : patch;
      state = { ...state, ...next };
      listeners.forEach((l) => l(state));
    },
    subscribe(l) { listeners.add(l); return () => listeners.delete(l); },
  };
}
