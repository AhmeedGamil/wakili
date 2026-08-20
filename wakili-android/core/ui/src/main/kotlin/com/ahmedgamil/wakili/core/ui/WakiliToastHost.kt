package com.ahmedgamil.wakili.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private const val TOAST_MILLIS = 4_000L

/**
 * The web client's `.toast` — one reusable centered notice near the bottom,
 * auto-hiding after four seconds; a new message replaces the current one and
 * restarts the timer. Render above the whole app (last child of a full-size Box).
 */
@Composable
fun WakiliToastHost(messages: Flow<String>, modifier: Modifier = Modifier) {
    val colors = WakiliTheme.colors
    var text by remember { mutableStateOf("") }
    var epoch by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(messages) {
        messages.collect { msg ->
            text = msg
            visible = true
            epoch++
        }
    }
    LaunchedEffect(epoch) {
        if (visible) {
            delay(TOAST_MILLIS)
            visible = false
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .padding(bottom = WakiliDimens.ToastBottomOffset)
                    .padding(horizontal = WakiliDimens.Space24)
                    .shadow(WakiliDimens.Space8, RoundedCornerShape(WakiliDimens.RadiusLg))
                    .background(colors.panel, RoundedCornerShape(WakiliDimens.RadiusLg))
                    .border(
                        WakiliDimens.BorderThin,
                        colors.border,
                        RoundedCornerShape(WakiliDimens.RadiusLg),
                    )
                    .padding(horizontal = WakiliDimens.Space14, vertical = WakiliDimens.Space9),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
