# Wakili Android — Latest Chat-State Bugs Handoff

## Scope and current status

- These problems are in `wakili-android`.
- The web client is working correctly and must not be modified.
- The gateway/server supports multiple agents running in different chats at the same time.
- No server or web changes were made during the latest Android configuration work.
- The latest investigation was read-only. None of the bugs below has been fixed yet.
- Do not modify code in the new session until the user explicitly approves the proposed fix.

## Trigger shared by the bugs

The problems are easiest to reproduce when one chat already has an agent running and the user opens or starts another chat. Concurrent turns in separate sessions are supported behavior, not an unsupported edge case.

## Bug 1 — New response is hidden until the chat is reopened

### Reproduction

1. Start an agent turn in chat A and leave it running.
2. Open chat B and start another turn.
3. Chat B can show the user message and working state, but the agent response does not appear live.
4. Leave chat B and open it again.
5. The response then appears.

### Expected behavior

Chat B must render its own streamed response immediately, regardless of chat A running in the background.

### Important implication

The gateway has the response because reopening the session restores it. The failure is in Android's live session projection/rendering, not in generation or persistence on the server.

## Bug 2 — Working pulse and Stop button remain after the turn ends

### Reproduction

1. Reproduce the concurrent-chat situation above.
2. Return to the affected chat while its turn is running.
3. Wait for the turn to finish.
4. The working pulse and red Stop button can remain visible even though the agent has finished.

### Expected behavior

`turn_end`, `stopped`, or an authoritative snapshot/GET with `busy=false` must immediately remove both the pulse and Stop button for that session.

## Is the Stop/Cancel button shared?

- On the server: no. Stop targets `/api/sessions/{sessionId}/stop`, and the running process/thread is stored by session ID.
- On the web: no. The one reusable composer reads `busy` only for the selected session and stops only that session.
- On Android: the button action calls `stop(sessionId)`, so the action itself is session-specific. However, its visibility is derived from Android chat `busy` state, and that state currently depends on a singleton shared "active session" projection. This makes the button capable of appearing stale or effectively leaking running state between chats.

## Bug 3 — Interrupting with a queued message temporarily removes the current turn

### Reproduction

1. Start a turn and let the agent produce visible text/tool content.
2. While the agent is running, send another message so it becomes queued.
3. Press Stop to interrupt the current turn.
4. The visible content from the interrupted turn disappears.
5. Leave the chat and open it again.
6. The content returns from persisted history.

### Expected behavior

The interrupted turn's partial content must remain visible continuously. The stopped marker and queued message may be added, but the existing content must never disappear and reappear.

### Likely direct cause

After Stop, the server emits `stopped`, then persists the partial response and emits `turn_end`. Android immediately dispatches the queued message. Its next `turn_start` resets the single live-turn buffer before the previous interrupted turn has finished moving into rendered history. Reopening fetches persisted history, which is why the content comes back.

## Read-only Android findings

The current architecture is only partially session-scoped:

- `GatewayStreamRepository` stores buffered live turns by session internally.
- But it exposes one global `_activeId`, `liveParts`, `activeBusy`, `cards`, and active client-message ID.
- Every `ChatViewModel` binds to those global active projections rather than a state flow keyed directly by its own `sessionId`.
- `ChatViewModel` calculates visible busy as `live.busy || local turnPhase != Idle`. If a terminal lifecycle event is missed or routed during a session switch, the local phase can keep the pulse and Stop button alive after authoritative busy becomes false.
- Terminal lifecycle events are also delivered through a non-replayed shared event flow. State correctness should not depend on a screen observing every transient event at exactly the right time.
- The cached/fetched detail recovery path can refuse to apply HTTP state when a buffered live state already exists. A stale buffered `busy=true` can therefore survive until a successful resync instead of being corrected immediately by fetched `busy=false`.

High-signal files:

- `wakili-android/core/data/src/main/kotlin/com/ahmedgamil/wakili/core/data/stream/GatewayStreamRepository.kt`
- `wakili-android/feature/chat/src/main/kotlin/com/ahmedgamil/wakili/feature/chat/ChatViewModel.kt`
- `wakili-android/feature/chat/src/main/kotlin/com/ahmedgamil/wakili/feature/chat/ChatScreen.kt`
- `wakili-android/core/data/src/main/kotlin/com/ahmedgamil/wakili/core/data/repository/QueueRepository.kt`
- `wakili-android/core/data/src/test/kotlin/com/ahmedgamil/wakili/core/data/stream/GatewayStreamRepositoryTest.kt`

## Recommended Android-only solution

1. Make the live-turn store and all exposed UI state explicitly keyed by `sessionId`; remove the single globally projected active chat as the source consumed by `ChatViewModel`.
2. Let each `ChatViewModel` observe only `stateFor(sessionId)`.
3. Put optimistic `AwaitingStart`, streamed `Running`, and terminal `Idle` transitions in one per-session reducer/state owner. An authoritative `busy=false` must replace stale local running state, not be OR-ed with it.
4. Derive pulse and Stop visibility only from the selected session's state.
5. Preserve a completed/interrupted turn as a generation-keyed pending history tail until the matching persisted assistant content is present. Starting a queued turn must create a new live region without erasing that pending tail.
6. Apply fetched and resynced `{parts, busy}` for the requested session, including explicit `busy=false`, so reopening is recovery rather than the only way to repair the UI.
7. Keep the web and server untouched.

## Required regression tests before another release

### Concurrent chats

- A is running while B starts.
- A events never change B's live text, pulse, or Stop button.
- B response renders without reopening.
- B `turn_end` removes B's pulse and Stop button immediately while A may remain busy.

### Interrupt plus queue

- Turn 1 has partial visible content.
- Message 2 is queued.
- Stop Turn 1.
- Turn 1 content remains visible while its stopped marker and Message 2 appear.
- Message 2 starts without clearing Turn 1.
- Reopening the chat produces no visual/content difference.

### Recovery

- A missed terminal event followed by GET or snapshot `busy=false` clears the stale pulse and Stop state.
- Rapid A → B → A switching never projects one session's live state into another.

## Latest completed work before these reports

The preceding Android release fixed new-chat configuration pinning and ordered effort/control persistence. New chats now snapshot their complete configuration before navigation, and effort changes apply to the next turn. That work changed Android only. These new live-turn bugs were reported after that release and should be fixed without undoing the configuration work.
