package com.ahmedgamil.wakili

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** |dx| must beat |dy| by this factor — a swipe within ~27° of horizontal. */
private const val HORIZONTAL_DOMINANCE = 2f

/** Material's drawer numbers, so the feel matches the stock component. */
private val SettleVelocityThreshold = 400.dp
private const val SettleDurationMillis = 256
private const val PositionalThreshold = 0.5f

/**
 * Drawer position as an animated offset: -widthPx (closed, off the start edge)
 * … 0 (open). Offsets are LOGICAL — positive is always "toward open"; the
 * layout mirrors them for RTL. Dragging snaps the offset to the finger, and
 * snapTo/animateTo share Animatable's mutex, so a new drag naturally catches
 * and cancels a running settle animation.
 */
@Stable
class SideDrawerState(internal val widthPx: Float) {
    internal val offset = Animatable(-widthPx)

    /** Target state — true from the moment a gesture/call commits to opening. */
    var isOpen by mutableStateOf(false)
        private set

    /** 0 fully closed … 1 fully open. */
    val progress: Float
        get() = 1f + offset.value / widthPx

    internal val settledClosed: Boolean
        get() = !isOpen && !offset.isRunning

    suspend fun open() {
        isOpen = true
        offset.animateTo(0f, tween(SettleDurationMillis))
    }

    suspend fun close() {
        isOpen = false
        offset.animateTo(-widthPx, tween(SettleDurationMillis))
    }

    internal suspend fun dragBy(delta: Float) {
        offset.snapTo((offset.value + delta).coerceIn(-widthPx, 0f))
    }

    internal suspend fun settle(velocity: Float, velocityThreshold: Float) {
        val opening = when {
            velocity >= velocityThreshold -> true
            velocity <= -velocityThreshold -> false
            else -> progress > PositionalThreshold
        }
        isOpen = opening
        offset.animateTo(
            targetValue = if (opening) 0f else -widthPx,
            animationSpec = tween(SettleDurationMillis),
            initialVelocity = velocity,
        )
    }
}

/**
 * ModalNavigationDrawer look-alike that owns its gestures: the sheet tracks
 * the finger with the stock settle physics, but OPENING is claimed only for
 * horizontally dominant swipes, so a diagonal chat scroll never yanks the
 * sidebar in. While the drawer is showing, any mostly-horizontal drag moves
 * it (swipe-to-close, catching the animation) and a scrim tap closes it.
 */
@Composable
fun SideDrawerLayout(
    state: SideDrawerState,
    gesturesEnabled: Boolean,
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val velocityThresholdPx = with(LocalDensity.current) { SettleVelocityThreshold.toPx() }
    val scrimColor = DrawerDefaults.scrimColor

    Box(
        modifier = modifier
            .fillMaxSize()
            .sideDrawerGestures(state, gesturesEnabled, layoutDirection, scope, velocityThresholdPx),
    ) {
        content()
        // Scrim. The tap handler doubles as the input shield: while the drawer
        // is open it wins hit testing over the content beneath, and it is
        // removed entirely while closed so the content is reachable again.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.progress }
                .background(scrimColor)
                .then(
                    if (state.isOpen) {
                        Modifier.pointerInput(state) {
                            detectTapGestures { scope.launch { state.close() } }
                        }
                    } else {
                        Modifier
                    },
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = if (layoutDirection == LayoutDirection.Ltr) {
                        state.offset.value
                    } else {
                        -state.offset.value
                    }
                },
        ) {
            drawerContent()
        }
    }
}

/**
 * The one gesture handler, on the container so it runs in the Main pass after
 * every child — anything a list or button consumed is never fought over.
 * Claiming is decided once per gesture at touch-slop: from CLOSED only a
 * horizontal-dominant swipe toward the drawer qualifies; while SHOWING (open
 * or mid-animation) any mostly-horizontal drag does. After claiming, the
 * offset follows the finger and the release velocity picks the anchor.
 */
private fun Modifier.sideDrawerGestures(
    state: SideDrawerState,
    enabled: Boolean,
    layoutDirection: LayoutDirection,
    scope: CoroutineScope,
    velocityThresholdPx: Float,
): Modifier = pointerInput(state, enabled, layoutDirection, velocityThresholdPx) {
    if (!enabled) return@pointerInput
    val toward = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val closedAtDown = state.settledClosed
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var dragging = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) break
            if (!dragging && change.isConsumed) return@awaitEachGesture
            val delta = change.positionChange()
            if (dragging) {
                change.consume()
                scope.launch { state.dragBy(delta.x * toward) }
                continue
            }
            dx += delta.x
            dy += delta.y
            val slop = viewConfiguration.touchSlop
            if (abs(dx) <= slop && abs(dy) <= slop) continue
            val claimed = if (closedAtDown) {
                dx * toward > 0 && abs(dx) > abs(dy) * HORIZONTAL_DOMINANCE
            } else {
                abs(dx) > abs(dy)
            }
            if (!claimed) return@awaitEachGesture
            dragging = true
            change.consume()
            // Carry the pre-slop travel so the sheet meets the finger.
            val initial = dx * toward
            scope.launch { state.dragBy(initial) }
        }
        if (dragging) {
            val velocity = tracker.calculateVelocity().x * toward
            scope.launch { state.settle(velocity, velocityThresholdPx) }
        }
    }
}
