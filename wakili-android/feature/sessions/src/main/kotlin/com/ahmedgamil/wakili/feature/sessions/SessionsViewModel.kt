package com.ahmedgamil.wakili.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.data.repository.ConnectionRepository
import com.ahmedgamil.wakili.core.data.repository.FolderRepository
import com.ahmedgamil.wakili.core.data.repository.SessionRepository
import com.ahmedgamil.wakili.core.data.stream.GatewayStreamRepository
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.model.FolderListing
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionRowUi(
    val session: SessionSummary,
    val busy: Boolean,
    val unread: Boolean,
    val pending: Boolean,
)

data class SessionGroup(
    val folder: String,
    val cwd: String?,
    val rows: List<SessionRowUi>,
)

data class SessionsUiState(
    val loading: Boolean = true,
    val rows: List<SessionRowUi> = emptyList(),
    val groups: List<SessionGroup> = emptyList(),
    val byProject: Boolean = true,
    val host: String = "",
    val error: Boolean = false,
    val disconnected: Boolean = false,
    val creating: Boolean = false,
    val openSessionId: String? = null,
    val streamConnected: Boolean = true,
    // folder picker
    val pickingFolder: Boolean = false,
    val folderListing: FolderListing? = null,
    val folderLoading: Boolean = false,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val connectionRepository: ConnectionRepository,
    private val streamRepository: GatewayStreamRepository,
    private val folderRepository: FolderRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val local = MutableStateFlow(SessionsUiState())
    private val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    val uiState: StateFlow<SessionsUiState> = combine(
        local,
        sessions,
        streamRepository.busyIds,
        streamRepository.unreadIds,
        settingsStore.sessionView,
    ) { state, list, busyIds, unreadIds, view ->
        val rows = list.map { s ->
            SessionRowUi(
                session = s,
                busy = s.id in busyIds || s.busy,
                unread = s.id in unreadIds,
                pending = s.pending > 0,
            )
        }
        state.copy(
            rows = rows,
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
                .sortedByDescending { g -> g.rows.maxOf { it.session.updatedAt } },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    init {
        refresh()
        viewModelScope.launch {
            connectionRepository.profile.collect { profile ->
                local.update { it.copy(host = profile?.baseUrl.orEmpty()) }
            }
        }
        viewModelScope.launch {
            streamRepository.connected.collect { connected ->
                local.update { it.copy(streamConnected = connected) }
            }
        }
        viewModelScope.launch {
            streamRepository.events.collect { event ->
                if (event is GatewayEvent.TurnEnd || event is GatewayEvent.TurnStart) refresh(silent = true)
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        if (!silent) local.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try {
                sessions.value = sessionRepository.sessions()
                local.update { it.copy(loading = false) }
            } catch (e: Exception) {
                if (!silent) local.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun toggleView() {
        viewModelScope.launch {
            settingsStore.setSessionView(if (uiState.value.byProject) "all" else "project")
        }
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
        local.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val created = sessionRepository.create(cwd = cwd)
                local.update { it.copy(creating = false, openSessionId = created.id) }
                refresh(silent = true)
            } catch (e: Exception) {
                local.update { it.copy(creating = false, error = true) }
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

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { sessionRepository.delete(id) }
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
