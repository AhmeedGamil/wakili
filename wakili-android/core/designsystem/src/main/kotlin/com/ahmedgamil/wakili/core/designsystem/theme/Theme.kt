package com.ahmedgamil.wakili.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * App theme. Web-parity colors are exposed through [WakiliTheme.colors]
 * (the app.css custom properties); a matching Material scheme is still
 * provided so M3 scaffolding (sheets, dialogs, ripples) blends in.
 */
@Composable
fun WakiliTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        wakiliDarkColors(accent ?: DarkAccentDefault)
    } else {
        wakiliLightColors(accent ?: LightAccentDefault)
    }
    CompositionLocalProvider(LocalWakiliColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = WakiliTypography,
            content = content,
        )
    }
}

object WakiliTheme {
    val colors: WakiliColorScheme
        @Composable @ReadOnlyComposable get() = LocalWakiliColors.current
}

private fun WakiliColorScheme.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = accentInk,
        primaryContainer = accent,
        onPrimaryContainer = accentInk,
        secondary = accent,
        onSecondary = accentInk,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceContainer = panel,
        surfaceContainerLow = panel,
        surfaceContainerHigh = panel2,
        surfaceVariant = panel2,
        onSurfaceVariant = muted,
        error = danger,
        onError = Color.White,
        outline = border,
        outlineVariant = border,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = accentInk,
        primaryContainer = accent,
        onPrimaryContainer = accentInk,
        secondary = accent,
        onSecondary = accentInk,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceContainer = panel,
        surfaceContainerLow = panel,
        surfaceContainerHigh = panel2,
        surfaceVariant = panel2,
        onSurfaceVariant = muted,
        error = danger,
        onError = Color.White,
        outline = border,
        outlineVariant = border,
    )
}
