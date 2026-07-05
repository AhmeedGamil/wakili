package com.ahmedgamil.wakili.core.network.stream

import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.PendingCard
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payloads below are wire-exact: each is what server.mjs publishes (with the
 * sessionId tag the multiplexed stream adds in sse.mjs publish()).
 */
class GatewayEventParserTest {

    private val parser = GatewayEventParser()

    @Test
    fun `connected has no session`() {
        val event = parser.parse("""{"type":"_gateway","subtype":"connected"}""")
        assertEquals(GatewayEvent.Connected, event)
    }

    @Test
    fun `turn lifecycle events`() {
        assertEquals(
            GatewayEvent.TurnStart("s1"),
            parser.parse("""{"type":"_gateway","subtype":"turn_start","sessionId":"s1"}"""),
        )
        assertEquals(
            GatewayEvent.TurnEnd("s1", "Fix the bug"),
            parser.parse("""{"type":"_gateway","subtype":"turn_end","title":"Fix the bug","sessionId":"s1"}"""),
        )
        assertEquals(
            GatewayEvent.Stopped("s1"),
            parser.parse("""{"type":"_gateway","subtype":"stopped","sessionId":"s1"}"""),
        )
    }

    @Test
    fun `text and thinking deltas`() {
        val text = parser.parse(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}},"sessionId":"s1"}""",
        )
        assertEquals(GatewayEvent.TextDelta("s1", "Hel"), text)

        val thinking = parser.parse(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hmm"}},"sessionId":"s1"}""",
        )
        assertEquals(GatewayEvent.ThinkingDelta("s1", "hmm"), thinking)
    }

    @Test
    fun `assistant tool_use blocks become ToolUses`() {
        val event = parser.parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Bash","input":{"command":"ls"}}]},"sessionId":"s1"}""",
        ) as GatewayEvent.ToolUses
        assertEquals(1, event.tools.size)
        assertEquals("Bash", event.tools[0].name)
        assertEquals("tu_1", event.tools[0].id)
        assertEquals("ls", event.tools[0].input?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `synthetic assistant message is whole text`() {
        val event = parser.parse(
            """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"Total cost: 1"}]},"sessionId":"s1"}""",
        )
        assertEquals(GatewayEvent.TextDelta("s1", "Total cost: 1"), event)
    }

    @Test
    fun `permission request carries tool and input`() {
        val event = parser.parse(
            """{"type":"_gateway","subtype":"permission_request","id":"p1","tool":"Write","input":{"file_path":"a.txt"},"sessionId":"s1"}""",
        ) as GatewayEvent.PermissionRequest
        assertEquals("p1", event.id)
        assertEquals("Write", event.tool)
        assertEquals("a.txt", event.input?.get("file_path")?.jsonPrimitive?.content)
    }

    @Test
    fun `question request with string and object options`() {
        val event = parser.parse(
            """{"type":"_gateway","subtype":"question_request","id":"q1","input":{"questions":[{"header":"DB","question":"Which database?","multiSelect":false,"options":["SQLite",{"label":"Postgres","description":"needs a server"}]}]},"sessionId":"s1"}""",
        ) as GatewayEvent.QuestionRequest
        assertEquals("q1", event.id)
        val q = event.questions.single()
        assertEquals("Which database?", q.question)
        assertEquals(false, q.multiSelect)
        assertEquals("SQLite", q.options[0].label)
        assertEquals("Postgres", q.options[1].label)
        assertEquals("needs a server", q.options[1].description)
    }

    @Test
    fun `snapshot replays parts and pending cards`() {
        val event = parser.parse(
            """{"type":"_gateway","subtype":"snapshot","client":"c-abc","busy":true,"parts":[{"type":"text","text":"working"},{"type":"tool","name":"Bash","id":"tu_9","input":{"command":"pwd"},"output":"/home","isError":false}],"pending":[{"type":"_gateway","subtype":"permission_request","id":"p2","tool":"Edit","input":{}}],"sessionId":"s1"}""",
        ) as GatewayEvent.Snapshot
        assertTrue(event.busy)
        assertEquals("c-abc", event.client)
        assertEquals(2, event.parts.size)
        val card = event.pending.single() as PendingCard.Permission
        assertEquals("Edit", card.tool)
    }

    @Test
    fun `tool result and auto-approved chip`() {
        val result = parser.parse(
            """{"type":"_gateway","subtype":"tool_result","id":"tu_1","output":"ok","isError":false,"sessionId":"s1"}""",
        )
        assertEquals(GatewayEvent.ToolResult("s1", "tu_1", "ok", false), result)

        val chip = parser.parse(
            """{"type":"_gateway","subtype":"tool","tool":"Bash","input":{"command":"ls"},"id":"tu_2","sessionId":"s1"}""",
        ) as GatewayEvent.AutoApprovedTool
        assertEquals("Bash", chip.tool.name)
        assertEquals("tu_2", chip.tool.id)
    }

    @Test
    fun `file delivery`() {
        val event = parser.parse(
            """{"type":"_gateway","subtype":"file","token":"abc","name":"chart.png","caption":"the chart","url":"/api/files/abc","sessionId":"s1"}""",
        ) as GatewayEvent.FileDelivered
        assertEquals("chart.png", event.file.name)
        assertEquals("/api/files/abc", event.file.url)
    }

    @Test
    fun `unknown events are ignored not crashed`() {
        assertNull(parser.parse("""{"type":"result","session_id":"resume-1","sessionId":"s1"}"""))
        assertNull(parser.parse("""{"type":"user","message":{"content":[{"type":"tool_result"}]},"sessionId":"s1"}"""))
        assertNull(parser.parse("""{"type":"_gateway","subtype":"brand_new_thing","sessionId":"s1"}"""))
        assertNull(parser.parse("not json at all"))
    }
}
