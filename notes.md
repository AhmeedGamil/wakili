# Notes — requested features / TODO

Backlog captured from the user. Not yet implemented; ordered roughly by priority.

## 1. Rich permission prompts (match Claude's real options) — DONE
- ~~Today the phone shows only **Allow / Deny**.~~ The card now offers three:
  **Deny / Allow once / Always** (the last remembers the tool for the session via
  `session.allowedTools`, auto-approved by the gateway thereafter).
- Note: the headless hook only exposes allow/deny, so we synthesize the
  "don't ask again" option ourselves rather than mirroring Claude's exact prompt.

## 2. Interactive question options (AskUserQuestion parity) — DONE (best-effort)
- An `AskUserQuestion` tool_use is rendered as a card of selectable option buttons
  (header, question, single/multi-select; descriptions as tooltips). The pick is
  sent back as the next message.
- Caveat: in headless `-p` the agent can't truly pause mid-turn for the answer, so
  this surfaces the options and replies on the **next** turn rather than in-place.
  Not yet observed firing live — verify when a real AskUserQuestion occurs.

## 3. Project selection (cwd) — DONE
- ~~Let the user choose which **project folder** a session runs in.~~ Topbar folder
  picker browses the laptop FS and sets the session's `cwd`; both adapters spawn
  with `{ cwd }`. Changing it starts a fresh agent thread in the new folder.
- This unblocks git-in-chat and the git slash commands (`/review`, `/code-review`, …),
  which now operate inside the chosen project.

## 4. Session organization by project — DONE
- The sidebar now groups sessions under their project folder (by `cwd` basename;
  sessions with none fall under "Default folder"). Group order follows the
  newest-first session order.

## 5. Auto-approve controls (permanent / disable) — DONE
- The **Permissions** control (see #6) is the per-session knob: "Ask each time"
  (disable auto-approve), "Auto-accept edits", "Plan mode", "Allow all".
- The last-chosen mode is remembered (localStorage `ra-perm-mode`) as a **global
  default** new sessions open with; per-session changes still win.
- Per-tool "Always" (from #1) is the granular complement.

## 6. Claude operating modes — DONE
- Exposed as the **Permissions** dropdown in the model picker (consistent with the
  other native controls rather than a separate button group): `default` /
  `acceptEdits` / `plan` / `bypassPermissions`.
- Wired to `--permission-mode`; the gateway honors the mode for auto-approve so the
  PreToolUse hook doesn't re-prompt for what the mode already grants.

## 7. Files UI — dedicated sections — DONE
- A 🗂 button in the topbar (with a count badge; hidden when empty) opens a Files
  modal with three sections: **Images from the AI** (thumbnail grid), **Received
  files** (download links), and **Sent from this device** (uploads).
- Collected live per session from the file/upload events. Caveat: not reconstructed
  from history across a server restart (download tokens are in-memory — see
  PROGRESS roadmap "persist file-download tokens").

---

### Fixed already
- Thoughts/thinking block now sits in the same centered column as the answer text
  (was stretching full-width and misaligning on wide screens).
- Slash-command (`<synthetic>`) output now renders + persists (e.g. `/cost`).
- Slash menu lists Claude's real advertised commands (from the `init` event).
