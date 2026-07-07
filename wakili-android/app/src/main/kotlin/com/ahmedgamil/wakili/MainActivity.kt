package com.ahmedgamil.wakili

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.ahmedgamil.wakili.core.datastore.Settings
import com.ahmedgamil.wakili.core.datastore.ThemeMode
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.ui.WakiliLoading
import com.ahmedgamil.wakili.navigation.WakiliNavHost
import dagger.hilt.android.AndroidEntryPoint

// AppCompatActivity (not ComponentActivity) so per-app locale switching works
// below Android 13 via AppCompatDelegate.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
                    val startState by viewModel.startState.collectAsStateWithLifecycle()
                    when (val state = startState) {
                        AppStartState.Loading -> WakiliLoading()
                        else -> WakiliNavHost(
                            navController = rememberNavController(),
                            startConnected = state is AppStartState.Connected,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }
                }
            }
        }
    }
}

/** Null when unset — WakiliTheme then falls back to the per-theme web default. */
private fun Settings.accentColorOrNull(): Color? =
    accentHex.takeIf { it.isNotEmpty() }?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    }
