package com.ahmedgamil.wakili.feature.chat.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Browser-parity containment for scrollers nested inside the transcript
 * (tool-card bodies, permission previews, question panes): a DRAG that crosses
 * the scroller's edge keeps moving the chat (scroll chaining, like the web),
 * but leftover FLING momentum stops at the edge — web flings never cross a
 * scroller boundary, and letting Compose hand the velocity to the chat list
 * slingshots the transcript the moment the card runs out of content.
 */
fun Modifier.containFlingChaining(): Modifier = nestedScroll(ContainFling)

private object ContainFling : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}
