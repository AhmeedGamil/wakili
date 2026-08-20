package com.ahmedgamil.wakili.core.data.stream

import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.network.dto.SessionDto
import com.ahmedgamil.wakili.core.network.dto.toModel
import com.ahmedgamil.wakili.core.network.stream.GatewayEventParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden test: replays a REAL transcript recorded from the live gateway
 * (a Claude haiku turn that reads package.json via the Read tool) through
 * GatewayEventParser + TurnReducer, and asserts the assembled parts equal the
 * parts the server itself persisted for that turn. If this passes, the whole
 * client pipeline — wire parsing to turn assembly — matches the server.
 *
 * Fixtures: fixtures/golden-turn-stream.jsonl (every `data:` payload from
 * /api/stream during the turn) + fixtures/golden-turn-session.json
 * (GET /api/sessions/:id afterwards — the ground truth).
 */
class GoldenTurnTest {

    private val parser = GatewayEventParser()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `replayed stream reproduces the persisted turn exactly`() {
        val session = json.decodeFromString<SessionDto>(resource("fixtures/golden-turn-session.json"))
        val expected = session.messages.orEmpty()
            .last { it.role == "assistant" }
            .parts.orEmpty()
            .mapNotNull { it.toModel() }
        assertTrue("fixture turn must contain parts", expected.isNotEmpty())

        val events = resource("fixtures/golden-turn-stream.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parser.parse(it) }
            .filterIsInstance<GatewayEvent.SessionEvent>()
            .filter { it.sessionId == session.id }
            .toList()
        assertTrue("stream must contain session events", events.isNotEmpty())

        val assembled = events.fold(emptyList<Part>()) { acc, e -> TurnReducer.reduce(acc, e) }

        assertEquals(expected, assembled)
    }

    @Test
    fun `stream contains the expected event kinds`() {
        val kinds = resource("fixtures/golden-turn-stream.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parser.parse(it) }
            .map { it::class.simpleName }
            .toSet()

        // A real turn exercises the core protocol: lifecycle, deltas, tools, results.
        assertTrue("TurnStart" in kinds)
        assertTrue("TurnEnd" in kinds)
        assertTrue("TextDelta" in kinds)
        assertTrue("ToolUses" in kinds)
        assertTrue("ToolResult" in kinds)
    }
}
