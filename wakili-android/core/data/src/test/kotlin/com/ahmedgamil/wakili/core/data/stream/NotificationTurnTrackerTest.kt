package com.ahmedgamil.wakili.core.data.stream

import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTurnTrackerTest {

    @Test
    fun `turn end returns latest agent text after tool activity`() {
        val tracker = NotificationTurnTracker()
        tracker.record(GatewayEvent.TurnStart("s"))
        tracker.record(GatewayEvent.TextDelta("s", "I am checking."))
        tracker.record(
            GatewayEvent.ToolUses(
                "s",
                listOf(Part.Tool(id = "t1", name = "Read", input = null)),
            ),
        )
        tracker.record(GatewayEvent.TextDelta("s", "The final agent reply."))

        assertEquals(
            "The final agent reply.",
            tracker.record(GatewayEvent.TurnEnd("s", "The original user prompt")),
        )
    }

    @Test
    fun `completed text is not reused by the next turn`() {
        val tracker = NotificationTurnTracker()
        tracker.record(GatewayEvent.TurnStart("s"))
        tracker.record(GatewayEvent.TextDelta("s", "First reply"))
        assertEquals("First reply", tracker.record(GatewayEvent.TurnEnd("s", null)))

        tracker.record(GatewayEvent.TurnStart("s"))
        assertNull(tracker.record(GatewayEvent.TurnEnd("s", null)))
    }

    @Test
    fun `snapshot text can supply notification after reconnect`() {
        val tracker = NotificationTurnTracker()
        tracker.record(
            GatewayEvent.Snapshot(
                sessionId = "s",
                parts = listOf(Part.Text("Restored agent reply")),
                busy = true,
                pending = emptyList(),
                client = "",
            ),
        )

        assertEquals(
            "Restored agent reply",
            tracker.record(GatewayEvent.TurnEnd("s", null)),
        )
    }
}
