// Scheduled tasks + auto-continue after a usage-limit death. One persistent
// store, two kinds of entries:
//
//   jobs   — "send this text (+ any attached files) into that chat at that
//            time" (one-shot, v1). If the time lands inside a dead quota
//            window the job doesn't fail: it retries until quota is back.
//   resume — sessions whose last turn was cut by the usage limit and whose
//            autoContinue toggle is on: the gateway sends a continue prompt
//            itself once quota returns, so a sleeping user's task finishes.
//
// The store lives in ~/.wakili/schedule.json (atomic tmp+rename writes), so a
// gateway restart loses nothing — same durability rule as everything else.
// This module owns timing and state only; actually starting a turn goes
// through the `dispatch` hook the server wires in (reserveTurn + runTurn),
// so busy-claims and transcript persistence stay in one place.

import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { config } from "./config.mjs";

const STORE = path.join(config.runtimeDir, "schedule.json");

// How a turn DIED decides how (and whether) to retry it. Three tiers:
//   limit     — out of quota. Waiting is free and recovery is certain: retry
//               patiently (15 min × 96 ≈ 24h).
//   transient — network resets, timeouts, API overload, the flaky proxy.
//               These clear in minutes: a short escalating ladder, then stop.
//   permanent — expired auth, invalid request, context overflow, missing
//               binary. Retrying returns the same error forever: fail fast.
// Classification is text-matching over the terminal error, so the patterns are
// deliberately conservative; an error we can't name gets the BOUNDED transient
// ladder, never the 24h one. Extend when new phrasings show up in the wild.
const LIMIT_RE = /(usage|rate|5-hour|weekly|session).{0,20}limit|limit.{0,40}(reset|reached|exceeded)|out of (extra )?usage|quota exhaust|resets? at \d|try again (at|after|in)|hit your usage|\b429\b/i;
const PERMANENT_RE = /authentication|unauthorized|\b401\b|invalid.?api.?key|revoked|credit balance|billing|prompt is too long|context.{0,20}(length|window)|maximum context|invalid_request|not_found_error|ENOENT|not recognized as|command not found/i;
const TRANSIENT_RE = /ECONNRESET|ECONNREFUSED|ETIMEDOUT|EPIPE|ENETUNREACH|EAI_AGAIN|socket hang up|network|connection|timed? ?out|overloaded|\b529\b|\b503\b|\b502\b|\b500\b|internal server error|unable to connect|fetch failed|^API Error/i;

/**
 * "limit" | "transient" | "permanent" | "clean" for a closed turn. A death
 * presents as an error-flagged result, or as a tiny turn (≤2 parts) whose
 * final short text is the error — a long answer that merely MENTIONS limits
 * or networks is a clean close. `parts` is the turn as createTurnIO built it.
 */
export function classifyDeath(parts, resultInfo) {
  // A turn that ended having produced NOTHING — no parts, no terminal result —
  // is a process that died mute (spawn failure, hard kill, cut pipe). That is
  // never a clean finish: calling it clean disarmed the very auto-continue
  // that was about to retry it. The caller overrides for a user-requested Stop.
  if ((!Array.isArray(parts) || parts.length === 0) && !resultInfo) return "transient";
  const errText = resultInfo && resultInfo.isError ? (resultInfo.text || "") : "";
  let tinyText = "";
  if (Array.isArray(parts) && parts.length > 0 && parts.length <= 2) {
    const last = parts[parts.length - 1];
    if (last && last.type === "text" && last.text && last.text.length < 300) tinyText = last.text;
  }
  const text = errText || tinyText;
  if (!text) return "clean";
  if (LIMIT_RE.test(text)) return "limit";
  if (PERMANENT_RE.test(text)) return "permanent";
  if (errText) return "transient"; // an error result we can't name — bounded retry
  return TRANSIENT_RE.test(tinyText) ? "transient" : "clean";
}

// Per-tier retry ladders: delay before attempt N, last step repeating.
const LADDERS = {
  limit: { steps: [15 * 60 * 1000], cap: 96 },
  transient: { steps: [2 * 60 * 1000, 5 * 60 * 1000, 15 * 60 * 1000, 30 * 60 * 1000], cap: 6 },
};
function nextDelay(kind, attempts) {
  const l = LADDERS[kind] || LADDERS.transient;
  return l.steps[Math.min(attempts, l.steps.length - 1)];
}
const TICK_MS = 30 * 1000;

// One prompt for every resume kind — the turn may have died to the usage
// limit, a network failure, or a gateway crash/restart; the agent's own
// thread (--resume) carries the context of what it was doing.
export const AUTO_CONTINUE_PROMPT =
  "[auto] The previous turn was interrupted before it finished (usage limit, crash, or restart). Continue from where you left off.";

export function createScheduler({ dispatch, onGiveUp = () => {}, log = () => {} }) {
  let state = { jobs: [], resume: [] };
  try { state = { jobs: [], resume: [], ...JSON.parse(fs.readFileSync(STORE, "utf8")) }; } catch { /* fresh */ }

  let saving = Promise.resolve();
  function persist() {
    // Serialize writes; tmp+rename so a crash never leaves half a store.
    saving = saving.then(async () => {
      const tmp = STORE + ".tmp";
      await fsp.writeFile(tmp, JSON.stringify(state, null, 2));
      await fsp.rename(tmp, STORE);
    }).catch(() => { /* retried on the next mutation */ });
    return saving;
  }

  // ---- jobs -----------------------------------------------------------------

  function addJob(sessionId, text, at, attachments) {
    const job = { id: crypto.randomUUID(), sessionId, text, at, createdAt: Date.now(), attempts: 0, nextTryAt: at };
    // Files scheduled with the task. Already uploaded (eagerly, on pick), so
    // only their {name, path, url} descriptors ride in the job — the paths
    // stay valid on disk until fire time.
    if (attachments && attachments.length) job.attachments = attachments;
    state.jobs.push(job);
    persist();
    return job;
  }

  function jobsFor(sessionId) {
    return state.jobs.filter((j) => j.sessionId === sessionId).sort((a, b) => a.at - b.at);
  }

  function allJobs() {
    return [...state.jobs].sort((a, b) => a.at - b.at);
  }

  /**
   * Edit a pending job's text and/or time (the Scheduled tasks page).
   * Attachments stay as scheduled. Retry state resets — the new time is a
   * fresh promise, not attempt N of the old one.
   */
  function updateJob(id, { text, at }) {
    const job = state.jobs.find((j) => j.id === id);
    if (!job) return null;
    if (typeof text === "string") job.text = text;
    if (Number.isFinite(at)) job.at = at;
    job.attempts = 0;
    job.nextTryAt = job.at;
    persist();
    return job;
  }

  function countFor(sessionId) {
    return state.jobs.reduce((n, j) => n + (j.sessionId === sessionId ? 1 : 0), 0);
  }

  function removeJob(id) {
    const i = state.jobs.findIndex((j) => j.id === id);
    if (i === -1) return null;
    const [job] = state.jobs.splice(i, 1);
    persist();
    return job;
  }

  function forgetSession(sessionId) {
    state.jobs = state.jobs.filter((j) => j.sessionId !== sessionId);
    state.resume = state.resume.filter((r) => r.sessionId !== sessionId);
    persist();
  }

  // ---- auto-continue --------------------------------------------------------

  function giveUp(sessionId, kind, attempts) {
    log(`resume for ${sessionId} gave up after ${attempts} attempt(s) [${kind}]`);
    try { onGiveUp(sessionId, kind, attempts); } catch { /* reporting must not break the tick */ }
  }

  /** Arm (or back off) a retry after a failed close. Kind change = fresh ladder. */
  function armResume(sessionId, kind) {
    const existing = state.resume.find((r) => r.sessionId === sessionId);
    if (existing && existing.kind === kind) {
      existing.attempts += 1;
      const cap = (LADDERS[kind] || LADDERS.transient).cap;
      if (existing.attempts >= cap) {
        state.resume = state.resume.filter((r) => r !== existing);
        persist();
        giveUp(sessionId, kind, existing.attempts);
        return;
      }
      existing.nextTryAt = Date.now() + nextDelay(kind, existing.attempts);
    } else {
      // New arm, or the failure MODE changed (limit → transient …): a fresh
      // ladder — the old strikes described a different problem. The arming
      // close IS failure #1, so the cap counts real failed turns.
      state.resume = state.resume.filter((r) => r.sessionId !== sessionId);
      state.resume.push({ sessionId, kind, attempts: 1, since: Date.now(), nextTryAt: Date.now() + nextDelay(kind, 0) });
      log(`armed auto-continue for ${sessionId} [${kind}]`);
    }
    persist();
  }

  function disarmResume(sessionId) {
    const before = state.resume.length;
    state.resume = state.resume.filter((r) => r.sessionId !== sessionId);
    if (state.resume.length !== before) persist();
  }

  /**
   * Turn-close hook. `verdict` is classifyDeath's tier; `autoContinue` the
   * session's toggle at close time. A clean close disarms any pending resume
   * (the work finished); limit/transient deaths arm their ladders; a permanent
   * death never retries — it reports and stops. `adopted` marks agent-initiated
   * background continuations: their clean close is some OTHER thread finishing,
   * not the interrupted work this resume promised to re-drive — it must not
   * cancel the pending resume. Their deaths still arm ladders as usual.
   */
  function onTurnClosed(sessionId, { verdict, autoContinue, adopted }) {
    if (verdict === "clean") {
      if (!adopted) disarmResume(sessionId);
      return;
    }
    if (!autoContinue) return;
    if (verdict === "permanent") {
      disarmResume(sessionId);
      giveUp(sessionId, verdict, 0);
      return;
    }
    armResume(sessionId, verdict);
  }

  // ---- the tick -------------------------------------------------------------

  let ticking = false;
  async function tick(now = Date.now()) {
    if (ticking) return; // a slow dispatch must not stack a second pass
    ticking = true;
    try {
      // Resume entries first: they continue work already underway.
      for (const r of [...state.resume]) {
        if (r.nextTryAt > now) continue;
        const res = await dispatch(r.sessionId, AUTO_CONTINUE_PROMPT).catch(() => ({ ok: false, reason: "error" }));
        if (res.ok) {
          // Dispatched. Whether it survives quota is decided at ITS close:
          // another limit death re-arms (attempts+1), a clean close disarms.
          state.resume = state.resume.filter((x) => x !== r);
          persist();
          log(`auto-continue dispatched for ${r.sessionId}`);
        } else if (res.reason === "gone") {
          state.resume = state.resume.filter((x) => x !== r);
          persist();
        } else if (res.reason === "busy") {
          // Not a strike: the running turn's own close decides what's next.
          r.nextTryAt = now + TICK_MS;
          persist();
        } else {
          // A dispatch that failed outright (store error, spawn refusal) IS a
          // strike. Without counting it, a persistent failure retried silently
          // forever — indistinguishable from success, which is the one thing
          // an unattended retry must never be.
          r.attempts += 1;
          const cap = (LADDERS[r.kind] || LADDERS.transient).cap;
          if (r.attempts >= cap) {
            state.resume = state.resume.filter((x) => x !== r);
            persist();
            giveUp(r.sessionId, r.kind || "transient", r.attempts);
          } else {
            r.nextTryAt = now + nextDelay(r.kind || "transient", r.attempts);
            persist();
          }
        }
      }
      for (const j of [...state.jobs]) {
        if (j.nextTryAt > now) continue;
        const res = await dispatch(j.sessionId, j.text, j.attachments).catch(() => ({ ok: false, reason: "error" }));
        if (res.ok) {
          removeJob(j.id); // one-shot: consumed the moment its turn starts
          log(`scheduled job ${j.id} dispatched for ${j.sessionId}`);
        } else if (res.reason === "gone") {
          removeJob(j.id);
        } else {
          j.attempts += 1;
          if (j.attempts >= LADDERS.limit.cap) {
            removeJob(j.id);
            giveUp(j.sessionId, "job", j.attempts);
          } else {
            // Busy chats retry fast (the turn will end); anything else —
            // including a dead quota window discovered at fire time — waits
            // out the patient quota ladder. The turn a limit kills right
            // after start is caught by autoContinue, which schedule() set.
            j.nextTryAt = now + (res.reason === "busy" ? TICK_MS : nextDelay("limit", 0));
            persist();
          }
        }
      }
    } finally {
      ticking = false;
    }
  }

  const timer = setInterval(() => { tick().catch(() => {}); }, TICK_MS);
  timer.unref?.();

  return { addJob, jobsFor, allJobs, updateJob, countFor, removeJob, forgetSession, onTurnClosed, disarmResume, tick };
}
