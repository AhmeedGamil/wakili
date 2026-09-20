package com.ahmedgamil.wakili.feature.chat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ahmedgamil.wakili.core.common.Toaster
import com.ahmedgamil.wakili.core.data.repository.AgentControlPersistence
import com.ahmedgamil.wakili.core.data.repository.AgentRepository
import com.ahmedgamil.wakili.core.data.repository.FileRepository
import com.ahmedgamil.wakili.core.data.repository.FileDownloader
import com.ahmedgamil.wakili.core.data.repository.HandoffStash
import com.ahmedgamil.wakili.core.data.repository.MediaClipboard
import com.ahmedgamil.wakili.core.data.repository.OutboxEntry
import com.ahmedgamil.wakili.core.data.repository.OutboxRepository
import com.ahmedgamil.wakili.core.data.repository.QueueRepository
import com.ahmedgamil.wakili.core.data.repository.QueuedSend
import com.ahmedgamil.wakili.core.data.repository.SendAttachment
import com.ahmedgamil.wakili.core.data.repository.SendResult
import com.ahmedgamil.wakili.core.data.repository.SessionRepository
import com.ahmedgamil.wakili.core.data.repository.resolveAgentControls
import com.ahmedgamil.wakili.core.data.stream.GatewayStreamRepository
import com.ahmedgamil.wakili.core.datastore.CHAT_FONT_DEFAULT
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.model.ChatMessage
import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.model.SessionDetail
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.dto.ExecBody
import com.ahmedgamil.wakili.feature.chat.navigation.ChatRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Client-side records that are not part of the persisted gateway transcript. */
sealed interface TransientItem {
    data class Exec(val command: String, val output: String, val ok: Boolean) : TransientItem
    data class Decided(
        val tool: Part.Tool,
        val decision: String,
        val awaitingOutput: Boolean,
    ) : TransientItem

}

/**
 * One web-style stopped note anchored to the user turn that produced it.
 * [pendingLiveTail] keeps it below the still-live assistant parts until those
 * parts reconcile into history (or a newer turn begins).
 */
data class StoppedMarkerUi(
    val generation: Long,
    val userOrdinal: Int,
    val interrupted: Boolean,
    val pendingLiveTail: Boolean = true,
) {
    val key: String get() = "stopped:$generation"
}

/** Monotonic visible lifecycle: Idle -> AwaitingStart -> Running -> Idle. */
enum class TurnPhase { Idle, AwaitingStart, Running }

data class PendingAttachment(
    val name: String,
    val uploading: Boolean = true,
    /** Real HTTP request-body progress, from 0f through 1f. */
    val progress: Float = 0f,
    val failed: Boolean = false,
    val path: String = "",
    val url: String? = null,
    val image: Boolean = false,
    /** Source Uri, kept so a failed upload can be retried. */
    val uri: android.net.Uri? = null,
)

/** Uploaded-attachment shape persisted with the per-session draft. */
@kotlinx.serialization.Serializable
private data class DraftAttachment(
    val name: String,
    val path: String,
    val url: String? = null,
    val image: Boolean = false,
)

data class ChatUiState(
    val title: String = "",
    val loading: Boolean = true,
    val messages: List<ChatMessage> = emptyList(),
    val transients: List<TransientItem> = emptyList(),
    val stoppedMarkers: List<StoppedMarkerUi> = emptyList(),
    val liveParts: List<Part> = emptyList(),
    val liveClientMessageId: String? = null,
    val liveTailRevealedChars: Int = 0,
    val busy: Boolean = false,
    /**
     * Kept in one StateFlow so the optimistic -> stream handoff cannot emit an
     * idle frame and structurally remove/remount the working pulse.
     */
    val turnPhase: TurnPhase = TurnPhase.Idle,
    val cards: List<PendingCard> = emptyList(),
    val connected: Boolean = true,
    val input: String = "",
    val sending: Boolean = false,
    val queuedCount: Int = 0,
    val queuedFirst: String = "",
    val error: Boolean = false,
    val markdown: Boolean = true,
    /** Transcript body text size in sp (Settings → Other). */
    val chatFontSize: Int = CHAT_FONT_DEFAULT,
    /** "Auto continue" — gateway re-drives a limit-killed turn once quota
     *  returns. On unless the chat has explicitly turned it off. */
    val autoContinue: Boolean = true,
    val scheduledCount: Int = 0,
    val agents: List<AgentManifest> = emptyList(),
    val agentId: String = "claude",
    val controls: Map<String, String> = emptyMap(),
    val attachments: List<PendingAttachment> = emptyList(),
    val slashCommands: List<String> = emptyList(),
    val baseUrl: String = "",
    val cwd: String? = null,
    /** Set when an agent handoff opened a fresh chat — the UI navigates to it. */
    val openSessionId: String? = null,
    /** Failed sends waiting on Retry/Discard (web outbox rows). */
    val outbox: List<OutboxEntry> = emptyList(),
    /** Bumped on every send — the screen scrolls to the bottom when it changes. */
    val sendSeq: Int = 0,
) {
    val blocked: Boolean get() = cards.isNotEmpty()

    /** Slash-command menu content: typing "/name" (no space) filters commands.
     *  ALL matches, like the web — the menu itself scrolls inside its max height. */
    val slashMatches: List<String>
        get() {
            val prefix = input.takeIf { it.startsWith("/") && !it.contains(' ') }
                ?: return emptyList()
            return slashCommands
                .filter { it.startsWith(prefix.drop(1), ignoreCase = true) }
        }
    val canSend: Boolean
        get() = (input.isNotBlank() || attachments.any { !it.uploading && !it.failed }) &&
            !blocked && !sending && attachments.none { it.uploading }
    val showStop: Boolean get() = busy && input.isBlank() && attachments.isEmpty()
    val agent: AgentManifest? get() = agents.firstOrNull { it.id == agentId }
}


private data class LiveTurnProjection(
    val parts: List<Part>,
    val revealedChars: Int,
    val busy: Boolean,
    val cards: List<PendingCard>,
    val clientMessageId: String?,
)

private fun List<ChatMessage>.lastUserOrdinal(): Int =
    count { it is ChatMessage.User } - 1

internal fun List<StoppedMarkerUi>.retirePendingStoppedMarkers(
    exceptGeneration: Long? = null,
): List<StoppedMarkerUi> = map { marker ->
    if (marker.pendingLiveTail && marker.generation != exceptGeneration) {
        marker.copy(pendingLiveTail = false)
    } else {
        marker
    }
}

/** "photo.jpg" -> "photo (2).jpg" when [taken] already holds the name. */
internal fun uniqueAttachmentName(name: String, taken: Collection<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 2
    while ("$stem ($n)$ext" in taken) n++
    return "$stem ($n)$ext"
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val sessionRepository: SessionRepository,
    private val agentControlPersistence: AgentControlPersistence,
    private val streamRepository: GatewayStreamRepository,
    private val agentRepository: AgentRepository,
    private val fileRepository: FileRepository,
    private val fileDownloader: FileDownloader,
    private val queueRepository: QueueRepository,
    private val outboxRepository: OutboxRepository,
    private val mediaClipboard: MediaClipboard,
    private val settingsStore: SettingsStore,
    private val api: GatewayApi,
    private val attachmentReader: AttachmentReader,
    private val toaster: Toaster,
    private val handoffStash: HandoffStash,
    connection: GatewayConnection,
) : ViewModel() {

    private val sessionId: String = savedStateHandle.toRoute<ChatRoute>().sessionId
    val sessionIdPublic: String get() = sessionId
    private val json = Json { ignoreUnknownKeys = true }

    private val local = MutableStateFlow(ChatUiState())
    /**
     * This screen's claim on the shared live state. A notification tap into the
     * chat already on screen rebuilds this ViewModel for the same id, so the
     * popped twin must close ITS open — not the one now on screen.
     */
    private var openToken = 0L
    private var historyLoadJob: Job? = null
    /** True from optimistic send/turn_start until a terminal event or idle snapshot. */
    private var turnInFlight = false

    /**
     * A finished turn whose live-to-history handoff hasn't landed (the refresh
     * is still in flight, or failed). If a new turn starts in that window, its
     * TurnStart wipes the live buffer — the finished turn would be on NO
     * surface at all, "discarded" until the next turn ended. The stream repo
     * folds it into the cache before the wipe; this flag makes the screen
     * reload history right then so it paints immediately.
     */
    private var reconcilePending = false
    private data class ActiveTurnAnchor(val generation: Long, val userOrdinal: Int)
    private var activeTurnAnchor: ActiveTurnAnchor? = null
    private var lastStartedUserOrdinal = -1
    private var pendingLocalUserOrdinal: Int? = null
    private var recoveryStoppedGeneration = -1L

    /**
     * The in-flight send's user bubble. A history refresh can race the POST
     * (a queued dispatch and the ended turn's refresh share one TurnEnd) and
     * come back without the message — the web dodges this by rendering
     * in-flight sends outside history; here renderDetail re-appends it.
     */
    private var optimisticTail: ChatMessage.User? = null

    private val liveTurnProjection = combine(
            streamRepository.liveParts,
            streamRepository.liveTailRevealedChars,
            streamRepository.activeBusy,
            streamRepository.cards,
            streamRepository.activeClientMessageId,
        ) { parts, revealedChars, busy, cards, clientMessageId ->
            LiveTurnProjection(parts, revealedChars, busy, cards, clientMessageId)
        }

    val uiState: StateFlow<ChatUiState> = combine(
        combine(local, liveTurnProjection) { state, live ->
            state.copy(
                liveParts = live.parts,
                liveClientMessageId = live.clientMessageId,
                liveTailRevealedChars = live.revealedChars,
                busy = live.busy || state.turnPhase != TurnPhase.Idle,
                cards = live.cards,
            )
        },
        streamRepository.connected,
        queueRepository.queued,
        settingsStore.settings,
        connection.profile,
    ) { state, connected, queued, settings, profile: ConnectionProfile? ->
        val q = queued[sessionId].orEmpty()
        state.copy(
            connected = connected,
            queuedCount = q.size,
            queuedFirst = q.firstOrNull()?.text.orEmpty(),
            markdown = settings.markdown,
            chatFontSize = settings.chatFontSize,
            baseUrl = profile?.baseUrl.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    init {
        openToken = streamRepository.openSession(sessionId)
        loadHistory()
        viewModelScope.launch {
            // A chat opened by an agent handoff starts with the transcript attached
            // and a suggested first message; otherwise restore the saved draft.
            val handoffFile = handoffStash.take(sessionId)
            if (handoffFile != null) {
                local.update {
                    it.copy(
                        input = appContext.getString(R.string.chat_handoff_prefill),
                        attachments = it.attachments + PendingAttachment(
                            name = handoffFile.name,
                            uploading = false,
                            path = handoffFile.path,
                            url = handoffFile.url,
                        ),
                    )
                }
            } else {
                val draft = settingsStore.draft(sessionId).first()
                val draftAtts = runCatching {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(DraftAttachment.serializer()),
                        settingsStore.draftAttachments(sessionId).first(),
                    )
                }.getOrDefault(emptyList())
                local.update {
                    it.copy(
                        input = draft,
                        attachments = it.attachments + draftAtts.map { d ->
                            PendingAttachment(
                                name = d.name,
                                uploading = false,
                                path = d.path,
                                url = d.url,
                                image = d.image,
                            )
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            runCatching { agentRepository.agents() }.getOrNull()?.let { agents ->
                local.update { it.copy(agents = agents) }
                refreshSlashCommands()
            }
        }
        viewModelScope.launch {
            // Opened while unreachable, connected later: the agent manifest
            // (model label, controls panel) and the history fetch both ran
            // against a dead link — heal whatever is still missing the moment
            // the stream connects.
            var last = false
            streamRepository.connected.collect { connected ->
                if (connected && !last) {
                    if (local.value.agents.isEmpty()) {
                        runCatching { agentRepository.agents() }.getOrNull()?.let { agents ->
                            local.update { it.copy(agents = agents) }
                            refreshSlashCommands()
                        }
                    }
                    if (local.value.error || local.value.messages.isEmpty()) loadHistory()
                }
                last = connected
            }
        }
        viewModelScope.launch {
            streamRepository.events.collect { event ->
                if (event !is GatewayEvent.SessionEvent || event.sessionId != sessionId) return@collect
                when (event) {
                    is GatewayEvent.TurnStart -> {
                        val generation = streamRepository.liveTurnGeneration(sessionId)
                            ?: activeTurnAnchor?.generation
                            ?: recoveryStoppedGeneration--
                        val ordinal = pendingLocalUserOrdinal
                            ?: maxOf(
                                local.value.messages.lastUserOrdinal(),
                                lastStartedUserOrdinal + 1,
                            ).coerceAtLeast(0)
                        activeTurnAnchor = ActiveTurnAnchor(generation, ordinal)
                        lastStartedUserOrdinal = maxOf(lastStartedUserOrdinal, ordinal)
                        pendingLocalUserOrdinal = null
                        turnInFlight = true
                        local.update { state ->
                            state.copy(
                                // AwaitingStart -> Running is one monotonic local
                                // transition. Never hand busy between two flows.
                                turnPhase = TurnPhase.Running,
                                stoppedMarkers = state.stoppedMarkers
                                    .retirePendingStoppedMarkers(generation),
                            )
                        }
                        if (reconcilePending) {
                            reconcilePending = false
                            // The previous turn never reached rendered history;
                            // this TurnStart just wiped its live copy. The
                            // stream repo folded it into the cache before the
                            // wipe — reload so it paints NOW, not after this
                            // whole new turn finishes.
                            loadHistory()
                        }
                    }
                    is GatewayEvent.TurnEnd -> {
                        val completedGeneration = streamRepository.completedTurnGeneration(sessionId)
                        turnInFlight = false
                        activeTurnAnchor = null
                        pendingLocalUserOrdinal = null
                        reconcilePending = true
                        local.update {
                            it.copy(
                                title = event.title ?: it.title,
                                turnPhase = TurnPhase.Idle,
                            )
                        }
                        loadHistory(
                            clearLiveAfterGeneration = completedGeneration,
                            authoritativeOnly = true,
                        )
                        // Web turnEnd → flushQueued: the queued message goes out
                        // now — through this ViewModel, so its bubble paints in
                        // this same event instead of after the refresh lands.
                        queueRepository.take(sessionId)?.let(::sendQueued)
                    }
                    is GatewayEvent.Stopped -> {
                        val generation = streamRepository.completedTurnGeneration(sessionId)
                            ?: activeTurnAnchor?.generation
                            ?: recoveryStoppedGeneration--
                        val ordinal = activeTurnAnchor?.userOrdinal
                            ?: maxOf(
                                local.value.messages.lastUserOrdinal(),
                                lastStartedUserOrdinal,
                            ).coerceAtLeast(0)
                        val interrupted = !queueRepository.queued.value[sessionId].isNullOrEmpty()
                        turnInFlight = false
                        activeTurnAnchor = null
                        pendingLocalUserOrdinal = null
                        reconcilePending = true
                        local.update { state ->
                            val marker = StoppedMarkerUi(generation, ordinal, interrupted)
                            state.copy(
                                // Replace the pulse with the note in this same
                                // state update; there is no blank tail frame.
                                turnPhase = TurnPhase.Idle,
                                stoppedMarkers = if (
                                    state.stoppedMarkers.any { it.generation == generation }
                                ) {
                                    state.stoppedMarkers
                                } else {
                                    state.stoppedMarkers + marker
                                },
                            )
                        }
                    }
                    is GatewayEvent.Snapshot -> {
                        val state = local.value
                        val awaitingStart = state.turnPhase == TurnPhase.AwaitingStart
                        val reconcileCompletedTurn =
                            !event.busy && !state.sending && turnInFlight && !awaitingStart
                        if (event.busy) {
                            val generation = streamRepository.liveTurnGeneration(sessionId)
                                ?: activeTurnAnchor?.generation
                                ?: recoveryStoppedGeneration--
                            if (activeTurnAnchor?.generation != generation) {
                                val ordinal = maxOf(
                                    state.messages.lastUserOrdinal(),
                                    lastStartedUserOrdinal + 1,
                                ).coerceAtLeast(0)
                                activeTurnAnchor = ActiveTurnAnchor(generation, ordinal)
                                lastStartedUserOrdinal = maxOf(lastStartedUserOrdinal, ordinal)
                            }
                            turnInFlight = true
                            local.update {
                                it.copy(
                                    turnPhase = TurnPhase.Running,
                                    stoppedMarkers = it.stoppedMarkers
                                        .retirePendingStoppedMarkers(generation),
                                )
                            }
                        } else if (!awaitingStart) {
                            turnInFlight = false
                            activeTurnAnchor = null
                            pendingLocalUserOrdinal = null
                            local.update { it.copy(turnPhase = TurnPhase.Idle) }
                            // Web refreshSessions(): a queued message whose
                            // session turns out idle goes out now — covers a
                            // TurnEnd missed while the app was asleep, which
                            // otherwise left the queue waiting forever.
                            if (!state.sending) {
                                queueRepository.take(sessionId)?.let(::sendQueued)
                            }
                        }
                        if (reconcileCompletedTurn) {
                            loadHistory(
                                clearLiveAfterGeneration = streamRepository.liveTurnGeneration(sessionId),
                                authoritativeOnly = true,
                            )
                        }
                    }
                    is GatewayEvent.SessionUpdated -> event.title?.let { title ->
                        local.update { it.copy(title = title) }
                    }
                    is GatewayEvent.ToolResult -> attachResultToDecision(event)
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            outboxRepository.outbox.collect { map ->
                local.update { it.copy(outbox = map[sessionId].orEmpty()) }
            }
        }
    }

    override fun onCleared() {
        streamRepository.closeSession(sessionId, openToken)
    }

    private fun loadHistory(
        clearLiveAfterGeneration: Long? = null,
        authoritativeOnly: Boolean = false,
    ) {
        historyLoadJob?.cancel()
        historyLoadJob = viewModelScope.launch {
            var hasUsableHistory = local.value.messages.isNotEmpty()
            if (!authoritativeOnly) {
                sessionRepository.cachedDetail(sessionId)?.let { cached ->
                    renderDetail(cached)
                    ensureControls(cached.controls)
                    hasUsableHistory = true
                }
            }
            try {
                val detail = sessionRepository.refreshDetail(sessionId)
                if (
                    clearLiveAfterGeneration != null &&
                    !streamRepository.canReconcileCompletedTurn(
                        sessionId,
                        clearLiveAfterGeneration,
                    )
                ) {
                    // A newer turn started while this request was in flight, so
                    // the completed turn's atomic live-to-history handoff is
                    // void. Still RENDER the fetched transcript — it already
                    // contains that turn, and dropping it wholesale here hid
                    // the previous turn for the entire length of the next one.
                    // Only the newer live region and its markers stay untouched.
                    renderDetail(detail)
                    reconcilePending = false
                    ensureControls(detail.controls)
                    return@launch
                }
                renderDetail(detail, reconciledGeneration = clearLiveAfterGeneration)
                if (clearLiveAfterGeneration != null) {
                    reconcilePending = false
                    streamRepository.clearLiveTurn(sessionId, clearLiveAfterGeneration)
                }
                ensureControls(detail.controls)
                refreshSlashCommands()
            } catch (e: Exception) {
                if (!hasUsableHistory && !authoritativeOnly) {
                    local.update { it.copy(loading = false, error = true) }
                }
            }
        }
    }

    private fun renderDetail(
        detail: SessionDetail,
        reconciledGeneration: Long? = null,
    ) {
        streamRepository.seedFetchedLiveTurn(
            sessionId = sessionId,
            parts = detail.liveParts,
            busy = detail.summary.busy,
            clientMessageId = detail.liveClientMessageId,
        )
        val lastUserOrdinal = detail.messages.lastUserOrdinal()
        lastStartedUserOrdinal = maxOf(lastStartedUserOrdinal, lastUserOrdinal)
        if (
            detail.summary.busy &&
            streamRepository.activeBusy.value &&
            lastUserOrdinal >= 0
        ) {
            val generation = streamRepository.liveTurnGeneration(sessionId)
                ?: activeTurnAnchor?.generation
                ?: recoveryStoppedGeneration--
            val anchor = activeTurnAnchor
            activeTurnAnchor = if (anchor?.generation == generation) {
                // A snapshot/TurnStart can beat the first history load and
                // temporarily infer ordinal 0. Authoritative append-only
                // history may advance that anchor, but never move it backward.
                anchor.copy(userOrdinal = maxOf(anchor.userOrdinal, lastUserOrdinal))
            } else {
                ActiveTurnAnchor(generation, lastUserOrdinal)
            }
        }
        val messages = withOptimisticTail(detail.messages)
        local.update { state ->
            state.copy(
                loading = false,
                error = false,
                title = detail.summary.title,
                messages = messages,
                stoppedMarkers = if (reconciledGeneration == null) {
                    state.stoppedMarkers
                } else {
                    state.stoppedMarkers.map { marker ->
                        if (marker.generation == reconciledGeneration) {
                            marker.copy(pendingLiveTail = false)
                        } else {
                            marker
                        }
                    }
                },
                agentId = detail.summary.agentId,
                // controls are NOT taken from the detail directly:
                // applyDefaultControls layers them over the agent's defaults,
                // so the picker always shows a model (never a bare agent name).
                cwd = detail.summary.effectiveCwd,
                autoContinue = detail.autoContinue,
                scheduledCount = detail.scheduled,
            )
        }

        // A failed HTTP response can hide a successful gateway acceptance.
        // Retain the outbox row until history proves the exact id was persisted,
        // then remove it after the canonical bubble is already on screen.
        val persistedClientMessageIds = detail.messages
            .filterIsInstance<ChatMessage.User>()
            .mapNotNull { it.clientMessageId }
            .toSet()
        outboxRepository.outbox.value[sessionId].orEmpty()
            .filter { it.clientMessageId in persistedClientMessageIds }
            .forEach { outboxRepository.remove(sessionId, it.id) }
    }

    /** Refreshed history that predates an in-flight POST must not eat its bubble. */
    private fun withOptimisticTail(refreshed: List<ChatMessage>): List<ChatMessage> {
        val tail = optimisticTail ?: return refreshed
        val lastUser = refreshed.lastOrNull { it is ChatMessage.User } as? ChatMessage.User
        // Name-only attachment compare: the server sets url/image flags the
        // optimistic copy can't know, and a false "not persisted" would leave
        // a duplicate bubble behind.
        val persisted = tail.clientMessageId?.let { clientMessageId ->
            refreshed.any { message ->
                message is ChatMessage.User && message.clientMessageId == clientMessageId
            }
        } ?: (lastUser != null &&
            lastUser.text == tail.text &&
            lastUser.attachments.map { it.name } == tail.attachments.map { it.name })
        if (persisted) {
            optimisticTail = null
            return refreshed
        }
        return refreshed + tail
    }

    /** URL being fetched for the clipboard — drives the card's copy overlay. */
    val copyingMedia = mediaClipboard.copying

    fun download(url: String, name: String) {
        fileDownloader.enqueue(url, name)
    }

    /** Long-press → Copy on a media card: pixels for images, the link otherwise. */
    fun copyMedia(url: String, name: String, image: Boolean) {
        viewModelScope.launch { mediaClipboard.copy(url, name, image) }
    }

    /**
     * Defaults layering (web defaultsFor + normalizeControls): agent defaults
     * → last-used → global permissionMode → the session's stored controls.
     * The session only overrides what it actually stored, so a fresh chat
     * still shows the agent's default model, never a bare agent name.
     */
    private suspend fun applyDefaultControls(sessionControls: Map<String, String> = emptyMap()) {
        val agentId = local.value.agentId
        val agent = agentRepository.agent(agentId) ?: return
        val healed = resolveAgentControls(
            agent = agent,
            lastConfig = agentControlPersistence.snapshot(),
            permissionMode = agentControlPersistence.permissionMode(),
            sessionControls = sessionControls,
        )
        local.update { it.copy(controls = healed) }
        // The session you're on becomes the default for new chats (web
        // applySession → saveLastConfig) — not just explicit control changes.
        agentControlPersistence.remember(agentId, healed)
    }

    private suspend fun ensureControls(sessionControls: Map<String, String>) {
        if (local.value.controls.isNotEmpty()) return
        applyDefaultControls(sessionControls)
        // Fresh/handoff sessions start with controls={}. Pin the resolved full
        // map immediately, exactly like web newSession -> persistControls.
        if (sessionControls.isEmpty()) persistControls()
    }

    private fun refreshSlashCommands() {
        val agent = local.value.agents.firstOrNull { it.id == local.value.agentId }
        local.update { s -> s.copy(slashCommands = agent?.commands?.map { it.name }.orEmpty()) }
    }

    fun retry() {
        local.update { it.copy(loading = true) }
        loadHistory()
    }

    fun onInputChange(value: String) {
        local.update { it.copy(input = value) }
        viewModelScope.launch { settingsStore.saveDraft(sessionId, value) }
    }

    fun setAgent(id: String) {
        val state = local.value
        // A chat with history can't carry its native thread to another agent —
        // hand off to a fresh chat instead (mirrors web continueWithAgent).
        if (id != state.agentId && state.messages.isNotEmpty()) {
            continueWithAgent(id)
            return
        }
        local.update { it.copy(agentId = id, controls = emptyMap()) }
        viewModelScope.launch {
            applyDefaultControls()
            persistControls() // pin the new agent's defaults on the gateway too
        }
        refreshSlashCommands()
    }

    /**
     * The server exports this chat's transcript to a handoff file and creates a
     * NEW chat for the new agent (same folder); we open it with the file attached
     * and a suggested first message prefilled. Nothing sends until the user does,
     * and this chat stays intact.
     */
    private fun continueWithAgent(agentId: String) {
        val label = local.value.agents.firstOrNull { it.id == agentId }?.label ?: agentId
        toaster.show(appContext.getString(R.string.chat_handoff_switching, label))
        viewModelScope.launch {
            val result = runCatching { sessionRepository.handoff(sessionId, agentId) }.getOrNull()
            if (result == null) {
                toaster.show(appContext.getString(R.string.chat_handoff_failed))
                return@launch
            }
            handoffStash.put(result.session.id, result.file)
            local.update { it.copy(openSessionId = result.session.id) }
            toaster.show(appContext.getString(R.string.chat_handoff_done, label))
        }
    }

    /** The UI navigated to the handoff chat — stop asking. */
    fun onHandoffOpened() {
        local.update { it.copy(openSessionId = null) }
    }

    fun setControl(key: String, value: String) {
        local.update { it.copy(controls = it.controls + (key to value)) }
        val state = local.value
        agentControlPersistence.persistSession(
            sessionId = sessionId,
            agentId = state.agentId,
            controls = state.controls,
            permissionMode = value.takeIf { key == "permissionMode" },
        )
    }

    /**
     * Ordered app-lifetime PATCH of the ACTIVE session's picker state (web
     * persistControls). It survives navigation; send-time controls remain the
     * final guarantee for the very next turn if the network write fails.
     */
    private fun persistControls() {
        val state = local.value
        agentControlPersistence.persistSession(sessionId, state.agentId, state.controls)
    }

    /**
     * "Auto continue" — per chat, ON by default and stored on the GATEWAY: a
     * turn this chat loses to the usage limit is re-driven by the gateway once
     * quota returns, while the phone sleeps.
     */
    fun setAutoContinue(on: Boolean) {
        local.update { it.copy(autoContinue = on) }
        viewModelScope.launch {
            runCatching { sessionRepository.setAutoContinue(sessionId, on) }
                // The gateway never heard the change — reflect reality, not the wish.
                .onFailure { _ -> local.update { it.copy(autoContinue = !on) } }
        }
    }

    // ---- scheduled tasks ----

    /** + menu Schedule tap: false = nothing schedulable yet (told the user why). */
    fun scheduleGate(): Boolean {
        val state = local.value
        // A file mid-upload would be silently dropped from the job — wait it out.
        if (state.attachments.any { it.uploading }) {
            toaster.show(appContext.getString(R.string.chat_schedule_uploading))
            return false
        }
        if (state.input.isBlank() && state.attachments.none { !it.uploading && !it.failed }) {
            toaster.show(appContext.getString(R.string.chat_schedule_empty))
            return false
        }
        return true
    }

    /**
     * Schedule the composer text (+ attached files) for the next occurrence of
     * [hour]:[minute]. Attachments are already uploaded (eagerly, on pick), so
     * the job carries their laptop paths — same shape a normal send posts.
     */
    fun schedule(hour: Int, minute: Int) {
        val state = local.value
        val text = state.input.trim()
        val attachments = state.attachments
            .filter { !it.uploading && !it.failed }
            .map { SendAttachment(name = it.name, path = it.path, url = it.url) }
        if (text.isEmpty() && attachments.isEmpty()) return
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        viewModelScope.launch {
            runCatching { sessionRepository.schedule(sessionId, text, cal.timeInMillis, attachments) }
                .onSuccess { job ->
                    // Scheduling also flipped the chat's autoContinue on (gateway).
                    local.update {
                        it.copy(
                            input = "",
                            attachments = emptyList(),
                            autoContinue = true,
                            scheduledCount = it.scheduledCount + 1,
                        )
                    }
                    settingsStore.saveDraft(sessionId, "")
                    persistAttachments() // the banked draft attachments left with the job
                    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(job.at))
                    toaster.show(appContext.getString(R.string.chat_scheduled_toast, time))
                }
                .onFailure { toaster.show(appContext.getString(R.string.chat_schedule_failed)) }
        }
    }

    fun setMarkdown(on: Boolean) {
        viewModelScope.launch { settingsStore.setMarkdown(on) }
    }

    // ---- attachments ----

    // In-flight uploads by attachment name, so removing an attachment aborts it.
    private val uploadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Reads the picked Uri (name, bytes, kind) and starts the upload. */
    fun attach(uri: android.net.Uri) = attach(listOf(uri))

    /** A multi-select pick: describe one at a time so the chips keep the picked
     *  order. Only name/size is read here — the bytes stream at upload time. */
    fun attach(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            for (uri in uris) {
                val info = attachmentReader.describe(uri) ?: continue
                attach(info, uri)
            }
        }
    }

    private fun attach(info: AttachmentReader.Info, uri: android.net.Uri) {
        // Uploads, retries and removals are all keyed by name, and one pick can
        // now carry several files sharing one ("image.jpg" from two folders).
        val unique = uniqueAttachmentName(info.name, local.value.attachments.map { it.name })
        local.update {
            it.copy(attachments = it.attachments + PendingAttachment(name = unique, image = info.image, uri = uri))
        }
        launchAttachmentUpload(unique, info.size) { attachmentReader.open(uri) }
    }

    private fun launchAttachmentUpload(
        name: String,
        size: Long,
        openStream: () -> InputStream,
    ) {
        uploadJobs[name] = viewModelScope.launch {
            try {
                val uploaded = fileRepository.upload(name, size, sessionId, { progress ->
                    local.update { state ->
                        state.copy(
                            attachments = state.attachments.map {
                                if (it.name == name && it.uploading) {
                                    it.copy(progress = progress.coerceIn(0f, 1f))
                                } else {
                                    it
                                }
                            },
                        )
                    }
                }, openStream)
                local.update { s ->
                    s.copy(
                        attachments = s.attachments.map {
                            if (it.name == name && it.uploading) {
                                it.copy(uploading = false, progress = 1f, path = uploaded.path, url = uploaded.url)
                            } else {
                                it
                            }
                        },
                    )
                }
                persistAttachments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: an upload that dies on an Error —
                // OutOfMemoryError above all — has to mark the chip failed.
                // Catching only Exception is what turned a too-big attachment
                // into an app that vanished mid-pick.
                local.update { s ->
                    s.copy(
                        attachments = s.attachments.map {
                            if (it.name == name && it.uploading) it.copy(uploading = false, failed = true) else it
                        },
                    )
                }
            } finally {
                uploadJobs.remove(name)
            }
        }
    }

    /** Re-read and upload in place so the existing preview never flashes away. */
    fun retryAttachment(attachment: PendingAttachment) {
        val uri = attachment.uri ?: return
        if (uploadJobs[attachment.name]?.isActive == true) return
        local.update { state ->
            state.copy(
                attachments = state.attachments.map { current ->
                    if (current == attachment) {
                        current.copy(uploading = true, progress = 0f, failed = false)
                    } else {
                        current
                    }
                },
            )
        }
        uploadJobs[attachment.name] = viewModelScope.launch {
            val info = attachmentReader.describe(uri)
            if (info == null) {
                local.update { state ->
                    state.copy(
                        attachments = state.attachments.map { current ->
                            if (current.name == attachment.name && current.uploading) {
                                current.copy(uploading = false, failed = true)
                            } else {
                                current
                            }
                        },
                    )
                }
                uploadJobs.remove(attachment.name)
                return@launch
            }
            // The source is the same attachment; retain its stable row/name and
            // only replace the upload status as progress arrives.
            try {
                val uploaded = fileRepository.upload(attachment.name, info.size, sessionId, { progress ->
                    local.update { state ->
                        state.copy(
                            attachments = state.attachments.map { current ->
                                if (current.name == attachment.name && current.uploading) {
                                    current.copy(progress = progress.coerceIn(0f, 1f))
                                } else {
                                    current
                                }
                            },
                        )
                    }
                }, { attachmentReader.open(uri) })
                local.update { state ->
                    state.copy(
                        attachments = state.attachments.map { current ->
                            if (current.name == attachment.name && current.uploading) {
                                current.copy(
                                    uploading = false,
                                    progress = 1f,
                                    path = uploaded.path,
                                    url = uploaded.url,
                                )
                            } else {
                                current
                            }
                        },
                    )
                }
                persistAttachments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { state ->
                    state.copy(
                        attachments = state.attachments.map { current ->
                            if (current.name == attachment.name && current.uploading) {
                                current.copy(uploading = false, failed = true)
                            } else {
                                current
                            }
                        },
                    )
                }
            } finally {
                uploadJobs.remove(attachment.name)
            }
        }
    }

    fun removeAttachment(attachment: PendingAttachment) {
        // Abort an in-flight upload (web a._abort()) before dropping the chip.
        uploadJobs.remove(attachment.name)?.cancel()
        local.update { s -> s.copy(attachments = s.attachments - attachment) }
        if (attachment.path.isNotEmpty()) {
            viewModelScope.launch { runCatching { fileRepository.deleteUpload(attachment.path) } }
        }
        persistAttachments()
    }

    /** Bank the uploaded attachments with the draft (survives switch/restart). */
    private fun persistAttachments() {
        viewModelScope.launch {
            val uploaded = local.value.attachments
                .filter { !it.uploading && !it.failed && it.path.isNotEmpty() }
                .map { DraftAttachment(it.name, it.path, it.url, it.image) }
            settingsStore.saveDraftAttachments(
                sessionId,
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(DraftAttachment.serializer()),
                    uploaded,
                ),
            )
        }
    }

    // ---- sending ----

    fun send() {
        val state = local.value
        val text = state.input.trim()
        if (text.isEmpty() && state.attachments.none { !it.uploading && !it.failed }) return

        // Direct shell command (the web chat's `!cmd`), not persisted as a turn.
        if (text.startsWith("!")) {
            runExec(text.drop(1))
            return
        }

        val attachments = state.attachments
            .filter { !it.uploading && !it.failed }
            .map { SendAttachment(name = it.name, path = it.path, url = it.url) }
        val clientMessageId = UUID.randomUUID().toString()

        // Web send(): a busy session queues instead of POSTing. The bubble is
        // never painted here — the queue chip carries the message until the
        // turn ends or is stopped, so there is no insert→hide→reappear cycle
        // (the old flow appended optimistically, got the gateway's 409, pulled
        // the bubble back out, then re-appended it on TurnEnd). The 409 branch
        // in dispatch() stays as the net for a busy state we didn't know about.
        val busyNow = turnInFlight ||
            streamRepository.activeBusy.value ||
            state.turnPhase != TurnPhase.Idle
        if (busyNow) {
            queueRepository.enqueue(
                sessionId,
                QueuedSend(text, state.controls, state.agentId, attachments, clientMessageId),
            )
            local.update {
                it.copy(input = "", attachments = emptyList(), sendSeq = it.sendSeq + 1)
            }
            viewModelScope.launch {
                settingsStore.saveDraft(sessionId, "")
                persistAttachments()
            }
            return
        }

        // Optimistic paint (the web's outbox render + working pulse): the
        // message, cleared composer, and busy state all appear the moment Send
        // is tapped — not a network round trip later. The busy/failure paths
        // in dispatch() take the message back out (queue chip / outbox row
        // carry it instead), so nothing is silently dropped or doubled.
        val optimistic = ChatMessage.User(
            text,
            attachments.map { a ->
                com.ahmedgamil.wakili.core.model.Attachment(a.name, a.url.orEmpty(), false)
            },
            clientMessageId = clientMessageId,
        )
        optimisticTail = optimistic
        val startsIdleTurn =
            !turnInFlight &&
                !streamRepository.activeBusy.value &&
                state.turnPhase == TurnPhase.Idle
        if (startsIdleTurn) {
            pendingLocalUserOrdinal = maxOf(
                state.messages.lastUserOrdinal() + 1,
                lastStartedUserOrdinal + 1,
            )
        }
        turnInFlight = true
        local.update {
            it.copy(
                sending = true,
                turnPhase = if (startsIdleTurn) TurnPhase.AwaitingStart else it.turnPhase,
                messages = it.messages + optimistic,
                stoppedMarkers = if (startsIdleTurn) {
                    // The old terminal note now belongs before this new turn;
                    // it must not suppress the new optimistic working pulse.
                    it.stoppedMarkers.retirePendingStoppedMarkers()
                } else {
                    it.stoppedMarkers
                },
                // A decided permission is a live-turn record. On the next turn,
                // reveal the canonical persisted tool card in its original place.
                transients = it.transients.filterNot { item -> item is TransientItem.Decided },
                input = "",
                attachments = emptyList(),
                sendSeq = it.sendSeq + 1, // sending always jumps to the bottom (web stick = true)
            )
        }
        viewModelScope.launch {
            settingsStore.saveDraft(sessionId, "")
            persistAttachments()
            dispatch(
                text = text,
                controls = state.controls,
                agentId = state.agentId,
                attachments = attachments,
                clientMessageId = clientMessageId,
                optimistic = optimistic,
            )
        }
    }

    /** Drop the LAST occurrence (identical earlier messages must survive). */
    private fun List<ChatMessage>.withoutOptimistic(m: ChatMessage?): List<ChatMessage> {
        if (m == null) return this
        val i = lastIndexOf(m)
        return if (i < 0) this else toMutableList().apply { removeAt(i) }
    }

    /**
     * Send one payload: 202 keeps the optimistic message (or appends, on a
     * retry with none), 409 queues, an error lands in the outbox — the latter
     * two take the optimistic message back out.
     */
    private suspend fun dispatch(
        text: String,
        controls: Map<String, String>,
        agentId: String?,
        attachments: List<SendAttachment>,
        clientMessageId: String,
        optimistic: ChatMessage? = null,
    ) {
        try {
            when (
                val result = sessionRepository.send(
                    id = sessionId,
                    text = text,
                    controls = controls,
                    agentId = agentId,
                    attachments = attachments,
                    clientMessageId = clientMessageId,
                )
            ) {
                is SendResult.Accepted -> {
                    // A retry has no optimistic copy on screen — append one,
                    // shielded from racing refreshes like any other send.
                    val appended = if (optimistic == null) {
                        ChatMessage.User(
                            text,
                            attachments.map { a ->
                                com.ahmedgamil.wakili.core.model.Attachment(a.name, a.url.orEmpty(), false)
                            },
                            clientMessageId = clientMessageId,
                        ).also { optimisticTail = it }
                    } else {
                        null
                    }
                    local.update {
                        val alreadyVisible = it.messages.any { message ->
                            message is ChatMessage.User &&
                                message.clientMessageId == clientMessageId
                        }
                        it.copy(
                            sending = false,
                            // Keep the current phase. send() armed it before the
                            // POST; TurnStart/TurnEnd/Snapshot may already have
                            // authoritatively cleared it before this 202 arrives.
                            messages = if (appended == null || alreadyVisible) {
                                it.messages
                            } else {
                                it.messages + appended
                            },
                        )
                    }
                    if (result.alreadyAccepted) {
                        // The POST reached the gateway before an earlier response
                        // was lost. Pull the canonical row instead of launching or
                        // visually inventing a second turn.
                        loadHistory(authoritativeOnly = true)
                    }
                }
                SendResult.Busy -> {
                    turnInFlight = streamRepository.activeBusy.value
                    pendingLocalUserOrdinal = null
                    optimisticTail = null
                    if (!turnInFlight) activeTurnAnchor = null
                    queueRepository.enqueue(
                        sessionId,
                        QueuedSend(text, controls, agentId, attachments, clientMessageId),
                    )
                    local.update {
                        it.copy(
                            sending = false,
                            turnPhase = if (turnInFlight) TurnPhase.Running else TurnPhase.Idle,
                            messages = it.messages.withoutOptimistic(optimistic),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            turnInFlight = streamRepository.activeBusy.value
            pendingLocalUserOrdinal = null
            optimisticTail = null
            if (!turnInFlight) activeTurnAnchor = null
            outboxRepository.add(
                sessionId,
                text,
                controls,
                agentId,
                attachments,
                clientMessageId,
            )
            local.update {
                it.copy(
                    sending = false,
                    turnPhase = if (turnInFlight) TurnPhase.Running else TurnPhase.Idle,
                    messages = it.messages.withoutOptimistic(optimistic),
                )
            }
        }
    }

    /**
     * Deliver a queued message the moment its turn ended (web flushQueued →
     * outbox.deliver): the bubble, working pulse, and bottom scroll paint in
     * the same TurnEnd event, before any network round trip. Same optimistic
     * machinery as send(), minus the composer/draft side effects — the
     * composer may already hold the user's next message.
     */
    private fun sendQueued(q: QueuedSend) {
        val optimistic = ChatMessage.User(
            q.text,
            q.attachments.map { a ->
                com.ahmedgamil.wakili.core.model.Attachment(a.name, a.url.orEmpty(), false)
            },
            clientMessageId = q.clientMessageId,
        )
        optimisticTail = optimistic
        pendingLocalUserOrdinal = maxOf(
            local.value.messages.lastUserOrdinal() + 1,
            lastStartedUserOrdinal + 1,
        )
        turnInFlight = true
        local.update {
            it.copy(
                sending = true,
                turnPhase = TurnPhase.AwaitingStart,
                messages = it.messages + optimistic,
                stoppedMarkers = it.stoppedMarkers.retirePendingStoppedMarkers(),
                transients = it.transients.filterNot { item -> item is TransientItem.Decided },
                sendSeq = it.sendSeq + 1,
            )
        }
        viewModelScope.launch {
            dispatch(
                text = q.text,
                controls = q.controls.orEmpty(),
                agentId = q.agentId,
                attachments = q.attachments,
                clientMessageId = q.clientMessageId,
                optimistic = optimistic,
            )
        }
    }

    fun retryOutbox(entry: OutboxEntry) {
        if (!outboxRepository.markRetrying(sessionId, entry.id)) return
        pendingLocalUserOrdinal = maxOf(
            local.value.messages.lastUserOrdinal() + 1,
            lastStartedUserOrdinal + 1,
        )
        turnInFlight = true
        local.update { it.copy(sending = true, turnPhase = TurnPhase.AwaitingStart) }
        viewModelScope.launch {
            try {
                when (
                    val result = sessionRepository.send(
                        id = sessionId,
                        text = entry.text,
                        controls = entry.controls,
                        agentId = entry.agentId,
                        attachments = entry.attachments,
                        clientMessageId = entry.clientMessageId,
                    )
                ) {
                    is SendResult.Accepted -> {
                        if (result.alreadyAccepted) {
                            // Keep the retry row mounted until refresh paints the
                            // already-persisted user row. renderDetail removes it
                            // only after that canonical row is actually present.
                            val detail = sessionRepository.refreshDetail(sessionId)
                            renderDetail(detail)
                            val busyNow = detail.summary.busy || streamRepository.activeBusy.value
                            turnInFlight = busyNow
                            pendingLocalUserOrdinal = null
                            if (!busyNow) activeTurnAnchor = null
                            local.update {
                                it.copy(
                                    sending = false,
                                    turnPhase = if (busyNow) TurnPhase.Running else TurnPhase.Idle,
                                )
                            }
                            val persisted = detail.messages.any { message ->
                                message is ChatMessage.User &&
                                    message.clientMessageId == entry.clientMessageId
                            }
                            if (!persisted) outboxRepository.markFailed(sessionId, entry.id)
                        } else {
                            val optimistic = ChatMessage.User(
                                text = entry.text,
                                attachments = entry.attachments.map { attachment ->
                                    com.ahmedgamil.wakili.core.model.Attachment(
                                        attachment.name,
                                        attachment.url.orEmpty(),
                                        false,
                                    )
                                },
                                clientMessageId = entry.clientMessageId,
                            )
                            optimisticTail = optimistic
                            local.update { state ->
                                val exists = state.messages.any { message ->
                                    message is ChatMessage.User &&
                                        message.clientMessageId == entry.clientMessageId
                                }
                                state.copy(
                                    sending = false,
                                    messages = if (exists) state.messages else state.messages + optimistic,
                                )
                            }
                            // Canonical/optimistic content is already mounted;
                            // removing the retry card cannot create a blank frame.
                            outboxRepository.remove(sessionId, entry.id)
                        }
                    }
                    SendResult.Busy -> {
                        // A retry is never converted into a new queued send. Its
                        // stable id stays in the outbox and can be retried safely.
                        val busyNow = streamRepository.activeBusy.value
                        turnInFlight = busyNow
                        pendingLocalUserOrdinal = null
                        if (!busyNow) activeTurnAnchor = null
                        outboxRepository.markFailed(sessionId, entry.id)
                        local.update {
                            it.copy(
                                sending = false,
                                turnPhase = if (busyNow) TurnPhase.Running else TurnPhase.Idle,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                val busyNow = streamRepository.activeBusy.value
                turnInFlight = busyNow
                pendingLocalUserOrdinal = null
                if (!busyNow) activeTurnAnchor = null
                outboxRepository.markFailed(sessionId, entry.id)
                local.update {
                    it.copy(
                        sending = false,
                        turnPhase = if (busyNow) TurnPhase.Running else TurnPhase.Idle,
                    )
                }
            }
        }
    }

    fun discardOutbox(entry: OutboxEntry) {
        outboxRepository.remove(sessionId, entry.id)
    }

    fun cancelQueue() = queueRepository.cancelAll(sessionId)

    private fun runExec(command: String) {
        if (command.isBlank()) return
        local.update { it.copy(sending = true) }
        viewModelScope.launch {
            val result = runCatching { api.exec(sessionId, ExecBody(command)) }.getOrNull()
            local.update {
                it.copy(
                    sending = false,
                    input = "",
                    transients = it.transients + TransientItem.Exec(
                        command = command,
                        output = result?.output ?: "exec failed",
                        ok = result?.ok == true,
                    ),
                )
            }
            settingsStore.saveDraft(sessionId, "")
        }
    }

    fun stop() {
        viewModelScope.launch { runCatching { sessionRepository.stop(sessionId) } }
    }

    // ---- cards ----

    fun answerPermission(card: PendingCard.Permission, decision: String) {
        streamRepository.dismissCard(card.id)
        val tool = streamRepository.toolForPermission(card)
        local.update {
            it.copy(
                transients = it.transients + TransientItem.Decided(
                    tool = tool,
                    decision = decision,
                    awaitingOutput = decision != "deny" || tool.id != null,
                ),
            )
        }
        viewModelScope.launch {
            deliverAnswer { sessionRepository.answerPermission(sessionId, card.id, decision, card.tool) }
        }
    }

    /**
     * The card hides optimistically; the POST must actually land or the
     * gateway keeps waiting and REPLAYS the card on the next resync — which
     * read as "I answered and it didn't hide". Retry a flaky link a few
     * times; if it still fails, say so (the replayed card is tappable again).
     */
    private suspend fun deliverAnswer(post: suspend () -> Unit) {
        repeat(3) { attempt ->
            if (runCatching { post() }.isSuccess) return
            if (attempt < 2) kotlinx.coroutines.delay(1500L * (attempt + 1))
        }
        toaster.show(appContext.getString(R.string.chat_answer_failed))
    }

    /** Attach the gateway's later tool_result to the nested permission tool card. */
    private fun attachResultToDecision(event: GatewayEvent.ToolResult) {
        local.update { state ->
            val exact = event.id?.let { id ->
                state.transients.indexOfFirst { item ->
                    item is TransientItem.Decided && item.awaitingOutput && item.tool.id == id
                }
            } ?: -1
            val fallback = state.transients.indexOfFirst { item ->
                item is TransientItem.Decided && item.awaitingOutput && item.tool.id == null
            }
            val index = if (exact >= 0) exact else fallback
            if (index < 0) return@update state

            state.copy(
                transients = state.transients.toMutableList().apply {
                    val item = get(index) as TransientItem.Decided
                    set(
                        index,
                        item.copy(
                            tool = item.tool.copy(output = event.output, isError = event.isError),
                            awaitingOutput = false,
                        ),
                    )
                },
            )
        }
    }

    /** Batched parallel requests: answer every open permission card at once. */
    fun answerAllPermissions(decision: String) {
        uiState.value.cards.filterIsInstance<PendingCard.Permission>().forEach {
            answerPermission(it, decision)
        }
    }

    fun answerQuestion(card: PendingCard.Question, answer: String) {
        streamRepository.dismissCard(card.id)
        // The record goes INTO the live parts, at the position the answer
        // happened — the server pushes the same qa part, so history replays it
        // in exactly this spot between the turn's tool calls (web Dock archive).
        streamRepository.appendLivePart(Part.Qa(answer))
        viewModelScope.launch {
            deliverAnswer { sessionRepository.answerQuestion(sessionId, card.id, answer) }
        }
    }
}
