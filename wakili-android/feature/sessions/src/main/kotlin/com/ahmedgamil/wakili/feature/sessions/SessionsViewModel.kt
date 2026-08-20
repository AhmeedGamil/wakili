package com.ahmedgamil.wakili.feature.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.common.Toaster
import com.ahmedgamil.wakili.core.data.repository.AgentRepository
import com.ahmedgamil.wakili.core.data.repository.ConnectionRepository
import com.ahmedgamil.wakili.core.data.repository.FileRepository
import com.ahmedgamil.wakili.core.data.repository.FolderRepository
import com.ahmedgamil.wakili.core.data.repository.SessionRepository
import com.ahmedgamil.wakili.core.data.repository.NewSessionFactory
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.data.stream.GatewayStreamRepository
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.model.FolderListing
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class SessionRowUi(
    val session: SessionSummary,
    val busy: Boolean,
    val unread: Boolean,
    val pending: Boolean,
)

/** "hostname-lan" / "hostname-tailscale" / "name-cloudflare" (react-native
 *  networks.js), the fallback when a host has no user-chosen name yet. */
internal fun deriveHostName(baseUrl: String): String {
    val host = baseUrl.substringAfter("://").substringBefore(":").substringBefore("/")
    if (host.isBlank()) return baseUrl
    val kind = when {
        host.endsWith("trycloudflare.com") -> "cloudflare"
        host.startsWith("100.") -> "tailscale"
        else -> "lan"
    }
    val stem = if (kind == "cloudflare") host.substringBefore(".") else host
    return "$stem-$kind"
}

data class SessionGroup(
    val folder: String,
    val cwd: String?,
    val rows: List<SessionRowUi>,
)

data class SessionsUiState(
    val loading: Boolean = true,
    /** A user-initiated refresh (pull-to-refresh) is in flight. */
    val refreshing: Boolean = false,
    val rows: List<SessionRowUi> = emptyList(),
    val groups: List<SessionGroup> = emptyList(),
    val byProject: Boolean = true,
    val showModel: Boolean = false,
    /** Collapsed project groups, keyed by cwd ("" = default project). */
    val collapsedGroups: Set<String> = emptySet(),
    /** The agent tab selected on the home page — new chats use it and the list
     *  filters to its chats. Falls back to the first agent until one is picked. */
    val homeAgentId: String = "",
    /** Global files-registry size, shown as the badge on the Files entry (web .side-files-count). */
    val filesCount: Int = 0,
    val agents: List<AgentManifest> = emptyList(),
    val host: String = "",
    /** Friendly display name for the active host (custom, else derived). */
    val hostName: String = "",
    val error: Boolean = false,
    val disconnected: Boolean = false,
    val creating: Boolean = false,
    /**
     * WHICH project's ➕ is waiting on the round trip. `creating` alone can only
     * say "a chat is being made somewhere", so every group header spun at once
     * and the list looked like it was creating a chat in all of them. Null is a
     * real value here (the default folder), so it is only meaningful while
     * [creating] is true.
     */
    val creatingCwd: String? = null,
    /** Chats mid-delete: red spinner on the row until the server answers. */
    val deletingIds: Set<String> = emptySet(),
    val openSessionId: String? = null,
    val streamConnected: Boolean = true,
    // folder picker
    val pickingFolder: Boolean = false,
    val folderListing: FolderListing? = null,
    val folderLoading: Boolean = false,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toaster: Toaster,
    private val sessionRepository: SessionRepository,
    private val newSessionFactory: NewSessionFactory,
    private val connectionRepository: ConnectionRepository,
    private val streamRepository: GatewayStreamRepository,
    private val folderRepository: FolderRepository,
    private val fileRepository: FileRepository,
    private val agentRepository: AgentRepository,
    private val settingsStore: SettingsStore,
    private val connection: GatewayConnection,
) : ViewModel() {

    private val local = MutableStateFlow(SessionsUiState())
    private val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    // The connected computer's own name (os.hostname), fetched on connect; the
    // sidebar's default host label unless the user set a custom name.
    private val deviceHostname = MutableStateFlow("")
    private var refreshJob: Job? = null
    private var refreshPending = false

    val uiState: StateFlow<SessionsUiState> = combine(
        local,
        sessions,
        streamRepository.busyOverrides,
        streamRepository.unreadIds,
        settingsStore.sessionView,
    ) { state, list, busyOverrides, unreadIds, view ->
        // The home tab is the top of the hierarchy: with more than one agent
        // registered, only that agent's chats show. Sessions from before agents
        // were recorded count as Claude's (web tabAgent filter).
        val agentFilter = state.homeAgentId.ifBlank { state.agents.firstOrNull()?.id ?: "" }
        val visible = if (state.agents.size > 1 && agentFilter.isNotBlank()) {
            list.filter { (it.agentId ?: "claude") == agentFilter }
        } else {
            list
        }
        val rows = visible.map { session ->
            SessionRowUi(
                session = session,
                busy = busyOverrides[session.id] ?: session.busy,
                unread = session.id in unreadIds,
                pending = session.pending > 0,
            )
        }
        state.copy(
            rows = rows,
            homeAgentId = agentFilter,
            byProject = view == "project",
            groups = rows
                .groupBy { it.session.effectiveCwd ?: "" }
                .map { (cwd, groupRows) ->
                    SessionGroup(
                        folder = cwd.substringAfterLast('\\').substringAfterLast('/').ifEmpty { "~" },
                        cwd = cwd.ifEmpty { null },
                        rows = groupRows,
                    )
                }
                .sortedByDescending { group -> group.rows.maxOf { it.session.updatedAt } },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    init {
        viewModelScope.launch {
            runCatching { agentRepository.agents() }.getOrNull()?.let { list ->
                local.update { it.copy(agents = list) }
            }
        }
        viewModelScope.launch {
            settingsStore.showModel.collect { on ->
                local.update { it.copy(showModel = on) }
            }
        }
        viewModelScope.launch {
            settingsStore.homeAgent.collect { id ->
                local.update { it.copy(homeAgentId = id) }
            }
        }
        viewModelScope.launch {
            settingsStore.collapsedGroups.collect { set ->
                local.update { it.copy(collapsedGroups = set) }
            }
        }
        viewModelScope.launch {
            // The single owner of initial loading and gateway-change reloads.
            // UI composables must not start their own first-composition refresh.
            connectionRepository.profile.distinctUntilChanged().collect { profile ->
                local.update { it.copy(host = profile?.baseUrl.orEmpty()) }
                deviceHostname.value = ""
                if (profile != null) {
                    refresh()
                    // The computer's real name (os.hostname), not its IP/network.
                    deviceHostname.value = runCatching { connectionRepository.deviceHostname() }
                        .getOrNull().orEmpty()
                }
            }
        }
        viewModelScope.launch {
            // Friendly host name for the sidebar: the user's custom name wins,
            // then the computer's own device name, then the derived "host-lan".
            // Rides its own flow so a rename never retriggers the refresh above.
            combine(
                connectionRepository.profile,
                connectionRepository.hostNames,
                deviceHostname,
            ) { profile, names, device ->
                val base = profile?.baseUrl.orEmpty()
                when {
                    base.isBlank() -> ""
                    names[base]?.isNotBlank() == true -> names.getValue(base)
                    device.isNotBlank() -> device
                    else -> deriveHostName(base)
                }
            }.distinctUntilChanged().collect { name ->
                local.update { it.copy(hostName = name) }
            }
        }
        viewModelScope.launch {
            var last = false
            streamRepository.connected.collect { connected ->
                local.update { it.copy(streamConnected = connected) }
                // Reconnect = events were missed (the hub replays nothing). A
                // turn may have started or ended while away — a continuation
                // after a background build most of all — so refetch summaries
                // for authoritative busy/pending the moment the stream is back.
                if (connected && !last) {
                    refresh(silent = true)
                    // Opened while unreachable, connected later: the one-shot
                    // loads (agent tabs, host name) ran against a dead link and
                    // never retried — the home page sat nameless and tabless.
                    if (local.value.agents.isEmpty()) {
                        viewModelScope.launch {
                            runCatching { agentRepository.agents() }.getOrNull()?.let { list ->
                                local.update { it.copy(agents = list) }
                            }
                        }
                    }
                    if (deviceHostname.value.isBlank()) {
                        viewModelScope.launch {
                            deviceHostname.value =
                                runCatching { connectionRepository.deviceHostname() }
                                    .getOrNull().orEmpty()
                        }
                    }
                }
                last = connected
            }
        }
        viewModelScope.launch {
            streamRepository.events.collect { event ->
                when (event) {
                    is GatewayEvent.TurnStart -> {
                        sessions.update { list ->
                            list.map { if (it.id == event.sessionId) it.copy(busy = true) else it }
                        }
                        refresh(silent = true)
                    }
                    is GatewayEvent.TurnEnd -> {
                        sessions.update { list ->
                            list.map {
                                if (it.id == event.sessionId) {
                                    it.copy(busy = false, title = event.title ?: it.title)
                                } else {
                                    it
                                }
                            }
                        }
                        refresh(silent = true)
                    }
                    is GatewayEvent.Stopped -> {
                        sessions.update { list ->
                            list.map { if (it.id == event.sessionId) it.copy(busy = false) else it }
                        }
                        refresh(silent = true)
                    }
                    is GatewayEvent.SessionUpdated -> {
                        sessions.update { list ->
                            list.map {
                                if (it.id == event.sessionId) {
                                    it.copy(
                                        title = event.title ?: it.title,
                                        updatedAt = event.updatedAt ?: it.updatedAt,
                                    )
                                } else {
                                    it
                                }
                            }
                        }
                        refresh(silent = true)
                    }
                    is GatewayEvent.Snapshot -> sessions.update { list ->
                        list.map {
                            if (it.id == event.sessionId) {
                                it.copy(busy = event.busy, pending = event.pending.size)
                            } else {
                                it
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        // Client-side list changes without a turn event (e.g. agent handoff).
        viewModelScope.launch {
            sessionRepository.invalidations.collect { refresh(silent = true) }
        }
        // A 401 mid-session means the token was revoked/rotated: back to the
        // connect gate. Ignored while on the gate itself (no saved profile),
        // where a wrong-URL attempt also answers 401.
        viewModelScope.launch {
            connection.unauthorized.collect {
                if (connectionRepository.profile.first() != null) disconnect()
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        if (refreshJob?.isActive == true) {
            // Conflate a burst (session_updated + turn_start/turn_end) into one
            // guaranteed follow-up pass instead of dropping the terminal refresh.
            refreshPending = true
            if (!silent) local.update { it.copy(refreshing = true) }
            return
        }
        // The full-pane spinner only before anything is loaded; a pull-to-refresh
        // over an existing list shows just the indicator.
        if (!silent) local.update { it.copy(loading = sessions.value.isEmpty(), refreshing = true, error = false) }
        refreshJob = viewModelScope.launch {
            var firstPass = true
            do {
                refreshPending = false
                val passSilent = if (firstPass) silent else true
                var hasUsableData = sessions.value.isNotEmpty()
                if (!hasUsableData) {
                    sessionRepository.cachedSessions()?.let { cached ->
                        sessions.value = cached
                        hasUsableData = true
                        local.update { it.copy(loading = false, error = false) }
                    }
                }
                try {
                    sessions.value = sessionRepository.refreshSessions()
                    local.update { it.copy(loading = false, refreshing = false, error = false) }
                } catch (e: Exception) {
                    local.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = if (!passSilent && !hasUsableData) true else it.error,
                        )
                    }
                }
                // Files badge (web refreshFiles piggybacking on every list refresh).
                runCatching { fileRepository.files() }.getOrNull()?.let { files ->
                    local.update { it.copy(filesCount = files.size) }
                }
                firstPass = false
            } while (refreshPending)
        }
    }

    fun toggleView() {
        viewModelScope.launch {
            settingsStore.setSessionView(if (uiState.value.byProject) "all" else "project")
        }
    }

    fun setView(project: Boolean) {
        viewModelScope.launch {
            settingsStore.setSessionView(if (project) "project" else "all")
        }
    }

    fun toggleShowModel() {
        viewModelScope.launch {
            settingsStore.setShowModel(!uiState.value.showModel)
        }
    }

    /** Switch the home agent tab — persisted; the list re-filters and new chats
     *  are created under it. */
    fun setHomeAgent(id: String) {
        if (id == uiState.value.homeAgentId) return
        viewModelScope.launch { settingsStore.setHomeAgent(id) }
    }

    /** Collapse/expand a project group (persisted). cwd null = default project. */
    fun toggleGroup(cwd: String?) {
        viewModelScope.launch { settingsStore.toggleCollapsedGroup(cwd ?: "") }
    }

    // ---- new chat / folder picker ----

    fun newChat() {
        local.update { it.copy(pickingFolder = true) }
        browseFolder(null)
    }

    /** Per-group ➕ — start a chat directly in that project's folder. */
    fun newChatIn(cwd: String?) = create(cwd)

    fun browseFolder(path: String?) {
        local.update { it.copy(folderLoading = true) }
        viewModelScope.launch {
            val listing = runCatching { folderRepository.list(path) }.getOrNull()
            local.update { it.copy(folderLoading = false, folderListing = listing) }
        }
    }

    fun createFolder(name: String) {
        val parent = local.value.folderListing?.path ?: return
        viewModelScope.launch {
            runCatching { folderRepository.create(parent, name) }
                .onSuccess { browseFolder(parent) }
        }
    }

    fun useFolder() {
        val path = local.value.folderListing?.path
        local.update { it.copy(pickingFolder = false) }
        create(path?.takeIf { it.isNotEmpty() })
    }

    fun cancelFolderPicker() {
        local.update { it.copy(pickingFolder = false) }
    }

    private fun create(cwd: String?) {
        if (local.value.creating) return
        local.update { it.copy(creating = true, creatingCwd = cwd) }
        viewModelScope.launch {
            try {
                val created = newSessionFactory.create(
                    cwd = cwd,
                    knownAgents = local.value.agents,
                    agentIdOverride = uiState.value.homeAgentId,
                )
                local.update { it.copy(creating = false, creatingCwd = null, openSessionId = created.id) }
                refresh(silent = true)
            } catch (e: Exception) {
                local.update { it.copy(creating = false, creatingCwd = null, error = true) }
            }
        }
    }

    fun onOpened() {
        local.update { it.copy(openSessionId = null) }
    }

    // ---- rename / delete ----

    fun rename(id: String, title: String) {
        viewModelScope.launch {
            runCatching { sessionRepository.rename(id, title) }
            refresh(silent = true)
        }
    }

    /**
     * Confirmed delete: red spinner on the row until the server answers, then
     * the row goes NOW (waiting for the refetch left the spinner up even
     * though the delete was done); failure keeps the row and says why.
     * [wasActive] = the chat being deleted is the one on screen — it must not
     * stay up as a dead page: open the newest remaining chat, or start a fresh
     * one when this was the last (web deleteSession behavior).
     */
    fun delete(id: String, wasActive: Boolean = false) {
        if (id in local.value.deletingIds) return
        local.update { it.copy(deletingIds = it.deletingIds + id) }
        viewModelScope.launch {
            try {
                sessionRepository.delete(id)
            } catch (e: Exception) {
                toaster.show(context.getString(R.string.sessions_delete_failed))
                local.update { it.copy(deletingIds = it.deletingIds - id) }
                return@launch
            }
            sessions.value = sessions.value.filterNot { it.id == id }
            local.update { it.copy(deletingIds = it.deletingIds - id) }
            toaster.show(context.getString(R.string.sessions_deleted))
            if (wasActive) {
                val next = sessions.value.firstOrNull()
                if (next != null) {
                    local.update { it.copy(openSessionId = next.id) }
                } else {
                    create(null)
                }
            }
            refresh(silent = true)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectionRepository.disconnect()
            local.update { it.copy(disconnected = true) }
        }
    }
}
