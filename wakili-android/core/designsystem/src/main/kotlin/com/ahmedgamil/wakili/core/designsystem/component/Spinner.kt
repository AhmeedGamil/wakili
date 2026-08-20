package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/**
 * .spinner-danger — the red delete-in-progress ring: a danger arc over a 30%
 * danger track, shown until the server confirms a removal.
 */
@Composable
fun WakiliDangerSpinner(
    size: Dp,
    modifier: Modifier = Modifier,
    stroke: Dp = SpinnerStroke,
) {
    val danger = WakiliTheme.colors.danger
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = danger,
        trackColor = danger.copy(alpha = SpinnerTrackAlpha),
        strokeWidth = stroke,
    )
}

private val SpinnerStroke = 3.dp
private const val SpinnerTrackAlpha = 0.3f
