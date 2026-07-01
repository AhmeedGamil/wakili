// Data-access layer. The only module that knows the backend's HTTP shape.

// Access token. Captured once from the ?t= the laptop prints (so opening the
// tunnel link "just works"), then persisted so later visits need no token in the
// URL. Sent as a header on every call; the URL is scrubbed so it isn't bookmarked.
const TOKEN_KEY = "remoteAgentToken";
function loadToken() {
  try {
    const u = new URL(location.href);
    const t = u.searchParams.get("t");
    if (t) {
      localStorage.setItem(TOKEN_KEY, t);
      u.searchParams.delete("t");
      history.replaceState(null, "", u.pathname + u.search + u.hash);
      return t;
    }
    return localStorage.getItem(TOKEN_KEY) || "";
  } catch { return ""; }
}
let TOKEN = loadToken();
const withT = (url) => url + (url.includes("?") ? "&" : "?") + "t=" + encodeURIComponent(TOKEN);

async function call(method, url, body) {
  const headers = { "x-auth-token": TOKEN };
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) throw new Error("unauthorized — open the link with its ?t= token again");
  if (!res.ok && res.status !== 409) throw new Error(`${method} ${url} -> ${res.status}`);
  return res.status === 204 ? null : res.json().catch(() => null);
}

export const api = {
  hasToken: () => !!TOKEN,
  agents: () => call("GET", "/api/agents"),
  endpoints: () => call("GET", "/api/endpoints"),
  power: () => call("GET", "/api/power"),
  lockScreen: () => call("POST", "/api/lock-screen", {}),
  screenOff: () => call("POST", "/api/screen-off", {}),
  keepAwake: (on) => call("POST", "/api/keep-awake", { on }),
  listSessions: () => call("GET", "/api/sessions"),
  createSession: (body) => call("POST", "/api/sessions", body || {}),
  getSession: (id) => call("GET", `/api/sessions/${id}`),
  renameSession: (id, title) => call("PATCH", `/api/sessions/${id}`, { title }),
  setCwd: (id, cwd) => call("PATCH", `/api/sessions/${id}`, { cwd }),
  folders: (path) => call("GET", `/api/folders?path=${encodeURIComponent(path || "")}`),
  createFolder: (parent, name) => call("POST", "/api/folders", { parent, name }),
  files: () => call("GET", "/api/files"),
  deleteSession: (id) => call("DELETE", `/api/sessions/${id}`),
  send: (id, text, controls, attachments, agentId) =>
    call("POST", `/api/sessions/${id}/messages`, { text, controls, attachments, agentId }),
  exec: (id, command) => call("POST", `/api/sessions/${id}/exec`, { command }),
  // Terminal page: stateful shell — runs in `cwd`, returns { ok, output, cwd }.
  term: (id, command, cwd) => call("POST", `/api/sessions/${id}/term`, { command, cwd }),
  stop: (id) => call("POST", `/api/sessions/${id}/stop`),
  upload: (name, dataBase64) => call("POST", "/api/upload", { name, dataBase64 }),
  answerPermission: (id, requestId, decision, tool) =>
    call("POST", `/api/sessions/${id}/permission`, { id: requestId, decision, tool }),
  // AskUserQuestion answer: same endpoint, carries the chosen answer text.
  answerQuestion: (id, requestId, answer) =>
    call("POST", `/api/sessions/${id}/permission`, { id: requestId, answer }),
  stream: (id) => new EventSource(withT(`/api/sessions/${id}/stream`)),
};
