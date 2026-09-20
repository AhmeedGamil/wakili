// Data-access layer. The only module that knows the backend's HTTP shape.

import { KEYS, getPref, setPref } from "../core/prefs.js";

// Access token. Captured once from the ?t= the laptop prints (so opening the
// tunnel link "just works"), then persisted so later visits need no token in the
// URL. Sent as a header on every call; the URL is scrubbed so it isn't bookmarked.
function loadToken() {
  try {
    const u = new URL(location.href);
    const t = u.searchParams.get("t");
    if (t) {
      setPref(KEYS.token, t);
      u.searchParams.delete("t");
      history.replaceState(null, "", u.pathname + u.search + u.hash);
      return t;
    }
  } catch { /* fall through to the stored token */ }
  return getPref(KEYS.token);
}
let TOKEN = loadToken();
const withT = (url) => url + (url.includes("?") ? "&" : "?") + "t=" + encodeURIComponent(TOKEN);

// On a dead/slow connection a bare fetch can hang for minutes with no feedback,
// so every call aborts after `timeout` ms and rejects — callers surface the
// failure (toast/retry) instead of appearing frozen. Long-running endpoints
// (shell commands, uploads) pass a higher budget.
async function call(method, url, body, timeout = 15000) {
  const headers = { "x-auth-token": TOKEN };
  if (body) headers["Content-Type"] = "application/json";
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  let res;
  try {
    res = await fetch(url, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      signal: ctrl.signal,
    });
  } finally { clearTimeout(timer); }
  if (res.status === 401) throw new Error("unauthorized — open the link with its ?t= token again");
  if (!res.ok && res.status !== 409) throw new Error(`${method} ${url} -> ${res.status}`);
  return res.status === 204 ? null : res.json().catch(() => null);
}

// The file's bytes ARE the request body; its name rides in a header, percent-
// encoded because header values are ASCII-only. The browser streams the Blob
// from disk, so a 40MB pick costs nothing here — the old shape read the whole
// file into a base64 string and wrapped THAT in JSON, which on a phone meant
// several times the file's size in memory before a single byte left.
//
// XHR, not fetch: only XHR reports upload progress and can be aborted mid-flight.
function uploadBinary(name, blob, sessionId, onProgress) {
  const xhr = new XMLHttpRequest();
  const promise = new Promise((resolve, reject) => {
    xhr.open("POST", "/api/upload");
    xhr.setRequestHeader("x-auth-token", TOKEN);
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.setRequestHeader("X-File-Name", encodeURIComponent(name || "file"));
    xhr.setRequestHeader("X-Session-Id", sessionId || "");
    xhr.timeout = 120000;
    xhr.upload.onprogress = (e) => { if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total); };
    xhr.onload = () => {
      if (xhr.status === 200) { try { resolve(JSON.parse(xhr.responseText)); } catch { reject(new Error("bad upload response")); } }
      else reject(new Error(`upload -> ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error("upload failed"));
    xhr.ontimeout = () => reject(new Error("upload timed out"));
    xhr.onabort = () => reject(new Error("upload cancelled"));
    xhr.send(blob);
  });
  return { promise, abort: () => { try { xhr.abort(); } catch { /* not started */ } } };
}

export const api = {
  hasToken: () => !!TOKEN,
  agents: () => call("GET", "/api/agents"),
  endpoints: () => call("GET", "/api/endpoints"),
  host: () => call("GET", "/api/host"),
  power: () => call("GET", "/api/power"),
  lockScreen: () => call("POST", "/api/lock-screen", {}),
  screenOff: () => call("POST", "/api/screen-off", {}),
  shutdown: () => call("POST", "/api/shutdown", {}),
  keepAwake: (on) => call("POST", "/api/keep-awake", { on }),
  autostart: () => call("GET", "/api/autostart"),
  setAutostart: (on) => call("POST", "/api/autostart", { on }),
  listSessions: () => call("GET", "/api/sessions"),
  createSession: (body) => call("POST", "/api/sessions", body || {}),
  // opts: { since, after } — conditional/delta fetch. With `since` the server
  // answers { unchanged: true } when nothing moved; with `after` it sends only
  // the messages past that index (flagged { delta: true }).
  getSession: (id, opts) => {
    const q = new URLSearchParams();
    if (opts && opts.since != null) q.set("since", opts.since);
    if (opts && opts.after != null) q.set("after", opts.after);
    const qs = q.toString();
    return call("GET", `/api/sessions/${id}${qs ? "?" + qs : ""}`);
  },
  renameSession: (id, title) => call("PATCH", `/api/sessions/${id}`, { title }),
  setCwd: (id, cwd) => call("PATCH", `/api/sessions/${id}`, { cwd }),
  // "Auto continue" — stored on the gateway (which re-drives a limit-killed
  // turn once quota returns), so it works with no client awake. ON by default:
  // only an explicit false is ever sent.
  setAutoContinue: (id, on) => call("PATCH", `/api/sessions/${id}`, { autoContinue: !!on }),
  // One-shot scheduled task at epoch-millis `at`; also flips autoContinue on.
  // `attachments` are uploaded {name, path, url} files that ride with the job.
  schedule: (id, text, at, attachments) => call("POST", `/api/sessions/${id}/schedule`, { text, at, attachments }),
  scheduledJobs: (id) => call("GET", `/api/sessions/${id}/schedule`),
  // Every pending task across every chat (the Scheduled tasks page). Each job
  // carries its sessionTitle so the list can say WHERE it will run.
  allScheduledJobs: () => call("GET", "/api/schedule"),
  // Edit a pending task's text and/or time. Attachments stay as scheduled.
  updateScheduled: (jobId, text, at) => call("PATCH", `/api/schedule/${jobId}`, { text, at }),
  cancelScheduled: (jobId) => call("DELETE", `/api/schedule/${jobId}`),
  // Persist the picker state (agent + controls) on change, not just at send.
  saveControls: (id, agentId, controls) => call("PATCH", `/api/sessions/${id}`, { agentId, controls }),
  folders: (path) => call("GET", `/api/folders?path=${encodeURIComponent(path || "")}`),
  createFolder: (parent, name) => call("POST", "/api/folders", { parent, name }),
  // Known project folders — one list for every agent, held by the gateway so
  // all devices see the same projects.
  projects: () => call("GET", "/api/projects"),
  addProject: (path) => call("POST", "/api/projects", { path }),
  forgetProject: (path) => call("DELETE", "/api/projects", { path }),
  files: () => call("GET", "/api/files"),
  deleteFile: (token) => call("DELETE", `/api/files/${token}`),
  deleteSession: (id) => call("DELETE", `/api/sessions/${id}`),
  // Continue with a different agent: exports the transcript to a handoff file
  // and returns the fresh session for the new agent plus the file to attach.
  handoff: (id, agentId) => call("POST", `/api/sessions/${id}/handoff`, { agentId }),
  // Switch this chat's agent in place: same chat, same history, transcript
  // exported so the new agent (which starts a fresh native thread) has context.
  switchAgent: (id, agentId) => call("POST", `/api/sessions/${id}/agent`, { agentId }),
  send: (id, text, controls, attachments, agentId) =>
    call("POST", `/api/sessions/${id}/messages`, { text, controls, attachments, agentId }),
  // Shell commands can legitimately run up to the server's 60s cap — give them room.
  exec: (id, command) => call("POST", `/api/sessions/${id}/exec`, { command }, 90000),
  // Terminal page: stateful shell — runs in `cwd`, returns { ok, output, cwd }.
  term: (id, command, cwd) => call("POST", `/api/sessions/${id}/term`, { command, cwd }, 90000),
  stop: (id) => call("POST", `/api/sessions/${id}/stop`),
  // Uploads move real bytes over possibly-slow links — the most generous budget.
  upload: (name, blob, sessionId) => uploadBinary(name, blob, sessionId).promise,
  // Same upload, with live progress + cancel. onProgress gets 0..1; abort()
  // cancels the transfer.
  uploadProgress: (name, blob, sessionId, onProgress) => uploadBinary(name, blob, sessionId, onProgress),
  deleteUpload: (path) => call("POST", "/api/upload/delete", { path }),
  answerPermission: (id, requestId, decision, tool) =>
    call("POST", `/api/sessions/${id}/permission`, { id: requestId, decision, tool }),
  // AskUserQuestion answer: same endpoint, carries the chosen answer text.
  answerQuestion: (id, requestId, answer) =>
    call("POST", `/api/sessions/${id}/permission`, { id: requestId, answer }),
  // ONE multiplexed stream for all sessions (events tagged with sessionId).
  stream: () => new EventSource(withT("/api/stream")),
  // Ask the server to publish a session's live state (turn parts + pending
  // cards) into the stream; `client` lets other tabs ignore our snapshot.
  resync: (id, client) => call("POST", `/api/sessions/${id}/resync`, { client }),
  // Upload this client's stream flight recorder (see core/recorder.js) so the
  // gateway's own recorder holds both sides of the same turn. Diagnostics only:
  // the gateway drops it unless WAKILI_DEBUG_STREAM=1.
  debugReport: (report) => call("POST", "/api/debug/report", report),
};
