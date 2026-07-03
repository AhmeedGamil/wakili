// Entry point: thin HTTP layer that wires the agent registry, the session
// repository, and the SSE hub together. All real logic lives in src/.
//
// Run: node server.mjs   then open the printed URL on your phone (same Wi-Fi).

import http from "node:http";
import fsp from "node:fs/promises";
import fsSync from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { spawn, exec } from "node:child_process";

import { config } from "./src/config.mjs";
import { tailscaleUrl, startCloudflare } from "./src/tunnel.mjs";
import { listFolders, isDir, createFolder } from "./src/folders.mjs";
import { qrTerminal } from "./src/qr.mjs";
import { getAgent, listAgents } from "./src/agents/registry.mjs";
import { commandsReady, modelsReady } from "./src/agents/claude.mjs";
import { sessionStore } from "./src/sessions/store.mjs";
import { subscribe, subscribeAll, publish } from "./src/sse.mjs";
import { createPermission, waitPermission, resolvePermission } from "./src/permissions.mjs";
import { lockScreen, screenOff, setKeepAwake, powerStatus, shutdownPower } from "./src/power.mjs";

const EDIT_TOOLS = new Set(["Write", "Edit", "MultiEdit", "NotebookEdit"]); // auto-approved under acceptEdits
const busy = new Set(); // session ids with an in-flight turn
const children = new Map(); // sessionId -> spawned agent child process (for stop/interrupt)

// Interrupt an agent. On Windows the agent is spawned via the shell (cmd.exe),
// so child.kill() only kills the cmd wrapper and leaves the real `claude`
// process (and its node subprocess) running — the turn would never stop. Kill
// the whole tree with taskkill /T instead. The child's "close" then fires,
// which runs onClose -> turn_end (unbusies the UI).
function killTree(child) {
  if (!child || child.killed) return;
  if (config.isWin && child.pid) {
    try { spawn("taskkill", ["/pid", String(child.pid), "/t", "/f"], { stdio: "ignore" }); return; }
    catch { /* fall through to a plain kill */ }
  }
  try { child.kill(); } catch { /* already gone */ }
}
const files = new Map(); // token -> { path, name } for downloadable files
const activeTurns = new Map(); // sessionId -> parts[] being assembled this turn

// Open permission/question cards awaiting an answer, kept per session so a
// stream that (re)opens — switching back to the chat, or a network reconnect —
// can replay them. Without this the card is published once and lost if nobody
// is listening at that moment, leaving the turn stalled until the timeout.
const pendingCards = new Map(); // sessionId -> Map<permId, gateway event>
function trackCard(sessionId, id, event) {
  if (!pendingCards.has(sessionId)) pendingCards.set(sessionId, new Map());
  pendingCards.get(sessionId).set(id, event);
}
function untrackCard(sessionId, id) {
  const m = pendingCards.get(sessionId);
  if (m) { m.delete(id); if (!m.size) pendingCards.delete(sessionId); }
  // Removes the card from any client still showing it (another tab, or a card
  // whose wait timed out). The answering client already dropped its own card.
  publish(sessionId, { type: "_gateway", subtype: "request_resolved", id });
}

// Tool output (Bash stdout, Read contents, …) is captured per turn so the UI can
// show what a command printed — not just the command. Capped so a runaway
// command can't bloat the session JSON on disk or freeze the phone's DOM.
const MAX_TOOL_OUTPUT_BYTES = 50_000;

// A tool_result's content is either a plain string or an array of content blocks
// ({type:"text",text}); normalise to a single string.
function toolResultText(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) return content.map((b) => (typeof b === "string" ? b : b?.text || "")).join("");
  return content == null ? "" : String(content);
}

// Truncate to a byte budget (UTF-8), leaving a marker so the cut is visible.
function capBytes(str, max) {
  const buf = Buffer.from(str, "utf8");
  if (buf.length <= max) return str;
  const kept = buf.subarray(0, max).toString("utf8");
  return kept + `\n… [truncated — ${buf.length} bytes total, showing first ${max}]`;
}

// Where the agent runs when a session has no folder of its own (used so the
// folder button can always show a real name instead of "Default folder").
const GATEWAY_CWD = process.cwd();
// `pending` = open permission/question cards awaiting the user, so the sidebar
// can flag background sessions that need attention.
const withMeta = (s) => ({ ...s, busy: busy.has(s.id), pending: pendingCards.get(s.id)?.size || 0, effectiveCwd: s.cwd || GATEWAY_CWD });

// The URLs this same gateway is reachable on, surfaced to the page so it can
// offer an in-page connection switcher. LAN + Tailscale are discovered at
// startup; Cloudflare is registered by cf-bridge.mjs (its quick-tunnel URL is
// random per launch, so the bridge POSTs it to /internal/cf-url on start).
const reachable = { lan: [], tailscale: null, cloudflare: null };

// Persistent registry of every file that's flowed through (uploads + agent-sent),
// so the sidebar can list them across all chats and downloads survive a restart.
const IMG_RE = /\.(png|jpe?g|gif|webp|svg|bmp|heic)$/i;
const FILES_LOG = path.join(config.runtimeDir, "files.json");
let filesLog = [];
try { filesLog = JSON.parse(fsSync.readFileSync(FILES_LOG, "utf8")); } catch { filesLog = []; }
// Re-arm download tokens from the saved registry so links keep working post-restart.
for (const f of filesLog) if (f.token && f.path) files.set(f.token, { path: f.path, name: f.name });
function saveFilesLog() { fsp.writeFile(FILES_LOG, JSON.stringify(filesLog)).catch(() => {}); }
function recordFile({ sessionId, source, filePath, name, caption }) {
  const token = crypto.randomUUID();
  files.set(token, { path: filePath, name });
  filesLog.push({ token, sessionId, source, name, caption: caption || "", image: IMG_RE.test(name), url: `/api/files/${token}` });
  saveFilesLog();
  return token;
}

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".pdf": "application/pdf",
  ".apk": "application/vnd.android.package-archive",
  ".zip": "application/zip",
  ".txt": "text/plain; charset=utf-8",
  ".json": "application/json",
};

const json = (res, code, obj) => {
  res.writeHead(code, { "Content-Type": "application/json" });
  res.end(JSON.stringify(obj));
};
const notFound = (res) => { res.writeHead(404); res.end("not found"); };

// Token gate. The phone sends it as a header; the EventSource (which can't set
// headers) and image <img src> send it as ?t=. Constant-time compare so the
// token can't be guessed by timing. Open paths (static assets, capability-URL
// file downloads) bypass this — see the handler.
function authed(req, url) {
  const sent = req.headers["x-auth-token"] || url.searchParams.get("t") || "";
  return strEq(sent, config.token);
}
// constant-time string compare (avoids leaking length-independent timing)
function strEq(x, y) {
  const a = Buffer.from(String(x ?? "")), b = Buffer.from(String(y ?? ""));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
async function readBody(req) {
  let b = "";
  for await (const c of req) b += c;
  return b ? JSON.parse(b) : {};
}

// --- one streaming turn: persist the user message, stream agent events to the
//     session's subscribers, then persist the assembled assistant reply. ---
async function runTurn(session, text, controls, attachments, agentId) {
  // switching agent on a session starts a fresh thread (resume ids are per-agent)
  if (agentId && agentId !== session.agentId) { session.agentId = agentId; session.resumeId = null; }
  const agent = getAgent(session.agentId) || getAgent("claude");
  busy.add(session.id);

  // Attachments are persisted with the message (name + display URL) so the chat
  // still shows them after switching sessions or reloading — not just live.
  const userMsg = { role: "user", text };
  if (attachments && attachments.length) {
    userMsg.attachments = attachments.map((a) => ({ name: a.name, url: a.url || "", image: IMG_RE.test(a.name || "") }));
  }
  session.messages.push(userMsg);
  if (session.title === "New chat" && (text || attachments?.length)) session.title = (text || attachments[0].name).slice(0, 48);
  if (controls) session.controls = controls;
  await sessionStore.save(session);

  publish(session.id, { type: "_gateway", subtype: "turn_start" });

  // Give the agent the uploaded files' paths so it can Read them (images/docs).
  let prompt = text;
  if (attachments && attachments.length) {
    const lines = attachments.map((a) => `- ${a.path}`).join("\n");
    prompt = `${text ? text + "\n\n" : ""}[The user attached these files; use the Read tool to view them if relevant:\n${lines}]`;
    // Register for the sidebar "Attached by you" list. Uploads that came through
    // /api/upload already carry a url (recorded there) — don't double-register.
    for (const a of attachments) if (!a.url) recordFile({ sessionId: session.id, source: "user", filePath: a.path, name: a.name });
  }

  // Assemble the turn as ordered parts so history (commands, thoughts) replays.
  const parts = [];
  activeTurns.set(session.id, parts);
  const pushText = (t) => { const l = parts[parts.length - 1]; if (l && l.type === "text") l.text += t; else parts.push({ type: "text", text: t }); };
  const pushThink = (t) => { const l = parts[parts.length - 1]; if (l && l.type === "thinking") l.text += t; else parts.push({ type: "thinking", text: t }); };

  const child = agent.run({
    text: prompt,
    controls: session.controls || {},
    resumeId: session.resumeId,
    sessionId: session.id,
    cwd: session.cwd || undefined, // undefined => adapter inherits the gateway's dir
    gatewayUrl: config.gatewayUrl,
    onEvent: (evt) => {
      const x = evt?.event;
      if (evt.type === "stream_event" && x?.type === "content_block_delta") {
        if (x.delta?.type === "text_delta") pushText(x.delta.text);
        else if (x.delta?.type === "thinking_delta") pushThink(x.delta.thinking);
      } else if (evt.type === "assistant" && evt.message) {
        // synthetic = slash-command output (whole, not streamed); persist its text
        if (evt.message.model === "<synthetic>") {
          for (const b of evt.message.content || []) if (b.type === "text") pushText(b.text);
        } else {
          // AskUserQuestion / ask_options render as their own interactive card, not a tool chip.
          const skip = new Set(["AskUserQuestion", "mcp__remoteagent__ask_options"]);
          // Keep the tool_use id so the matching tool_result (output) can attach to it.
          for (const b of evt.message.content || []) if (b.type === "tool_use" && !skip.has(b.name)) parts.push({ type: "tool", name: b.name, input: b.input, id: b.id });
        }
      } else if (evt.type === "user" && evt.message) {
        // Tool output comes back as tool_result blocks on a synthetic "user" turn.
        // Attach each to its tool part (by id) so it persists in history, and push
        // it live to subscribers so the output appears under the command card.
        for (const b of evt.message.content || []) {
          if (b.type !== "tool_result") continue;
          const output = capBytes(toolResultText(b.content), MAX_TOOL_OUTPUT_BYTES);
          const isError = !!b.is_error;
          const part = parts.find((p) => p.type === "tool" && p.id === b.tool_use_id);
          if (part) { part.output = output; part.isError = isError; }
          publish(session.id, { type: "_gateway", subtype: "tool_result", id: b.tool_use_id, output, isError });
        }
      } else if (evt.type === "result" && evt.session_id) {
        session.resumeId = evt.session_id;
      }
      publish(session.id, evt);
    },
    onError: (text) => publish(session.id, { type: "_gateway", subtype: "stderr", text }),
    onClose: async () => {
      activeTurns.delete(session.id);
      children.delete(session.id);
      if (parts.length) session.messages.push({ role: "assistant", parts });
      await sessionStore.save(session);
      busy.delete(session.id);
      publish(session.id, { type: "_gateway", subtype: "turn_end", title: session.title });
    },
  });
  children.set(session.id, child); // keep a handle so a turn can be interrupted
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://x");
    const p = url.pathname;
    const m = req.method;
    let mm;

    // ---- auth gate ----
    // Everything under /api and /internal needs the token, EXCEPT file downloads:
    // those carry their own unguessable per-file UUID (a capability) so <img src>
    // and download links work without smuggling the token into every URL.
    const guarded = (p.startsWith("/api/") || p.startsWith("/internal/")) && !p.startsWith("/api/files/");
    if (guarded && !authed(req, url)) return json(res, 401, { error: "unauthorized" });

    // ---- API ----
    if (p === "/api/agents" && m === "GET") return json(res, 200, listAgents());

    // Device controls: lock the laptop's screen, and keep the machine awake (so a
    // remote turn isn't suspended by idle-sleep). Keep-awake lets the display
    // still lock/turn off — it only blocks system sleep.
    if (p === "/api/power" && m === "GET") return json(res, 200, powerStatus());
    if (p === "/api/lock-screen" && m === "POST") return json(res, 200, await lockScreen());
    if (p === "/api/screen-off" && m === "POST") return json(res, 200, await screenOff());
    // Combined: lock first, then blank the display (locking can wake it, so order matters).
    if (p === "/api/lock-off" && m === "POST") {
      const lock = await lockScreen();
      const screen = await screenOff();
      return json(res, 200, { ok: lock.ok && screen.ok, lock, screen });
    }
    if (p === "/api/keep-awake" && m === "POST") {
      const b = await readBody(req);
      setKeepAwake(!!b.on);
      return json(res, 200, powerStatus());
    }

    // The connection switcher: every URL this same gateway answers on. Picking
    // one in the page just navigates there (same server, same sessions, same
    // token). Cloudflare appears only when the bridge has registered its URL.
    if (p === "/api/endpoints" && m === "GET") {
      const out = [];
      for (const ip of reachable.lan) out.push({ label: "Local network", host: `${ip}:${config.port}`, url: withToken(`http://${ip}:${config.port}`) });
      if (reachable.tailscale) out.push({ label: "Tailscale", host: new URL(reachable.tailscale).host, url: withToken(reachable.tailscale) });
      if (reachable.cloudflare) { const u = new URL(reachable.cloudflare); out.push({ label: "Cloudflare", host: u.host, url: `${reachable.cloudflare.replace(/\/$/, "")}/cf.html?t=${config.token}` }); }
      return json(res, 200, out);
    }

    // browse the laptop filesystem so the phone can pick a project folder
    if (p === "/api/folders" && m === "GET") return json(res, 200, await listFolders(url.searchParams.get("path")));
    // create a new sub-folder inside an existing one
    if (p === "/api/folders" && m === "POST") {
      const b = await readBody(req);
      try { return json(res, 200, { path: await createFolder(b.parent, b.name) }); }
      catch (e) { return json(res, 400, { error: String((e && e.message) || e) }); }
    }

    // every file that's flowed through (uploads + agent-sent), for the sidebar
    if (p === "/api/files" && m === "GET") return json(res, 200, filesLog);

    // Each summary carries a live `busy` flag so the phone can show per-session
    // status (badges) and enable/disable the composer for the active session only.
    if (p === "/api/sessions" && m === "GET") return json(res, 200, (await sessionStore.list()).map(withMeta));
    if (p === "/api/sessions" && m === "POST") {
      const b = await readBody(req);
      return json(res, 201, withMeta(await sessionStore.create({ agentId: b.agentId, model: b.model, cwd: b.cwd || null })));
    }

    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)$/))) {
      const id = mm[1];
      if (m === "GET") {
        const s = await sessionStore.get(id);
        return s ? json(res, 200, withMeta(s)) : notFound(res);
      }
      if (m === "PATCH") {
        const b = await readBody(req);
        const s = await sessionStore.get(id);
        if (!s) return notFound(res);
        if (typeof b.title === "string") s.title = b.title;
        if (typeof b.cwd === "string") {
          const dir = b.cwd.trim();
          if (dir && !(await isDir(dir))) return json(res, 400, { error: "not a directory" });
          // changing the working dir starts a fresh thread (resume ids are dir-bound)
          if ((s.cwd || "") !== dir) { s.cwd = dir || null; s.resumeId = null; }
        }
        await sessionStore.save(s);
        return json(res, 200, withMeta(s));
      }
      if (m === "DELETE") {
        await sessionStore.remove(id);
        return json(res, 200, { ok: true });
      }
    }

    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/messages$/)) && m === "POST") {
      const id = mm[1];
      const s = await sessionStore.get(id);
      if (!s) return notFound(res);
      if (busy.has(id)) return json(res, 409, { error: "busy" });
      const b = await readBody(req);
      const text = (b.text || "").trim();
      // A message can be attachments-only (an image with nothing typed).
      if (!text && !(b.attachments && b.attachments.length)) return json(res, 400, { error: "empty" });
      json(res, 202, { ok: true });
      runTurn(s, text, b.controls, b.attachments, b.agentId);
      return;
    }

    // Direct shell command ("!cmd" from the chat box): run it in the session's
    // folder and return the combined output. No agent/LLM — a plain terminal on
    // the laptop, token-gated like everything else.
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/exec$/)) && m === "POST") {
      const id = mm[1];
      const s = await sessionStore.get(id);
      if (!s) return notFound(res);
      const b = await readBody(req);
      const command = (b.command || "").trim();
      if (!command) return json(res, 400, { error: "empty" });
      exec(command, { cwd: s.cwd || GATEWAY_CWD, timeout: 60000, maxBuffer: 5 * 1024 * 1024, windowsHide: true }, (err, stdout, stderr) => {
        const output = capBytes((stdout || "") + (stderr || ""), MAX_TOOL_OUTPUT_BYTES);
        json(res, 200, { ok: !err, code: err && typeof err.code === "number" ? err.code : (err ? 1 : 0), output });
      });
      return;
    }

    // Terminal page: a stateful shell for the phone. Runs `command` in the caller-
    // supplied `cwd` (falling back to the session's folder), and echoes back the
    // cwd so the phone can display it. A standalone `cd <dir>` doesn't spawn a
    // shell — it resolves + validates the target and returns the new cwd, so the
    // phone can track the current directory as the user moves around (each exec is
    // its own process, so a real `cd` wouldn't otherwise persist). Not saved as
    // chat; command history lives on the phone.
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/term$/)) && m === "POST") {
      const id = mm[1];
      const s = await sessionStore.get(id);
      if (!s) return notFound(res);
      const b = await readBody(req);
      const command = (b.command || "").trim();
      const base = (typeof b.cwd === "string" && b.cwd.trim()) || s.cwd || GATEWAY_CWD;
      if (!command) return json(res, 400, { error: "empty" });
      const cd = /^cd(?:\s+([\s\S]+))?$/.exec(command);
      if (cd) {
        const arg = (cd[1] || "").trim().replace(/^["']|["']$/g, "");
        let target;
        if (!arg || arg === "~") target = os.homedir();
        else if (arg.startsWith("~/") || arg.startsWith("~\\")) target = path.join(os.homedir(), arg.slice(2));
        else target = path.resolve(base, arg);
        if (await isDir(target)) return json(res, 200, { ok: true, cwd: path.resolve(target), output: "" });
        return json(res, 200, { ok: false, cwd: base, output: `cd: no such directory: ${arg || "~"}` });
      }
      exec(command, { cwd: base, timeout: 60000, maxBuffer: 5 * 1024 * 1024, windowsHide: true }, (err, stdout, stderr) => {
        const output = capBytes((stdout || "") + (stderr || ""), MAX_TOOL_OUTPUT_BYTES);
        json(res, 200, { ok: !err, code: err && typeof err.code === "number" ? err.code : (err ? 1 : 0), output, cwd: base });
      });
      return;
    }

    // interrupt: kill the running agent for this session. The child's onClose then
    // persists whatever streamed so far and emits turn_end (so the UI unbusies).
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/stop$/)) && m === "POST") {
      const id = mm[1];
      const child = children.get(id);
      if (child) {
        publish(id, { type: "_gateway", subtype: "stopped" });
        killTree(child);
      }
      return json(res, 200, { ok: true, stopped: !!child });
    }

    // phone uploads a file (base64) -> saved to disk; path goes to the agent
    if (p === "/api/upload" && m === "POST") {
      const b = await readBody(req);
      if (!b.name || !b.dataBase64) return json(res, 400, { error: "bad upload" });
      await fsp.mkdir(config.uploadsDir, { recursive: true });
      const safe = path.basename(b.name).replace(/[^\w.\- ]/g, "_");
      const dest = path.join(config.uploadsDir, `${crypto.randomUUID()}-${safe}`);
      await fsp.writeFile(dest, Buffer.from(b.dataBase64, "base64"));
      // Register right away so the phone gets a capability URL for displaying the
      // attachment in the chat (image thumbnails / download links) and the sidebar
      // lists it. runTurn skips re-recording attachments that carry a url.
      const token = recordFile({ sessionId: b.sessionId || "", source: "user", filePath: dest, name: safe });
      return json(res, 200, { path: dest, name: safe, url: `/api/files/${token}` });
    }

    // permission decision requested by the agent's hook (a child process).
    // The gateway short-circuits to "allow" when the session's mode or its
    // remembered approvals already cover this tool; otherwise it asks the phone.
    // cf-bridge.mjs registers its live quick-tunnel URL here on startup, so the
    // page's connection switcher can offer Cloudflare even though the URL is
    // random each launch. Pass the base (https://<name>.trycloudflare.com).
    if (p === "/internal/cf-url" && m === "POST") {
      const b = await readBody(req);
      reachable.cloudflare = (b && b.url) ? String(b.url) : null;
      return json(res, 200, { ok: true });
    }

    if (p === "/internal/permission" && m === "POST") {
      const b = await readBody(req);

      // AskUserQuestion is not a yes/no permission: it needs a real answer. Forward
      // the question(s) to the phone, wait for the choice, then hand it back as the
      // hook's "deny" reason — a denied tool's reason is surfaced to the model, so
      // the answer reaches the agent in-turn. Auto-approve modes don't apply here.
      if (b.tool === "AskUserQuestion") {
        const id = createPermission();
        const ev = { type: "_gateway", subtype: "question_request", id, input: b.input };
        trackCard(b.sessionId, id, ev);
        publish(b.sessionId, ev);
        const answer = await waitPermission(id, 120000);
        untrackCard(b.sessionId, id);
        const reason = !answer || answer === "deny"
          ? "The user did not answer the question in time. Make a reasonable assumption or ask again next turn."
          : `The user answered your question(s):\n${answer}\n\nUse these answers to continue — do not call AskUserQuestion again for this.`;
        return json(res, 200, { decision: "deny", reason });
      }

      const s = await sessionStore.get(b.sessionId);
      const mode = s?.controls?.permissionMode || "default";
      const allowed = s?.allowedTools || [];
      if (mode === "bypassPermissions" || (mode === "acceptEdits" && EDIT_TOOLS.has(b.tool)) || allowed.includes(b.tool)) {
        // Auto-approved without a card. The UI suppresses gated tools' chips
        // (expecting a permission card), so publish a chip here — otherwise the
        // action would be invisible in auto-accept / bypass / remembered modes.
        // Find the matching tool_use part (most recent, same name+input) so the
        // chip carries the tool id — letting its output attach to the right card.
        const live = activeTurns.get(b.sessionId) || [];
        const inp = JSON.stringify(b.input);
        const part = [...live].reverse().find((p) => p.type === "tool" && p.name === b.tool && JSON.stringify(p.input) === inp);
        publish(b.sessionId, { type: "_gateway", subtype: "tool", tool: b.tool, input: b.input, id: part?.id });
        return json(res, 200, { decision: "allow" });
      }
      const id = createPermission();
      const ev = { type: "_gateway", subtype: "permission_request", id, tool: b.tool, input: b.input };
      trackCard(b.sessionId, id, ev);
      publish(b.sessionId, ev);
      const decision = await waitPermission(id, 120000);
      untrackCard(b.sessionId, id);
      return json(res, 200, { decision });
    }

    // ask_options (MCP tool): show the phone a multiple-choice card and BLOCK until
    // the user answers. Reuses the pending registry; the phone's answer comes back
    // via /api/sessions/:id/permission with { id, answer } (resolvePermission).
    if (p === "/internal/question" && m === "POST") {
      const b = await readBody(req);
      const id = createPermission();
      const ev = { type: "_gateway", subtype: "question_request", id, input: { questions: b.questions || [] } };
      trackCard(b.sessionId, id, ev);
      publish(b.sessionId, ev);
      const answer = await waitPermission(id, 300000); // up to 5 min for the user to choose
      untrackCard(b.sessionId, id);
      return json(res, 200, { answer: (!answer || answer === "deny") ? "" : answer });
    }

    // permission answer from the phone. "allow_session" remembers the tool so the
    // gateway auto-approves it for the rest of this session (the third card button).
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/permission$/)) && m === "POST") {
      const b = await readBody(req);
      // An AskUserQuestion reply carries the chosen answer text; resolve the parked
      // request with it verbatim (the gateway wraps it into the hook's reason).
      if (typeof b.answer === "string") {
        resolvePermission(b.id, b.answer);
        return json(res, 200, { ok: true });
      }
      if (b.decision === "allow_session" && b.tool) {
        const s = await sessionStore.get(mm[1]);
        if (s) { s.allowedTools = [...new Set([...(s.allowedTools || []), b.tool])]; await sessionStore.save(s); }
      }
      resolvePermission(b.id, b.decision === "deny" ? "deny" : "allow");
      return json(res, 200, { ok: true });
    }

    // agent delivers a file (via the send_to_user MCP tool) -> register + notify phone
    if (p === "/internal/file" && m === "POST") {
      const b = await readBody(req);
      if (!b.path) return json(res, 400, { error: "no path" });
      const name = path.basename(b.path);
      // register in the persistent files log (sidebar "Sent by the agent" + survives restart)
      const token = recordFile({ sessionId: b.sessionId, source: "agent", filePath: b.path, name, caption: b.caption });
      const turn = activeTurns.get(b.sessionId);
      if (turn) turn.push({ type: "file", name, caption: b.caption || "" });
      publish(b.sessionId, {
        type: "_gateway", subtype: "file",
        token, name, caption: b.caption || "", url: `/api/files/${token}`,
      });
      return json(res, 200, { ok: true });
    }

    // phone downloads/views a delivered file
    if ((mm = p.match(/^\/api\/files\/([\w-]+)$/)) && m === "GET") {
      const f = files.get(mm[1]);
      if (!f) return notFound(res);
      try {
        const data = await fsp.readFile(f.path);
        res.writeHead(200, {
          "Content-Type": MIME[path.extname(f.path).toLowerCase()] || "application/octet-stream",
          "Content-Disposition": `inline; filename="${f.name}"`,
        });
        return res.end(data);
      } catch { return notFound(res); }
    }

    // Multiplexed live stream: ONE connection carrying every session's events,
    // each tagged with its sessionId. The client routes them (active session
    // renders live; background sessions update badges/caches). Session-specific
    // state (in-progress turn, pending cards) is delivered via /resync below.
    if (p === "/api/stream" && m === "GET") {
      res.writeHead(200, {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
        "X-Accel-Buffering": "no",
      });
      res.flushHeaders?.();
      res.write(":" + " ".repeat(2048) + "\n\n");
      res.write(`data: ${JSON.stringify({ type: "_gateway", subtype: "connected" })}\n\n`);
      const unsub = subscribeAll(res);
      const ping = setInterval(() => res.write(": ping\n\n"), 15000);
      req.on("close", () => { clearInterval(ping); unsub(); });
      return;
    }

    // Publish a session's live state (in-progress turn parts + pending cards)
    // INTO the multiplexed stream, so it arrives in order with subsequent live
    // events — the client ignores that session's content events until this
    // snapshot lands, then processes only what follows it (no gaps, no dupes).
    // `client` tags the snapshot so other tabs can ignore someone else's resync.
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/resync$/)) && m === "POST") {
      const id = mm[1];
      const b = await readBody(req);
      publish(id, {
        type: "_gateway", subtype: "snapshot", client: (b && b.client) || "",
        parts: activeTurns.get(id) || [], busy: busy.has(id),
        pending: [...(pendingCards.get(id)?.values() || [])],
      });
      return json(res, 200, { ok: true });
    }

    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/stream$/)) && m === "GET") {
      const id = mm[1];
      res.writeHead(200, {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
        // Tell proxies (Cloudflare/nginx) NOT to buffer — otherwise the live stream
        // is held back and the phone sees nothing until the connection closes.
        "X-Accel-Buffering": "no",
      });
      res.flushHeaders?.();
      // A ~2KB comment up front forces buffering proxies to flush immediately so
      // events start flowing right away over a tunnel (harmless on the LAN).
      res.write(":" + " ".repeat(2048) + "\n\n");
      res.write(`data: ${JSON.stringify({ type: "_gateway", subtype: "connected" })}\n\n`);
      // Replay the in-progress turn (if any) so reopening a working session — or
      // switching back to it — restores the text/tools streamed while away. Sent
      // synchronously before subscribing, so it always precedes new live events.
      const live = activeTurns.get(id);
      if (busy.has(id) || (live && live.length)) {
        res.write(`data: ${JSON.stringify({ type: "_gateway", subtype: "snapshot", parts: live || [], busy: busy.has(id) })}\n\n`);
      }
      // Replay any permission/question cards still awaiting an answer (they were
      // published live, but this subscriber may have missed them — a chat switch
      // or a reconnect). Sent after the snapshot so cards land on rendered history.
      for (const ev of pendingCards.get(id)?.values() || []) res.write(`data: ${JSON.stringify(ev)}\n\n`);
      const unsub = subscribe(id, res);
      // Frequent comments keep the connection warm through proxy idle timeouts.
      const ping = setInterval(() => res.write(": ping\n\n"), 15000);
      req.on("close", () => { clearInterval(ping); unsub(); });
      return;
    }

    // ---- static files ----
    if (m === "GET") {
      const rel = p === "/" ? "index.html" : p.replace(/^\/+/, "");
      const file = path.normalize(path.join(config.publicDir, rel));
      if (!file.startsWith(config.publicDir)) return notFound(res); // traversal guard
      try {
        const data = await fsp.readFile(file);
        // No caching for app assets: this is an actively-developed LAN tool, and
        // stale cached CSS/JS after an edit caused real confusion. Always serve fresh.
        res.writeHead(200, {
          "Content-Type": MIME[path.extname(file)] || "application/octet-stream",
          "Cache-Control": "no-store, must-revalidate",
        });
        return res.end(data);
      } catch { return notFound(res); }
    }

    notFound(res);
  } catch (e) {
    json(res, 500, { error: String((e && e.message) || e) });
  }
});

await Promise.all([commandsReady, modelsReady]); // discover Claude's real slash commands + latest model labels before serving the manifest

// --tunnel cloudflare | tailscale | both | none   (default: tailscale if present)
function parseTunnel() {
  const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i++) {
    let v = null;
    if (a[i] === "--tunnel") v = a[i + 1];
    else if (a[i].startsWith("--tunnel=")) v = a[i].slice("--tunnel=".length);
    else if (a[i] === "--cloudflare") v = "cloudflare";
    else if (a[i] === "--tailscale") v = "tailscale";
    if (v) return String(v).toLowerCase();
  }
  return "auto";
}

// Append the token so opening the link on the phone just works (stored after).
const withToken = (base) => `${base}/?t=${config.token}`;

let cloudflaredChild = null;
const qrTargets = []; // {label, url} to render as QR codes after the text URLs
async function announceTunnel(mode) {
  if (mode === "none") return;

  if (mode === "tailscale" || mode === "auto" || mode === "both") {
    const ts = await tailscaleUrl(config.port);
    if (ts) { reachable.tailscale = ts; console.log(`  Tunnel:  ${withToken(ts)}   (Tailscale — private)`); qrTargets.push({ label: "Scan to connect from anywhere (Tailscale)", url: withToken(ts) }); }
    else if (mode === "tailscale") console.log("  Tunnel:  Tailscale not detected (is `tailscale` installed & up?).");
  }

  if (mode === "cloudflare" || mode === "both") {
    console.log("  Tunnel:  starting Cloudflare quick tunnel…");
    try {
      const { url, child } = await startCloudflare(config.port);
      cloudflaredChild = child;
      console.log(`  Tunnel:  ${withToken(url)}   (Cloudflare — PUBLIC, token-gated)`);
      qrTargets.push({ label: "Scan to connect from anywhere (Cloudflare)", url: withToken(url) });
    } catch (e) {
      console.log(`  Tunnel:  Cloudflare failed — ${e.message}`);
    }
  }
}

function shutdown() {
  if (cloudflaredChild) { try { cloudflaredChild.kill(); } catch { /* already gone */ } }
  shutdownPower(); // release the keep-awake lock so the machine can sleep again
  process.exit(0);
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

server.listen(config.port, async () => {
  const ips = Object.values(os.networkInterfaces())
    .flat()
    .filter((n) => n && n.family === "IPv4" && !n.internal)
    .map((n) => n.address);
  reachable.lan = ips; // remember LAN addresses for the page's connection switcher
  // Keep the machine awake by default so remote turns aren't suspended by idle
  // sleep while you're away (the screen can still lock). Set REMOTE_AGENT_KEEP_AWAKE=0
  // to opt out; toggle it live from the app.
  if (process.env.REMOTE_AGENT_KEEP_AWAKE !== "0") setKeepAwake(true);
  console.log(`\n  Remote Agent gateway running on :${config.port}\n`);
  console.log(`  Laptop:  http://localhost:${config.port}/?t=${config.token}`);
  const phoneUrl = ips.length ? withToken(`http://${ips[0]}:${config.port}`) : "";
  for (const ip of ips) console.log(`  Phone:   ${withToken(`http://${ip}:${config.port}`)}   (same Wi-Fi)`);
  if (phoneUrl) qrTargets.push({ label: "Scan to open on your phone (same Wi-Fi)", url: phoneUrl });
  await announceTunnel(parseTunnel()); // may append tunnel QR targets

  // Scannable QR per URL (token included) so there's nothing to type. --no-qr suppresses.
  if (!process.argv.includes("--no-qr")) {
    for (const t of qrTargets) {
      console.log(`\n  ${t.label}:\n`);
      try { console.log(qrTerminal(t.url).replace(/^/gm, "  ")); } catch { /* url too long; skip */ }
    }
  }
  console.log(`  The ?t= token authorizes the device; it's saved after the first open.`);
  console.log(`  Ctrl+C to stop.\n`);
});
