package com.ahmedgamil.wakili

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ahmedgamil.wakili.core.designsystem.component.ConnectionBanner
import com.ahmedgamil.wakili.feature.chat.navigation.ChatRoute
import com.ahmedgamil.wakili.feature.sessions.FolderPickerSheet
import com.ahmedgamil.wakili.feature.sessions.R as SessionsR
import com.ahmedgamil.wakili.feature.sessions.SessionsViewModel
import com.ahmedgamil.wakili.navigation.ConnectRoute
import com.ahmedgamil.wakili.navigation.HomeRoute
import com.ahmedgamil.wakili.navigation.WakiliNavHost

/**
 * App shell. The sidebar is now the Home PAGE (a full-screen route), not a
 * drawer sliding over the chat — so this just hosts the navigation graph and,
 * above it, a single floating connection banner that hovers over EVERY screen.
 * A back arrow in the chat returns to Home; there is no swipe/scrim drawer. A
 * single activity-scoped SessionsViewModel backs Home and this shell so they
 * stay in sync.
 */
@Composable
fun WakiliApp(
    startConnected: Boolean,
    notificationSessionId: String?,
    onNotificationHandled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val sessionsViewModel: SessionsViewModel = hiltViewModel()
    val uiState by sessionsViewModel.uiState.collectAsStateWithLifecycle()

    fun openChat(sessionId: String) {
        // Already open (a notification tap into this very chat, or with
        // Settings/Terminal stacked on top of it): come BACK to that screen.
        // Navigating again would pop the live chat and build a second one for
        // the same id, and the popped twin's teardown then detached the screen
        // that had just opened — a card answered there could never hide.
        if (runCatching { navController.getBackStackEntry(ChatRoute(sessionId)) }.isSuccess) {
            navController.popBackStack(ChatRoute(sessionId), inclusive = false)
            return
        }
        // Chats never stack: replace whatever we're on (Home or the current chat)
        // so back always leaves to Home rather than walking through old chats.
        val currentChatId = runCatching {
            navController.currentBackStackEntry?.toRoute<ChatRoute>()?.sessionId
        }.getOrNull()
        navController.navigate(ChatRoute(sessionId)) {
            launchSingleTop = true
            if (currentChatId != null) {
                // Chat → chat (handoff): drop the old chat, keep Home underneath.
                popUpTo(ChatRoute(currentChatId)) { inclusive = true }
            } else {
                // Home → chat: keep Home in the stack so Back returns to it.
                popUpTo(HomeRoute) { inclusive = false }
            }
        }
    }

    // Back from a chat returns to the Home page (kept underneath by openChat).
    fun backToHome() {
        if (!navController.popBackStack()) {
            navController.navigate(HomeRoute) { popUpTo(HomeRoute) { inclusive = true } }
        }
    }

    // Notification taps carry the originating session. Handle both a cold
    // launch and onNewIntent while preserving the app's single-chat back stack.
    LaunchedEffect(notificationSessionId, startConnected) {
        val sessionId = notificationSessionId?.takeIf { it.isNotBlank() }
        if (startConnected && sessionId != null) {
            openChat(sessionId)
            onNotificationHandled(sessionId)
        }
    }

    // A chat created via the folder picker / group ➕ opens as soon as it exists.
    LaunchedEffect(uiState.openSessionId) {
        uiState.openSessionId?.let { id ->
            sessionsViewModel.onOpened()
            openChat(id)
        }
    }
    // Disconnect (from Settings) returns to the connect screen.
    LaunchedEffect(uiState.disconnected) {
        if (uiState.disconnected) {
            navController.navigate(ConnectRoute()) {
                popUpTo(HomeRoute) { inclusive = true }
            }
        }
    }

    // The reconnect banner now lives ONLY on the home page (see SidebarDrawer),
    // not floating over every screen.
    WakiliNavHost(
        navController = navController,
        startConnected = startConnected,
        sessionsViewModel = sessionsViewModel,
        onOpenChat = { openChat(it) },
        onChatBack = { backToHome() },
        onOpenConnect = {
            navController.navigate(ConnectRoute(fromSettings = true))
        },
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
    )

    // Rendered above everything so it works from any screen.
    if (uiState.pickingFolder) {
        FolderPickerSheet(uiState, sessionsViewModel)
    }
}
