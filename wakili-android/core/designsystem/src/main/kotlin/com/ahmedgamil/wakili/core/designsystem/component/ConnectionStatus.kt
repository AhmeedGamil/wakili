package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/**
 * Connection-state indicators — the ONE source of truth for "is the live stream
 * up?", reused by every consumer (the home host row's dot, the chat folder
 * chip's dot, and the home banner). Green when connected, red when it's down; a
 * down dot pulses to pull the eye. Keeping the color rule here means all
 * indicators can never disagree.
 */

/** Green = live stream connected. Matches the web --diff-add green. */
val ConnectedColor = Color(0xFF2EA043)

/** A small round dot colored by connection state; pulses while disconnected. */
@Composable
fun ConnectionDot(
    connected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = WakiliDimens.BusyDot,
) {
    StatusDot(
        color = if (connected) ConnectedColor else WakiliTheme.colors.danger,
        pulse = !connected,
        size = size,
        modifier = modifier,
    )
}

/**
 * A small "Reconnecting…" pill — a spinner + one line, no escalation. Shown only
 * on the home page, at the trailing end of the host row; renders nothing while
 * connected. It expands HORIZONTALLY because it sits inline in that row — a
 * vertical expand would shove the row's height around as the state flips.
 */
@Composable
fun ConnectionBanner(
    connected: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = !connected,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
        modifier = modifier,
    ) {
        val colors = WakiliTheme.colors
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            modifier = Modifier
                .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                .background(colors.pill)
                .border(WakiliDimens.BorderThin, colors.border, RoundedCornerShape(WakiliDimens.RadiusLg))
                .padding(horizontal = WakiliDimens.Space10, vertical = WakiliDimens.Space4),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                color = colors.muted,
                trackColor = colors.muted.copy(alpha = 0.25f),
                strokeWidth = 1.5.dp,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}
