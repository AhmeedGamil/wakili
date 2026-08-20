package com.ahmedgamil.wakili

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ahmedgamil.wakili.core.common.AppForeground
import com.ahmedgamil.wakili.core.data.stream.NOTIFICATION_SESSION_ID_EXTRA
import com.ahmedgamil.wakili.core.data.stream.QuestionOverlay
import com.ahmedgamil.wakili.core.datastore.Settings
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.datastore.ThemeMode
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.ui.WakiliLoading
import com.ahmedgamil.wakili.core.ui.WakiliToastHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// AppCompatActivity (not ComponentActivity) so per-app locale switching works
// below Android 13 via AppCompatDelegate.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val notificationTarget = MutableStateFlow<String?>(null)

    @Inject
    lateinit var appForeground: AppForeground

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var questionOverlay: QuestionOverlay

    // Notifications are ON by default, but Android 13+ still needs the runtime
    // grant — ask on first launch (Settings' own toggle re-asks if denied).
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun requestNotificationPermissionIfWanted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        lifecycleScope.launch {
            if (settingsStore.notifications.first()) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appForeground.foreground = true
        // Floating question cards deliberately SURVIVE foregrounding: they
        // belong to whichever chat asked, and only opening THAT chat (or
        // answering) takes them down — see GatewayStreamRepository.openSession.
    }

    override fun onStop() {
        appForeground.foreground = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        notificationTarget.value = intent.notificationSessionId()
        requestNotificationPermissionIfWanted()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            WakiliTheme(
                darkTheme = darkTheme,
                accent = settings.accentColorOrNull(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val startState by viewModel.startState.collectAsStateWithLifecycle()
                        val notificationSessionId by notificationTarget.collectAsStateWithLifecycle()
                        when (val state = startState) {
                            AppStartState.Loading -> WakiliLoading()
                            else -> WakiliApp(
                                startConnected = state is AppStartState.Connected,
                                notificationSessionId = notificationSessionId,
                                onNotificationHandled = { handledId ->
                                    if (notificationTarget.value == handledId) {
                                        notificationTarget.value = null
                                    }
                                    // The asking chat is on screen now, so its
                                    // floating copy comes down. openSession also
                                    // does this, but it only runs when the chat
                                    // is BUILT — arriving at a chat already in
                                    // the back stack (the common notification
                                    // case) reuses it and never calls it.
                                    questionOverlay.removeForSession(handledId)
                                    // The Activity keeps the launch intent, so a
                                    // later recreation (theme/locale change,
                                    // restore) would re-read the same extra and
                                    // navigate to this chat all over again.
                                    if (intent?.notificationSessionId() == handledId) {
                                        intent?.removeExtra(NOTIFICATION_SESSION_ID_EXTRA)
                                    }
                                },
                            )
                        }
                        WakiliToastHost(
                            messages = viewModel.toasts,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.notificationSessionId()?.let { notificationTarget.value = it }
    }
}

private fun Intent.notificationSessionId(): String? =
    getStringExtra(NOTIFICATION_SESSION_ID_EXTRA)?.takeIf { it.isNotBlank() }

/** Null when unset — WakiliTheme then falls back to the per-theme web default. */
private fun Settings.accentColorOrNull(): Color? =
    accentHex.takeIf { it.isNotEmpty() }?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    }
