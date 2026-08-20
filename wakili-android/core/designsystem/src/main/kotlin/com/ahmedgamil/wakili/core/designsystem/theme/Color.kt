package com.ahmedgamil.wakili.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Semantic color roles — a 1:1 port of the CSS custom properties in
 * public/app.css (`[data-theme=dark]` / `[data-theme=light]`). The web app is
 * the design source of truth; every composable reads these instead of
 * hardcoding colors.
 */
@Immutable
data class WakiliColorScheme(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
    val accentInk: Color,
    val user: Color,
    val hover: Color,
    val pill: Color,
    val danger: Color,
    val diffAdd: Color,
    val termError: Color,
    val isDark: Boolean,
)

fun wakiliDarkColors(accent: Color = DarkAccentDefault) = WakiliColorScheme(
    bg = Color(0xFF18181B),
    panel = Color(0xFF242428),
    panel2 = Color(0xFF303036),
    text = Color(0xFFECECEC),
    muted = Color(0xFF9A9AA3),
    border = Color(0xFF34343A),
    accent = accent,
    accentInk = accentInk(accent),
    user = Color(0xFF303036),
    hover = Color(0xFF2A2A2F),
    pill = Color(0xFF2A2A30),
    danger = Danger,
    diffAdd = DiffAdd,
    termError = TermError,
    isDark = true,
)

fun wakiliLightColors(accent: Color = LightAccentDefault) = WakiliColorScheme(
    bg = Color(0xFFFFFFFF),
    panel = Color(0xFFF7F7F8),
    panel2 = Color(0xFFECECF0),
    text = Color(0xFF1D1D22),
    muted = Color(0xFF6A6A74),
    border = Color(0xFFE6E6EC),
    accent = accent,
    accentInk = accentInk(accent),
    user = Color(0xFFF0F0F4),
    hover = Color(0xFFF1F1F4),
    pill = Color(0xFFF4F4F6),
    danger = Danger,
    diffAdd = DiffAdd,
    termError = TermError,
    isDark = false,
)

val LocalWakiliColors = staticCompositionLocalOf { wakiliDarkColors() }

val Danger = Color(0xFFE5484D)
val DiffAdd = Color(0xFF2EA043)
val TermError = Color(0xFFFF6B6B)

/** Per-theme accent defaults, same as --accent in app.css. */
val DarkAccentDefault = Color(0xFF3B82F6)
val LightAccentDefault = Color(0xFF3B82F6)
val AccentDefault = LightAccentDefault

data class AccentOption(val name: String, val color: Color)

// Mirrors PALETTE in public/js/components/AppearanceMenu.js — same 16 swatches.
val AccentPalette = listOf(
    AccentOption("Claude", Color(0xFFD97757)),
    AccentOption("Indigo", Color(0xFF6D5CF0)),
    AccentOption("Violet", Color(0xFF8B5CF6)),
    AccentOption("Blue", Color(0xFF3B82F6)),
    AccentOption("Cyan", Color(0xFF06B6D4)),
    AccentOption("Teal", Color(0xFF14B8A6)),
    AccentOption("Green", Color(0xFF22C55E)),
    AccentOption("Lime", Color(0xFF84CC16)),
    AccentOption("Yellow", Color(0xFFEAB308)),
    AccentOption("Amber", Color(0xFFF59E0B)),
    AccentOption("Orange", Color(0xFFF97316)),
    AccentOption("Red", Color(0xFFEF4444)),
    AccentOption("Rose", Color(0xFFF43F5E)),
    AccentOption("Pink", Color(0xFFEC4899)),
    AccentOption("Slate", Color(0xFF64748B)),
    AccentOption("Gray", Color(0xFF8B8B96)),
)

/**
 * Readable "ink" on top of the accent — same luminance rule the web app uses
 * to compute --accent-ink.
 */
fun accentInk(accent: Color): Color =
    if (accent.luminance() > 0.55f) Color(0xFF111114) else Color.White

/** "#RRGGBB", the format the settings store and the web palette use. */
fun Color.toHexString(): String = "#%06X".format(toArgb() and 0xFFFFFF)
