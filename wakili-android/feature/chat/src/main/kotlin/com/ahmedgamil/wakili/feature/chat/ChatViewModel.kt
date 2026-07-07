package com.ahmedgamil.wakili.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ahmedgamil.wakili.core.data.repository.AgentRepository
import com.ahmedgamil.wakili.core.data.repository.FileRepository
import com.ahmedgamil.wakili.core.data.repository.QueueRepository
import com.ahmedgamil.wakili.core.data.repository.QueuedSend
import com.ahmedgamil.wakili.core.data.repository.SendAttachment
import com.ahmedgamil.wakili.core.data.repository.SendResult
import com.ahmedgamil.wakili.core.data.repository.SessionRepository
import com.ahmedgamil.wakili.core.data.stream.GatewayStreamRepository
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.model.ChatMessage
import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.dto.ExecBody
import com.ahmedgamil.wakili.feature.chat.navigation.ChatRoute
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Items shown after history that are client-side only (like the web's records). */
sealed interface TransientItem {
    data class Exec(val command: String, val output: String, val ok: Boolean) : TransientItem
    data class Decided(val tool: String, val decision: String) : TransientItem
    data object Stopped : TransientItem
    data class Answered(val summary: String) : TransientItem
}

data class PendingAttachment(
    val name: String,
    val uploading: Boolean = true,
    val failed: Boolean = false,
    val path: String = "",
    val url: String? = null,
    val image: Boolean = false,
)

data class ChatUiState(
    val title: String = "",
    val loading: Boolean = true,
    val messages: List<ChatMessage> = emptyList(),
    val transients: List<TransientItem> = emptyList(),
    val liveParts: List<Part> = emptyList(),
    val busy: Boolean = false,
    val cards: List<PendingCard> = emptyList(),
    val connected: Boolean = true,
    val input: String = "",
    val sending: Boolean = false,
    val queuedCount: Int = 0,
    val queuedFirst: String = "",
    val error: Boolean = false,
    val markdown: Boolean = true,
    val autoAllow: Boolean = false,
    val agents: List<AgentManifest> = emptyList(),
    val agentId: String = "claude",
    val controls: Map<String, String> = emptyMap(),
    val attachments: List<PendingAttachment> = emptyList(),
    val slashCommands: List<String> = emptyList(),
    val baseUrl: String = "",
    val cwd: String? = null,
) {
    val blocked: Boolean get() = cards.isNotEmpty()

    /** Slash-command menu content: typing "/name" (no space) filters commands. */
    val slashMatches: List<String>
        get() {
            val prefix = input.takeIf { it.startsWith("/") && !it.contains(' ') }
                ?: return emptyList()
            return slashCommands
                .filter { it.startsWith(prefix.drop(1), ignoreCase = true) }
                .take(MAX_SLASH_MATCHES)
        }
    val canSend: Boolean
        get() = (input.isNotBlank() || attachments.any { !it.uploading && !it.failed }) &&
            !blocked && !sending && attachments.none { it.uploading }
    val showStop: Boolean get() = busy && input.isBlank() && attachments.isEmpty()
    val agent: AgentManifest? get() = agents.firstOrNull { it.id == agentId }
}

private const val MAX_SLASH_MATCHES = 6

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val streamRepository: GatewayStreamRepository,
    private val agentRepository: AgentRepository,
    private val fileRepository: FileRepository,
    private val queueRepository: QueueRepository,
    private val settingsStore: SettingsStore,
    private val api: GatewayApi,
    private val attachmentReader: AttachmentReader,
    connection: GatewayConnection,
) : ViewModel() {

    private val sessionId: String = savedStateHandle.toRoute<ChatRoute>().sessionId
    val sessionIdPublic: String get() = sessionId
    private val json = Json { ignoreUnknownKeys = true }

    private val local = MutableStateFlow(ChatUiState())

    val uiState: StateFlow<ChatUiState> = combine(
        combine(
            local,
            streamRepository.liveParts,
            streamRepository.activeBusy,
            streamRepository.cards,
        ) { state, live, busy, cards ->
            state.copy(liveParts = live, busy = busy, cards = cards)
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
            autoAllow = settings.autoAllow,
            baseUrl = profile?.baseUrl.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    init {
        streamRepository.openSession(sessionId)
        loadHistory()
        viewModelScope.launch {
            local.update { it.copy(input = settingsStore.draft(sessionId).first()) }
        }
        viewModelScope.launch {
            runCatching { agentRepository.agents() }.getOrNull()?.let { agents ->
                local.update { it.copy(agents = agents) }
                refreshSlashCommands()
            }
        }
        viewModelScope.launch {
            streamRepository.events.collect { event ->
                if (event !is GatewayEvent.SessionEvent || event.sessionId != sessionId) return@collect
                when (event) {
                    is GatewayEvent.TurnEnd -> {
                        event.title?.let { t -> local.update { it.copy(title = t) } }
                        loadHistory(clearLiveAfter = true)
                    }
                    is GatewayEvent.Stopped -> local.update {
                        it.copy(transients = it.transients + TransientItem.Stopped)
                    }
                    else -> Unit
                }
            }
        }
        // Global auto-allow: resolve permission cards instantly, no card shown.
        viewModelScope.launch {
            uiState.collect { state ->
                if (!state.autoAllow) return@collect
                state.cards.filterIsInstance<PendingCard.Permission>().forEach { card ->
                    answerPermission(card, "allow", record = false)
                }
            }
        }
    }

    override fun onCleared() {
        streamRepository.closeSession()
    }

    private fun loadHistory(clearLiveAfter: Boolean = false) {
        viewModelScope.launch {
            try {
                val detail = sessionRepository.detail(sessionId)
                local.update {
                    it.copy(
                        loading = false,
                        error = false,
                        title = detail.summary.title,
                        messages = detail.messages,
                        agentId = detail.summary.agentId,
                        controls = it.controls.ifEmpty { detail.controls },
                        cwd = detail.summary.effectiveCwd,
                    )
                }
                if (clearLiveAfter) streamRepository.clearLiveTurn()
                if (local.value.controls.isEmpty()) applyDefaultControls()
                refreshSlashCommands()
            } catch (e: Exception) {
                local.update { it.copy(loading = false, error = true) }
            }
        }
    }

    /** Defaults layering: agent defaults → last-used → global permissionMode. */
    private suspend fun applyDefaultControls() {
        val agent = agentRepository.agent(local.value.agentId) ?: return
        val defaults = agent.controls.mapNotNull { (k, c) -> c.default?.let { k to it } }.toMap().toMutableMap()
        settingsStore.lastConfig.first()?.let { (lastAgent, controlsJson) ->
            if (lastAgent == local.value.agentId) {
                runCatching {
                    json.parseToJsonElement(controlsJson).jsonObject.forEach { (k, v) ->
                        defaults[k] = v.jsonPrimitive.content
                    }
                }
            }
        }
        settingsStore.permMode.first()?.let { if ("permissionMode" in agent.controls) defaults["permissionMode"] = it }
        // Heal stale values the agent no longer offers.
        val healed = defaults.filter { (k, v) ->
            agent.controls[k]?.options?.any { it.value == v } != false
        }
        local.update { it.copy(controls = healed) }
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
        local.update { it.copy(agentId = id, controls = emptyMap()) }
        viewModelScope.launch { applyDefaultControls() }
        refreshSlashCommands()
    }

    fun setControl(key: String, value: String) {
        local.update { it.copy(controls = it.controls + (key to value)) }
        viewModelScope.launch {
            if (key == "permissionMode") settingsStore.setPermMode(value)
            val state = local.value
            settingsStore.setLastConfig(
                state.agentId,
                json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.JsonObject(
                        state.controls.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) },
                    ),
                ),
            )
        }
    }

    fun setAutoAllow(on: Boolean) {
        viewModelScope.launch { settingsStore.setAutoAllow(on) }
    }

    fun setMarkdown(on: Boolean) {
        viewModelScope.launch { settingsStore.setMarkdown(on) }
    }

    // ---- attachments ----

    /** Reads the picked Uri (name, bytes, kind) and starts the upload. */
    fun attach(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = attachmentReader.read(uri) ?: return@launch
            attach(result.name, result.bytes, result.image)
        }
    }

    private fun attach(name: String, bytes: ByteArray, image: Boolean) {
        local.update { it.copy(attachments = it.attachments + PendingAttachment(name = name, image = image)) }
        viewModelScope.launch {
            try {
                val uploaded = fileRepository.upload(name, bytes, sessionId)
                local.update { s ->
                    s.copy(
                        attachments = s.attachments.map {
                            if (it.name == name && it.uploading) {
                                it.copy(uploading = false, path = uploaded.path, url = uploaded.url)
                            } else {
                                it
                            }
                        },
                    )
                }
            } catch (e: Exception) {
                local.update { s ->
                    s.copy(
                        attachments = s.attachments.map {
                            if (it.name == name && it.uploading) it.copy(uploading = false, failed = true) else it
                        },
                    )
                }
            }
        }
    }

    fun removeAttachment(attachment: PendingAttachment) {
        local.update { s -> s.copy(attachments = s.attachments - attachment) }
        if (attachment.path.isNotEmpty()) {
            viewModelScope.launch { runCatching { fileRepository.deleteUpload(attachment.path) } }
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

        local.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                when (sessionRepository.send(sessionId, text, state.controls, state.agentId, attachments)) {
                    SendResult.Accepted -> {
                        local.update {
                            it.copy(
                                sending = false,
                                input = "",
                                attachments = emptyList(),
                                messages = it.messages + ChatMessage.User(
                                    text,
                                    attachments.map { a ->
                                        com.ahmedgamil.wakili.core.model.Attachment(a.name, a.url.orEmpty(), false)
                                    },
                                ),
                            )
                        }
                        settingsStore.saveDraft(sessionId, "")
                    }
                    SendResult.Busy -> {
                        queueRepository.enqueue(
                            sessionId,
                            QueuedSend(text, state.controls, state.agentId, attachments),
                        )
                        local.update { it.copy(sending = false, input = "", attachments = emptyList()) }
                        settingsStore.saveDraft(sessionId, "")
                    }
                }
            } catch (e: Exception) {
                local.update { it.copy(sending = false) }
            }
        }
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

    fun answerPermission(card: PendingCard.Permission, decision: String, record: Boolean = true) {
        streamRepository.dismissCard(card.id)
        if (record) {
            local.update { it.copy(transients = it.transients + TransientItem.Decided(card.tool, decision)) }
        }
        viewModelScope.launch {
            runCatching { sessionRepository.answerPermission(sessionId, card.id, decision, card.tool) }
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
        local.update { it.copy(transients = it.transients + TransientItem.Answered(answer)) }
        viewModelScope.launch {
            runCatching { sessionRepository.answerQuestion(sessionId, card.id, answer) }
        }
    }
}
