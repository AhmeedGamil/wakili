# Handoff — richer permission card (VS Code-style decisions + scopes)

Pick this up in a fresh chat (set the folder to `d:\remote-agent` so the agent can read this file). It captures a discussion-in-progress; **no code has been written for this yet.**

## Goal
Make the phone's permission card match how VS Code / Claude Code prompts, which **varies**:
- Sometimes just **Yes / No**.
- Sometimes **Yes / Yes-always(→ choose scope) / No**.

The user's open confusion (the thing to resolve FIRST): **what decides which format shows — plain yes/no vs yes + "always with scopes" + no?**

### Best current understanding (VERIFY with the claude-code-guide agent before building)
Claude Code shows the **"always / don't ask again"** option when the action maps to a **savable permission rule** — a tool plus an optional specifier:
- **Bash** → rule per command pattern (e.g. `npm install`, `git *`).
- **Edit/Write/etc.** → rule per tool (optionally per path).
- **WebFetch** → rule per domain.
- **MCP tools** → rule per tool.
When there's a sensible rule to persist, it offers **"always" + a scope**. When there isn't (one-off / non-rememberable action), it's just **Yes / No**.

The **4 scopes** the user saw on "always" are just *where the rule is saved*:
- All projects → `~/.claude/settings.json`
- This project (shared) → `<cwd>/.claude/settings.json` (git-committed)
- This project (you) → `<cwd>/.claude/settings.local.json` (not committed)
- This session → memory only

## Current state of THIS app
- Permissions go through our **PreToolUse hook** (`src/permission-hook.mjs` → `POST /internal/permission` in `server.mjs`), NOT Claude's native settings.
- Card today (in `public/js/components/Dock.js` → `addPermission`): **Deny / Allow once / Always**, where "Always" = **session only** (`session.allowedTools`).
- There's also a separate global **"Allow always" toggle** (client auto-approves everything) — different feature, keep it.

## Proposed design (app-specific, to confirm)
Because our hook is its own system (don't entangle with Claude's settings files), have the app keep its **own** allow-lists at 3 scopes:
- **Allow once** (exists)
- **Always — this session** (exists: `session.allowedTools`)
- **Always — this project** (NEW: keyed by `cwd`, persisted in `data/`)
- **Always — all projects** (NEW: global list in `data/`)
- **Deny** (exists)

Gateway auto-approve check becomes: `session OR project(cwd) OR global`.
Card UI: an **"Always ▾"** that fans out to *This session / This project / All projects*.

Recommended refinements:
- **Collapse** "this project (shared)" vs "(you)" into one "This project" — the git-committed distinction doesn't fit a phone flow. (Could write `<cwd>/.claude/settings.json` if truly wanted, but it tangles our hook with Claude's native rules — not recommended.)
- **Granularity:** per-**command** for Bash (safer; remember the specific command), per-**tool** for the edit tools.

## Three open questions to settle before building
1. Scopes: session / this-project / all-projects — enough? Or really need shared-vs-you?
2. Granularity: per-command Bash + per-tool edits (recommended) — or per-tool for everything?
3. UI: "Always ▾" expanding to scopes, or all buttons shown in one row?

## Files that would change
- `server.mjs` — `/internal/permission` auto-approve check + the `/api/sessions/:id/permission` answer handler (new decision kinds: `allow_project`, `allow_global`), plus persisted project/global allow-lists.
- `src/permissions.mjs` — unchanged (registry already generic).
- `public/js/components/Dock.js` — `addPermission` expanded buttons.
- `public/js/services/api.js`, `public/js/controllers/chatController.js` — pass the new decisions.
- `public/app.css` — the expanded card.
- Needs a **server restart** after.

## How to run / access (current setup)
- Gateway: `node server.mjs` (binds `0.0.0.0:8730`; LAN + Tailscale both work).
- Tailscale (private): PC IP `100.104.128.42` → `http://100.104.128.42:8730/?t=<token>` (needs Tailscale app on phone).
- Cloudflare (no VPN, WebSocket, no buffering): `node cf-bridge.mjs` → prints a `…/cf.html?t=<token>` URL. (New files: `cf-bridge.mjs`, `public/cf-shim.js`, `public/cf.html`.)
- Token is in `data/token.txt`.
