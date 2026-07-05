package com.ahmedgamil.wakili.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ahmedgamil.wakili.feature.chat.navigation.chatScreen
import com.ahmedgamil.wakili.feature.chat.navigation.navigateToChat
import com.ahmedgamil.wakili.feature.connect.ConnectScreen
import com.ahmedgamil.wakili.feature.files.FilesScreen
import com.ahmedgamil.wakili.feature.sessions.SessionsScreen
import com.ahmedgamil.wakili.feature.settings.SettingsScreen
import com.ahmedgamil.wakili.feature.terminal.navigation.navigateToTerminal
import com.ahmedgamil.wakili.feature.terminal.navigation.terminalScreen
import kotlinx.serialization.Serializable

@Serializable
data object ConnectRoute

@Serializable
data object SessionsRoute

@Serializable
data object SettingsRoute

@Serializable
data object FilesRoute

@Composable
fun WakiliNavHost(
    navController: NavHostController,
    startConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = if (startConnected) SessionsRoute else ConnectRoute,
        modifier = modifier,
    ) {
        composable<ConnectRoute> {
            ConnectScreen(
                onConnected = {
                    navController.navigate(SessionsRoute) {
                        popUpTo(ConnectRoute) { inclusive = true }
                    }
                },
            )
        }
        composable<SessionsRoute> {
            SessionsScreen(
                onDisconnected = {
                    navController.navigate(ConnectRoute) {
                        popUpTo(SessionsRoute) { inclusive = true }
                    }
                },
                onOpenSession = { sessionId -> navController.navigateToChat(sessionId) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenFiles = { navController.navigate(FilesRoute) },
            )
        }
        chatScreen(
            onBack = { navController.popBackStack() },
            onOpenTerminal = { sessionId, cwd -> navController.navigateToTerminal(sessionId, cwd) },
        )
        terminalScreen(onBack = { navController.popBackStack() })
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<FilesRoute> {
            FilesScreen(onBack = { navController.popBackStack() })
        }
    }
}
