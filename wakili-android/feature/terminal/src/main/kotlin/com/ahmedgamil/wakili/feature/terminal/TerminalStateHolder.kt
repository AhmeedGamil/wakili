package com.ahmedgamil.wakili.feature.terminal

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps each session's terminal exactly as you left it (scrollback + cwd +
 * half-typed input) across navigation — the web keeps terminal tabs alive the
 * same way. App-lifetime only; a fresh process starts clean.
 */
@Singleton
class TerminalStateHolder @Inject constructor() {

    private val states = mutableMapOf<String, TerminalUiState>()

    fun restore(sessionId: String): TerminalUiState? = states[sessionId]

    fun bank(sessionId: String, state: TerminalUiState) {
        states[sessionId] = state.copy(running = false)
    }
}
