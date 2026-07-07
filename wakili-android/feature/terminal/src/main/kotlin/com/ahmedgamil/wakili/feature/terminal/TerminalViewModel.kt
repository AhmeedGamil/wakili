package com.ahmedgamil.wakili.feature.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.TermBody
import com.ahmedgamil.wakili.feature.terminal.navigation.TerminalRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TermLine(val text: String, val kind: Kind) {
    enum class Kind { COMMAND, OUTPUT, ERROR, HINT }
}

data class TerminalUiState(
    val cwd: String = "",
    val lines: List<TermLine> = emptyList(),
    val input: String = "",
    val running: Boolean = false,
    val history: List<String> = emptyList(),
)

/** Interactive programs would hang the no-TTY backend — same guard as the web. */
private val INTERACTIVE = setOf(
    "vim", "vi", "nano", "emacs", "less", "more", "top", "htop", "ssh", "mysql",
    "psql", "python", "python3", "node", "irb", "claude", "codex",
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: GatewayApi,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val route: TerminalRoute = savedStateHandle.toRoute<TerminalRoute>()

    private val _uiState = MutableStateFlow(TerminalUiState(cwd = route.cwd.orEmpty()))
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(history = settingsStore.termHistory.first()) }
        }
    }

    fun onInput(value: String) = _uiState.update { it.copy(input = value) }

    fun run() {
        val command = _uiState.value.input.trim()
        if (command.isEmpty() || _uiState.value.running) return

        val head = command.substringBefore(' ').substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (head in INTERACTIVE && !command.contains("--version") && !command.contains("-v")) {
            _uiState.update {
                it.copy(
                    lines = it.lines +
                        TermLine("$ $command", TermLine.Kind.COMMAND) +
                        TermLine("'$head' needs an interactive terminal — run it on the computer.", TermLine.Kind.HINT),
                    input = "",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                running = true,
                input = "",
                lines = it.lines + TermLine("$ $command", TermLine.Kind.COMMAND),
            )
        }
        viewModelScope.launch {
            settingsStore.addTermHistory(command)
            _uiState.update { it.copy(history = listOf(command) + it.history.filterNot { h -> h == command }) }
            val result = runCatching {
                api.term(route.sessionId, TermBody(command = command, cwd = _uiState.value.cwd.ifEmpty { null }))
            }.getOrNull()
            _uiState.update { state ->
                state.copy(
                    running = false,
                    cwd = result?.cwd ?: state.cwd,
                    lines = state.lines + TermLine(
                        text = result?.output?.ifEmpty { "(no output)" } ?: "request failed",
                        kind = if (result?.ok == true) TermLine.Kind.OUTPUT else TermLine.Kind.ERROR,
                    ),
                )
            }
        }
    }
}
