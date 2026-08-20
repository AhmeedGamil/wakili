package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.ChatMessage
import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.dto.MessageDto
import com.ahmedgamil.wakili.core.network.dto.OkDto
import com.ahmedgamil.wakili.core.network.dto.PartDto
import com.ahmedgamil.wakili.core.network.dto.SendMessageBody
import com.ahmedgamil.wakili.core.network.dto.SessionDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SessionRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unchanged response merges live state without replacing cached detail`() = runTest {
        val api = mockk<GatewayApi>()
        val cache = mockk<SessionCache>(relaxed = true)
        val connection = GatewayConnection().apply { update(PROFILE) }
        val cached = SessionDto(
            id = SESSION_ID,
            title = "Cached title",
            agentId = "codex",
            model = "cached-model",
            cwd = "D:/work",
            effectiveCwd = "D:/work",
            updatedAt = 1234L,
            messages = listOf(
                MessageDto(role = "user", text = "question"),
                MessageDto(role = "assistant", text = "answer"),
            ),
            controls = emptyMap(),
            allowedTools = listOf("Read"),
            autoAllow = true,
        )
        val response = SessionDto(
            unchanged = true,
            updatedAt = 1234L,
            busy = true,
            pending = 2,
            parts = listOf(PartDto(type = "text", text = "still running")),
            activeClientMessageId = "client-7",
        )
        val writtenDetail = slot<String>()

        coEvery { cache.readDetail(PROFILE.token, SESSION_ID) } returns
            json.encodeToString(SessionDto.serializer(), cached)
        coEvery { cache.readSessions(PROFILE.token) } returns null
        coEvery { api.session(SESSION_ID, since = 1234L, after = 2) } returns response
        coEvery { cache.writeDetail(PROFILE.token, SESSION_ID, capture(writtenDetail)) } returns Unit

        val repository = SessionRepository(
            api = api,
            cache = cache,
            connection = connection,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val detail = repository.refreshDetail(SESSION_ID)

        assertEquals(SESSION_ID, detail.summary.id)
        assertEquals("Cached title", detail.summary.title)
        assertEquals("codex", detail.summary.agentId)
        assertEquals("cached-model", detail.summary.model)
        assertTrue(detail.summary.busy)
        assertEquals(2, detail.summary.pending)
        assertEquals(listOf(Part.Text("still running")), detail.liveParts)
        assertEquals("client-7", detail.liveClientMessageId)
        assertEquals(
            listOf(
                ChatMessage.User("question"),
                ChatMessage.Assistant(listOf(Part.Text("answer"))),
            ),
            detail.messages,
        )
        assertEquals(listOf("Read"), detail.allowedTools)
        assertTrue(detail.autoAllow)

        val persisted = json.decodeFromString(SessionDto.serializer(), writtenDetail.captured)
        assertEquals(SESSION_ID, persisted.id)
        assertEquals("Cached title", persisted.title)
        assertEquals(2, persisted.messages?.size)
        assertTrue(persisted.busy)
        assertEquals(2, persisted.pending)
        assertEquals("still running", persisted.parts?.single()?.text)
        assertEquals("client-7", persisted.activeClientMessageId)
        assertFalse(persisted.unchanged)
        assertFalse(persisted.delta)
        coVerify(exactly = 1) { api.session(SESSION_ID, since = 1234L, after = 2) }
    }

    @Test
    fun `provisional fold persists the turn and the next delta fetch replaces it`() = runTest {
        val api = mockk<GatewayApi>()
        val cache = mockk<SessionCache>(relaxed = true)
        val connection = GatewayConnection().apply { update(PROFILE) }
        val cached = SessionDto(
            id = SESSION_ID,
            title = "T",
            updatedAt = 1000L,
            messages = listOf(MessageDto(role = "user", text = "question")),
            parts = listOf(PartDto(type = "text", text = "half-streamed")),
            busy = true,
        )
        val store = mutableMapOf(SESSION_ID to json.encodeToString(SessionDto.serializer(), cached))
        coEvery { cache.readDetail(PROFILE.token, SESSION_ID) } answers { store[SESSION_ID] }
        coEvery { cache.readSessions(PROFILE.token) } returns null
        coEvery { cache.writeDetail(PROFILE.token, SESSION_ID, any()) } answers {
            store[SESSION_ID] = thirdArg()
        }
        val repository = SessionRepository(
            api = api,
            cache = cache,
            connection = connection,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(
            repository.appendProvisionalTurn(SESSION_ID, listOf(Part.Text("full reply"))),
        )
        val folded = json.decodeFromString(SessionDto.serializer(), store.getValue(SESSION_ID))
        assertEquals(2, folded.messages?.size)
        assertEquals(true, folded.messages?.last()?.provisional)
        assertEquals("full reply", folded.messages?.last()?.parts?.single()?.text)
        // The retired turn's live copy must not resurrect beside the fold.
        assertEquals(null, folded.parts)
        assertFalse(folded.busy)

        // The delta cursor anchors on the CONFIRMED prefix (1 message), and the
        // server's copy of the turn replaces the provisional one.
        coEvery { api.session(SESSION_ID, since = 1000L, after = 1) } returns SessionDto(
            id = SESSION_ID,
            title = "T",
            updatedAt = 2000L,
            delta = true,
            after = 1,
            messages = listOf(
                MessageDto(role = "assistant", parts = listOf(PartDto(type = "text", text = "full reply (server)"))),
            ),
        )
        val detail = repository.refreshDetail(SESSION_ID)
        assertEquals(
            listOf(
                ChatMessage.User("question"),
                ChatMessage.Assistant(listOf(Part.Text("full reply (server)"))),
            ),
            detail.messages,
        )
    }

    @Test
    fun `in-flight refresh does not erase a send folded meanwhile`() = runTest {
        val api = mockk<GatewayApi>()
        val cache = mockk<SessionCache>(relaxed = true)
        val connection = GatewayConnection().apply { update(PROFILE) }
        val before = SessionDto(
            id = SESSION_ID,
            title = "T",
            updatedAt = 1000L,
            messages = listOf(
                MessageDto(role = "user", text = "question"),
                MessageDto(role = "assistant", parts = listOf(PartDto(type = "text", text = "answer"))),
            ),
        )
        // While the fetch was on the wire, a send landed in the cache.
        val after = before.copy(
            messages = before.messages.orEmpty() +
                MessageDto(role = "user", text = "follow-up", clientMessageId = "c9", provisional = true),
            busy = true,
        )
        // First read anchors the request cursors; the locked re-read must see
        // the folded copy.
        coEvery { cache.readDetail(PROFILE.token, SESSION_ID) } returnsMany listOf(
            json.encodeToString(SessionDto.serializer(), before),
            json.encodeToString(SessionDto.serializer(), after),
        )
        coEvery { cache.readSessions(PROFILE.token) } returns null
        // The server snapshot predates the send: nothing changed from its view.
        coEvery { api.session(SESSION_ID, since = 1000L, after = 2) } returns SessionDto(
            unchanged = true,
            updatedAt = 1000L,
            busy = true,
        )
        val repository = SessionRepository(
            api = api,
            cache = cache,
            connection = connection,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val detail = repository.refreshDetail(SESSION_ID)

        assertEquals(3, detail.messages.size)
        assertEquals(ChatMessage.User("follow-up", clientMessageId = "c9"), detail.messages.last())
    }

    @Test
    fun `send forwards stable id and reports an already accepted retry`() = runTest {
        val api = mockk<GatewayApi>()
        val body = slot<SendMessageBody>()
        coEvery { api.sendMessage(SESSION_ID, capture(body)) } returns
            Response.success(202, OkDto(ok = true, duplicate = true))
        val repository = SessionRepository(
            api = api,
            cache = mockk(relaxed = true),
            connection = GatewayConnection().apply { update(PROFILE) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.send(
            id = SESSION_ID,
            text = "question",
            controls = mapOf("model" to "gpt-large", "effort" to "high"),
            clientMessageId = "client-7",
        )

        assertEquals(SendResult.Accepted(alreadyAccepted = true), result)
        assertEquals("client-7", body.captured.clientMessageId)
        assertEquals("high", body.captured.controls?.get("effort"))
    }

    private companion object {
        const val SESSION_ID = "session-1"
        val PROFILE = ConnectionProfile("https://wakili.example", "token")
    }
}
