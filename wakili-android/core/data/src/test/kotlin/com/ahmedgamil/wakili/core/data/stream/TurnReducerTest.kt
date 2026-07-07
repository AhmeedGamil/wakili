package com.ahmedgamil.wakili.core.data.stream

import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnReducerTest {

    private fun reduceAll(events: List<GatewayEvent.SessionEvent>): List<Part> =
        events.fold(emptyList()) { acc, e -> TurnReducer.reduce(acc, e) }

    @Test
    fun `full turn assembles like the server`() {
        // Mirrors runTurn: thinking → text → tool_use → tool_result → more text.
        val parts = reduceAll(
            listOf(
                GatewayEvent.TurnStart("s"),
                GatewayEvent.ThinkingDelta("s", "let me "),
                GatewayEvent.ThinkingDelta("s", "look"),
                GatewayEvent.TextDelta("s", "I'll check"),
                GatewayEvent.TextDelta("s", " the file."),
                GatewayEvent.ToolUses("s", listOf(Part.Tool(id = "t1", name = "Read", input = null))),
                GatewayEvent.ToolResult("s", "t1", "contents", false),
                GatewayEvent.TextDelta("s", "Done."),
            ),
        )

        assertEquals(
            listOf(
                Part.Thinking("let me look"),
                Part.Text("I'll check the file."),
                Part.Tool(id = "t1", name = "Read", input = null, output = "contents", isError = false),
                Part.Text("Done."),
            ),
            parts,
        )
    }

    @Test
    fun `auto-approved chip does not duplicate an existing tool part`() {
        val tool = Part.Tool(id = "t1", name = "Bash", input = null)
        val parts = reduceAll(
            listOf(
                GatewayEvent.ToolUses("s", listOf(tool)),
                GatewayEvent.AutoApprovedTool("s", tool),
            ),
        )
        assertEquals(1, parts.size)
    }

    @Test
    fun `auto-approved chip without prior tool_use is added`() {
        val parts = reduceAll(
            listOf(GatewayEvent.AutoApprovedTool("s", Part.Tool(id = "t2", name = "Write", input = null))),
        )
        assertEquals(listOf(Part.Tool(id = "t2", name = "Write", input = null)), parts)
    }

    @Test
    fun `tool result without id attaches FIFO`() {
        val parts = reduceAll(
            listOf(
                GatewayEvent.ToolUses(
                    "s",
                    listOf(
                        Part.Tool(id = "a", name = "Bash", input = null),
                        Part.Tool(id = "b", name = "Bash", input = null),
                    ),
                ),
                GatewayEvent.ToolResult("s", null, "first", false),
            ),
        )
        assertEquals("first", (parts[0] as Part.Tool).output)
        assertEquals(null, (parts[1] as Part.Tool).output)
    }

    @Test
    fun `snapshot replaces everything`() {
        val snapshotParts = listOf(Part.Text("replayed"))
        val parts = reduceAll(
            listOf(
                GatewayEvent.TextDelta("s", "stale"),
                GatewayEvent.Snapshot("s", snapshotParts, busy = true, pending = emptyList(), client = ""),
            ),
        )
        assertEquals(snapshotParts, parts)
    }

    @Test
    fun `file delivery appends a file part`() {
        val parts = reduceAll(
            listOf(
                GatewayEvent.TextDelta("s", "here you go"),
                GatewayEvent.FileDelivered("s", Part.File(name = "x.png", caption = "c", url = "/api/files/t")),
            ),
        )
        assertEquals(2, parts.size)
        assertEquals("x.png", (parts[1] as Part.File).name)
    }

    @Test
    fun `card and lifecycle events leave parts untouched`() {
        val before = listOf<Part>(Part.Text("hi"))
        val after = listOf(
            GatewayEvent.PermissionRequest("s", "p1", "Write", null),
            GatewayEvent.RequestResolved("s", "p1"),
            GatewayEvent.Stderr("s", "warning"),
            GatewayEvent.TurnEnd("s", "title"),
        ).fold(before) { acc, e -> TurnReducer.reduce(acc, e) }
        assertEquals(before, after)
    }
}
