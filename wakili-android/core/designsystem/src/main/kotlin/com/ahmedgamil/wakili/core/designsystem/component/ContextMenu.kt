package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/** One row of a long-press menu (web .sess-pop button; danger = .ctx-danger). */
data class WakiliMenuItem(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * THE dismissal recipe for every anchored popup in the app. Call sites bring a
 * position provider and content; they never decide how closing works.
 *
 * Compose's own `dismissOnClickOutside` fires on touch-DOWN, which is what made
 * these menus feel hostile: a press-and-hold anywhere closed them before the
 * finger lifted, and any scroll that began outside was eaten as a dismissal
 * instead of scrolling. So it is turned off, and an invisible full-screen shield
 * underneath catches outside taps on RELEASE — the same `.pop-backdrop` shield
 * the web client settled on, so both clients close the same way.
 *
 * The shield is deliberately transparent, not a dim scrim: it matches the web,
 * and a dim would be a visual change rather than a behavioural fix. Making it
 * visible later is one `.background(...)` call here and nowhere else.
 *
 * WHICH window is focusable is load-bearing, and not for the obvious reason.
 * Compose never sets FLAG_NOT_TOUCH_MODAL (see `createFlags` — it only ever ORs
 * in WATCH_OUTSIDE_TOUCH, NOT_FOCUSABLE, SECURE, LAYOUT_NO_LIMITS), so a
 * *focusable* popup is touch-modal: it swallows every touch in the task,
 * including the ones outside its own bounds, which arrive as ACTION_OUTSIDE and
 * are then dropped because dismissOnClickOutside is off. Make the MENU focusable
 * and the shield below it never sees a single event — only the system back
 * button closes anything.
 *
 * So focus goes on the SHIELD instead. FLAG_NOT_FOCUSABLE implies
 * NOT_TOUCH_MODAL, so the non-focusable menu passes outside touches down to the
 * shield, which is full-screen, touch-modal, and closes on release. Back-press
 * lands on the shield too — it is the focused window, and its onDismissRequest
 * is the same `onDismiss`. Nothing reaches the app underneath either way.
 *
 * This costs nothing today because no anchored menu contains a text field; if
 * one ever does, it needs its own focusable window rather than a flag flip here.
 */
@Composable
fun WakiliAnchoredPopup(
    positionProvider: PopupPositionProvider,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Composed FIRST so it sits BELOW the menu window. Focusable, so it owns
    // back-press; touch-modal, so outside taps stop here instead of at the app.
    Popup(
        popupPositionProvider = FullScreenPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() } // on finger UP, not down
                },
        )
    }
    // Non-focusable, so it is NOT touch-modal: taps outside the card fall
    // through to the shield instead of being eaten here.
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false),
        content = content,
    )
}

private object FullScreenPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * Compact action menu anchored to the content that received the long press —
 * the web's ONE popover recipe (.sess-pop): fixed 190dp width, same row
 * height/text/icon sizes everywhere, only the item count changes the height.
 * Row metrics MATCH the composer's + menu (AddMenuItem): body-size text,
 * 18dp icons, 12/10dp row padding — not the fatter ChromeRow sizing.
 */
@Composable
fun WakiliContextMenu(
    expanded: Boolean,
    items: List<WakiliMenuItem>,
    onDismiss: () -> Unit,
    alignEnd: Boolean = false,
) {
    if (!expanded) return
    val gapPx = with(LocalDensity.current) { WakiliDimens.Space4.roundToPx() }
    WakiliAnchoredPopup(
        positionProvider = remember(gapPx, alignEnd) {
            ContextMenuPositionProvider(gapPx, alignEnd)
        },
        onDismiss = onDismiss,
    ) {
        val colors = WakiliTheme.colors
        WakiliCard(
            radius = WakiliDimens.Radius,
            contentPadding = WakiliDimens.Space6,
            modifier = Modifier.width(WakiliDimens.ContextMenuWidth),
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
                        .clickable {
                            onDismiss()
                            item.onClick()
                        }
                        .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
                ) {
                    WakiliIcon(
                        item.icon,
                        size = WakiliDimens.Icon,
                        tint = if (item.danger) colors.danger else colors.muted,
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item.danger) colors.danger else colors.text,
                    )
                }
            }
        }
    }
}

private class ContextMenuPositionProvider(
    private val gapPx: Int,
    private val alignEnd: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rawX = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = rawX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val rawY = if (below + popupContentSize.height <= windowSize.height) below else above
        val y = rawY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
