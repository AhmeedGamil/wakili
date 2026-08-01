// Sessions that were started on the HOST — the machine the gateway runs on —
// rather than through Wakili. Claude writes one JSONL transcript per session
// under ~/.claude/projects/<slug>/<session-id>.jsonl; Codex writes
// ~/.codex/sessions/<y>/<m>/<d>/rollout-<ts>-<id>.jsonl. Both CLIs can resume by
// id, and a Wakili session already carries the agent's resume id, so a host
// session can be adopted and continued from the phone.
//
// Three rules make this list usable instead of overwhelming:
//
//  1. DEDUP. Every Wakili chat ALSO writes a host transcript — the CLI doesn't
//     know who called it. Listing those back would show the user their own phone
//     chats under "From the host". A transcript whose id is any Wakili session's
//     resumeId is therefore skipped: it isn't a host session, it's a Wakili one
//     seen from the other side.
//  2. SCOPE. There are thousands of transcripts here, including junk cwds
//     (C:\Windows\System32, temp dirs) from one-off CLI invocations. Only
//     folders the user actually works in — the project registry — are offered,
//     unless the caller explicitly asks for everything.
//  3. PAGING. The caller takes a few at a time (cursor-based, newest first), so
//     no client ever has to hold the whole set.
//
// Header parsing is cached by (mtime, size), so the expensive part happens once
// per file per change: a cold scan of ~2,000 transcripts takes ~2s, a warm one
// costs a stat per file.

import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import os from "node:os";

const CLAUDE_PROJECTS = path.join(process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), ".claude"), "projects");
const CODEX_SESSIONS = path.join(process.env.CODEX_HOME || path.join(os.homedir(), ".codex"), "sessions");

const TITLE_MAX = 80;
// How far into a transcript to look for the cwd and the first real user line.
// Codex puts a multi-KB instruction blob in its very first record, and Claude
// can open with a run of queue-operation lines, so a small peek isn't enough.
const HEAD_BYTES = 96 * 1024;

// filePath -> { mtimeMs, size, entry }. `entry` is null for files that parsed to
// nothing (not a session, or unreadable) — cached too, so they aren't re-read.
const index = new Map();

/** Read the first `HEAD_BYTES` of a file and return its COMPLETE lines. */
async function headLines(file) {
  let fh;
  try {
    fh = await fsp.open(file, "r");
    const buf = Buffer.alloc(HEAD_BYTES);
    const { bytesRead } = await fh.read(buf, 0, HEAD_BYTES, 0);
    const text = buf.subarray(0, bytesRead).toString("utf8");
    const lines = text.split("\n");
    // The last piece is only whole if the read ended exactly on a newline.
    if (!text.endsWith("\n")) lines.pop();
    return lines;
  } catch {
    return [];
  } finally {
    await fh?.close().catch(() => {});
  }
}

// A user turn often opens with machinery the user never typed — slash-command
// envelopes, injected reminders, IDE context, environment blocks — all of it
// shaped like <tag>…</tag> or a bare <tag …>. Strip those off the FRONT until
// real prose is left; if nothing is, this turn has no title in it and the next
// one is tried. Generic on purpose: new wrappers appear over time, and a fixed
// list of tag names silently rots into wrong titles.
function humanText(raw) {
  let s = String(raw || "");
  for (let i = 0; i < 8; i++) {
    // A complete <tag>…</tag> block goes first and, crucially, ALONE: running
    // the bare-tag rule in the same pass would strip the NEXT block's opening
    // tag and leave its innards behind as the title ("usage</command-message>…").
    const paired = s.replace(/^\s*<([a-zA-Z][\w.-]*)\b[^>]*>[\s\S]*?<\/\1>\s*/, "");
    if (paired !== s) { s = paired; continue; }
    // Only once no block matches: an unclosed or self-closing tag.
    const bare = s.replace(/^\s*<[a-zA-Z][\w.-]*\b[^>]*\/?>\s*/, "");
    if (bare === s) break;
    s = bare;
  }
  return s.trim();
}

// Codex opens a thread by injecting the project's AGENTS.md and environment as
// USER-role messages. They aren't tag-wrapped, so humanText can't see them —
// but attributing them to the user would put a wall of config in their bubble.
const INJECTED = /^#+\s*(AGENTS\.md|Environment|Instructions)\b|^<INSTRUCTIONS>|^<user_instructions>/i;

/** What the user actually typed, or "" when the turn is injected machinery. */
const userText = (raw) => {
  const s = humanText(raw);
  return INJECTED.test(s) ? "" : s;
};

const clean = (s) =>
  String(s || "").replace(/\s+/g, " ").trim().slice(0, TITLE_MAX);

/** First human line out of a Claude `message.content` (string or block array). */
function claudeText(message) {
  if (!message) return "";
  const c = message.content;
  if (typeof c === "string") return c;
  if (!Array.isArray(c)) return "";
  for (const block of c) {
    if (block && block.type === "text" && block.text) return block.text;
  }
  return "";
}

/** Parse one Claude transcript's header: { id, cwd, title, updatedAt }. */
async function parseClaude(file, id, stat) {
  let cwd = null;
  let title = "";
  for (const line of await headLines(file)) {
    if (!line.trim()) continue;
    let ev;
    try { ev = JSON.parse(line); } catch { continue; }
    if (!cwd && ev.cwd) cwd = ev.cwd;
    // A resumed session opens with a summary of what came before — a better
    // title than the first prompt, which may just be "continue".
    if (!title && ev.type === "summary" && ev.summary) title = clean(ev.summary);
    if (!title && ev.type === "user" && !ev.isSidechain && !ev.isMeta) {
      title = clean(userText(claudeText(ev.message)));
    }
    if (cwd && title) break;
  }
  // No human prompt anywhere in the head = nothing the user ever said. That's
  // not a session worth offering to resume, and it's also how the machine-made
  // transcripts get filtered: Wakili's own `/cost` model probes (four per
  // gateway start, one per model family) each leave a transcript whose only
  // content is a slash-command envelope.
  if (!cwd || !title) return null;
  return { id, agentId: "claude", cwd, title, updatedAt: stat.mtimeMs, file };
}

/** First human line out of a Codex `payload.content` block array. */
function codexText(payload) {
  const c = payload?.content;
  if (typeof c === "string") return c;
  if (!Array.isArray(c)) return "";
  for (const block of c) {
    if (block && (block.type === "input_text" || block.type === "text") && block.text) return block.text;
  }
  return "";
}

/** Parse one Codex rollout's header. Returns null for subagent threads. */
async function parseCodex(file, stat) {
  let meta = null;
  let title = "";
  for (const line of await headLines(file)) {
    if (!line.trim()) continue;
    let ev;
    try { ev = JSON.parse(line); } catch { continue; }
    if (!meta && ev.type === "session_meta" && ev.payload) {
      meta = ev.payload;
      // Sub-agent threads are internal to another session, not something the
      // user started or can meaningfully resume on their own.
      if (meta.thread_source === "subagent" || meta.source?.subagent) return null;
      continue;
    }
    if (!title && ev.type === "response_item" && ev.payload?.type === "message" && ev.payload.role === "user") {
      title = clean(userText(codexText(ev.payload)));
    }
    if (meta && title) break;
  }
  if (!meta || !title) return null; // same rule as Claude: no human turn, no entry
  const id = meta.session_id || meta.id;
  if (!id) return null;
  return { id, agentId: "codex", cwd: meta.cwd || null, title, updatedAt: stat.mtimeMs, file };
}

/** Cached header read — reparses only when the file changed underneath us. */
async function entryFor(file, parse) {
  let stat;
  try { stat = await fsp.stat(file); } catch { index.delete(file); return null; }
  const hit = index.get(file);
  if (hit && hit.mtimeMs === stat.mtimeMs && hit.size === stat.size) return hit.entry;
  const entry = await parse(stat);
  index.set(file, { mtimeMs: stat.mtimeMs, size: stat.size, entry });
  return entry;
}

async function scanClaude() {
  let dirs = [];
  try { dirs = await fsp.readdir(CLAUDE_PROJECTS); } catch { return []; }
  const out = [];
  for (const dir of dirs) {
    let files = [];
    try { files = await fsp.readdir(path.join(CLAUDE_PROJECTS, dir)); } catch { continue; }
    for (const name of files) {
      if (!name.endsWith(".jsonl")) continue;
      const file = path.join(CLAUDE_PROJECTS, dir, name);
      const id = name.slice(0, -".jsonl".length);
      const entry = await entryFor(file, (stat) => parseClaude(file, id, stat));
      if (entry) out.push(entry);
    }
  }
  return out;
}

async function scanCodex() {
  const out = [];
  const walk = async (dir) => {
    let items = [];
    try { items = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
    for (const item of items) {
      const full = path.join(dir, item.name);
      if (item.isDirectory()) { await walk(full); continue; }
      if (!/^rollout-.*\.jsonl$/.test(item.name)) continue;
      const entry = await entryFor(full, (stat) => parseCodex(full, stat));
      if (entry) out.push(entry);
    }
  };
  await walk(CODEX_SESSIONS);
  return out;
}

// Windows paths are case-insensitive; match the project registry's own key rule.
const pathKey = (p) => {
  const s = String(p || "").trim().replace(/[\\/]+$/, "");
  return process.platform === "win32" ? s.toLowerCase().replace(/\//g, "\\") : s;
};

// Newest first, id breaking ties so the order is total and stable — a cursor
// can then name an exact position instead of an offset that shifts when new
// transcripts appear.
const byRecency = (a, b) => (b.updatedAt - a.updatedAt) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
const after = (entry, cursor) =>
  entry.updatedAt < cursor.updatedAt || (entry.updatedAt === cursor.updatedAt && entry.id > cursor.id);

export const encodeCursor = (e) => `${e.updatedAt}:${e.id}`;
function decodeCursor(raw) {
  if (!raw) return null;
  const at = String(raw).indexOf(":");
  if (at < 0) return null;
  const updatedAt = Number(String(raw).slice(0, at));
  const id = String(raw).slice(at + 1);
  return Number.isFinite(updatedAt) && id ? { updatedAt, id } : null;
}

/**
 * One page of host sessions, newest first.
 *
 * @param ownedIds   resume ids already owned by Wakili sessions — skipped (see rule 1)
 * @param scopePaths folders to offer; empty or `all` means every folder (rule 2)
 * @param agentId    "claude" | "codex" | falsy for both
 * @param cursor     opaque marker from a previous page's `nextCursor` (rule 3)
 */
export async function listHostSessions({
  ownedIds = new Set(),
  scopePaths = [],
  agentId = "",
  cursor = "",
  limit = 5,
  all = false,
} = {}) {
  const [claude, codex] = await Promise.all([scanClaude(), scanCodex()]);
  const scope = new Set(scopePaths.map(pathKey).filter(Boolean));
  const from = decodeCursor(cursor);

  const matches = [...claude, ...codex].filter((e) => {
    if (agentId && e.agentId !== agentId) return false;
    if (ownedIds.has(e.id)) return false;
    if (!all && scope.size) {
      if (!e.cwd || !scope.has(pathKey(e.cwd))) return false;
    }
    return true;
  });

  matches.sort(byRecency);
  const page = [];
  for (const e of matches) {
    if (from && !after(e, from)) continue;
    if (page.length >= limit) break;
    page.push(e);
  }
  const last = page[page.length - 1];
  // total is the size of the FILTERED set — what "Load more" is counting down.
  return {
    sessions: page.map((e) => ({
      id: e.id,
      agentId: e.agentId,
      cwd: e.cwd || null,
      title: e.title || "(no prompt yet)",
      updatedAt: Math.round(e.updatedAt),
      host: true,
    })),
    nextCursor: last && matches.some((e) => after(e, { updatedAt: last.updatedAt, id: last.id }))
      ? encodeCursor(last)
      : null,
    total: matches.length,
  };
}

/** One host session by id, with the file to read its transcript from. */
export async function findHostSession(id) {
  const [claude, codex] = await Promise.all([scanClaude(), scanCodex()]);
  return [...claude, ...codex].find((e) => e.id === id) || null;
}

// ---- transcript import -------------------------------------------------------
// Adopting a host session shows its conversation in Wakili. Only what the chat
// pane can render is carried over — user text, assistant text, thinking, and the
// NAMES of tools that ran. The agent's own context is not reconstructed from
// this and doesn't need to be: the adopted session resumes by id, so the CLI
// reloads the real thing, tool results and all.

const MAX_TRANSCRIPT_BYTES = 24 * 1024 * 1024;
const MAX_IMPORTED_MESSAGES = 400;

/** Append `part` to `msg`, merging consecutive text so it renders as one block. */
function addPart(parts, part) {
  const last = parts[parts.length - 1];
  if (part.type === "text" && last && last.type === "text") last.text += part.text;
  else if (part.type === "thinking" && last && last.type === "thinking") last.text += part.text;
  else parts.push(part);
}

function importClaude(lines) {
  const messages = [];
  for (const line of lines) {
    if (!line.trim()) continue;
    let ev;
    try { ev = JSON.parse(line); } catch { continue; }
    if (ev.isSidechain || ev.isMeta) continue; // subagent traffic isn't this conversation
    const content = ev.message?.content;
    if (ev.type === "user") {
      // Tool RESULTS arrive as user-role messages. Rendering them as the user's
      // words would put text they never wrote in their own bubbles.
      if (Array.isArray(content) && content.some((b) => b?.type === "tool_result")) continue;
      const text = userText(claudeText(ev.message));
      if (text) messages.push({ role: "user", text });
    } else if (ev.type === "assistant" && Array.isArray(content)) {
      const parts = [];
      for (const b of content) {
        if (b?.type === "text" && b.text) addPart(parts, { type: "text", text: b.text });
        else if (b?.type === "thinking" && b.thinking) addPart(parts, { type: "thinking", text: b.thinking });
        else if (b?.type === "tool_use") addPart(parts, { type: "tool", name: b.name, input: b.input, id: b.id });
      }
      if (parts.length) messages.push({ role: "assistant", parts });
    }
  }
  return messages;
}

function importCodex(lines) {
  const messages = [];
  for (const line of lines) {
    if (!line.trim()) continue;
    let ev;
    try { ev = JSON.parse(line); } catch { continue; }
    const p = ev.payload;
    if (ev.type !== "response_item" || !p) continue;
    if (p.type === "message" && p.role === "user") {
      const text = userText(codexText(p));
      if (text) messages.push({ role: "user", text });
    } else if (p.type === "message" && p.role === "assistant") {
      const text = codexText(p) || (Array.isArray(p.content)
        ? p.content.map((b) => b?.text).filter(Boolean).join("")
        : "");
      if (text) messages.push({ role: "assistant", parts: [{ type: "text", text }] });
    } else if (p.type === "reasoning") {
      const text = (p.summary || []).map((s) => s?.text).filter(Boolean).join("\n");
      if (text) messages.push({ role: "assistant", parts: [{ type: "thinking", text }] });
    } else if (p.type === "function_call" && p.name) {
      let input;
      try { input = JSON.parse(p.arguments); } catch { input = p.arguments; }
      messages.push({ role: "assistant", parts: [{ type: "tool", name: p.name, input, id: p.call_id }] });
    }
  }
  return messages;
}

/**
 * The host session's conversation in Wakili's message format, newest last.
 * Returns { messages, truncated } — `truncated` when only the tail was kept.
 */
export async function readHostTranscript(entry) {
  let raw = "";
  try {
    const stat = await fsp.stat(entry.file);
    if (stat.size > MAX_TRANSCRIPT_BYTES) {
      // Read the TAIL of a huge transcript: the recent conversation is what a
      // user continuing the session needs to see.
      const fh = await fsp.open(entry.file, "r");
      try {
        const buf = Buffer.alloc(MAX_TRANSCRIPT_BYTES);
        const { bytesRead } = await fh.read(buf, 0, MAX_TRANSCRIPT_BYTES, stat.size - MAX_TRANSCRIPT_BYTES);
        raw = buf.subarray(0, bytesRead).toString("utf8");
        raw = raw.slice(raw.indexOf("\n") + 1); // drop the partial first line
      } finally {
        await fh.close();
      }
    } else {
      raw = await fsp.readFile(entry.file, "utf8");
    }
  } catch {
    return { messages: [], truncated: false };
  }
  const lines = raw.split("\n");
  const all = entry.agentId === "codex" ? importCodex(lines) : importClaude(lines);
  const truncated = all.length > MAX_IMPORTED_MESSAGES;
  return { messages: truncated ? all.slice(-MAX_IMPORTED_MESSAGES) : all, truncated };
}

/** Warm the header cache in the background so the first list isn't the slow one. */
export function warmHostSessions() {
  return Promise.all([scanClaude(), scanCodex()]).then(
    ([a, b]) => a.length + b.length,
    () => 0,
  );
}

export const hostSessionPaths = { CLAUDE_PROJECTS, CODEX_SESSIONS };
