package com.ahmedgamil.wakili.core.designsystem.icon

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens

/**
 * The web app's icon set (public/js/components/icons.js — inline Lucide line
 * icons, ISC license) ported as stroked ImageVectors. Path data is copied
 * verbatim; `<line>/<rect>/<circle>/<polyline>` elements are pre-converted to
 * equivalent path commands. Icons inherit the local content color, exactly
 * like `currentColor` on the web.
 */
object WakiliIcons {
    val Plus by lazyIcon("plus", "M5 12h14", "M12 5v14")
    val ArrowUp by lazyIcon("arrow-up", "m5 12 7-7 7 7", "M12 19V5")
    val ArrowUpRight by lazyIcon("arrow-up-right", "M7 7h10v10", "M7 17 17 7")
    val Square by lazyIcon("square", "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val Paperclip by lazyIcon("paperclip", "m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")
    val Menu by lazyIcon("menu", "M4 6h16", "M4 12h16", "M4 18h16")
    val Folder by lazyIcon("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    val FileText by lazyIcon("file-text", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v5h5", "M16 13H8", "M16 17H8", "M10 9H8")
    val Image by lazyIcon("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M11 9a2 2 0 1 1-4 0a2 2 0 1 1 4 0z", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
    val Wifi by lazyIcon("wifi", "M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.86a10 10 0 0 1 14 0", "M8.5 16.43a5 5 0 0 1 7 0")
    val Moon by lazyIcon("moon", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")
    val Sun by lazyIcon(
        "sun",
        "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41",
        "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
    )
    val Lock by lazyIcon("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    val MonitorOff by lazyIcon("monitor-off", "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 21h8", "M12 17v4", "M2 2 22 22")
    val Power by lazyIcon("power", "M12 2v10", "M18.36 6.64a9 9 0 1 1-12.73 0")
    val Droplet by lazyIcon("droplet", "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z")
    val Zap by lazyIcon("zap", "M13 2 3 14h9l-1 8 10-12h-9l1-8z")
    val Trash by lazyIcon("trash", "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", "M10 11v6", "M14 11v6")
    val ChevronRight by lazyIcon("chevron-right", "m9 18 6-6-6-6")
    val ChevronDown by lazyIcon("chevron-down", "m6 9 6 6 6-6")
    val Terminal by lazyIcon("terminal", "m4 17 6-6-6-6", "M12 19h8")
    val Pencil by lazyIcon("pencil", "M12 20h9", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z")
    val Wrench by lazyIcon("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")
    val Bulb by lazyIcon("bulb", "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.8 1.3 1.5 1.5 2.5", "M9 18h6", "M10 22h4")
    val Download by lazyIcon("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3")
    val Help by lazyIcon("help", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    val Check by lazyIcon("check", "M20 6 9 17l-5-5")
    val X by lazyIcon("x", "M18 6 6 18", "m6 6 12 12")
    val CornerUpLeft by lazyIcon("corner-up-left", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5v0a5.5 5.5 0 0 1-5.5 5.5H11")
    val Type by lazyIcon("type", "M4 7V4h16v3", "M9 20h6", "M12 4v16")
    val Search by lazyIcon("search", "M19 11a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "m21 21-4.3-4.3")
    val Bot by lazyIcon("bot", "M12 8V4H8", "M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2")
    val Settings by lazyIcon(
        "settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    )
    val Clock by lazyIcon("clock", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M12 6v6l4 2")
}

/** Renders a [WakiliIcons] icon at the design-system size, tinted like `currentColor`. */
@Composable
fun WakiliIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = WakiliDimens.Icon,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

private fun lazyIcon(name: String, vararg pathData: String): Lazy<ImageVector> = lazy {
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}
