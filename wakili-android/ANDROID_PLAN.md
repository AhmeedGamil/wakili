# Wakili Android — Native Kotlin Client Plan

Native Android app (Kotlin, Jetpack Compose) replicating **100% of the web client's functionality** against the existing gateway (`server.mjs`). No WebView. The gateway API is the contract; the server is unchanged.

---

## 1. Stack (the "what and why")

| Concern | Choice | Why it's the best practice |
|---|---|---|
| Language | Kotlin 2.1.x | Modern standard, coroutines/Flow native |
| UI | Jetpack Compose + Material 3 | Declarative, themable, reusable components by construction |
| Architecture | Clean Architecture, MVVM + UDF (unidirectional data flow) | Business logic lives in domain/data; UI is a pure state renderer |
| DI | Hilt | Compile-time DI, scales across modules |
| Async | Coroutines + Flow/StateFlow | Streaming-first app; everything is a Flow |
| HTTP | OkHttp + Retrofit + kotlinx.serialization | Typed API surface, mature |
| Streaming | okhttp-sse (LAN/Tailscale) + OkHttp WebSocket (Cloudflare `/cf-ws`) behind one `EventStreamClient` interface | Strategy pattern — transport is invisible above the data layer |
| Local cache | Room | Offline-first sessions/messages, outbox, drafts, terminal history |
| Settings | DataStore (Preferences) + Android Keystore-encrypted token | Token is a credential; never plain SharedPreferences |
| Images | Coil 3 | Compose-native, handles auth headers per-request |
| QR scan | CameraX + ML Kit barcode | Same onboarding as the web QR flow |
| Markdown | Custom lightweight renderer in `:core:ui` mirroring `public/js/core/markdown.js` (same feature set: headings, bold/italic/strike, inline+fenced code, links, lists, blockquotes, hr) with golden tests | Guarantees rendering parity with the web client; no library surprises with streaming text |
| Navigation | Navigation Compose (type-safe routes) | Single-activity |
| Localization | `strings.xml` — `en` (default) + `ar` (full RTL) | Per-app language (Android 13 `LocaleManager` + in-app picker for older) |
| Quality | detekt + ktlint, JUnit + MockK + Turbine, Roborazzi screenshot tests, MockWebServer SSE fixtures | Testable by layer |
| Build | Gradle version catalog + convention plugins (`build-logic/`) | One place per decision; scales to many modules |
| Min/target SDK | 26 / 35 | Covers ~98% devices, modern APIs |

**Toolchain note:** use **Gradle 8.14 + AGP 8.11** — Gradle 8.14 is already cached on this machine (`~/.gradle/wrapper/dists`), which sidesteps the throttled-download problem that blocked the Expo build.

Project lives at `wakili-android/` (standalone Gradle project; the Expo `mobile/` folder stays untouched until this replaces it).

---

## 2. Module graph

```
wakili-android/
├── build-logic/                 # convention plugins (android-library, compose, hilt, test)
├── app/                         # NavHost, DI graph, MainActivity — wiring only
├── core/
│   ├── model/                   # pure Kotlin domain models (no Android deps)
│   ├── common/                  # Result, dispatchers, clock, di qualifiers
│   ├── network/                 # Retrofit API, DTOs, SSE/WS EventStreamClient, mappers
│   ├── database/                # Room: sessions, messages, outbox, drafts, files, term history
│   ├── datastore/               # settings, connection profile, encrypted token
│   ├── data/                    # repositories = single source of truth (offline-first)
│   ├── domain/                  # use cases; the ONLY door ViewModels use
│   ├── designsystem/            # theme (dark/light, accent palette), atoms: buttons, chips, switches, dropdowns, sheets
│   └── ui/                      # composite components: MarkdownText, TypewriterText, ToolCard, PermissionCard, QuestionCard, MessageBubble, Toast host
└── feature/
    ├── connect/                 # QR scan, manual URL, token gate, endpoint switcher
    ├── sessions/                # list, by-project grouping, badges, new chat, rename/delete
    ├── chat/                    # message list, composer, dock (permission/question), model picker, folder chip
    ├── terminal/                # stateful shell page
    ├── files/                   # images/files tabs, uploads/downloads
    └── settings/                # appearance, language, device controls
```

**Dependency rule (enforced by module boundaries):** `feature → domain → data → (network|database|datastore)`. Features never see Retrofit/Room. `core:model` is dependency-free. UI components in `designsystem`/`ui` are stateless and reusable — they receive state and emit events, never call anything.

**SOLID mapping:**
- **S** — one module/one reason to change; use cases are single-verb (`SendMessage`, `AnswerPermission`, `ObserveSessionEvents`).
- **O** — agent controls UI is **manifest-driven** from `/api/agents` (dropdowns generated from `controls{}`): a new agent or new control on the server requires **zero client changes**. Tool cards use a registry keyed by tool name with a default renderer fallback.
- **L** — `SseStreamClient` and `CfWebSocketStreamClient` are interchangeable `EventStreamClient`s.
- **I** — repositories are per-concern interfaces (`SessionRepository`, `GatewayStreamRepository`, `FileRepository`, `DeviceRepository`, `FolderRepository`, `AgentRepository`, `SettingsRepository`).
- **D** — domain depends on repository interfaces; `core:data` implements them; Hilt binds.

---

## 3. The streaming core (hardest part, built first)

Replicates `chatController.js` + `streamParser.js` semantics exactly, but in the data layer:

```
EventStreamClient (SSE or CF-WebSocket)          ← transport strategy
  └─> Flow<GatewayEvent>                          ← typed sealed interface, parsed DTOs
       └─> TurnAssembler                          ← rebuilds ordered parts[] (text/thinking/tool/file)
            └─> GatewayStreamRepository           ← multiplex router:
                 • active-session events → live turn StateFlow
                 • other sessions → busy/unread/pending badge flows + Room cache invalidation
                 • permission_request / question_request → DockRepository queue
                 • request_resolved → drop card
                 • connected(first) → resync active; connected(later) → refreshSessions + resync
                 • snapshot gating: suppress deltas between session switch and snapshot (10s self-clearing), tagged by clientId
```

- **One** multiplexed stream (`GET /api/stream?t=`), exactly like the web client. Per-session endpoint exists but is unused — same here.
- Reconnect: exponential backoff (1.5s base like cf-shim), `resync` POST with `clientId` on reopen.
- Cloudflare endpoints detected by profile → WebSocket `/cf-ws?path=/api/stream?t=…`, JSON payloads identical.
- **Golden tests:** record real SSE transcripts from the gateway into fixtures; TurnAssembler must reproduce the exact `parts[]` the server persists. MockWebServer replays fixtures incl. reconnect/snapshot/dedupe cases.

Send pipeline (per-session, mirrors web):
- **Outbox** (Room): FIFO chain per session, in-flight → sent/failed, retry/discard actions, survives process death.
- **Queued** (Room): messages typed while busy; one dispatched per `turn_end`; cancellable chip.
- 409 busy → auto-queue; 202 → sent.

---

## 4. Feature parity checklist (every item = the web behavior, verified against `public/js`)

### Connect & auth
- [ ] QR scan (CameraX/ML Kit) of the gateway QR (`http://…/?t=token`), manual URL entry fallback
- [ ] Token captured, stored encrypted; all calls send `x-auth-token`; stream uses `?t=`
- [ ] Token gate screen on 401/no-token (points user to laptop QR)
- [ ] Connection switcher: `GET /api/endpoints`, current marked, switch = swap base URL (token kept), sessions identical
- [ ] Cloudflare profile → cf.html-equivalent transport (WebSocket shim path)
- [ ] Version-skew warning: API ok but stream not connected in 6s → toast "restart the gateway"
- [ ] Timeouts: 15s default, 90–120s exec/upload; "Can't connect — retrying…" toast

### Sessions
- [ ] List newest-first with `{title, agentId, model, cwd, busy, pending}`
- [ ] Two view modes persisted: **By project** (grouped by cwd, per-group ➕ new chat) / **All chats** (folder badge)
- [ ] Status flags priority: pending (waiting for answer) > busy (working pulse) > unread (new reply)
- [ ] Unread set on background `turn_end`, cleared on open
- [ ] Create (agent+model+cwd), rename (PATCH title), delete, "Select project" primary flow
- [ ] Session cache (Room) for instant switching; per-session scroll position restored

### Chat
- [ ] Send text + attachments (`POST messages` 202/409/400 handling)
- [ ] Stop button morphs: Stop (■) only while busy & box empty; Send (↑) once typing (queue)
- [ ] Queued chip: first message + "(+N more)", ✕ cancels all
- [ ] Outbox rows with retry/discard on failure
- [ ] Per-session drafts (text + attachments + scroll), restored on switch, survive app restart (Room)
- [ ] Typewriter smooth streaming (text + thinking deltas)
- [ ] "Thinking…" pulse → collapsible "Thoughts" section
- [ ] Markdown rendering + global toggle (re-renders in place from raw)
- [ ] Stick-to-bottom with threshold; interrupted/stopped markers
- [ ] `!cmd` exec output blocks (not persisted)
- [ ] Slash-command menu from the agent's real `commands[]` (↑/↓/Enter/Tab)
- [ ] Attachments: eager upload w/ progress ring, retry overlay, ✕ remove (abort+delete), image thumbs, lightbox

### Tool cards
- [ ] Per-tool icon/label/subtitle: Bash/PowerShell (terminal + desc), Edit-family (pencil), Read (+line range), Grep (+pattern), Glob (+pattern), Task/Agent (bot), fallback wrench
- [ ] Expandable bodies: Edit/Write/MultiEdit/NotebookEdit red-green diff; Bash command; JSON pretty fallback
- [ ] Badges: "N lines", "Added/Removed N", "Modified", "N matches" (filled when output arrives)
- [ ] Output attaches by tool id (FIFO fallback), error tint on `isError`

### Permission cards (dock, pinned above composer)
- [ ] Queue + batch bar (front card, "+N more", Allow all / Deny all)
- [ ] Deny / Allow once / **Always** (`allow_session` → allowedTools)
- [ ] Inline diff for Edit/Bash inside the card
- [ ] Global **Auto-allow** switch (instant resolve, no card)
- [ ] Decided records archived into history (expandable)
- [ ] `request_resolved` → card dropped (answered elsewhere/timeout)
- [ ] Composer blocked while a card is up

### Question cards (`ask_options`)
- [ ] Multi-question tabs with done states
- [ ] Radio (single) / checkbox (multi); every question gets **Other** + free text
- [ ] Lone single-select auto-submits; "Send answers" gated until all answered
- [ ] Answered record archived

### Agent controls (manifest-driven)
- [ ] `/api/agents` → dynamic control panel: Agent picker, Model, then every control in `controls{}` (claude: effort/thinking/permissionMode; codex: reasoning/approval) — rendered generically
- [ ] Defaults layering: agent default → last-used (`ra-last-config` equiv) → global permissionMode → session's stored controls; stale-value normalization
- [ ] Switching agent resets controls to that agent's defaults; agent/cwd change ⇒ fresh thread (server resets resumeId)
- [ ] Closed picker shows current model label

### Files
- [ ] Registry list; Images/Files tabs; each split "Attached by you" / "Sent by the agent"
- [ ] Thumbnail grid (tap = full-screen viewer); downloads via DownloadManager (capability URLs need no auth header)
- [ ] Upload base64 (`POST /api/upload`), delete own upload
- [ ] Agent file cards in chat: image thumb or download link + caption

### Terminal page
- [ ] Stateful `POST term` with cwd tracking (`cd` updates header without spawning)
- [ ] Scrollback, Enter runs / Shift+Enter newline
- [ ] `/` command-history menu (Room, ≤200, newest-first)
- [ ] Interactive-program guard (vim/nano/top/ssh/REPLs blocked with hint)

### Folder picker
- [ ] Browse roots (Home + drives), drill down, up, breadcrumb
- [ ] Create new folder; "Use this folder"
- [ ] Used for new-chat cwd and changing session cwd (topbar chip)

### Device menu
- [ ] Lock screen / Turn off screen / (lock&off) momentary actions
- [ ] Keep awake stateful toggle; Autostart toggle (hidden when unsupported)

### Settings & appearance
- [ ] Theme: dark / light / follow system
- [ ] Accent palette (16 swatches incl. Claude `#d97757`, default `#6d5cf0`) driving Material scheme + readable on-accent color
- [ ] Markdown toggle
- [ ] **Language picker: English / العربية** with full RTL layout audit
- [ ] All persisted keys mapped from web localStorage → DataStore

### Android-native additions (post-parity, optional)
- [ ] Notifications for `turn_end` / `permission_request` while app is backgrounded (foreground "connected" service, user-toggleable)
- [ ] Share-into-Wakili (share sheet → attachment)
- [ ] App shortcuts (new chat in recent project)

---

## 5. Phases (each ends runnable + tested)

| # | Phase | Contents | Acceptance |
|---|---|---|---|
| 0 | Skeleton | build-logic, catalogs, modules, detekt/ktlint, theme (dark/light/accent), en+ar scaffolding, nav shell | `assembleDebug` green; screenshot tests of theme atoms |
| 1 | Protocol core | `core:network` (all endpoints typed), SSE+WS clients, TurnAssembler, models | Golden fixture tests reproduce recorded real transcripts incl. reconnect/snapshot |
| 2 | Connect | QR scan, manual entry, encrypted token, gate screen, endpoint profiles | Fresh install → scan → sessions visible on phone against live gateway |
| 3 | Sessions | list/grouping/badges/CRUD, Room cache, offline open | Kill server → cached sessions still browsable; badges update live |
| 4 | Chat core | send/stream/typewriter/markdown/thinking/stop/queued/outbox/drafts | Full conversation with Claude from the phone; airplane-mode send lands in outbox and retries |
| 5 | Cards | tool cards, permission dock, questions, auto-allow, permission modes | End-to-end: gated Write → card → Allow → file written; ask_options round-trip |
| 6 | Files & attachments | uploads w/ progress, files page, downloads, lightbox, captions | Photo → agent reads it; agent `send_to_user` → appears + downloads |
| 7 | Controls & tools | model picker, dynamic controls, slash menu, folder picker/cwd, terminal | Switch agent/model/effort mid-session; terminal `cd`+history verified |
| 8 | Device & settings | device menu, connection switcher UI, appearance, language, version-skew, toasts | Switch LAN→Tailscale live; Arabic RTL pass; lock screen from phone |
| 9 | Hardening & release | reconnect chaos tests, TalkBack/contrast audit, baseline profile, R8, signing | Signed release APK; feature-parity checklist 100% ticked against web app side-by-side |
| 10 | Native extras | background notifications service, share-into-app | Permission request notifies phone with screen off |

Testing per phase: unit (domain/data), Turbine flow tests, MockWebServer SSE fixtures, Compose UI tests for cards/composer, Roborazzi snapshots (light/dark/RTL), and a live smoke script against the real gateway via `adb reverse tcp:8730 tcp:8730`.

---

## 6. Risks & mitigations

- **SSE through OkHttp on flaky networks** → transport already abstracted; aggressive-but-bounded backoff; resync-on-reconnect is the source of truth (snapshot), not the delta tail.
- **Typewriter + markdown streaming** → stream plain text live, promote block to markdown when it settles (same visual behavior as web, which re-renders per delta anyway).
- **Cloudflare WS shim differences** → dedicated fixture tests recorded from a real `cf-bridge` run.
- **Codex asymmetries** (no permission cards, approval control, whole-item events) → manifest-driven UI already handles it; TurnAssembler treats whole-item text as one delta.
- **Gradle/network** → pinned to the locally-cached Gradle distribution; dependency downloads are many small parallel fetches (unaffected by single-stream throttling).
