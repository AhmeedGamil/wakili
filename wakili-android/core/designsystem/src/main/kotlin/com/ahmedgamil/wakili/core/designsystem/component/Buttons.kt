package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/** Button looks from app.css: .btn, .btn.primary, .btn.ghost, .btn.deny, .btn.allow-session. */
enum class WakiliButtonStyle { Primary, Default, Ghost, Outline, AccentOutline, Danger }

@Composable
fun WakiliButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WakiliButtonStyle = WakiliButtonStyle.Default,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = wakiliButtonPadding(style),
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    val background = when (style) {
        WakiliButtonStyle.Primary -> colors.accent
        WakiliButtonStyle.Default -> colors.panel2
        WakiliButtonStyle.Danger -> colors.danger
        else -> Color.Transparent
    }
    val contentColor = when (style) {
        WakiliButtonStyle.Primary -> colors.accentInk
        WakiliButtonStyle.AccentOutline -> colors.accent
        WakiliButtonStyle.Danger -> Color.White
        else -> colors.text
    }
    val borderColor = when (style) {
        WakiliButtonStyle.Default, WakiliButtonStyle.Outline -> colors.border
        WakiliButtonStyle.AccentOutline -> colors.accent
        else -> null
    }
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else WakiliDimens.AlphaDisabled)
            .clip(shape)
            .background(background, shape)
            .let { m -> borderColor?.let { m.border(WakiliDimens.BorderThin, it, shape) } ?: m }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            icon?.let { WakiliIcon(it) }
            Text(
                text = text,
                style = if (style == WakiliButtonStyle.Primary || style == WakiliButtonStyle.AccentOutline) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = contentColor,
            )
        }
    }
}

private fun wakiliButtonPadding(style: WakiliButtonStyle): PaddingValues = when (style) {
    // .btn.primary { padding: 11px 12px }
    WakiliButtonStyle.Primary -> PaddingValues(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space11)
    // .btn.allow / .btn.deny / .btn.allow-session { padding: 8px 18px }
    WakiliButtonStyle.Outline, WakiliButtonStyle.AccentOutline, WakiliButtonStyle.Danger ->
        PaddingValues(horizontal = WakiliDimens.Space18, vertical = WakiliDimens.Space8)
    // .btn { padding: 9px 12px }
    else -> PaddingValues(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space9)
}

/** .icon-btn.round / .files-btn — a circular icon button on the pill background. */
@Composable
fun WakiliRoundIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = WakiliDimens.RoundButton,
    iconSize: Dp = WakiliDimens.Icon,
    contentDescription: String? = null,
) {
    val colors = WakiliTheme.colors
    Row(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.pill, CircleShape)
            .border(WakiliDimens.BorderThin, colors.border, CircleShape)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WakiliIcon(icon, size = iconSize, tint = colors.text, contentDescription = contentDescription)
    }
}

/** A borderless tap target for a single icon (the web's ghost icon buttons). */
@Composable
fun WakiliGhostIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = WakiliTheme.colors.muted,
    iconSize: Dp = WakiliDimens.Icon,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(WakiliDimens.Space8),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WakiliIcon(icon, size = iconSize, tint = tint, contentDescription = contentDescription)
    }
}
