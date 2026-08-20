package com.ahmedgamil.wakili.core.data.stream

import android.content.Context
import com.ahmedgamil.wakili.core.common.AppForeground
import com.ahmedgamil.wakili.core.common.Toaster
import com.ahmedgamil.wakili.core.data.R
import com.ahmedgamil.wakili.core.data.di.AppScope
import com.ahmedgamil.wakili.core.data.repository.SessionRepository
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.dto.ResyncBody
import com.ahmedgamil.wakili.core.network.stream.EventStreamClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The client-side twin of chatController.js's stream handling: ONE multiplexed
 * stream for the app's lifetime. Events for the active (open) session drive the
 * live turn (via TurnReducer) and the card dock; every session's events drive
 * busy/unread bookkeeping for the list badges.
 *
 * Opening a session first projects its app-lifetime buffer, then requests an
 * ordered snapshot. Deltas keep updating the buffer while that request is in
 * flight; the snapshot authoritatively replaces them at its stream position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GatewayStreamRepository @Inject constructor(
    private val factory: EventStreamClientFactory,
    private val connection: GatewayConnection,
    private val api: GatewayApi,
    private val sessions: SessionRepository,
    private val toaster: Toaster,
    private val notifier: GatewayNotifier,
    private val questionOverlay: QuestionOverlay,
    private val appForeground: AppForeground,
    private val settingsStore: SettingsStore,
    private val recorder: StreamRecorder,
    @ApplicationContext private val appContext: Context,
    @AppScope private val scope: CoroutineScope,
) {

    /** Prefix for epoch-tagged resyncs so stale/foreign snapshots are ignored. */
    private val clientId = "and-" + UUID.randomUUID().toString().take(8)

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    /**
     * Stream-authoritative busy values, including explicit false. The explicit
     * idle override is important: a slower HTTP list response may still contain
     * busy=true after turn_end and must not resurrect the sidebar pulse.
     */
    private val _busyOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val busyOverrides: StateFlow<Map<String, Boolean>> = _busyOverrides.asStateFlow()

    private val _unreadIds = MutableStateFlow<Set<String>>(emptySet())
    val unreadIds: StateFlow<Set<String>> = _unreadIds.asStateFlow()

    // ---- active (open) session state ----
    private val _activeId = MutableStateFlow<String?>(null)
    private val _liveParts = MutableStateFlow<List<Part>>(emptyList())
    val liveParts: StateFlow<List<Part>> = _liveParts.asStateFlow()

    /** Snapshot/fetch prefix that was already visible before new deltas arrive. */
    private val _liveTailRevealedChars = MutableStateFlow(0)
    val liveTailRevealedChars: StateFlow<Int> = _liveTailRevealedChars.asStateFlow()

    private val _activeBusy = MutableStateFlow(false)
    val activeBusy: StateFlow<Boolean> = _activeBusy.asStateFlow()

    private val _activeClientMessageId = MutableStateFlow<String?>(null)
    val activeClientMessageId: StateFlow<String?> = _activeClientMessageId.asStateFlow()

    private val _cards = MutableStateFlow<List<PendingCard>>(emptyList())
    val cards: StateFlow<List<PendingCard>> = _cards.asStateFlow()

    /**
     * App-lifetime live turns for every streamed session. The active StateFlows
     * below are only a projection of this store, so leaving a running chat no
     * longer throws away the text and cards that arrive while another chat is
     * open.
     */
    private val liveTurns = NotificationTurnTracker()
    private var activeHydratedFromBuffer = false

    /**
     * History refreshes finish asynchronously. A generation token prevents an
     * old turn's refresh from clearing parts that already belong to a newer turn
     * (or to a newly opened session).
     */
    private val liveTurnLock = Any()
    private var liveTurnGeneration = 0L
    private var completedTurnGeneration: Long? = null

    /**
     * Every [openSession] hands out a token and keeps the newest one. Only the
     * close that owns the CURRENT open may tear it down — see [closeSession].
     */
    private var openToken = 0L

    private var awaitingSnapshot = false
    private var snapshotTimeout: Job? = null
    private var resyncJob: Job? = null
    private var snapshotEpoch = 0L
    private var expectedSnapshotClient: String? = null

    init {
        scope.launch {
            connection.profile.collectLatest { profile ->
                _connected.value = false
                _busyIds.value = emptySet()
                _busyOverrides.value = emptyMap()
                _unreadIds.value = emptySet()
                questionOverlay.clearAll() // another gateway's cards are moot
                clearSnapshotGate()
                synchronized(liveTurnLock) {
                    liveTurns.clear()
                    activeHydratedFromBuffer = false
                    projectActive(LiveTurnState())
                }
                if (profile == null) return@collectLatest
                coroutineScope {
                    // The API answers but the live stream still hasn't opened.
                    // Say so and no more: this condition has many causes — a
                    // gateway too old to have /api/stream, a proxy buffering
                    // SSE, a slow tunnel, doze — and naming one of them (it
                    // used to tell you to restart the gateway) is wrong advice
                    // most of the time. The transport keeps retrying regardless.
                    launch {
                        delay(SKEW_CHECK_MILLIS)
                        if (!_connected.value && runCatching { api.agents() }.isSuccess) {
                            toaster.show(appContext.getString(R.string.data_stream_skew))
                        }
                    }
                    factory.forProfile(profile).events(profile).collect { handle(it) }
                }
            }
        }
    }

    /**
     * Open a session: paint its buffered turn immediately, then verify by
     * resync. Returns the screen's claim on the live state — hand it back to
     * [closeSession] so a stale close cannot detach the screen that owns it.
     */
    fun openSession(id: String): Long {
        recorder.note("open-session", sessionId = id)
        // Entering the asking chat: its in-chat card is on screen now — the
        // floating copy for THIS chat comes down (others stay up).
        questionOverlay.removeForSession(id)
        clearSnapshotGate()
        val token = synchronized(liveTurnLock) {
            liveTurnGeneration++
            completedTurnGeneration = null
            _activeId.value = id
            val buffered = liveTurns.state(id)
            val hydrated = buffered?.copy(
                // Everything accumulated while this chat was away is restored,
                // not replayed through the typewriter on re-entry.
                tailRevealedChars = restoredTailLength(buffered.parts),
            ) ?: LiveTurnState()
            if (buffered != null) liveTurns.put(id, hydrated)
            activeHydratedFromBuffer = hydrated.hasLiveContent
            projectActive(hydrated)
            ++openToken
        }
        _unreadIds.update { it - id }
        resyncActive()
        return token
    }

    /**
     * Close the live state belonging to the open that produced [token]. The NEW
     * screen's openSession runs BEFORE the old ViewModel is cleared (navigation
     * composes the destination first, then disposes the popped entry), so a
     * stale close must not blank state another screen now owns — that silently
     * dropped every live event (no streaming, no busy) until the next app-start.
     *
     * The id alone cannot tell the two apart: re-entering the chat you are
     * already on (a notification tap) rebuilds the screen for the SAME id, and
     * the popped twin's close then detached the chat that had just opened —
     * answering a card could no longer hide it ([dismissCard] had no active
     * session to work on). The token settles ownership in every case.
     */
    fun closeSession(id: String, token: Long) {
        val orphan = synchronized(liveTurnLock) {
            if (_activeId.value != id || token != openToken) return
            liveTurnGeneration++
            completedTurnGeneration = null
            _activeId.value = null
            activeHydratedFromBuffer = false
            val state = liveTurns.state(id)
            when {
                // Running turns keep streaming into the buffer for the return.
                state == null || state.busy -> null
                // A terminal active copy is only a bridge until history
                // refreshes; an empty one has nothing left to bridge.
                state.parts.isEmpty() -> {
                    liveTurns.remove(id)
                    null
                }
                else -> state.parts
            }
        }
        clearSnapshotGate()
        // A terminal turn history never absorbed (its refresh failed, or the
        // screen closed while it was in flight): fold it into the cache
        // instead of discarding the only local copy.
        if (orphan != null) foldOrphanTurn(id, orphan)
    }

    /**
     * Persist a completed turn's streamed parts into the session cache, then
     * retire the buffer copy — but only if the cache write landed and the
     * buffer still holds exactly that turn (a new turn or a reopened screen
     * takes precedence). On failure the buffer is kept: better a restorable
     * in-memory copy than nothing.
     */
    private fun foldOrphanTurn(sessionId: String, parts: List<Part>) {
        scope.launch {
            val folded = runCatching { sessions.appendProvisionalTurn(sessionId, parts) }
                .getOrDefault(false)
            synchronized(liveTurnLock) {
                if (_activeId.value == sessionId) return@launch // the open screen owns it now
                val current = liveTurns.state(sessionId)
                if (folded && current != null && !current.busy && current.parts == parts) {
                    liveTurns.remove(sessionId)
                }
            }
        }
    }

    /** Generation of the active session's current live region. */
    fun liveTurnGeneration(sessionId: String): Long? = synchronized(liveTurnLock) {
        liveTurnGeneration.takeIf { _activeId.value == sessionId }
    }

    /** Generation captured when the latest active turn reached a terminal event. */
    fun completedTurnGeneration(sessionId: String): Long? = synchronized(liveTurnLock) {
        completedTurnGeneration.takeIf { _activeId.value == sessionId }
    }

    fun canReconcileCompletedTurn(sessionId: String, expectedGeneration: Long): Boolean =
        synchronized(liveTurnLock) {
            _activeId.value == sessionId &&
                liveTurnGeneration == expectedGeneration &&
                !_activeBusy.value
        }

    /**
     * Paint the live state carried by a cache/detail GET while the ordered
     * stream snapshot is still pending (or disconnected). Once a stream
     * snapshot has landed, this older HTTP copy is never allowed to overwrite
     * newer deltas.
     */
    fun seedFetchedLiveTurn(
        sessionId: String,
        parts: List<Part>,
        busy: Boolean,
        clientMessageId: String? = null,
    ) {
        synchronized(liveTurnLock) {
            if (_activeId.value != sessionId) return
            if (_connected.value && !awaitingSnapshot) return
            // The stream buffer is newer than an HTTP detail/cache response.
            // Keep it visible while the ordered snapshot is on its way.
            if (activeHydratedFromBuffer) return
            val wasBusy = _activeBusy.value
            if (busy && !wasBusy) {
                liveTurnGeneration++
                completedTurnGeneration = null
            } else if (!busy && wasBusy) {
                completedTurnGeneration = liveTurnGeneration
            }
            val fetched = (liveTurns.state(sessionId) ?: LiveTurnState()).copy(
                parts = parts,
                tailRevealedChars = restoredTailLength(parts),
                busy = busy,
                clientMessageId = clientMessageId,
            )
            liveTurns.put(sessionId, fetched)
            activeHydratedFromBuffer = fetched.hasLiveContent
            projectActive(fetched)
        }
    }

    /**
     * Retire the live copy only if it still represents the completed turn that
     * requested the history refresh. Returns false when a newer turn/session won.
     */
    fun clearLiveTurn(sessionId: String, expectedGeneration: Long): Boolean =
        synchronized(liveTurnLock) {
            if (
                _activeId.value != sessionId ||
                liveTurnGeneration != expectedGeneration ||
                _activeBusy.value
            ) {
                return@synchronized false
            }
            liveTurns.remove(sessionId)
            activeHydratedFromBuffer = false
            projectActive(LiveTurnState())
            true
        }

    private fun resyncActive() {
        val id = _activeId.value ?: return
        // Do not publish a snapshot before the stream is subscribed. The
        // connected event will request it once it can actually be received.
        if (!_connected.value) return

        val start = synchronized(liveTurnLock) {
            if (_activeId.value != id || !_connected.value) return
            val epoch = ++snapshotEpoch
            ResyncStart(
                epoch = epoch,
                client = "$clientId-$epoch",
                oldTimeout = snapshotTimeout,
                oldRequest = resyncJob,
            ).also {
                expectedSnapshotClient = it.client
                awaitingSnapshot = true
                snapshotTimeout = null
                resyncJob = null
            }
        }
        start.oldTimeout?.cancel()
        start.oldRequest?.cancel()

        val timeout = scope.launch(start = CoroutineStart.LAZY) {
            delay(SNAPSHOT_TIMEOUT_MILLIS)
            clearSnapshotGate(start.epoch)
        }
        val request = scope.launch(start = CoroutineStart.LAZY) {
            repeat(RESYNC_MAX_ATTEMPTS) { attempt ->
                if (!isCurrentResync(id, start.epoch)) return@launch
                recorder.note("resync-request", sessionId = id, detail = "${start.client}#$attempt")
                val succeeded = runCatching {
                    api.resync(id, ResyncBody(start.client))
                }.isSuccess
                if (succeeded) return@launch
                if (attempt < RESYNC_MAX_ATTEMPTS - 1) {
                    delay(RESYNC_RETRY_MILLIS * (attempt + 1))
                }
            }
            clearSnapshotGate(start.epoch)
        }
        val installed = synchronized(liveTurnLock) {
            if (!isCurrentResyncLocked(id, start.epoch)) {
                false
            } else {
                snapshotTimeout = timeout
                resyncJob = request
                true
            }
        }
        if (installed) {
            timeout.start()
            request.start()
        } else {
            timeout.cancel()
            request.cancel()
        }
    }

    private fun isCurrentResync(id: String, epoch: Long): Boolean =
        synchronized(liveTurnLock) { isCurrentResyncLocked(id, epoch) }

    private fun isCurrentResyncLocked(id: String, epoch: Long): Boolean =
        epoch == snapshotEpoch && _activeId.value == id && awaitingSnapshot

    private fun clearSnapshotGate(expectedEpoch: Long? = null): Boolean {
        val jobs = synchronized(liveTurnLock) {
            if (expectedEpoch != null && expectedEpoch != snapshotEpoch) return false
            snapshotEpoch++
            awaitingSnapshot = false
            expectedSnapshotClient = null
            (snapshotTimeout to resyncJob).also {
                snapshotTimeout = null
                resyncJob = null
            }
        }
        jobs.first?.cancel()
        jobs.second?.cancel()
        return true
    }

    private suspend fun handle(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Connected -> {
                _connected.value = true
                // One switch for all three sides: the gateway's WAKILI_DEBUG_STREAM.
                recorder.enable(event.debug)
                recorder.note("connected")
                // First connect or reconnect: the active session's live state may
                // have gaps — resync puts a fresh snapshot into the ordered stream.
                if (_activeId.value != null) resyncActive()
            }

            is GatewayEvent.Disconnected -> {
                // Only on the drop itself, not every failed retry — no toast spam.
                if (_connected.value) toaster.show(appContext.getString(R.string.data_reconnecting))
                _connected.value = false
                // A dead stream's explicit busy values are no longer authority:
                // a turn can start while nothing is listening (an agent-initiated
                // continuation after a background build), and a surviving
                // override=false from before the drop would beat the fresh
                // summary's busy=true forever. Overrides exist to outrank SLOWER
                // HTTP within a live stream — not to outlive the stream itself.
                _busyIds.value = emptySet()
                _busyOverrides.value = emptyMap()
                clearSnapshotGate()
            }

            is GatewayEvent.SessionEvent -> {
                // TurnStart resets the session's live buffer (TurnReducer). If
                // it still holds a COMPLETED turn history never absorbed — its
                // reconcile fetch failed or a queued send raced it — that turn
                // would vanish from every surface until the NEXT turn ended.
                // Persist it first (awaited: the write must land before the
                // reset is observable to anyone).
                if (event is GatewayEvent.TurnStart) {
                    val stale = synchronized(liveTurnLock) {
                        liveTurns.state(event.sessionId)
                            ?.takeIf { !it.busy && it.parts.isNotEmpty() }
                            ?.parts
                    }
                    if (stale != null) {
                        runCatching { sessions.appendProvisionalTurn(event.sessionId, stale) }
                    }
                }
                // A snapshot stamped BEFORE state already folded into this
                // session's buffer is stale: its resync was answered out of
                // order (HTTP guarantees no ordering), and adopting it rewinds
                // the turn — deltas then append onto that rewound base and the
                // middle of the answer is gone for good. A newer snapshot
                // contains everything an older one had, so refusing the older
                // one can never lose information.
                val snapshotSeq = (event as? GatewayEvent.Snapshot)?.seq
                if (event is GatewayEvent.Snapshot && snapshotSeq != null) {
                    val applied = synchronized(liveTurnLock) {
                        liveTurns.state(event.sessionId)?.appliedSeq
                    }
                    if (applied != null && snapshotSeq < applied) {
                        recorder.anomaly(
                            kind = "stale-snapshot",
                            sessionId = event.sessionId,
                            snapshotSeq = snapshotSeq,
                            appliedSeq = applied,
                            parts = event.parts.size,
                            client = event.client,
                        )
                        recorder.event(event)
                        // The gate opens either way: a stale snapshot still
                        // proves the resync round trip completed, and holding
                        // it shut until the timeout would trade a rewound turn
                        // for a stalled one. Only its contents are refused —
                        // including `busy`, which describes the same older
                        // instant and may since have flipped.
                        if (accepts(event)) {
                            acceptedSnapshotEpoch(event)?.let { clearSnapshotGate(it) }
                        }
                        return
                    }
                }
                recorder.event(event)
                val accepted = event !is GatewayEvent.Snapshot || accepts(event)
                val notificationText = if (accepted) {
                    synchronized(liveTurnLock) { liveTurns.record(event) }
                } else {
                    null
                }
                bookkeep(event)
                // Floating question cards come down the moment their card is
                // answered anywhere (or the turn ends) — regardless of the
                // toggle or foreground state, so none can outlive its question.
                when (event) {
                    is GatewayEvent.RequestResolved -> questionOverlay.remove(event.id)
                    is GatewayEvent.TurnEnd, is GatewayEvent.Stopped ->
                        questionOverlay.removeForSession(event.sessionId)
                    else -> Unit
                }
                maybeNotify(event, notificationText)
                if (accepted && event.sessionId == _activeId.value) {
                    applyToActive(event)
                } else if (
                    accepted &&
                    (event is GatewayEvent.TurnEnd || event is GatewayEvent.Stopped)
                ) {
                    // Completed inactive turns belong to persisted history — but
                    // with no screen open for this session, nothing else would
                    // save the streamed parts the phone already received. Fold
                    // them into the cached transcript (provisional; the next
                    // fetch replaces them with the server's copy) and drop the
                    // buffer only once the cache holds the turn.
                    val orphan = synchronized(liveTurnLock) {
                        if (_activeId.value == event.sessionId) {
                            null
                        } else {
                            liveTurns.state(event.sessionId)?.takeIf { !it.busy }?.parts
                        }
                    }
                    when {
                        orphan == null -> Unit
                        orphan.isEmpty() -> synchronized(liveTurnLock) {
                            if (_activeId.value != event.sessionId) liveTurns.remove(event.sessionId)
                        }
                        else -> foldOrphanTurn(event.sessionId, orphan)
                    }
                }
                // A snapshot requested by another client/tab is useful for broad
                // busy bookkeeping, but must not drive this screen's local state.
                if (accepted) _events.emit(event)
            }
        }
    }

    /** Background notifications for turn_end/permission_request, if enabled. */
    private suspend fun maybeNotify(event: GatewayEvent.SessionEvent, agentText: String?) {
        // The floating question card has its OWN visibility rule: everywhere
        // except the asking chat itself. Other apps, the home page, a different
        // chat — none of them show the in-chat card, so all of them get the
        // overlay. Only the chat that renders the real card suppresses it.
        if (event is GatewayEvent.QuestionRequest) {
            val lookingAtIt = appForeground.foreground && _activeId.value == event.sessionId
            if (!lookingAtIt &&
                settingsStore.notifications.first() &&
                settingsStore.floatingQuestions.first()
            ) {
                questionOverlay.show(
                    cardId = event.id,
                    sessionId = event.sessionId,
                    question = event.questions.firstOrNull()?.question,
                )
            }
        }
        if (appForeground.foreground) return
        if (!settingsStore.notifications.first()) return
        when (event) {
            is GatewayEvent.TurnEnd -> notifier.notifyTurnEnd(event.sessionId, agentText)
            is GatewayEvent.PermissionRequest -> notifier.notifyPermission(event.sessionId, event.tool)
            // A question card blocks the whole turn until answered — without a
            // notification it silently waited out its window whenever the app
            // was backgrounded, and the agent proceeded unanswered.
            is GatewayEvent.QuestionRequest -> notifier.notifyQuestion(
                event.sessionId,
                event.questions.firstOrNull()?.question,
            )
            else -> Unit
        }
    }

    /** Busy/unread flags for every session (list badges). */
    private fun bookkeep(event: GatewayEvent.SessionEvent) {
        when (event) {
            is GatewayEvent.TurnStart -> {
                _busyIds.update { it + event.sessionId }
                _busyOverrides.update { it + (event.sessionId to true) }
            }
            is GatewayEvent.TurnEnd -> {
                _busyIds.update { it - event.sessionId }
                _busyOverrides.update { it + (event.sessionId to false) }
                if (event.sessionId != _activeId.value) _unreadIds.update { it + event.sessionId }
            }
            is GatewayEvent.Stopped -> {
                _busyIds.update { it - event.sessionId }
                _busyOverrides.update { it + (event.sessionId to false) }
            }
            is GatewayEvent.Snapshot -> {
                _busyIds.update {
                    if (event.busy) it + event.sessionId else it - event.sessionId
                }
                _busyOverrides.update { it + (event.sessionId to event.busy) }
            }
            else -> Unit
        }
    }

    private fun applyToActive(event: GatewayEvent.SessionEvent) {
        if (event is GatewayEvent.Snapshot) {
            // The active resync may have changed after handle() first accepted
            // this event (for example A -> B -> A). Recheck before clearing the
            // gate so an old epoch cannot cancel the new session's request.
            val acceptedEpoch = acceptedSnapshotEpoch(event) ?: return
            if (!clearSnapshotGate(acceptedEpoch)) return
        }
        synchronized(liveTurnLock) {
            if (_activeId.value != event.sessionId) return
            val state = liveTurns.state(event.sessionId) ?: LiveTurnState()
            val wasBusy = _activeBusy.value
            when (event) {
                is GatewayEvent.TurnStart -> {
                    liveTurnGeneration++
                    completedTurnGeneration = null
                }
                is GatewayEvent.TurnEnd,
                is GatewayEvent.Stopped,
                -> completedTurnGeneration = liveTurnGeneration
                is GatewayEvent.Snapshot -> {
                    // A snapshot also repairs lifecycle events missed while the
                    // transport was disconnected.
                    if (state.busy && !wasBusy) {
                        liveTurnGeneration++
                        completedTurnGeneration = null
                    } else if (!state.busy && wasBusy) {
                        completedTurnGeneration = liveTurnGeneration
                    }
                }
                else -> Unit
            }
            activeHydratedFromBuffer = state.hasLiveContent
            projectActive(state)
        }
    }

    private fun accepts(event: GatewayEvent.Snapshot): Boolean =
        acceptedSnapshotEpoch(event) != null

    private fun acceptedSnapshotEpoch(event: GatewayEvent.Snapshot): Long? =
        synchronized(liveTurnLock) {
            snapshotEpoch.takeIf {
                event.client.isEmpty() || event.client == expectedSnapshotClient
            }
        }

    private fun projectActive(state: LiveTurnState) {
        _liveParts.value = state.parts
        _liveTailRevealedChars.value = state.tailRevealedChars
        _activeBusy.value = state.busy
        _activeClientMessageId.value = state.clientMessageId
        _cards.value = state.cards
    }

    /** Remove an answered card locally (the server also broadcasts resolution). */
    fun dismissCard(id: String) {
        synchronized(liveTurnLock) {
            val sessionId = _activeId.value ?: return
            val state = liveTurns.dismissCard(sessionId, id)
            projectActive(state)
        }
    }

    /**
     * Append a client-made part to the live turn — the answered-question record.
     * The server pushes the same qa part into its live parts when the answer
     * lands, so the position here matches what history will replay.
     */
    fun appendLivePart(part: Part) {
        synchronized(liveTurnLock) {
            val sessionId = _activeId.value ?: return
            val state = liveTurns.appendPart(sessionId, part)
            activeHydratedFromBuffer = state.hasLiveContent
            projectActive(state)
        }
    }

    /**
     * Resolve a permission request back to the tool_use already present in the
     * live turn. Keeping its id lets the nested decided card receive tool_result.
     */
    fun toolForPermission(card: PendingCard.Permission): Part.Tool =
        _liveParts.value
            .asReversed()
            .filterIsInstance<Part.Tool>()
            .firstOrNull { it.name == card.tool && it.input == card.input }
            ?: Part.Tool(id = null, name = card.tool, input = card.input)
}

private data class ResyncStart(
    val epoch: Long,
    val client: String,
    val oldTimeout: Job?,
    val oldRequest: Job?,
)

private const val SKEW_CHECK_MILLIS = 6_000L
private const val SNAPSHOT_TIMEOUT_MILLIS = 10_000L
private const val RESYNC_MAX_ATTEMPTS = 3
private const val RESYNC_RETRY_MILLIS = 300L

internal data class LiveTurnState(
    val parts: List<Part> = emptyList(),
    val tailRevealedChars: Int = 0,
    val busy: Boolean = false,
    val cards: List<PendingCard> = emptyList(),
    val clientMessageId: String? = null,
    /**
     * The gateway's ordering stamp for the newest event folded in here. Recorded
     * ONLY — nothing branches on it yet, so behaviour is unchanged; it exists so
     * an out-of-order snapshot becomes visible before we start rejecting one.
     */
    val appliedSeq: Long? = null,
) {
    val hasLiveContent: Boolean
        get() = busy || parts.isNotEmpty() || cards.isNotEmpty()
}

/**
 * App-lifetime reducer for every session's live turn. It also supplies the
 * terminal agent text used by notifications, so stream parts are reduced once
 * rather than by separate active-screen and notification trackers.
 */
internal class NotificationTurnTracker {
    private val turns = mutableMapOf<String, LiveTurnState>()
    private val notificationReady = mutableSetOf<String>()

    /**
     * Cards answered/resolved here must STAY gone: a resync snapshot built
     * before the gateway processed the answer still lists the card in
     * `pending`, and adopting it resurrected an already-answered card on
     * screen ("I answered and it didn't hide"). Remember recent ids and
     * refuse the resurrection, whatever the snapshot says.
     */
    private val dismissedIds = ArrayDeque<String>()

    private fun rememberDismissed(id: String) {
        dismissedIds.remove(id)
        dismissedIds.addLast(id)
        while (dismissedIds.size > 32) dismissedIds.removeFirst()
    }

    fun state(sessionId: String): LiveTurnState? = turns[sessionId]

    fun put(sessionId: String, state: LiveTurnState) {
        turns[sessionId] = state
    }

    fun record(event: GatewayEvent.SessionEvent): String? {
        val before = turns[event.sessionId] ?: LiveTurnState()
        val notificationText = when (event) {
            is GatewayEvent.TurnEnd -> if (notificationReady.remove(event.sessionId)) {
                before.parts.latestAgentText()
            } else {
                null
            }
            is GatewayEvent.Stopped -> {
                notificationReady.remove(event.sessionId)
                null
            }
            else -> null
        }

        val after = when (event) {
            is GatewayEvent.TurnStart -> {
                notificationReady += event.sessionId
                LiveTurnState(
                    busy = true,
                    clientMessageId = event.clientMessageId,
                )
            }
            is GatewayEvent.TurnEnd -> before.copy(
                busy = false,
                cards = emptyList(),
                clientMessageId = event.clientMessageId ?: before.clientMessageId,
            )
            is GatewayEvent.Stopped -> before.copy(busy = false, cards = emptyList())
            is GatewayEvent.Snapshot -> {
                if (event.busy || event.parts.isNotEmpty()) {
                    notificationReady += event.sessionId
                } else {
                    notificationReady -= event.sessionId
                }
                LiveTurnState(
                    parts = event.parts,
                    tailRevealedChars = restoredTailLength(event.parts),
                    busy = event.busy,
                    // A snapshot built before the gateway processed an answer
                    // still lists the answered card — never resurrect those.
                    cards = event.pending.filterNot { dismissedIds.contains(it.id) },
                    clientMessageId = event.clientMessageId,
                )
            }
            is GatewayEvent.PermissionRequest -> if (dismissedIds.contains(event.id)) {
                before
            } else {
                before.copy(
                    cards = before.cards.upsert(
                        PendingCard.Permission(event.id, event.tool, event.input),
                    ),
                )
            }
            is GatewayEvent.QuestionRequest -> if (dismissedIds.contains(event.id)) {
                before
            } else {
                before.copy(
                    cards = before.cards.upsert(
                        PendingCard.Question(event.id, event.questions),
                    ),
                )
            }
            is GatewayEvent.RequestResolved -> {
                rememberDismissed(event.id)
                before.copy(cards = before.cards.filterNot { it.id == event.id })
            }
            else -> reduceParts(before, event)
        }
        if (
            event !is GatewayEvent.Snapshot &&
            event !is GatewayEvent.TurnStart &&
            event !is GatewayEvent.TurnEnd &&
            event !is GatewayEvent.Stopped &&
            after.parts != before.parts
        ) {
            // Also recover notification tracking when TurnStart was missed.
            notificationReady += event.sessionId
        }
        // The watermark advances past every event seen, not only the ones that
        // change parts: a lifecycle event still proves the stream moved past
        // that point, which is exactly what makes a later-arriving older
        // snapshot recognisable as stale. It is a HIGH-WATER mark carried
        // across turns — TurnStart and Snapshot build a fresh state, and
        // letting it reset there would reopen the window it exists to close.
        val seq = event.seq
        val prior = before.appliedSeq
        turns[event.sessionId] = after.copy(
            appliedSeq = if (seq != null && (prior == null || seq > prior)) seq else prior,
        )
        return notificationText
    }

    fun dismissCard(sessionId: String, cardId: String): LiveTurnState {
        rememberDismissed(cardId) // answered here — replays must not bring it back
        val state = (turns[sessionId] ?: LiveTurnState()).let { current ->
            current.copy(cards = current.cards.filterNot { it.id == cardId })
        }
        turns[sessionId] = state
        return state
    }

    fun appendPart(sessionId: String, part: Part): LiveTurnState {
        val state = (turns[sessionId] ?: LiveTurnState()).let { current ->
            current.copy(parts = current.parts + part, tailRevealedChars = 0)
        }
        turns[sessionId] = state
        return state
    }

    fun remove(sessionId: String) {
        turns.remove(sessionId)
        notificationReady.remove(sessionId)
    }

    fun clear() {
        turns.clear()
        notificationReady.clear()
    }

    /** Preserve a restored prefix only while the same text/thinking tail grows. */
    private fun reduceParts(
        state: LiveTurnState,
        event: GatewayEvent.SessionEvent,
    ): LiveTurnState {
        val after = TurnReducer.reduce(state.parts, event)
        val continuesSameTextTail = state.parts.size == after.size && when {
            state.parts.lastOrNull() is Part.Text && after.lastOrNull() is Part.Text -> true
            state.parts.lastOrNull() is Part.Thinking && after.lastOrNull() is Part.Thinking -> true
            else -> false
        }
        return state.copy(
            parts = after,
            tailRevealedChars = if (continuesSameTextTail) state.tailRevealedChars else 0,
        )
    }
}

private fun restoredTailLength(parts: List<Part>): Int = when (val tail = parts.lastOrNull()) {
    is Part.Text -> tail.text.length
    is Part.Thinking -> tail.text.length
    else -> 0
}

private fun List<Part>.latestAgentText(): String? = asReversed()
    .filterIsInstance<Part.Text>()
    .firstOrNull { it.text.isNotBlank() }
    ?.text
    ?.trim()

private fun List<PendingCard>.upsert(card: PendingCard): List<PendingCard> =
    filterNot { it.id == card.id } + card
