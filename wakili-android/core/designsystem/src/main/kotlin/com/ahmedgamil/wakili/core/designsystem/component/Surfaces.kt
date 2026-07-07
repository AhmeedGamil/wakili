package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/** A bordered panel surface — .tool-card / .msg.perm / .ep-row / popups. */
@Composable
fun WakiliCard(
    modifier: Modifier = Modifier,
    radius: Dp = WakiliDimens.RadiusLg,
    color: Color = WakiliTheme.colors.panel,
    borderColor: Color = WakiliTheme.colors.border,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(color, shape)
            .border(WakiliDimens.BorderThin, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** .appr-label / .files-sec-title — small uppercase muted section heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = WakiliTheme.colors.muted,
        modifier = modifier,
    )
}

/** .group-head — uppercase group heading in lists (slightly larger). */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = WakiliTheme.colors.text,
        modifier = modifier,
    )
}

/** .s-busy / .s-unread — status dot; pulses like the web keyframe when [pulse]. */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = WakiliDimens.BusyDot,
    pulse: Boolean = false,
) {
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        value
    } else {
        1f
    }
    Box(
        modifier
            .size(size)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/** .picker-trigger / .topbar-folder — a pill chip with icon, label and caret. */
@Composable
fun WakiliPillChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = WakiliTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.pill, CircleShape)
            .border(WakiliDimens.BorderThin, colors.border, CircleShape)
            .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }
            .padding(horizontal = WakiliDimens.Space14, vertical = WakiliDimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
    ) {
        icon?.let { WakiliIcon(it, size = WakiliDimens.IconSm, tint = colors.text) }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.let { WakiliIcon(it, size = WakiliDimens.IconXs, tint = colors.muted) }
    }
}

/** border: 1px dashed — used by the queued-message chip (.queued). */
fun Modifier.dashedBorder(
    color: Color,
    radius: Dp,
    strokeWidth: Dp = WakiliDimens.BorderThin,
): Modifier = drawBehind {
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(WakiliDimens.Space6.toPx(), WakiliDimens.Space4.toPx()),
        ),
    )
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = stroke,
    )
}
