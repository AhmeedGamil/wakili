package com.ahmedgamil.wakili.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val FRAME_MILLIS = 16L
private const val DRAIN_DIVISOR = 20 // reveal ~1/20 of the backlog per frame

/**
 * Smooth streaming reveal — the web client's core/typewriter.js. The network
 * delivers text in bursty chunks; this drains the backlog a slice per frame
 * (proportional, so it catches up under load instead of falling behind).
 * When [enabled] is false the full text shows immediately.
 */
@Composable
fun rememberTypewriterText(
    target: String,
    enabled: Boolean,
    initiallyRevealedChars: Int = 0,
): String {
    val restoredPrefix = initiallyRevealedChars.coerceIn(0, target.length)
    // Saveable: a lazy-list item scrolled out of view is disposed, and a plain
    // remember would restart the reveal from zero when it scrolls back in.
    var shown by rememberSaveable {
        mutableIntStateOf(if (enabled) restoredPrefix else target.length)
    }
    if (!enabled && shown != target.length) shown = target.length
    // The stream only ever appends within one part; a shorter target means a
    // brand-new part reused this slot — start over.
    if (shown > target.length) shown = restoredPrefix
    if (enabled && shown < restoredPrefix) shown = restoredPrefix
    LaunchedEffect(target.length, enabled, restoredPrefix) {
        if (!enabled) return@LaunchedEffect
        while (shown < target.length) {
            val backlog = target.length - shown
            shown += (backlog / DRAIN_DIVISOR).coerceAtLeast(1)
            delay(FRAME_MILLIS)
        }
    }
    return target.take(shown)
}
