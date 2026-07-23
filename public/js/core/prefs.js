// Client-side preference storage: one safe layer over localStorage (private
// mode / disabled storage throws — every access is wrapped) plus the single
// registry of storage keys, so no key literal is duplicated across modules.

export const KEYS = {
  token: "remoteAgentToken",        // access token captured from ?t= (api.js)
  permMode: "ra-perm-mode",         // last-chosen permission mode, global default for new chats
  lastConfig: "ra-last-config",     // last agent + controls, applied to new chats
  theme: "ra-theme",                // "dark" | "light"
  accent: "ra-accent",              // accent hex override ("" = theme default)
  markdown: "ra-markdown",          // "1" = format markdown, "0" = raw text
  sessionView: "ra-session-view",   // sidebar grouping: "project" | "all"
  sessionModel: "ra-session-model", // "1" = show each session's model in the list
  sideGroups: "ra-side-groups",     // collapsed sidebar project groups (JSON array of cwds)
  termHistory: "ra-term-history",   // terminal command history (JSON array)
  resumeSid: "ra-resume-sid",       // sessionStorage: chat to reopen after a reload
};

export function getPref(key, fallback = "") {
  try { const v = localStorage.getItem(key); return v == null ? fallback : v; }
  catch { return fallback; }
}

// A null/undefined value clears the entry, so "set or remove" is one call.
export function setPref(key, value) {
  try { value == null ? localStorage.removeItem(key) : localStorage.setItem(key, value); }
  catch { /* private mode */ }
}

export function getJSON(key, fallback = null) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; }
  catch { return fallback; }
}

export function setJSON(key, value) { setPref(key, JSON.stringify(value)); }

// sessionStorage variants (per-tab). take* reads AND clears — for one-shot
// markers like the reload-resume session id.
export function putSession(key, value) {
  try { sessionStorage.setItem(key, value); } catch { /* private mode */ }
}
export function takeSession(key) {
  try { const v = sessionStorage.getItem(key); sessionStorage.removeItem(key); return v; }
  catch { return null; }
}
