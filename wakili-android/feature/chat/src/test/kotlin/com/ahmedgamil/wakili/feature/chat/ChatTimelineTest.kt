package com.ahmedgamil.wakili.feature.chat

import com.ahmedgamil.wakili.core.model.ChatMessage
import com.ahmedgamil.wakili.core.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {

    @Test
    fun `stopped marker stays after origin assistant and before next user`() {
        val messages = listOf(
            user("first"),
            assistant("answer one"),
            user("second"),
            assistant("answer two"),
        )
        val marker = marker(userOrdinal = 0, pending = false)

        val placement = placeStoppedMarkers(messages, listOf(marker))

        assertEquals(listOf(marker), placement.afterMessageIndex[1])
        assertTrue(placement.unresolvedBeforeLive.isEmpty())
        assertTrue(placement.pendingLiveTail.isEmpty())
    }

    @Test
    fun `assistant insertion does not change user-turn anchor`() {
        val marker = marker(userOrdinal = 1, pending = false)
        val optimistic = placeStoppedMarkers(
            listOf(user("first"), user("second")),
            listOf(marker),
        )
        val reconciled = placeStoppedMarkers(
            listOf(user("first"), assistant("answer"), user("second")),
            listOf(marker),
        )

        assertEquals(listOf(marker), optimistic.afterMessageIndex[1])
        assertEquals(listOf(marker), reconciled.afterMessageIndex[2])
    }

    @Test
    fun `pending marker owns live tail and unresolved older marker stays before live`() {
        val pending = marker(userOrdinal = 0, pending = true)
        val unresolved = marker(generation = 2, userOrdinal = 1, pending = false)

        val placement = placeStoppedMarkers(listOf(user("first")), listOf(pending, unresolved))

        assertEquals(listOf(unresolved), placement.unresolvedBeforeLive)
        assertEquals(listOf(pending), placement.pendingLiveTail)
    }

    @Test
    fun `awaiting start forces dots visible over stale completed text`() {
        assertTrue(shouldShowWorkingDots(TurnPhase.AwaitingStart, Part.Text("old answer")))
        assertEquals(false, shouldShowWorkingDots(TurnPhase.Running, Part.Text("new answer")))
    }

    @Test
    fun `arming next turn retires old pending marker so it cannot hide pulse`() {
        val old = marker(userOrdinal = 0, pending = true)

        val retired = listOf(old).retirePendingStoppedMarkers()

        assertEquals(false, retired.single().pendingLiveTail)
        assertEquals(old.key, retired.single().key)
    }

    @Test
    fun `persisted assistant id suppresses its stale live prefix`() {
        val content = visibleChatContent(
            messages = listOf(
                ChatMessage.Assistant(
                    parts = listOf(Part.Text("complete answer")),
                    clientMessageId = "client-7",
                ),
            ),
            liveParts = listOf(Part.Text("complete")),
            liveClientMessageId = "client-7",
            transients = emptyList(),
        )

        assertTrue(content.liveParts.isEmpty())
    }

    @Test
    fun `identical new answer stays live when its id differs`() {
        val parts = listOf(Part.Text("same answer"))
        val content = visibleChatContent(
            messages = listOf(
                ChatMessage.Assistant(parts = parts, clientMessageId = "client-old"),
            ),
            liveParts = parts,
            liveClientMessageId = "client-new",
            transients = emptyList(),
        )

        assertEquals(parts, content.liveParts)
    }

    private fun user(text: String) = ChatMessage.User(text)

    private fun assistant(text: String) = ChatMessage.Assistant(listOf(Part.Text(text)))

    private fun marker(
        generation: Long = 1,
        userOrdinal: Int,
        pending: Boolean,
    ) = StoppedMarkerUi(
        generation = generation,
        userOrdinal = userOrdinal,
        interrupted = false,
        pendingLiveTail = pending,
    )
}
