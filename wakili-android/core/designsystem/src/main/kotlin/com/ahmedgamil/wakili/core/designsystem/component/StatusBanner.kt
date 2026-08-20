package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import kotlinx.coroutines.delay

/**
 * Transient-status feedback (e.g. "Reconnecting…") as a quiet bordered pill
 * with a small working spinner, instead of a bare text jammed into the layout.
 * Slides/fades in and out so state changes never pop.
 *
 * A reconnect is usually over in seconds — so the resting tone is muted, not
 * alarming. Only when the state PERSISTS ([persistentAfterMs] of continuous
 * visibility) does it escalate: [persistentText] in the danger tone, for the
 * "this isn't a blip — here's what to do" moment. Hiding resets the escalation.
 */
@Composable
fun WakiliStatusBanner(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    persistentText: String? = null,
    persistentAfterMs: Long = 30_000,
) {
    var escalated by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        escalated = false
        if (visible && persistentText != null) {
            delay(persistentAfterMs)
            escalated = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val colors = WakiliTheme.colors
        val tone = if (escalated) colors.danger else colors.muted
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            modifier = Modifier
                .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                .background(colors.pill)
                .border(
                    WakiliDimens.BorderThin,
                    colors.border,
                    RoundedCornerShape(WakiliDimens.RadiusLg),
                )
                .padding(horizontal = WakiliDimens.Space10, vertical = WakiliDimens.Space6),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(BannerSpinnerSize),
                color = tone,
                trackColor = tone.copy(alpha = BannerTrackAlpha),
                strokeWidth = BannerSpinnerStroke,
            )
            Text(
                text = if (escalated && persistentText != null) persistentText else text,
                style = MaterialTheme.typography.bodySmall,
                color = tone,
            )
        }
    }
}

private val BannerSpinnerSize = 14.dp
private val BannerSpinnerStroke = 2.dp
private const val BannerTrackAlpha = 0.25f
