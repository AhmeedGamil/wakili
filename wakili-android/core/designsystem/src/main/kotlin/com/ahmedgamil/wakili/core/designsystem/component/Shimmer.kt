package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/**
 * .shimmer — a soft diagonal sweep painted over a reserved media box until its
 * image data arrives. The box itself keeps the final size — the shimmer only
 * paints, never sizes — so nothing collapses or shifts while pictures load.
 * Apply to a box that already has the placeholder background (panel-2).
 */
@Composable
fun Modifier.wakiliShimmer(active: Boolean): Modifier {
    if (!active) return this
    val sweep = WakiliTheme.colors.text.copy(alpha = WakiliDimens.AlphaMutedWash)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ShimmerSweepMillis, easing = LinearEasing),
        ),
        label = "shimmerSweep",
    )
    return drawWithContent {
        drawContent()
        val width = size.width
        // The gradient band rides from fully left of the box to fully right —
        // the web's translateX(-100%) → translateX(100%) keyframes at 100deg.
        val x = (progress * 2f - 1f) * width
        drawRect(
            brush = Brush.linearGradient(
                0.32f to Color.Transparent,
                0.5f to sweep,
                0.68f to Color.Transparent,
                start = Offset(x, 0f),
                end = Offset(x + width, size.height * ShimmerSlant),
            ),
        )
    }
}

private const val ShimmerSweepMillis = 1100
private const val ShimmerSlant = 0.35f
