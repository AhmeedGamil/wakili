package com.ahmedgamil.wakili.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ahmedgamil.wakili.feature.chat.navigation.chatScreen
import com.ahmedgamil.wakili.feature.connect.ConnectScreen
import com.ahmedgamil.wakili.feature.files.FilesScreen
import com.ahmedgamil.wakili.feature.sessions.HomeScreen
import com.ahmedgamil.wakili.feature.sessions.ScheduledTasksScreen
import com.ahmedgamil.wakili.feature.sessions.SessionsViewModel
import com.ahmedgamil.wakili.feature.settings.SettingsScreen
import com.ahmedgamil.wakili.feature.terminal.navigation.navigateToTerminal
import com.ahmedgamil.wakili.feature.terminal.navigation.terminalScreen
import kotlinx.serialization.Serializable

@Serializable
data class ConnectRoute(val fromSettings: Boolean = false)

@Serializable
data object HomeRoute

@Serializable
data object SettingsRoute

@Serializable
data object FilesRoute

@Serializable
data object ScheduledRoute

@Composable
fun WakiliNavHost(
    navController: NavHostController,
    startConnected: Boolean,
    sessionsViewModel: SessionsViewModel,
    onOpenChat: (String) -> Unit,
    onChatBack: () -> Unit,
    onOpenConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = if (startConnected) HomeRoute else ConnectRoute(),
        modifier = modifier,
    ) {
        composable<ConnectRoute> { entry ->
            val fromSettings = runCatching { entry.toRoute<ConnectRoute>().fromSettings }
                .getOrDefault(false)
            ConnectScreen(
                onConnected = {
                    // Fresh connection: rebuild the shell on top of it.
                    navController.navigate(HomeRoute) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    }
                },
                // Opened over a live session (Settings → add/change host): Back
                // returns to it, like the react-native hosts page.
                onBack = if (fromSettings) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                onOpenChat = onOpenChat,
                onOpenFiles = { navController.navigate(FilesRoute) },
                onOpenScheduled = { navController.navigate(ScheduledRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenConnect = onOpenConnect,
                viewModel = sessionsViewModel,
            )
        }
        chatScreen(
            onBack = onChatBack,
            onOpenTerminal = { sessionId, cwd -> navController.navigateToTerminal(sessionId, cwd) },
            onOpenChat = onOpenChat,
            onOpenScheduled = { navController.navigate(ScheduledRoute) },
        )
        terminalScreen(onBack = { navController.popBackStack() })
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenConnect = { navController.navigate(ConnectRoute(fromSettings = true)) },
            )
        }
        composable<FilesRoute> {
            FilesScreen(onBack = { navController.popBackStack() })
        }
        composable<ScheduledRoute> {
            ScheduledTasksScreen(onBack = { navController.popBackStack() })
        }
    }
}
