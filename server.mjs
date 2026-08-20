#!/usr/bin/env node
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
import zlib from "node:zlib";

import { config, ROOT } from "./src/config.mjs";
import { tailscaleUrl, startCloudflare } from "./src/tunnel.mjs";
import { listFolders, isDir, createFolder } from "./src/folders.mjs";
import { listProjects, rememberProject, forgetProject, backfillProjects } from "./src/projects.mjs";
import { qrTerminal } from "./src/qr.mjs";
import { getAgent, listAgents } from "./src/agents/registry.mjs";
import { commandsReady, modelsReady, closeWarmClaude } from "./src/agents/claude.mjs";
import { closeWarmSdk } from "./src/agents/claude-sdk.mjs";
import { closeWarmCodex } from "./src/agents/codex.mjs";
import { sessionStore, onSessionSave } from "./src/sessions/store.mjs";
import { subscribe, subscribeAll, publish } from "./src/sse.mjs";
import { record as recordDebug, recordClientReport, dump as debugDump, recorderEnabled } from "./src/recorder.mjs";
import { createPermission, waitPermission, resolvePermission } from "./src/permissions.mjs";
import { createScheduler, classifyDeath, AUTO_CONTINUE_PROMPT } from "./src/schedule.mjs";
import { lockScreen, screenOff, setKeepAwake, powerStatus, shutdownPower, shutdownComputer } from "./src/power.mjs";
import { autostartStatus, setAutostart, reconcileAutostart, enrollAutostartOnce, refreshAutostart } from "./src/autostart.mjs";

const EDIT_TOOLS = new Set(["Write", "Edit", "MultiEdit", "NotebookEdit"]); // auto-approved under acceptEdits

// How long a question / permission card waits for the user before giving up.
// These are LONG on purpose: the user may be asleep or away from the phone,
// and a short window auto-answered cards before they were ever seen. Each
// outer layer of the wait chain (MCP server / hook child / CLI hook timeout)
// adds headroom on top of these, so the innermost gateway wait always decides.
// Question timeout returns "no answer" (the agent proceeds); permission
// timeout still DENIES, so sleeping through a card never silently allows.
const QUESTION_WAIT_MS = Math.max(60_000, Number(process.env.WAKILI_QUESTION_TIMEOUT_MS) || 3_600_000);
const PERMISSION_WAIT_MS = Math.max(60_000, Number(process.env.WAKILI_PERMISSION_TIMEOUT_MS) || 3_600_000);

// Every session save becomes a push invalidation on the live stream: clients
// holding an in-memory copy compare updatedAt and refetch (as a delta) only
// what actually moved — this is what keeps client caches honest without polling.
onSessionSave((s) => publish(s.id, { type: "_gateway", subtype: "session_updated", updatedAt: s.updatedAt, title: s.title }));
const busy = new Set(); // session ids with an in-flight turn
const children = new Map(); // sessionId -> spawned agent child process (for stop/interrupt)
// Session ids whose current turn the USER interrupted (/stop). A killed child
// closes with no parts and no result — the same shape as a crash. This set is
// how the close path tells "you asked for this" (a clean stop) from "the
// process died mute" (retry it, and say so in the chat).
const stopRequested = new Set();
// Scheduled tasks + auto-continue (src/schedule.mjs). Assigned after runTurn
// exists (its dispatch hook needs it); `let` so withMeta below can null-guard.
let scheduler = null;

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

// Optional message ids make mobile retries idempotent without changing legacy
// clients. A claim reserves the session before any async session read/agent
// launch; known ids bridge the small window before the user row reaches disk.
const turnClaims = new Map(); // sessionId -> { clientMessageId: string|null }
const knownClientMessageIds = new Map(); // sessionId -> Set<clientMessageId>
const MAX_CLIENT_MESSAGE_ID_LENGTH = 200;
const MAX_KNOWN_CLIENT_MESSAGE_IDS = 512;

function knowsClientMessageId(sessionId, clientMessageId) {
  return !!clientMessageId && knownClientMessageIds.get(sessionId)?.has(clientMessageId) === true;
}

function rememberClientMessageId(sessionId, clientMessageId) {
  if (!clientMessageId) return;
  let ids = knownClientMessageIds.get(sessionId);
  if (!ids) { ids = new Set(); knownClientMessageIds.set(sessionId, ids); }
  // Refresh insertion order and keep only a small hot set. Older ids remain
  // safely discoverable in the persisted transcript after a cache miss.
  ids.delete(clientMessageId);
  ids.add(clientMessageId);
  while (ids.size > MAX_KNOWN_CLIENT_MESSAGE_IDS) ids.delete(ids.values().next().value);
}

function forgetClientMessageId(sessionId, clientMessageId) {
  if (!clientMessageId) return;
  const ids = knownClientMessageIds.get(sessionId);
  if (!ids) return;
  ids.delete(clientMessageId);
  if (!ids.size) knownClientMessageIds.delete(sessionId);
}

function rememberPersistedClientMessageIds(session) {
  for (const message of session?.messages || []) {
    if (typeof message?.clientMessageId === "string" && message.clientMessageId) {
      rememberClientMessageId(session.id, message.clientMessageId);
    }
  }
}

function hasPersistedClientMessageId(session, clientMessageId) {
  return !!clientMessageId && (session?.messages || []).some((message) => message?.clientMessageId === clientMessageId);
}

function reserveTurn(sessionId, clientMessageId) {
  if (turnClaims.has(sessionId) || busy.has(sessionId)) return null;
  const claim = { clientMessageId: clientMessageId || null };
  turnClaims.set(sessionId, claim);
  busy.add(sessionId);
  rememberClientMessageId(sessionId, clientMessageId);
  return claim;
}

function releaseTurn(sessionId, claim) {
  if (turnClaims.get(sessionId) !== claim) return;
  turnClaims.delete(sessionId);
  busy.delete(sessionId);
}

function activeClientMessageId(sessionId) {
  return turnClaims.get(sessionId)?.clientMessageId || null;
}

// Crash safety for the in-progress turn: RAM (activeTurns) is the live source,
// but a throttled copy (≤1 write/s) goes to a sidecar file so a gateway crash
// mid-turn loses at most the last second of streaming — anything that reached
// the server stays saved. The sidecar lives only between turn start and the
// turn-end save; boot folds any leftover one back into its transcript.
const turnSavers = new Map(); // sessionId -> { timer, dirty }
function touchTurn(id) {
  let t = turnSavers.get(id);
  if (!t) { t = { timer: null, dirty: false }; turnSavers.set(id, t); }
  t.dirty = true;
  if (t.timer) return; // trailing throttle: at most one write/s, always within 1s of the last delta
  t.timer = setTimeout(() => {
    t.timer = null; t.dirty = false;
    const parts = activeTurns.get(id);
    if (parts) sessionStore.savePendingTurn(id, parts).catch(() => { /* retried on the next delta */ });
  }, 1000);
}
function stopTurnSaver(id) {
  const t = turnSavers.get(id);
  if (t && t.timer) clearTimeout(t.timer);
  turnSavers.delete(id);
}

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

// Key-order-insensitive stringify, for matching a hook's tool_input against
// the assistant stream's tool_use input: same object through two serializers,
// and plain JSON.stringify makes key order significant — a miss shipped an
// id-less auto-approve chip that clients couldn't dedupe, so the Edit card
// rendered twice.
function stableStringify(v) {
  if (v === null || typeof v !== "object") return JSON.stringify(v);
  if (Array.isArray(v)) return "[" + v.map(stableStringify).join(",") + "]";
  return "{" + Object.keys(v).sort().map((k) => JSON.stringify(k) + ":" + stableStringify(v[k])).join(",") + "}";
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
// "Auto continue" is ON unless the chat says otherwise: only an explicit false
// (the user turned the switch off) counts as off, so chats written before the
// field existed — and any path that forgot to set it — still resume after a
// usage limit. Every read of s.autoContinue goes through here.
const autoContinueOn = (s) => s?.autoContinue !== false;

// `pending` = open permission/question cards awaiting the user, so the sidebar
// can flag background sessions that need attention.
const withMeta = (s) => ({
  ...s,
  busy: busy.has(s.id),
  pending: pendingCards.get(s.id)?.size || 0,
  effectiveCwd: s.cwd || GATEWAY_CWD,
  activeClientMessageId: activeClientMessageId(s.id),
  autoContinue: autoContinueOn(s),
  scheduled: scheduler ? scheduler.countFor(s.id) : 0,
});

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
// Backfill for entries saved before `path` was persisted (they lost their disk
// location across restarts). Uploads are recoverable: the disk filename is
// `<uuid>-<name>`, so re-link when exactly one candidate matches the name.
try {
  const disk = fsSync.readdirSync(config.uploadsDir);
  let relinked = false;
  for (const f of filesLog) {
    if (!f || f.path || !f.name) continue;
    const matches = disk.filter((d) => d.endsWith(`-${f.name}`));
    if (matches.length === 1) { f.path = path.join(config.uploadsDir, matches[0]); relinked = true; }
  }
  if (relinked) fsSync.writeFileSync(FILES_LOG, JSON.stringify(filesLog));
} catch { /* uploads dir doesn't exist yet */ }
// Re-arm download tokens from the saved registry so links keep working post-restart.
for (const f of filesLog) if (f.token && f.path) files.set(f.token, { path: f.path, name: f.name });
function saveFilesLog() { fsp.writeFile(FILES_LOG, JSON.stringify(filesLog)).catch(() => {}); }
function recordFile({ sessionId, source, filePath, name, caption }) {
  const token = crypto.randomUUID();
  files.set(token, { path: filePath, name });
  filesLog.push({ token, sessionId, source, name, path: filePath, caption: caption || "", image: IMG_RE.test(name), url: `/api/files/${token}` });
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
  const body = JSON.stringify(obj);
  // Compress sizable JSON (transcripts run to MBs) for clients that take gzip.
  if (res._gzip && Buffer.byteLength(body) > 1024) {
    return zlib.gzip(body, (err, buf) => {
      if (err || !buf) { res.writeHead(code, { "Content-Type": "application/json" }); return res.end(body); }
      res.writeHead(code, { "Content-Type": "application/json", "Content-Encoding": "gzip" });
      res.end(buf);
    });
  }
  res.writeHead(code, { "Content-Type": "application/json" });
  res.end(body);
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

// One turn's event plumbing, shared by user-initiated turns (runTurn) and
// agent-initiated continuation turns (adoptOrphanTurn): accumulate ordered
// parts (sidecar-backed), forward events to subscribers, and at close fold the
// assembled turn into the transcript. `session` may be a minimal stand-in
// ({ id, resumeId: null, … }) — onClose re-reads the real record from disk
// before folding, and only overwrites resumeId when this turn produced one.
function createTurnIO(session, claim, clientMessageId, { adopted = false } = {}) {
  // Assemble the turn as ordered parts so history (commands, thoughts) replays.
  const parts = [];
  activeTurns.set(session.id, parts);
  const pushText = (t) => { const l = parts[parts.length - 1]; if (l && l.type === "text") l.text += t; else parts.push({ type: "text", text: t }); touchTurn(session.id); };
  const pushThink = (t) => { const l = parts[parts.length - 1]; if (l && l.type === "thinking") l.text += t; else parts.push({ type: "thinking", text: t }); touchTurn(session.id); };

  let closed = false;
  // The turn's terminal result (error flag + text), for the limit classifier:
  // a turn cut by the usage limit arms auto-continue at close (see onClose).
  let resultInfo = null;
  return {
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
          // AskUserQuestion / ask_options render as their own interactive card
          // (and persist as a qa part), not a tool chip. Codex reports the MCP
          // tool under its bare name — cover both spellings.
          const skip = new Set(["AskUserQuestion", "mcp__wakili__ask_options", "ask_options"]);
          // Keep the tool_use id so the matching tool_result (output) can attach to it.
          for (const b of evt.message.content || []) if (b.type === "tool_use" && !skip.has(b.name)) { parts.push({ type: "tool", name: b.name, input: b.input, id: b.id }); touchTurn(session.id); }
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
          if (part) { part.output = output; part.isError = isError; touchTurn(session.id); }
          publish(session.id, { type: "_gateway", subtype: "tool_result", id: b.tool_use_id, output, isError });
        }
      } else if (evt.type === "result") {
        if (evt.session_id) session.resumeId = evt.session_id;
        resultInfo = {
          isError: !!evt.is_error,
          text: typeof evt.result === "string" ? evt.result : (typeof evt.error === "string" ? evt.error : ""),
        };
      }
      publish(session.id, evt);
    },
    onError: (text) => publish(session.id, { type: "_gateway", subtype: "stderr", text }),
    onClose: async () => {
      if (closed) return;
      closed = true;
      // Verdict FIRST, on the raw turn: a user-requested Stop is a clean close
      // no matter what shape the kill left; anything else is classified from
      // what the turn actually produced (the marker below must not feed it).
      const wasStopped = stopRequested.delete(session.id);
      const verdict = wasStopped ? "clean" : classifyDeath(parts, resultInfo);
      // A turn that died having streamed NOTHING used to vanish: parts was
      // empty, so nothing folded — the user's message just never got a reply
      // ("I come back and there's nothing"). Never silent: leave the reason
      // (or at least the fact) in the transcript. A non-error empty result is
      // the agent legitimately saying nothing — that one stays untouched.
      if (!wasStopped && parts.length === 0 && (!resultInfo || resultInfo.isError)) {
        const why = resultInfo && resultInfo.text ? `: ${resultInfo.text}` : "";
        parts.push({ type: "text", text: `⚠️ The agent process died before it could reply${why}` });
      }
      // The live copy deliberately OUTLIVES this point, until the transcript
      // save below lands. Deleting it here — as this used to — opened a window
      // where the turn existed in neither place a client can read: a resync
      // arriving in it answered `parts: []`, and the client's renderSnapshot
      // wiped the live region, so a finished turn vanished off the screen. The
      // window is one transcript write, which is not small on a session whose
      // JSON runs to megabytes.
      stopTurnSaver(session.id); // no sidecar writes may land after this point
      children.delete(session.id);
      try {
        // Re-read from disk before the turn-end save: PATCHes that landed
        // mid-turn (rename, Allow always, controls) were saved onto their own
        // fresh copies — saving this closure's stale `session` would silently
        // revert them. Only the turn's own results carry over. A missing fresh
        // copy means the session was deleted mid-turn — nothing to fold into,
        // and saving would resurrect the deleted chat.
        const fresh = await sessionStore.get(session.id);
        if (fresh) {
          // Only a turn that produced a resume id may move it: a continuation
          // stand-in starts at null and must not erase the session's thread.
          if (session.resumeId) fresh.resumeId = session.resumeId;
          session = fresh;
          if (parts.length) {
            const assistantMsg = { role: "assistant", parts };
            if (clientMessageId) assistantMsg.clientMessageId = clientMessageId;
            session.messages.push(assistantMsg);
          }
          await sessionStore.save(session);
        }
        // The transcript now holds the turn, so the live copy can go — and must,
        // before anything reads both and renders it twice. Handing over in this
        // order means every instant has the turn in exactly one readable place.
        //
        // Nothing from here to the publish() below may await: the handover is
        // only atomic because no request can be served between them. An await
        // inserted in this stretch silently reopens the empty-snapshot window.
        activeTurns.delete(session.id);
        // Sidecar cleanup only AFTER the save: the turn must never have a moment
        // where it exists nowhere durable. A crash in the gap just means boot
        // recovery sees the duplicate and skips it.
        sessionStore.clearPendingTurn(session.id);
      } catch (error) {
        console.error(`[turn] Failed to persist ${session.id}:`, error);
      } finally {
        // Also on the failure path: the save threw, so the handover above never
        // ran and the live copy would otherwise leak into the next turn.
        activeTurns.delete(session.id);
        releaseTurn(session.id, claim);
        publish(session.id, { type: "_gateway", subtype: "turn_end", title: session.title, clientMessageId });
        // After the release, so a re-dispatch can claim the session. The death
        // tier picks the retry ladder (limit waits hours, transient minutes,
        // permanent reports and stops); a clean close disarms any pending
        // resume — the work finished another way. Adopted background closes
        // pass the flag so their clean finish can't cancel a promised resume.
        scheduler?.onTurnClosed(session.id, {
          verdict,
          autoContinue: autoContinueOn(session),
          adopted,
        });
      }
    },
  };
}

// A turn the AGENT started on its own: a background task (a build, a long
// test) the model kicked off earlier finished, and its harness re-invoked the
// model with nobody asking. Those events used to be dropped at the adapter —
// its per-turn callbacks were already detached — so the continuation streamed
// only to whoever happened to be connected, was never marked busy (no pulse,
// no Stop), and never reached the transcript; the invariant "anything that
// reaches the gateway is saved" broke exactly here. Adopting makes it a REAL
// turn: busy is claimed, parts accumulate through the same sidecar-backed
// pipeline, Stop works (children registered), and turn end folds it into the
// transcript like any other turn. Returns null while another turn still holds
// the session — the adapter buffers and retries on its next event.
function adoptOrphanTurn(sessionId, child) {
  const claim = reserveTurn(sessionId, null);
  if (!claim) return null;
  stopRequested.delete(sessionId); // a stale stop flag must not soften THIS turn's close
  const io = createTurnIO({ id: sessionId, resumeId: null, title: "", messages: [] }, claim, null, { adopted: true });
  if (child) children.set(sessionId, child);
  publish(sessionId, { type: "_gateway", subtype: "turn_start", clientMessageId: null });
  return io;
}

// Start a turn with no HTTP request behind it — the scheduler's door into the
// same claim + runTurn path the /messages endpoint uses, so scheduled tasks
// and auto-continues get identical busy semantics, streaming, and persistence.
async function dispatchInternal(sessionId, text, attachments) {
  const claim = reserveTurn(sessionId, null);
  if (!claim) return { ok: false, reason: "busy" };
  let s;
  try {
    s = await sessionStore.get(sessionId);
  } catch (error) {
    releaseTurn(sessionId, claim);
    return { ok: false, reason: "error" };
  }
  if (!s) {
    releaseTurn(sessionId, claim);
    return { ok: false, reason: "gone" };
  }
  try {
    await runTurn(s, text, null, attachments || null, null, null, claim);
  } catch (error) {
    // runTurn's own catch already released the claim and published turn_end.
    console.error(`[schedule] Failed to dispatch for ${sessionId}:`, error);
    return { ok: false, reason: "error" };
  }
  return { ok: true };
}

scheduler = createScheduler({
  dispatch: dispatchInternal,
  // Give-up must be LOUD: unattended retries that quietly stop are
  // indistinguishable from success. A marker message lands in the transcript
  // (the failed turns above it carry the actual errors), and its save pushes
  // session_updated so open clients refresh.
  onGiveUp: async (sessionId, kind, attempts) => {
    try {
      const s = await sessionStore.get(sessionId);
      if (!s) return;
      const detail = kind === "permanent"
        ? "The error above needs your attention (auth/config) — retries would just repeat it."
        : `Gave up after ${attempts} failed attempt(s) [${kind}].`;
      s.messages.push({
        role: "assistant",
        parts: [{ type: "text", text: `⚠️ Auto-continue stopped. ${detail} Send any message to try again.` }],
      });
      await sessionStore.save(s);
    } catch (error) {
      console.error(`[schedule] Failed to record give-up for ${sessionId}:`, error);
    }
  },
  log: (m) => console.log(`[schedule] ${m}`),
});

// --- one streaming turn: persist the user message, stream agent events to the
//     session's subscribers, then persist the assembled assistant reply. ---
async function runTurn(session, text, controls, attachments, agentId, clientMessageId, claim) {
  let userPersisted = false;
  try {
    // switching agent on a session starts a fresh thread (resume ids are per-agent)
    if (agentId && agentId !== session.agentId) { session.agentId = agentId; session.resumeId = null; }
    const agent = getAgent(session.agentId) || getAgent("claude");
    stopRequested.delete(session.id); // a stale stop flag must not soften THIS turn's close

  // Attachments are persisted with the message (name + display URL) so the chat
  // still shows them after switching sessions or reloading — not just live.
  const userMsg = { role: "user", text };
  if (clientMessageId) userMsg.clientMessageId = clientMessageId;
  if (attachments && attachments.length) {
    userMsg.attachments = attachments.map((a) => ({ name: a.name, url: a.url || "", image: IMG_RE.test(a.name || "") }));
  }
  session.messages.push(userMsg);
  // The chat becomes real the moment it carries a message — until now it was a
  // draft and deliberately absent from the session list.
  if (session.draft) session.draft = false;
  if (session.title === "New chat" && (text || attachments?.length)) session.title = (text || attachments[0].name).slice(0, 48);
  if (controls) session.controls = controls;
  await sessionStore.save(session);
  userPersisted = true;

  publish(session.id, { type: "_gateway", subtype: "turn_start", clientMessageId });

  // Give the agent the uploaded files' paths so it can Read them (images/docs).
  let prompt = text;
  if (attachments && attachments.length) {
    const lines = attachments.map((a) => `- ${a.path}`).join("\n");
    prompt = `${text ? text + "\n\n" : ""}[The user attached these files; use the Read tool to view them if relevant:\n${lines}]`;
    // Register for the sidebar "Attached by you" list. Uploads that came through
    // /api/upload already carry a url (recorded there) — don't double-register.
    for (const a of attachments) if (!a.url) recordFile({ sessionId: session.id, source: "user", filePath: a.path, name: a.name });
  }

  const io = createTurnIO(session, claim, clientMessageId);
  const child = agent.run({
    text: prompt,
    controls: session.controls || {},
    resumeId: session.resumeId,
    sessionId: session.id,
    cwd: session.cwd || undefined, // undefined => adapter inherits the gateway's dir
    gatewayUrl: config.gatewayUrl,
    onEvent: io.onEvent,
    onError: io.onError,
    onClose: io.onClose,
    // Lets the adapter hand back events that arrive with NO turn attached — an
    // agent-initiated continuation (see adoptOrphanTurn). Adapters without
    // warm between-turn processes simply ignore the option.
    adoptTurn: (c) => adoptOrphanTurn(session.id, c),
  });
  children.set(session.id, child); // keep a handle so a turn can be interrupted
  } catch (error) {
    activeTurns.delete(session.id);
    stopTurnSaver(session.id);
    children.delete(session.id);
    releaseTurn(session.id, claim);
    if (!userPersisted) forgetClientMessageId(session.id, clientMessageId);
    publish(session.id, { type: "_gateway", subtype: "turn_end", title: session.title, clientMessageId });
    throw error;
  }
}

// Render a session's display transcript as readable markdown for an agent
// handoff (switching agents can't carry the native thread across — resume ids
// are per-agent — so the new agent reads this file for context instead).
// Text only: thinking is skipped, tool calls become one-line notes.
function transcriptMd(s) {
  const toolLine = (p) => {
    const input = p.input && typeof p.input === "object" ? (p.input.command || p.input.file_path || JSON.stringify(p.input)) : String(p.input || "");
    return `\`[${p.name}] ${String(input).replace(/\s+/g, " ").slice(0, 160)}\``;
  };
  const lines = [
    `# Handoff: ${s.title}`,
    "",
    `- Working directory: ${s.cwd || GATEWAY_CWD}`,
    `- Previous agent: ${s.agentId}`,
    "",
    "This is the transcript of the user's previous conversation with another agent.",
    "Read it to pick up the context, then continue the work from where it left off.",
    "",
  ];
  for (const msg of s.messages || []) {
    lines.push(msg.role === "user" ? "## User" : "## Assistant", "");
    if (msg.text) lines.push(msg.text, "");
    for (const a of msg.attachments || []) lines.push(`*(attached: ${a.name})*`, "");
    for (const p of msg.parts || []) {
      if (p.type === "text" && p.text.trim()) lines.push(p.text, "");
      else if (p.type === "tool") lines.push(toolLine(p), "");
    }
  }
  return lines.join("\n");
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://x");
    const p = url.pathname;
    const m = req.method;
    let mm;
    // Marks whether this client takes gzip; json() and the static handler use
    // it. Transcripts and app JS shrink ~5-10x, which matters on tunnel links.
    res._gzip = /\bgzip\b/.test(req.headers["accept-encoding"] || "");

    // ---- auth gate ----
    // Everything under /api and /internal needs the token, EXCEPT file downloads:
    // those carry their own unguessable per-file UUID (a capability) so <img src>
    // and download links work without smuggling the token into every URL. Only
    // GETs get that pass — mutating a file (DELETE) still needs the real token.
    const guarded = (p.startsWith("/api/") || p.startsWith("/internal/")) && !(p.startsWith("/api/files/") && m === "GET");
    if (guarded && !authed(req, url)) return json(res, 401, { error: "unauthorized" });

    // ---- API ----
    if (p === "/api/agents" && m === "GET") return json(res, 200, listAgents());

    // Device controls: lock the laptop's screen, and keep the machine awake (so a
    // remote turn isn't suspended by idle-sleep). Keep-awake lets the display
    // still lock/turn off — it only blocks system sleep.
    if (p === "/api/power" && m === "GET") return json(res, 200, powerStatus());
    if (p === "/api/lock-screen" && m === "POST") return json(res, 200, await lockScreen());
    if (p === "/api/screen-off" && m === "POST") return json(res, 200, await screenOff());
    if (p === "/api/shutdown" && m === "POST") return json(res, 200, await shutdownComputer());
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
    // Start-at-login toggle. Registers/unregisters the gateway with the OS's
    // per-user autostart mechanism (Run key / LaunchAgent / systemd user unit),
    // so after one manual launch the developer never needs the terminal again.
    if (p === "/api/autostart" && m === "GET") return json(res, 200, await autostartStatus());
    if (p === "/api/autostart" && m === "POST") {
      const b = await readBody(req);
      return json(res, 200, await setAutostart(!!b.on));
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

    // Identity for the phone app: lets it label a saved computer with its real
    // name ("AHMED-PC") instead of a bare ip:port when the user adds it.
    if (p === "/api/host" && m === "GET") return json(res, 200, { hostname: os.hostname(), platform: process.platform });

    // browse the laptop filesystem so the phone can pick a project folder
    if (p === "/api/folders" && m === "GET") return json(res, 200, await listFolders(url.searchParams.get("path")));
    // create a new sub-folder inside an existing one
    if (p === "/api/folders" && m === "POST") {
      const b = await readBody(req);
      try {
        const dest = await createFolder(b.parent, b.name);
        rememberProject(dest); // you made it to work in it — it's a project from now on
        return json(res, 200, { path: dest });
      } catch (e) { return json(res, 400, { error: String((e && e.message) || e) }); }
    }

    // Known projects. Agent-independent by design: the sidebar used to derive
    // this list from the sessions of the SELECTED agent, so a project was
    // invisible from every tab but the one it was first used in.
    if (p === "/api/projects" && m === "GET") return json(res, 200, listProjects());
    if (p === "/api/projects" && m === "POST") {
      const b = await readBody(req);
      const dir = typeof b.path === "string" ? b.path.trim() : "";
      if (!dir) return json(res, 400, { error: "path required" });
      if (!(await isDir(dir))) return json(res, 400, { error: "not a directory" });
      return json(res, 200, rememberProject(dir));
    }
    // Forget a project (the folder on disk is left alone).
    if (p === "/api/projects" && m === "DELETE") {
      const b = await readBody(req);
      return json(res, 200, { ok: forgetProject(b && b.path) });
    }

    // every file that's flowed through (uploads + agent-sent), for the sidebar
    if (p === "/api/files" && m === "GET") return json(res, 200, filesLog);

    // Remove one file from the registry (Files page long-press → Delete). Only
    // files wakili itself stores (uploads) are also removed from disk — agent-
    // sent entries point at real project files, which must stay untouched.
    if (p.startsWith("/api/files/") && m === "DELETE") {
      const token = decodeURIComponent(p.slice("/api/files/".length));
      const i = filesLog.findIndex((f) => f.token === token);
      if (i === -1) return json(res, 404, { error: "no such file" });
      const f = filesLog[i];
      filesLog.splice(i, 1);
      saveFilesLog();
      files.delete(token);
      if (f.path && f.path.startsWith(config.uploadsDir)) fsp.unlink(f.path).catch(() => {});
      return json(res, 200, { ok: true });
    }

    // Each summary carries a live `busy` flag so the phone can show per-session
    // status (badges) and enable/disable the composer for the active session only.
    if (p === "/api/sessions" && m === "GET") return json(res, 200, (await sessionStore.list()).map(withMeta));

    if (p === "/api/sessions" && m === "POST") {
      const b = await readBody(req);
      // Validate cwd like PATCH does — a bad path here (typo, mangled escape)
      // would otherwise surface later as a spawn failure mid-turn.
      const cwd = typeof b.cwd === "string" ? b.cwd.trim() : "";
      if (cwd && !(await isDir(cwd))) return json(res, 400, { error: "not a directory" });
      if (cwd) rememberProject(cwd);
      // A chat opened from the UI starts as a draft (hidden from the list until
      // its first message). `draft: false` lets a caller opt out explicitly.
      const draft = b.draft !== false;
      return json(res, 201, withMeta(await sessionStore.create({ agentId: b.agentId, model: b.model, cwd: cwd || null, draft })));
    }

    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)$/))) {
      const id = mm[1];
      if (m === "GET") {
        const s = await sessionStore.get(id);
        if (!s) return notFound(res);
        // Conditional GET: a client holding a cached copy sends the updatedAt it
        // has (?since=) — when nothing moved, answer with a tiny stub instead of
        // the whole transcript.
        // Include the in-progress turn's parts so a (re)opening client can paint
        // the streamed-so-far content immediately instead of waiting a resync
        // round trip. The resync snapshot stays authoritative (it's ordered with
        // live events on the stream); the client replaces this copy when it lands.
        const parts = activeTurns.get(id) || [];
        const since = url.searchParams.get("since");
        if (since !== null && Number(since) === s.updatedAt) {
          // `parts` ride along even here: mid-turn nothing bumps updatedAt, so a
          // client switching back always hits this branch — without them the
          // streamed-so-far turn was invisible until the resync round trip.
          return json(res, 200, {
            unchanged: true,
            updatedAt: s.updatedAt,
            busy: busy.has(id),
            pending: pendingCards.get(id)?.size || 0,
            parts,
            activeClientMessageId: activeClientMessageId(id),
          });
        }
        // Windowed GET: ?last=N returns only the newest N messages plus the
        // total, for clients that virtualize the transcript. Older history
        // pages in via ?before=<index>&count=N → messages [before-count, before).
        const lastQ = url.searchParams.get("last");
        if (lastQ !== null && Number.isInteger(Number(lastQ)) && Number(lastQ) > 0) {
          const { messages, ...meta } = withMeta(s);
          const from = Math.max(0, messages.length - Number(lastQ));
          return json(res, 200, { ...meta, windowed: true, from, total: messages.length, messages: messages.slice(from), parts });
        }
        const beforeQ = url.searchParams.get("before");
        if (beforeQ !== null) {
          const b = Math.max(0, Math.min(Number(beforeQ) || 0, s.messages.length));
          const count = Math.max(1, Math.min(Number(url.searchParams.get("count")) || 100, 500));
          const from = Math.max(0, b - count);
          return json(res, 200, { page: true, from, total: s.messages.length, messages: s.messages.slice(from, b) });
        }
        // Delta GET: ?after=<count> returns only the messages past what the
        // client already has. Messages are append-only, so the array index is a
        // valid cursor; anything out of range falls through to the full payload.
        const after = url.searchParams.get("after");
        const n = after === null ? NaN : Number(after);
        if (Number.isInteger(n) && n >= 0 && n <= s.messages.length) {
          const { messages, ...meta } = withMeta(s);
          return json(res, 200, { ...meta, delta: true, after: n, messages: messages.slice(n), parts });
        }
        return json(res, 200, { ...withMeta(s), parts });
      }
      if (m === "PATCH") {
        const b = await readBody(req);
        const s = await sessionStore.get(id);
        if (!s) return notFound(res);
        if (typeof b.title === "string") s.title = b.title;
        // "Allow always" lives on the gateway, not the client: a backgrounded
        // phone can't click Allow, so the approval must happen here. Turning it
        // on also answers any cards already waiting — they'd otherwise sit
        // until timeout even though the user just said "always allow".
        if (typeof b.autoAllow === "boolean") {
          s.autoAllow = b.autoAllow;
          if (b.autoAllow) {
            for (const [cardId, ev] of pendingCards.get(id) || []) {
              if (ev.subtype === "permission_request") resolvePermission(cardId, "allow");
            }
          }
        }
        // "Auto continue" — per-chat, like autoAllow: a turn this chat loses to
        // the usage limit is re-driven by the gateway once quota returns.
        // Turning it OFF also drops any resume already waiting on quota.
        if (typeof b.autoContinue === "boolean") {
          s.autoContinue = b.autoContinue;
          if (!b.autoContinue) scheduler.disarmResume(id);
        }
        if (typeof b.cwd === "string") {
          const dir = b.cwd.trim();
          if (dir && !(await isDir(dir))) return json(res, 400, { error: "not a directory" });
          if (dir) rememberProject(dir); // picking a folder registers it for every agent
          // changing the working dir starts a fresh thread (resume ids are dir-bound)
          if ((s.cwd || "") !== dir) { s.cwd = dir || null; s.resumeId = null; }
        }
        // Picker state, saved the moment it changes (not just at send time) so
        // a choice made without sending survives switches/reloads — and the
        // permission hook reads the NEW mode for the very next card, even
        // mid-turn. Same agent-switch semantics as runTurn: fresh thread.
        if (typeof b.agentId === "string" && getAgent(b.agentId) && b.agentId !== s.agentId) { s.agentId = b.agentId; s.resumeId = null; }
        if (b.controls && typeof b.controls === "object" && !Array.isArray(b.controls)) s.controls = b.controls;
        await sessionStore.save(s);
        return json(res, 200, withMeta(s));
      }
      if (m === "DELETE") {
        await sessionStore.remove(id);
        knownClientMessageIds.delete(id);
        scheduler.forgetSession(id); // its pending jobs and quota-resume go with it
        return json(res, 200, { ok: true });
      }
    }

    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/messages$/)) && m === "POST") {
      const id = mm[1];
      const b = await readBody(req);
      let clientMessageId = null;
      if (b.clientMessageId != null) {
        if (typeof b.clientMessageId !== "string") return json(res, 400, { error: "invalid clientMessageId" });
        clientMessageId = b.clientMessageId.trim();
        if (!clientMessageId || clientMessageId.length > MAX_CLIENT_MESSAGE_ID_LENGTH) {
          return json(res, 400, { error: "invalid clientMessageId" });
        }
      }

      // A retry of an id already accepted by this process is successful even
      // if another turn is now busy. This also covers the pre-persistence
      // window because reserveTurn remembers the id synchronously.
      if (knowsClientMessageId(id, clientMessageId)) return json(res, 202, { ok: true, duplicate: true });

      const activeClaim = turnClaims.get(id);
      if (clientMessageId && activeClaim?.clientMessageId === clientMessageId) {
        return json(res, 202, { ok: true, duplicate: true });
      }
      if (activeClaim || busy.has(id)) {
        // After a gateway restart the in-memory id set is empty. Let an old,
        // already-persisted retry remain idempotent while a different turn runs.
        if (clientMessageId) {
          const current = await sessionStore.get(id);
          if (!current) return notFound(res);
          if (hasPersistedClientMessageId(current, clientMessageId)) {
            rememberClientMessageId(id, clientMessageId);
            return json(res, 202, { ok: true, duplicate: true });
          }
        }
        return json(res, 409, { error: "busy" });
      }

      const text = (b.text || "").trim();
      // A message can be attachments-only (an image with nothing typed).
      if (!text && !(b.attachments && b.attachments.length)) return json(res, 400, { error: "empty" });

      // Claim before the async session read. Otherwise two concurrent POSTs can
      // both observe idle and launch, or a slow body can retain a stale session
      // object and overwrite the turn that completed while it was waiting.
      const claim = reserveTurn(id, clientMessageId);
      if (!claim) return json(res, 409, { error: "busy" });
      let s;
      try {
        s = await sessionStore.get(id);
      } catch (error) {
        releaseTurn(id, claim);
        forgetClientMessageId(id, clientMessageId);
        throw error;
      }
      if (!s) {
        releaseTurn(id, claim);
        forgetClientMessageId(id, clientMessageId);
        return notFound(res);
      }
      rememberPersistedClientMessageIds(s);
      if (hasPersistedClientMessageId(s, clientMessageId)) {
        releaseTurn(id, claim);
        return json(res, 202, { ok: true, duplicate: true });
      }

      // AWAIT the turn start before answering: runTurn persists the user
      // message (and spawns the agent) before returning. Answering 202 first
      // let a fetch racing right behind the send read a transcript WITHOUT
      // the message the client believes was accepted — the client then cached
      // that regressed copy over its own optimistic one.
      try {
        await runTurn(s, text, b.controls, b.attachments, b.agentId, clientMessageId, claim);
      } catch (error) {
        console.error(`[turn] Failed to start ${id}:`, error);
        return json(res, 500, { error: "failed to start the turn" });
      }
      return json(res, 202, { ok: true });
    }

    // Schedule a one-shot task into this chat: the text (+ any attached files)
    // goes out as a normal user message at (or as soon after as quota/busy
    // allows) the given time. Scheduling also flips the chat's autoContinue on
    // — an unattended task that hits the usage limit mid-run must finish
    // itself (approved design).
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/schedule$/)) && m === "POST") {
      const id = mm[1];
      const b = await readBody(req);
      const text = (b.text || "").trim();
      const at = Number(b.at);
      // Same shape the /messages endpoint takes: uploaded {name, path, url}.
      const attachments = Array.isArray(b.attachments)
        ? b.attachments.filter((a) => a && a.path).map((a) => ({ name: a.name || "file", path: a.path, url: a.url || "" }))
        : [];
      // A task can be attachments-only, like a normal send.
      if (!text && !attachments.length) return json(res, 400, { error: "empty" });
      if (!Number.isFinite(at) || at < Date.now() - 60_000 || at > Date.now() + 30 * 24 * 60 * 60 * 1000) {
        return json(res, 400, { error: "bad time" });
      }
      const s = await sessionStore.get(id);
      if (!s) return notFound(res);
      s.autoContinue = true;
      // A draft with a pending task is a real chat — it must show in the list.
      if (s.draft) s.draft = false;
      await sessionStore.save(s); // session_updated push → clients refresh the chip
      const job = scheduler.addJob(id, text, at, attachments);
      return json(res, 200, { job, session: withMeta(s) });
    }
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/schedule$/)) && m === "GET") {
      return json(res, 200, { jobs: scheduler.jobsFor(mm[1]) });
    }
    // Every pending task across every chat (the Scheduled tasks page). Session
    // titles ride along so the list can say WHERE each task will run.
    if (p === "/api/schedule" && m === "GET") {
      const titles = new Map((await sessionStore.list()).map((s) => [s.id, s.title]));
      const jobs = scheduler.allJobs().map((j) => ({ ...j, sessionTitle: titles.get(j.sessionId) || "" }));
      return json(res, 200, { jobs });
    }
    // Edit a pending task's text and/or time. Attachments stay as scheduled.
    if ((mm = p.match(/^\/api\/schedule\/([\w-]+)$/)) && m === "PATCH") {
      const b = await readBody(req);
      const existing = scheduler.allJobs().find((j) => j.id === mm[1]);
      if (!existing) return notFound(res);
      const text = typeof b.text === "string" ? b.text.trim() : existing.text;
      const at = b.at !== undefined ? Number(b.at) : existing.at;
      if (!text && !(existing.attachments && existing.attachments.length)) return json(res, 400, { error: "empty" });
      if (!Number.isFinite(at) || at < Date.now() - 60_000 || at > Date.now() + 30 * 24 * 60 * 60 * 1000) {
        return json(res, 400, { error: "bad time" });
      }
      const job = scheduler.updateJob(mm[1], { text, at });
      const s = await sessionStore.get(job.sessionId);
      if (s) await sessionStore.save(s); // bump updatedAt → clients refresh the chip
      return json(res, 200, { job });
    }
    if ((mm = p.match(/^\/api\/schedule\/([\w-]+)$/)) && m === "DELETE") {
      const job = scheduler.removeJob(mm[1]);
      if (!job) return notFound(res);
      const s = await sessionStore.get(job.sessionId);
      if (s) await sessionStore.save(s); // bump updatedAt → clients drop the chip
      return json(res, 200, { ok: true });
    }

    // Continue a conversation with a different agent. Native threads can't
    // cross agents, so instead: export this session's transcript to a markdown
    // file (saved like an upload — the existing chip/delete/download plumbing
    // all applies) and create a fresh session for the new agent in the same
    // folder. The phone attaches the file to the new chat's composer; the new
    // agent Reads it itself on the first turn.
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/handoff$/)) && m === "POST") {
      const src = await sessionStore.get(mm[1]);
      if (!src) return notFound(res);
      const b = await readBody(req);
      if (!getAgent(b.agentId)) return json(res, 400, { error: "unknown agent" });
      await fsp.mkdir(config.uploadsDir, { recursive: true });
      const slug = String(src.title || "chat").replace(/[^\w.\- ]/g, "_").trim().slice(0, 40) || "chat";
      const name = `handoff-${slug}.md`;
      const dest = path.join(config.uploadsDir, `${crypto.randomUUID()}-${name}`);
      await fsp.writeFile(dest, transcriptMd(src));
      const next = await sessionStore.create({ agentId: b.agentId, cwd: src.cwd || null });
      // A real title (not "New chat") survives the first turn's auto-titling.
      next.title = "↪ " + (src.title || "chat");
      await sessionStore.save(next);
      const token = recordFile({ sessionId: next.id, source: "user", filePath: dest, name });
      return json(res, 200, { session: withMeta(next), file: { name, path: dest, url: `/api/files/${token}` } });
    }

    // Switch this chat to a different agent IN PLACE. The chat, its history and
    // its folder all stay put; only the agent behind it changes. The native
    // thread cannot cross agents (resume ids are per-agent), so the transcript
    // so far is exported to a file the new agent reads on its first turn — the
    // same context bridge /handoff builds, minus the second chat. The old
    // agent's controls are dropped: they don't map onto the new one, which
    // re-applies its own defaults client-side.
    if ((mm = p.match(/^\/api\/sessions\/([\w-]+)\/agent$/)) && m === "POST") {
      const s = await sessionStore.get(mm[1]);
      if (!s) return notFound(res);
      const b = await readBody(req);
      if (!getAgent(b.agentId)) return json(res, 400, { error: "unknown agent" });
      let file = null;
      if (b.agentId !== s.agentId) {
        // Only a chat with something in it needs context carried across.
        if ((s.messages || []).length) {
          await fsp.mkdir(config.uploadsDir, { recursive: true });
          const slug = String(s.title || "chat").replace(/[^\w.\- ]/g, "_").trim().slice(0, 40) || "chat";
          const name = `handoff-${slug}.md`;
          const dest = path.join(config.uploadsDir, `${crypto.randomUUID()}-${name}`);
          await fsp.writeFile(dest, transcriptMd(s));
          const token = recordFile({ sessionId: s.id, source: "user", filePath: dest, name });
          file = { name, path: dest, url: `/api/files/${token}` };
        }
        s.agentId = b.agentId;
        s.resumeId = null;
        s.controls = {};
        await sessionStore.save(s);
      }
      return json(res, 200, { session: withMeta(s), file });
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
        stopRequested.add(id); // the coming close is deliberate, not a death
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

    // Undo an eager upload (the phone uploads on file-pick now; removing the
    // chip before sending deletes the file again). Only paths inside the
    // uploads folder are accepted, so this can't be aimed at arbitrary files.
    if (p === "/api/upload/delete" && m === "POST") {
      const b = await readBody(req);
      const target = path.resolve(String(b.path || ""));
      if (!target.startsWith(path.resolve(config.uploadsDir) + path.sep)) return json(res, 400, { error: "bad path" });
      // The registry rows don't carry the path — resolve it to its download tokens.
      const tokens = new Set();
      for (const [t, f] of files) if (f.path && path.resolve(f.path) === target) tokens.add(t);
      for (const t of tokens) files.delete(t);
      if (tokens.size) { filesLog = filesLog.filter((f) => !tokens.has(f.token)); saveFilesLog(); }
      try { await fsp.unlink(target); } catch { /* already gone */ }
      return json(res, 200, { ok: true });
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
        // The asker (a hook subprocess) can give up — its own timeout, or a
        // killed turn. A card nobody is waiting on must not linger: resolving
        // on socket close untracks it and broadcasts request_resolved, so the
        // phone doesn't keep showing a ghost for the rest of the long wait.
        res.on("close", () => resolvePermission(id, ""));
        const answer = await waitPermission(id, QUESTION_WAIT_MS);
        untrackCard(b.sessionId, id);
        // Persist the answered Q&A as a turn part — the live "Answered" record
        // otherwise exists only in the DOM of whoever answered, and reopening
        // the chat would show nothing.
        if (answer && answer !== "deny") {
          const live = activeTurns.get(b.sessionId);
          if (live) live.push({ type: "qa", answer });
        }
        const reason = !answer || answer === "deny"
          ? "The user did not answer the question in time. Make a reasonable assumption or ask again next turn."
          : `The user answered your question(s):\n${answer}\n\nUse these answers to continue — do not call AskUserQuestion again for this.`;
        if (res.destroyed) return;
        return json(res, 200, { decision: "deny", reason });
      }

      const s = await sessionStore.get(b.sessionId);
      const mode = s?.controls?.permissionMode || "default";
      const allowed = s?.allowedTools || [];
      // s.autoAllow = the session's "Allow always" switch. Checked here (not on
      // the client) so approvals keep flowing while the app is backgrounded.
      if (mode === "bypassPermissions" || (mode === "acceptEdits" && EDIT_TOOLS.has(b.tool)) || allowed.includes(b.tool) || s?.autoAllow) {
        // Auto-approved without a card. The UI suppresses gated tools' chips
        // (expecting a permission card), so publish a chip here — otherwise the
        // action would be invisible in auto-accept / bypass / remembered modes.
        // Find the matching tool_use part (most recent, same name+input) so the
        // chip carries the tool id — letting its output attach to the right card.
        const live = activeTurns.get(b.sessionId) || [];
        const inp = stableStringify(b.input);
        const part = [...live].reverse().find((p) => p.type === "tool" && p.name === b.tool && stableStringify(p.input) === inp);
        publish(b.sessionId, { type: "_gateway", subtype: "tool", tool: b.tool, input: b.input, id: part?.id });
        return json(res, 200, { decision: "allow" });
      }
      const id = createPermission();
      const ev = { type: "_gateway", subtype: "permission_request", id, tool: b.tool, input: b.input };
      trackCard(b.sessionId, id, ev);
      publish(b.sessionId, ev);
      // Same ghost-card guard as questions: the hook dying must take its card
      // with it (see the AskUserQuestion branch above).
      res.on("close", () => resolvePermission(id, "deny"));
      const decision = await waitPermission(id, PERMISSION_WAIT_MS);
      untrackCard(b.sessionId, id);
      if (res.destroyed) return;
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
      // Asker gone (MCP call aborted, turn killed) → the card dies with it.
      // Without this, an abandoned ask lingered in pendingCards for the whole
      // long wait, replaying a GHOST card that no answer could ever dismiss.
      res.on("close", () => resolvePermission(id, ""));
      const answer = await waitPermission(id, QUESTION_WAIT_MS); // patient: the user may be away (see QUESTION_WAIT_MS)
      untrackCard(b.sessionId, id);
      const text = (!answer || answer === "deny") ? "" : answer;
      // Persist the answered Q&A as a turn part (see the AskUserQuestion
      // branch above) so revisiting the chat replays the "Answered" record.
      if (text) {
        const live = activeTurns.get(b.sessionId);
        if (live) live.push({ type: "qa", answer: text });
      }
      if (res.destroyed) return;
      return json(res, 200, { answer: text });
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
      // Persist the download url/token + image flag so reopening the chat can
      // re-render the thumbnail — not just a name card (the live event below
      // carries the same fields, which is why it shows live but not from history).
      if (turn) turn.push({ type: "file", name, caption: b.caption || "", url: `/api/files/${token}`, token, image: IMG_RE.test(name) });
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
        // ?dl=1 → the phone's Download Manager should save it, not display it.
        const disp = url.searchParams.get("dl") ? "attachment" : "inline";
        res.writeHead(200, {
          "Content-Type": MIME[path.extname(f.path).toLowerCase()] || "application/octet-stream",
          "Content-Disposition": `${disp}; filename="${f.name}"`,
          // Each token URL's content is frozen forever (a new upload = a new
          // token), so let the browser cache hard: no re-download on every
          // chat reopen / Files view / copy. `private` keeps tunnel proxies
          // from caching these token-secret URLs.
          "Cache-Control": "private, max-age=31536000, immutable",
        });
        return res.end(data);
      } catch { return notFound(res); }
    }

    // ---- stream diagnostics (WAKILI_DEBUG_STREAM=1) ----
    // Read the flight recorder: what the gateway published, per-turn delta
    // counts, and whatever the clients uploaded when they saw an anomaly. One
    // request shows both sides of the same turn, which is what you need to tell
    // "the stream dropped it" from "the client's render lost it".
    if (p === "/api/debug/stream" && m === "GET") return json(res, 200, debugDump());

    // A client's own recorder ring, posted when it detects an out-of-order
    // snapshot. Ignored entirely when the recorder is off.
    if (p === "/api/debug/report" && m === "POST") {
      const b = await readBody(req);
      recordClientReport(b);
      return json(res, 200, { ok: true, recorded: recorderEnabled });
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
      // `debug` turns the CLIENTS' flight recorders on from the gateway's own
      // env var, so WAKILI_DEBUG_STREAM=1 is the single switch for all three
      // sides instead of a toggle to find on each device.
      res.write(`data: ${JSON.stringify({ type: "_gateway", subtype: "connected", debug: recorderEnabled })}\n\n`);
      const unsub = subscribeAll(res);
      // A DATA ping, not an SSE comment: comments are invisible to the browser's
      // EventSource API, so clients couldn't tell a silent dead socket (phone
      // backgrounded, wifi→cellular) from a quiet stream. A data event resets
      // the client's watchdog; both kinds keep proxies from idling the socket.
      const ping = setInterval(() => res.write(`data: ${JSON.stringify({ type: "_gateway", subtype: "ping" })}\n\n`), 15000);
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
      // The request side of a resync. Its snapshot is recorded by publish()
      // below; having both makes an out-of-order pair visible as two requests
      // answered in the opposite order.
      recordDebug("resync", { sessionId: id, client: (b && b.client) || "", parts: (activeTurns.get(id) || []).length, busy: busy.has(id) });
      publish(id, {
        type: "_gateway", subtype: "snapshot", client: (b && b.client) || "",
        parts: activeTurns.get(id) || [], busy: busy.has(id),
        clientMessageId: activeClientMessageId(id),
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
        res.write(`data: ${JSON.stringify({
          type: "_gateway",
          subtype: "snapshot",
          parts: live || [],
          busy: busy.has(id),
          clientMessageId: activeClientMessageId(id),
        })}\n\n`);
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
        const type = MIME[path.extname(file)] || "application/octet-stream";
        const headers = { "Content-Type": type, "Cache-Control": "no-store, must-revalidate" };
        // Text assets compress well and dominate page-load bytes on tunnel links.
        if (res._gzip && data.length > 1024 && /^(text\/|application\/json|image\/svg)/.test(type)) {
          const gz = await new Promise((r) => zlib.gzip(data, (e, b) => r(e ? null : b)));
          if (gz) {
            headers["Content-Encoding"] = "gzip";
            res.writeHead(200, headers);
            return res.end(gz);
          }
        }
        res.writeHead(200, headers);
        return res.end(data);
      } catch { return notFound(res); }
    }

    notFound(res);
  } catch (e) {
    json(res, 500, { error: String((e && e.message) || e) });
  }
});

// A previous run that died mid-turn left the streamed turn in a sidecar file —
// fold it into its transcript (marked interrupted) before serving anything.
// Auto-continue chats among them get RE-ARMED: a crash/restart that cut an
// unattended task must not end it silently — the transient ladder (first try
// in ~2 min, once the gateway has settled) picks the work back up.
const recoveredIds = await sessionStore.recoverPendingTurns();
let rearmed = 0;
for (const id of recoveredIds) {
  try {
    const s = await sessionStore.get(id);
    if (!s) continue;
    // The promise (or its honest absence) must be IN THE CHAT, not only on the
    // console: "Recovered 1 interrupted turn" read as "it will continue" even
    // for chats whose autoContinue was off, and then nothing visibly happened.
    const note = autoContinueOn(s)
      ? "⏳ This turn was cut off by a gateway restart. It will continue automatically in about 2 minutes."
      : "⚠️ This turn was cut off by a gateway restart. Auto continue is off for this chat — send a message to pick the work back up.";
    s.messages.push({ role: "assistant", parts: [{ type: "text", text: note }] });
    await sessionStore.save(s);
    if (autoContinueOn(s)) {
      rearmed += 1;
      scheduler.onTurnClosed(id, { verdict: "transient", autoContinue: true });
    }
  } catch { /* re-arm is best-effort; the transcript fold above already saved the content */ }
}
if (recoveredIds.length) console.log(`  Recovered ${recoveredIds.length} interrupted turn(s) from the previous run${rearmed ? ` — ${rearmed} will auto-continue in ~2 min` : ""}.`);

// Seed the project registry from folders sessions already point at, so an
// upgrade starts with the projects the user has actually worked in rather than
// an empty list. Idempotent: only paths not already registered are added.
const seededProjects = backfillProjects((await sessionStore.list()).map((s) => s.cwd).filter(Boolean));
if (seededProjects) console.log(`  Registered ${seededProjects} project folder(s) from existing sessions.`);

// Drafts nobody ever wrote in. Anything younger than a day is left alone — its
// client may still be sitting in that chat, about to send the first message.
const prunedDrafts = await sessionStore.pruneDrafts();
if (prunedDrafts) console.log(`  Removed ${prunedDrafts} abandoned draft chat(s).`);


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
  closeWarmClaude(); // kill per-session warm agent processes so they don't outlive the gateway
  closeWarmSdk();
  closeWarmCodex();
  shutdownPower(); // release the keep-awake lock so the machine can sleep again
  process.exit(0);
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

// Node kills the process on an unhandled rejection, so one stray promise in a
// teardown/timer path (nothing on the stack to catch it) drops every live
// session's stream — the exact failure the durability model exists to prevent.
// Log and keep serving: a rejected promise no one awaited never means the
// gateway's own state is unsound. uncaughtException is deliberately NOT trapped
// — that one can leave real state torn, and dying is the honest response.
process.on("unhandledRejection", (reason) => {
  console.error(`  Unhandled rejection (ignored, gateway still up): ${(reason && (reason.stack || reason.message)) || reason}`);
});

// An autostarted instance and a manual launch will eventually race for the
// port. Losing the race means a gateway is already up — that's success, not a
// crash, so exit 0 (also keeps systemd's Restart=on-failure from loop-restarting).
server.on("error", (e) => {
  if (e && e.code === "EADDRINUSE") {
    console.log(`\n  Port ${config.port} is already in use — the gateway looks to be running already.\n`);
    process.exit(0);
  }
  throw e;
});

// Finish the store relocation started in config.mjs: config copied <repo>/data →
// the home store; here we remove the in-repo original. Login autostart's launcher
// lived inside that folder (and, pre-rename, was registered under the old "Remote
// Agent" identity), so reconcileAutostart() carries it over to the new Wakili
// identity/location FIRST — only then is the old copy deleted, and only if that
// succeeded, so a failure never leaves login startup broken.
async function finishStoreRelocation() {
  const oldData = path.join(ROOT, "data");
  try {
    const ok = await reconcileAutostart();
    if (!ok) { console.log(`  Kept ${oldData} (couldn't migrate autostart yet)`); return; }
    if (!fsSync.existsSync(oldData)) return;                                   // nothing to clean up
    if (!fsSync.existsSync(path.join(config.runtimeDir, "token.txt"))) return; // new store not ready — don't delete
    await fsp.rm(oldData, { recursive: true, force: true });
    console.log(`  Session store moved out of the repo → ${config.runtimeDir}`);
  } catch (e) { /* leave the old copy in place if anything goes wrong */ }
}

server.listen(config.port, async () => {
  await finishStoreRelocation();
  // Start-at-login defaults to ON: the first launch ever registers autostart and
  // records it, so the Settings toggle stays the single authority afterwards.
  await enrollAutostartOnce();
  // ...and if it's on, re-register with this install's paths so the login
  // launcher can't go stale when the repo moves or node is upgraded.
  await refreshAutostart();
  // LAN = addresses a phone on the same network could actually reach. Virtual /
  // overlay adapters are excluded: Tailscale's CGNAT range (100.64.0.0/10)
  // would appear mislabeled as "Local network" (Tailscale already gets its own
  // entry), and link-local 169.254.x.x means the adapter never got a real
  // address — a connection row that can't work.
  const VIRTUAL_IF = /tailscale|zerotier|vethernet|wsl|virtualbox|vmware|hyper-v|docker|loopback/i;
  const isCgnat = (a) => { const m = /^100\.(\d+)\./.exec(a); return !!m && +m[1] >= 64 && +m[1] <= 127; };
  const ips = Object.entries(os.networkInterfaces())
    .filter(([name]) => !VIRTUAL_IF.test(name))
    .flatMap(([, nets]) => nets || [])
    .filter((n) => n && n.family === "IPv4" && !n.internal && !isCgnat(n.address) && !n.address.startsWith("169.254."))
    .map((n) => n.address);
  reachable.lan = ips; // remembered for the page's connection switcher
  // Keep the machine awake by default so remote turns aren't suspended by idle
  // sleep while you're away (the screen can still lock). Set WAKILI_KEEP_AWAKE=0
  // to opt out; toggle it live from the app.
  if (process.env.WAKILI_KEEP_AWAKE !== "0") setKeepAwake(true);
  console.log(`\n  Wakili gateway running on :${config.port}\n`);
  console.log(`  Computer:  http://localhost:${config.port}/?t=${config.token}`);
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
