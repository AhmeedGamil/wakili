package com.ahmedgamil.wakili.core.data.stream

import com.ahmedgamil.wakili.core.data.di.AppScope
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.dto.ResyncBody
import com.ahmedgamil.wakili.core.network.stream.EventStreamClientFactory
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The client-side twin of chatController.js's stream handling: ONE multiplexed
 * stream for the app's lifetime. Events for the active (open) session drive the
 * live turn (via TurnReducer) and the card dock; every session's events drive
 * busy/unread bookkeeping for the list badges.
 *
 * Snapshot gating mirrors the web client: after opening a session we resync and
 * ignore its content events until the snapshot lands (10s self-clearing), so
 * switching sessions never duplicates or drops parts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GatewayStreamRepository @Inject constructor(
    private val factory: EventStreamClientFactory,
    private val connection: GatewayConnection,
    private val api: GatewayApi,
    @AppScope private val scope: CoroutineScope,
) {

    /** Tags our resyncs so another device's resync snapshots are ignored. */
    private val clientId = "and-" + UUID.randomUUID().toString().take(8)

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    private val _unreadIds = MutableStateFlow<Set<String>>(emptySet())
    val unreadIds: StateFlow<Set<String>> = _unreadIds.asStateFlow()

    // ---- active (open) session state ----
    private val _activeId = MutableStateFlow<String?>(null)
    private val _liveParts = MutableStateFlow<List<Part>>(emptyList())
    val liveParts: StateFlow<List<Part>> = _liveParts.asStateFlow()

    private val _activeBusy = MutableStateFlow(false)
    val activeBusy: StateFlow<Boolean> = _activeBusy.asStateFlow()

    private val _cards = MutableStateFlow<List<PendingCard>>(emptyList())
    val cards: StateFlow<List<PendingCard>> = _cards.asStateFlow()

    private var awaitingSnapshot = false
    private var snapshotTimeout: Job? = null

    init {
        scope.launch {
            connection.profile.collectLatest { profile ->
                _connected.value = false
                if (profile == null) return@collectLatest
                factory.forProfile(profile).events(profile).collect { handle(it) }
            }
        }
    }

    /** Open a session: clear live state, mark read, resync its in-progress turn. */
    fun openSession(id: String) {
        _activeId.value = id
        _liveParts.value = emptyList()
        _cards.value = emptyList()
        _activeBusy.value = false
        _unreadIds.update { it - id }
        resyncActive()
    }

    fun closeSession() {
        _activeId.value = null
        snapshotTimeout?.cancel()
    }

    /** Called after the persisted history was reloaded post-turn. */
    fun clearLiveTurn() {
        _liveParts.value = emptyList()
    }

    private fun resyncActive() {
        val id = _activeId.value ?: return
        awaitingSnapshot = true
        snapshotTimeout?.cancel()
        snapshotTimeout = scope.launch {
            delay(10_000)
            awaitingSnapshot = false // self-clearing gate, like the web client
        }
        scope.launch {
            runCatching { api.resync(id, ResyncBody(clientId)) }
        }
    }

    private suspend fun handle(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Connected -> {
                _connected.value = true
                // First connect or reconnect: the active session's live state may
                // have gaps — resync puts a fresh snapshot into the ordered stream.
                if (_activeId.value != null) resyncActive()
            }

            is GatewayEvent.Disconnected -> _connected.value = false

            is GatewayEvent.SessionEvent -> {
                bookkeep(event)
                if (event.sessionId == _activeId.value) applyToActive(event)
                _events.emit(event)
            }
        }
    }

    /** Busy/unread flags for every session (list badges). */
    private fun bookkeep(event: GatewayEvent.SessionEvent) {
        when (event) {
            is GatewayEvent.TurnStart -> _busyIds.update { it + event.sessionId }
            is GatewayEvent.TurnEnd -> {
                _busyIds.update { it - event.sessionId }
                if (event.sessionId != _activeId.value) _unreadIds.update { it + event.sessionId }
            }
            is GatewayEvent.Stopped -> _busyIds.update { it - event.sessionId }
            is GatewayEvent.Snapshot -> _busyIds.update {
                if (event.busy) it + event.sessionId else it - event.sessionId
            }
            else -> Unit
        }
    }

    private fun applyToActive(event: GatewayEvent.SessionEvent) {
        // The snapshot is the sync point: until ours lands, content events are
        // stale (they precede the snapshot's state) and must be ignored.
        if (event is GatewayEvent.Snapshot) {
            if (event.client == clientId || event.client.isEmpty()) {
                awaitingSnapshot = false
                snapshotTimeout?.cancel()
                _liveParts.value = event.parts
                _activeBusy.value = event.busy
                _cards.value = event.pending
            }
            return
        }
        if (awaitingSnapshot) return

        when (event) {
            is GatewayEvent.TurnStart -> {
                _liveParts.value = emptyList()
                _activeBusy.value = true
            }
            is GatewayEvent.TurnEnd -> _activeBusy.value = false
            is GatewayEvent.Stopped -> _activeBusy.value = false
            is GatewayEvent.PermissionRequest -> _cards.update {
                it + PendingCard.Permission(event.id, event.tool, event.input)
            }
            is GatewayEvent.QuestionRequest -> _cards.update {
                it + PendingCard.Question(event.id, event.questions)
            }
            is GatewayEvent.RequestResolved -> _cards.update { cards ->
                cards.filterNot { it.id == event.id }
            }
            else -> _liveParts.update { TurnReducer.reduce(it, event) }
        }
    }

    /** Remove an answered card locally (the server also broadcasts resolution). */
    fun dismissCard(id: String) {
        _cards.update { cards -> cards.filterNot { it.id == id } }
    }
}
