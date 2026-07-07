package com.ahmedgamil.wakili.feature.terminal.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ahmedgamil.wakili.feature.terminal.TerminalScreen
import kotlinx.serialization.Serializable

@Serializable
data class TerminalRoute(val sessionId: String, val cwd: String? = null)

fun NavController.navigateToTerminal(sessionId: String, cwd: String?) =
    navigate(TerminalRoute(sessionId, cwd))

fun NavGraphBuilder.terminalScreen(onBack: () -> Unit) {
    composable<TerminalRoute> {
        TerminalScreen(onBack = onBack)
    }
}
