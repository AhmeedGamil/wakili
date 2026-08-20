package com.ahmedgamil.wakili.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * One image in a paged viewer: where to load it from, what to call it, and a
 * caller-owned `id` handed back by onDownload so the caller can act on the image
 * actually on screen without matching it back by URL.
 */
data class ViewerImage(
    val url: String,
    val name: String,
    val id: String = url,
)

/**
 * Full-screen image viewer — dark scrim over a Telephoto zoomable image, the
 * stock big-app feel: pinch anchored at the fingers, animated double-tap zoom,
 * momentum panning, rubber-band over-zoom that settles back. Closing is
 * DELIBERATE only — the ✕ button or the system back — a stray tap on the
 * picture never exits.
 */
@Composable
fun WakiliImageViewer(
    url: String,
    name: String,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    WakiliImageViewer(
        images = listOf(ViewerImage(url, name)),
        initialIndex = 0,
        onDismiss = onDismiss,
        onDownload = onDownload?.let { download -> { _ -> download() } },
    )
}

/**
 * The same viewer over a whole set of images: swiping left/right moves between
 * them, so opening one picture from a grid doesn't mean closing it to see the
 * next. Zoom wins over the swipe — Telephoto only hands the horizontal drag to
 * the pager once the image is back at its resting scale and panned to its edge,
 * so a zoomed-in inspection is never cut short by an accidental page turn.
 *
 * `onDownload` receives the image currently on screen, not the one it opened on.
 */
@Composable
fun WakiliImageViewer(
    images: List<ViewerImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onDownload: ((ViewerImage) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // The list can shrink underneath the viewer (a delete, a refresh), so the
        // starting page is clamped rather than trusted.
        val start = initialIndex.coerceIn(0, images.lastIndex)
        val pagerState = rememberPagerState(initialPage = start) { images.size }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = ScrimAlpha)),
        ) {
            HorizontalPager(
                state = pagerState,
                // Neighbours stay mounted so a swipe lands on a loaded picture
                // instead of a blank frame that fills in afterwards.
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val image = images[page]
                ZoomableAsyncImage(
                    model = image.url,
                    contentDescription = image.name,
                    // Keyed per page: each image owns its zoom, and returning to
                    // one doesn't inherit the previous image's scale.
                    state = rememberZoomableImageState(
                        rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MaxZoom)),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                WakiliRoundIconButton(
                    icon = WakiliIcons.X,
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(WakiliDimens.Space12),
                )
                if (onDownload != null) {
                    WakiliRoundIconButton(
                        icon = WakiliIcons.Download,
                        onClick = {
                            images.getOrNull(pagerState.currentPage)?.let(onDownload)
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(WakiliDimens.Space12),
                    )
                }
                // "3 / 12" — only worth showing when there's somewhere to swipe to.
                if (images.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${images.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = CounterAlpha),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(WakiliDimens.Space16),
                    )
                }
            }
        }
    }
}

private const val ScrimAlpha = 0.92f
private const val MaxZoom = 7f
private const val CounterAlpha = 0.75f
