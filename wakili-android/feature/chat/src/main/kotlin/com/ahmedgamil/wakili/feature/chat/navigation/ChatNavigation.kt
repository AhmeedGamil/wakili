package com.ahmedgamil.wakili.feature.chat.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ahmedgamil.wakili.feature.chat.ChatScreen
import kotlinx.serialization.Serializable

@Serializable
data class ChatRoute(val sessionId: String)

fun NavController.navigateToChat(sessionId: String) = navigate(ChatRoute(sessionId))

fun NavGraphBuilder.chatScreen(
    onBack: () -> Unit,
    onOpenTerminal: (sessionId: String, cwd: String?) -> Unit,
) {
    composable<ChatRoute> {
        ChatScreen(onBack = onBack, onOpenTerminal = onOpenTerminal)
    }
}
