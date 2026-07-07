package com.ahmedgamil.wakili.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/** Full-screen centered spinner used while a screen's first state loads. */
@Composable
fun WakiliLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = WakiliTheme.colors.accent)
    }
}

private const val BlinkMillis = 1200

/** .typing / .ti-dots — three muted dots blinking with a staggered delay. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier.padding(vertical = WakiliDimens.Space6, horizontal = WakiliDimens.Space2),
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(
                    // @keyframes blink { 0%,60%,100% { opacity:.25 } 30% { opacity:1 } }
                    animation = keyframes {
                        durationMillis = BlinkMillis
                        0.25f at (index * BlinkMillis / 6) using LinearEasing
                        1f at (index * BlinkMillis / 6 + (BlinkMillis * 3 / 10)) using LinearEasing
                        0.25f at (index * BlinkMillis / 6 + (BlinkMillis * 6 / 10)) using LinearEasing
                    },
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(WakiliDimens.TypingDot)
                    .alpha(alpha)
                    .background(WakiliTheme.colors.muted, CircleShape),
            )
        }
    }
}
