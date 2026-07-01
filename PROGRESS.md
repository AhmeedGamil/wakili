# Remote Agent — progress & handoff

A phone app to drive coding agents (Claude Code, Codex) running on the home
laptop, with **live streaming**, **interactive permissions**, **file send both
ways**, and **per-agent native controls**. Stage 1 = laptop stays on at home,
phone connects in. Stage 2 (later) = agents on a cloud server, no laptop.

Run: `cd d:\remote-agent && node server.mjs` → open the printed `http://<ip>:8730/?t=<token>`
on the phone (same Wi-Fi). Zero npm deps (pure Node). Windows: allow Node through
the firewall on first run.

**Outside home:** `node server.mjs --tunnel tailscale` (private; needs Tailscale on
both devices) or `--tunnel cloudflare` (public throwaway URL; needs `cloudflared`).
`--tunnel both` does both; no flag = auto-detect Tailscale. The printed link carries
a `?t=<token>` that authorizes the device and is saved after first open.

**Phone chats in your Claude Code list:** on by default — they appear in `claude --resume`
and the VS Code Claude Code window automatically. To point them at a different editor, set
`EDITOR_ENTRYPOINT` in `src/config.mjs` or run with `REMOTE_AGENT_CLAUDE_ENTRYPOINT=<value>`.

**Access:** token-only. The QR/link from the laptop carries `?t=<token>` (saved on first open);
that token is the sole credential. Opening the bare URL without a token shows a notice pointing
back to the laptop's link/QR — there's no password. Set a fixed token with `REMOTE_AGENT_TOKEN`,
else one is generated and persisted to `data/token.txt`.

---

## Architecture (layered, SOLID — keep this shape)

**Backend `src/`** (HTTP entry = `server.mjs`)
- `agents/registry.mjs` — Adapter registry. Add an agent = one file + one `register()`.
- `agents/claude.mjs` — Claude adapter. Declares native controls (model/effort/thinking);
  runs `claude -p --output-format stream-json --verbose --include-partial-messages
  --permission-mode default --settings <gen> --mcp-config <gen> --strict-mcp-config`.
- `agents/codex.mjs` — Codex adapter. Runs `codex exec --json`; **normalizes** Codex's
  whole-item events into Claude-shaped events so the UI is unchanged. Native control: reasoning.
- `sessions/store.mjs` — Repository (JSON files in `data/sessions/`). Swap for SQLite later here only.
- `sse.mjs` — Observer/pub-sub per session channel.
- `permissions.mjs` — pending-permission promise registry (resolved by phone or timeout).
- `permission-hook.mjs` — PreToolUse hook (child process); asks gateway → phone, returns allow/deny.
- `mcp-tools.mjs` — minimal hand-rolled MCP stdio server exposing `send_to_user(path)`.

**Frontend `public/js/`** (buildless ES modules)
- `core/` — `store` (state), `emitter` (events), `typewriter` (smooth reveal), `streamParser` (Claude→domain events).
- `controllers/chatController.js` — all business logic; no DOM.
- `components/` — dumb reusable UI: `Sidebar`, `Topbar`, `ModelPicker` + `Dropdown`, `MessageList`, `Composer`.
- `main.js` — composition root (wiring only).

Rule: components never call the API or hold logic; controller never touches the DOM.

---

## DONE & verified on this machine

- **Live streaming** — token-by-token via stream-json deltas, smooth client typewriter.
- **Chat + session continuity** (`--resume` / codex thread), **titles**, **session list**.
- **History persists as ordered `parts`** (text/tool/thinking/file) — commands/thoughts replay on reopen.
- **Interactive permissions** — PreToolUse hook gates `Bash|Write|Edit|MultiEdit|NotebookEdit`;
  phone shows Allow/Deny card (collapses to one line after deciding). Gated tools show **only** the
  card (no duplicate chip); read-only tools show a chip. Tested end-to-end (allow → file written).
- **File send both ways** — agent→phone via `send_to_user` MCP tool (images preview, others download);
  phone→agent via base64 upload → path injected into prompt → agent Reads it. Both tested.
- **Per-agent native controls** — Claude: model (Opus 4.8/Sonnet 4.6/Haiku 4.5) + effort
  (low/med/high/xhigh/max) + thinking; Codex: model (GPT-5.5/5.4/5.4-Mini) + reasoning (incl. Extra High=xhigh).
- **One model picker** — modern dropdowns (Agent / Model / settings); closed shows the model name.
- **Thinking indicator** — neutral pulse while working; "💭 Thinking…" only when real reasoning streams.
- **Dark/light theme**, ChatGPT/Claude-style UI.
- **Phone sessions show in native Claude Code resume list** — phone turns run via headless
  `claude`, which Claude tags `entrypoint: "sdk-cli"` and **hides** from the VS Code window /
  `/resume` picker (even though the transcript is saved + resumable by id). Fix: the adapter sets
  `CLAUDE_CODE_ENTRYPOINT` so the session is tagged with an interactive surface and appears
  automatically (and gets auto-titles too). **`claude-vscode` is verified to list in BOTH the
  VS Code window AND the terminal `claude --resume`** — it's the universal default. Change it
  via the clearly-labeled `EDITOR_ENTRYPOINT` constant at the top of `src/config.mjs`, or per
  launch with the `REMOTE_AGENT_CLAUDE_ENTRYPOINT` env var (override wins). To target another
  editor, read its `CLAUDE_CODE_ENTRYPOINT` and use that value. The two stores are unchanged:
  app copy in `data/sessions/<id>.json`, Claude transcript in
  `~/.claude/projects/<cwd>/<resumeId>.jsonl`. Note: only sessions created *after* this fix are
  listed; older `sdk-cli` chats stay hidden (resumable by id, or backfill the entrypoint line).
- **Permission cluster (rich prompts + modes + auto-approve)** — card now offers **Deny /
  Allow once / Always** ("Always" remembers the tool in `session.allowedTools`). A Claude
  **Permissions** control (`--permission-mode`: Ask / Auto-accept edits / Plan / Allow all)
  drives the gateway's auto-approve so the hook doesn't re-prompt for what the mode grants;
  the last mode is remembered (localStorage) as the default for new sessions. Auto-allow paths
  (acceptEdits→edits, bypass→all, remembered→tool) verified end-to-end.
- **Session grouping by project** — sidebar groups sessions under their `cwd` folder.
- **AskUserQuestion (interactive, in-turn)** — routed through the PreToolUse hook like a
  permission: `AskUserQuestion` is added to the hook matcher, so the gateway forwards the
  question(s) to the phone (`question_request` SSE event) and **blocks the turn** until the
  user picks. The chosen answer is returned to the hook as the tool's *deny reason* — a denied
  tool's reason is surfaced to the model, so the answer reaches the agent **in the same turn**
  (no more "answer on the next turn"). The phone shows a blocking question card (one block per
  question, single/multi-select); submitting POSTs the answer to `/api/sessions/:id/permission`
  with an `answer` field. Auto-approve modes don't apply (a question always needs a real answer).
  The raw `AskUserQuestion` tool_use is suppressed in the UI/history (GATED) since the card shows it.
- **Files panel** — topbar 🗂 modal: image gallery + received files + this-session uploads.
- **Per-session project folder (cwd)** — folder pill in the topbar opens a modal that browses
  the laptop filesystem (`GET /api/folders?path=` → `src/folders.mjs`; drives/home roots, drill
  down, "up"); picking a folder PATCHes the session's `cwd`, which the adapters pass to
  `spawn(..., { cwd })` (Claude + Codex). Changing the folder validates it's a real directory
  (400 if not) and resets `resumeId` so a fresh thread starts there. Verified end-to-end.
- **Token-only access** — the `?t=<token>` from the QR/link is the sole credential (24 random
  bytes, constant-time compared in `authed`). It's captured from the URL and saved to
  localStorage on first open; the connection switcher re-appends it when hopping endpoints. With
  no valid token the app shows a notice pointing back to the laptop's link/QR — there's no
  username/password path to brute-force (the old `admin/admin` login was removed as the weakest
  link, especially over a public tunnel).
- **QR codes at startup** — `src/qr.mjs` is a dependency-free QR encoder (byte mode, ECC level M,
  versions 1–10, Reed–Solomon over GF(256), spec mask selection). The server prints a scannable
  QR **per tokenized URL**: the LAN phone URL always, plus one for each active tunnel (Tailscale /
  Cloudflare) so scanning works outside the home too. `--no-qr` suppresses them (e.g. when piping
  logs). Validated: output is byte-identical to the Python `qrcode` reference for the URL, and the
  rendered terminal QR decodes back to the exact URL via OpenCV's detector. Renders with half-block
  chars — needs a UTF-8 terminal (Windows Terminal is fine; legacy conhost on a non-UTF-8 codepage
  may show mojibake).
- **Outside-home tunnel + token gate** — `src/tunnel.mjs` detects a Tailscale IP and/or
  spawns a Cloudflare quick tunnel (`--tunnel tailscale|cloudflare|both`). A shared secret
  (`config.token`, persisted in `data/token.txt`) gates all `/api/*` and `/internal/*` calls
  via `x-auth-token` header or `?t=` query; static assets and capability-URL file downloads
  (`/api/files/<uuid>`) stay open. Children (hook, MCP) send the token via env-injected header.
  Phone captures `?t=` once → localStorage → URL scrubbed. Verified: 401 without token,
  200 with (header **and** query). Cloudflared child is killed on Ctrl+C.

### Verified environment facts (don't re-investigate)
- Claude Code **2.1.173**: no `--permission-prompt-tool` flag → permissions via PreToolUse hook + `--settings`.
- Codex **0.139.0**: `exec --json` emits whole items (no token deltas); no `models` subcommand.
  Real models from `~/.codex/models_cache.json`: gpt-5.5, gpt-5.4, gpt-5.4-mini. Config uses
  `model_reasoning_effort = "xhigh"` (valid).
- Codex strips env for its own MCP servers (qantara gotcha) — why `send_to_user`/permissions are Claude-only so far.

---

## REMAINING (roadmap)

1. ~~**Tunnel for outside-home use (Stage 1 completion)**~~ — **DONE.** `--tunnel
   tailscale|cloudflare|both`, token-gated. Remaining: install Tailscale (or `cloudflared`)
   on this laptop — neither binary is present yet, so the flags no-op with a friendly message
   until one is installed. Tailscale is the recommended private path.
2. ~~**Point agents at real project folders**~~ — **DONE.** Topbar folder picker → per-session
   `cwd`, passed to both adapters' `spawn(...)`. (Default still `d:\remote-agent` until a folder
   is picked.)
3. **Codex parity** — phone-interactive permissions + `send_to_user` for Codex (needs Codex's
   own approval/MCP-env handling; Claude-only right now).
4. **Voice input** — deferred by user (transcribe on laptop → text before the agent).
5. **Stage 2** — move the same gateway to a cloud server; solve headless login/proxy (see qantara
   proxy notes) so no laptop is needed.
6. **Nice-to-haves** — "Always allow for this session" toggle; persist file-download tokens across
   server restart; rename sessions; markdown rendering of replies.

---

## Gotchas
- Always exit the server with Ctrl+C. If `EADDRINUSE :8730`, a stale node holds the port:
  `for /f "tokens=5" %p in ('netstat -ano ^| findstr :8730 ^| findstr LISTENING') do taskkill /PID %p /F`
- Effort is **not** supported on Haiku (Claude errors) — use Auto with Haiku.
- Prompts are sent to the CLI via **stdin** (never argv) to avoid shell injection.
- Tunnel binaries aren't bundled: `--tunnel tailscale` needs Tailscale up on both devices;
  `--tunnel cloudflare` needs `cloudflared` on PATH. If absent, the server still serves the LAN.
- Cloudflare quick tunnels are **public** — anyone with the URL hits the gateway, so the token
  is the only thing protecting it. To rotate it, delete `data/token.txt` (a new one is generated)
  or set `REMOTE_AGENT_TOKEN`. Lost devices: rotate the token to revoke them.
