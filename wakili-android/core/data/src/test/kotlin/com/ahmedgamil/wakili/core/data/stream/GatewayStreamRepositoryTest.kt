package com.ahmedgamil.wakili.core.data.stream

import android.content.Context
import com.ahmedgamil.wakili.core.common.AppForeground
import com.ahmedgamil.wakili.core.common.Toaster
import com.ahmedgamil.wakili.core.data.repository.SessionRepository
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.dto.OkDto
import com.ahmedgamil.wakili.core.network.stream.EventStreamClient
import com.ahmedgamil.wakili.core.network.stream.EventStreamClientFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayStreamRepositoryTest {

    @Test
    fun `opening before stream connection waits to request resync`() = runTest {
        val fixture = fixture()

        fixture.connection.update(PROFILE)
        runCurrent()
        fixture.repository.openSession(SESSION_ID)
        runCurrent()

        coVerify(exactly = 0) { fixture.api.resync(any(), any()) }

        fixture.source.emit(GatewayEvent.Connected())
        runCurrent()

        coVerify(exactly = 1) { fixture.api.resync(SESSION_ID, any()) }
    }

    @Test
    fun `snapshot older than applied state cannot rewind the live turn`() = runTest {
        val fixture = fixture(resyncNeverReturns = true)
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()

        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID, seq = 10))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "one", seq = 11))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, " two", seq = 12))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, " three", seq = 13))
        runCurrent()
        assertEquals(listOf(Part.Text("one two three")), fixture.repository.liveParts.value)

        // Two resyncs answered in the opposite order: the SECOND request's
        // snapshot (seq 14) lands first, then the FIRST request's older copy
        // (seq 11) arrives late. Adopting the late one is what used to leave a
        // hole in the middle of the answer.
        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = listOf(Part.Text("one two three")),
                busy = true,
                pending = emptyList(),
                client = "",
                seq = 14,
            ),
        )
        runCurrent()
        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = listOf(Part.Text("one")),
                busy = true,
                pending = emptyList(),
                client = "",
                seq = 11,
            ),
        )
        runCurrent()

        assertEquals(listOf(Part.Text("one two three")), fixture.repository.liveParts.value)

        // A delta after the stale snapshot appends to the un-rewound tail.
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, " four", seq = 15))
        runCurrent()
        assertEquals(listOf(Part.Text("one two three four")), fixture.repository.liveParts.value)
    }

    @Test
    fun `a stale snapshot still releases the gate so an HTTP seed is not blocked`() = runTest {
        val fixture = fixture(resyncNeverReturns = true)
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()

        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID, seq = 20))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "live", seq = 21))
        // Ordered snapshot, so the gate's expected client/epoch is satisfied.
        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = listOf(Part.Text("live")),
                busy = true,
                pending = emptyList(),
                client = "",
                seq = 22,
            ),
        )
        runCurrent()

        // Late/older snapshot: refused as content, but must not leave the turn
        // stalled behind a gate that never opens.
        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = emptyList(),
                busy = false,
                pending = emptyList(),
                client = "",
                seq = 21,
            ),
        )
        runCurrent()

        assertEquals(listOf(Part.Text("live")), fixture.repository.liveParts.value)
        assertTrue(fixture.repository.activeBusy.value) // stale busy=false refused with its parts

        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, " on", seq = 23))
        runCurrent()
        assertEquals(listOf(Part.Text("live on")), fixture.repository.liveParts.value)
    }

    @Test
    fun `ordered snapshot replaces pre-snapshot deltas and later deltas append`() = runTest {
        val fixture = fixture(resyncNeverReturns = true)
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()

        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID, clientMessageId = "client-7"))
        runCurrent()
        assertTrue(fixture.repository.activeBusy.value)

        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "before snapshot"))
        runCurrent()
        assertEquals(listOf(Part.Text("before snapshot")), fixture.repository.liveParts.value)

        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = listOf(Part.Text("snapshotted")),
                busy = true,
                pending = emptyList(),
                client = "",
            ),
        )
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, " + live"))
        runCurrent()
        assertEquals(listOf(Part.Text("snapshotted + live")), fixture.repository.liveParts.value)
        assertEquals("snapshotted".length, fixture.repository.liveTailRevealedChars.value)

        fixture.source.emit(GatewayEvent.TurnEnd(SESSION_ID, title = null))
        runCurrent()
        assertFalse(fixture.repository.activeBusy.value)
    }

    @Test
    fun `switching away keeps a running turn buffered and restores it immediately`() = runTest {
        val fixture = fixture(resyncNeverReturns = true)
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = emptyList(),
                busy = false,
                pending = emptyList(),
                client = "",
            ),
        )
        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID, clientMessageId = "client-7"))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "working"))
        runCurrent()

        fixture.repository.openSession("session-2")
        runCurrent()
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, " hard"))
        fixture.source.emit(
            GatewayEvent.PermissionRequest(
                sessionId = SESSION_ID,
                id = "permission-1",
                tool = "Write",
                input = null,
            ),
        )
        runCurrent()
        assertTrue(fixture.repository.liveParts.value.isEmpty())

        fixture.repository.openSession(SESSION_ID)

        assertTrue(fixture.repository.activeBusy.value)
        assertEquals("client-7", fixture.repository.activeClientMessageId.value)
        assertEquals(listOf(Part.Text("working hard")), fixture.repository.liveParts.value)
        assertEquals("working hard".length, fixture.repository.liveTailRevealedChars.value)
        assertEquals(listOf("permission-1"), fixture.repository.cards.value.map { it.id })
    }

    @Test
    fun `completed inactive turn is folded into the cache, not resurrected beside history`() = runTest {
        val fixture = fixture(resyncNeverReturns = true)
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "completed elsewhere"))
        runCurrent()

        fixture.repository.openSession("session-2")
        fixture.source.emit(GatewayEvent.TurnEnd(SESSION_ID, title = null))
        runCurrent()
        fixture.repository.openSession(SESSION_ID)

        // The streamed parts were persisted locally before the buffer retired.
        coVerify(exactly = 1) {
            fixture.sessions.appendProvisionalTurn(SESSION_ID, listOf(Part.Text("completed elsewhere")))
        }
        assertFalse(fixture.repository.activeBusy.value)
        assertTrue(fixture.repository.liveParts.value.isEmpty())
    }

    @Test
    fun `unfoldable completed inactive turn stays buffered instead of vanishing`() = runTest {
        // No cached transcript to anchor the fold (e.g. never opened on this
        // device): the buffer must survive so reopening can still show the turn.
        val fixture = fixture(resyncNeverReturns = true, foldSucceeds = false)
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "completed elsewhere"))
        runCurrent()

        fixture.repository.openSession("session-2")
        fixture.source.emit(GatewayEvent.TurnEnd(SESSION_ID, title = null))
        runCurrent()
        fixture.repository.openSession(SESSION_ID)

        assertFalse(fixture.repository.activeBusy.value)
        assertEquals(listOf(Part.Text("completed elsewhere")), fixture.repository.liveParts.value)
    }

    @Test
    fun `closing a chat with an unreconciled completed turn folds it into the cache`() = runTest {
        val fixture = fixture(resyncNeverReturns = true)
        connect(fixture)
        val token = fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "ended on screen"))
        fixture.source.emit(GatewayEvent.TurnEnd(SESSION_ID, title = null))
        runCurrent()

        fixture.repository.closeSession(SESSION_ID, token)
        runCurrent()

        coVerify(exactly = 1) {
            fixture.sessions.appendProvisionalTurn(SESSION_ID, listOf(Part.Text("ended on screen")))
        }
    }

    @Test
    fun `resync retries are bounded and a session switch cancels the old epoch`() = runTest {
        val fixture = fixture(resyncFailure = IOException("offline"))
        connect(fixture)
        fixture.repository.openSession("session-old")
        runCurrent()

        fixture.repository.openSession(SESSION_ID)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        coVerify(exactly = 1) { fixture.api.resync("session-old", any()) }
        coVerify(exactly = 3) { fixture.api.resync(SESSION_ID, any()) }
    }

    @Test
    fun `stale close from the previous chat keeps the new chat live`() = runTest {
        val fixture = fixture()
        connect(fixture)

        // Chat→chat switch: the NEW screen's openSession runs BEFORE the old
        // ViewModel is cleared — its close must not blank the new active id.
        val stale = fixture.repository.openSession("session-old")
        runCurrent()
        fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.repository.closeSession("session-old", stale)
        runCurrent()

        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = emptyList(),
                busy = false,
                pending = emptyList(),
                client = "",
            ),
        )
        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "still streaming"))
        runCurrent()

        assertTrue(fixture.repository.activeBusy.value)
        assertEquals(listOf(Part.Text("still streaming")), fixture.repository.liveParts.value)
        assertEquals(0, fixture.repository.liveTailRevealedChars.value)
    }

    @Test
    fun `stale close from a rebuilt screen keeps the same chat answerable`() = runTest {
        val fixture = fixture()
        connect(fixture)

        // A notification tap re-enters the chat ALREADY on screen: navigation
        // rebuilds the screen for the same id, so the new open runs before the
        // popped twin's close. Same id, so only the token tells them apart.
        val stale = fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID))
        fixture.source.emit(
            GatewayEvent.QuestionRequest(SESSION_ID, id = "card-1", questions = emptyList()),
        )
        runCurrent()

        fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.repository.closeSession(SESSION_ID, stale)
        runCurrent()

        // The card the notification was about is still on screen...
        assertEquals(listOf("card-1"), fixture.repository.cards.value.map { it.id })
        // ...answering it still takes it down...
        fixture.repository.dismissCard("card-1")
        assertTrue(fixture.repository.cards.value.isEmpty())

        // ...and the chat still receives its own live events.
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "still streaming"))
        runCurrent()
        assertTrue(fixture.repository.activeBusy.value)
        assertEquals(listOf(Part.Text("still streaming")), fixture.repository.liveParts.value)
    }

    @Test
    fun `failed resync releases the content gate immediately`() = runTest {
        val fixture = fixture(resyncFailure = IOException("offline"))
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()

        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "not blocked"))
        runCurrent()

        assertEquals(listOf(Part.Text("not blocked")), fixture.repository.liveParts.value)
    }

    @Test
    fun `completed turn token cannot clear a newer live turn`() = runTest {
        val fixture = fixture()
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()
        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = emptyList(),
                busy = false,
                pending = emptyList(),
                client = "",
            ),
        )
        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "first"))
        fixture.source.emit(GatewayEvent.TurnEnd(SESSION_ID, title = null))
        runCurrent()

        val completed = requireNotNull(fixture.repository.completedTurnGeneration(SESSION_ID))

        fixture.source.emit(GatewayEvent.TurnStart(SESSION_ID))
        fixture.source.emit(GatewayEvent.TextDelta(SESSION_ID, "second"))
        runCurrent()

        assertFalse(fixture.repository.clearLiveTurn(SESSION_ID, completed))
        assertEquals(listOf(Part.Text("second")), fixture.repository.liveParts.value)
    }

    @Test
    fun `foreign snapshot cannot replace active state`() = runTest {
        val fixture = fixture()
        connect(fixture)
        fixture.repository.openSession(SESSION_ID)
        runCurrent()

        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = listOf(Part.Text("ours")),
                busy = true,
                pending = emptyList(),
                client = "",
            ),
        )
        runCurrent()
        fixture.source.emit(
            GatewayEvent.Snapshot(
                sessionId = SESSION_ID,
                parts = listOf(Part.Text("foreign")),
                busy = false,
                pending = emptyList(),
                client = "another-client",
            ),
        )
        runCurrent()

        assertTrue(fixture.repository.activeBusy.value)
        assertEquals(listOf(Part.Text("ours")), fixture.repository.liveParts.value)
    }

    private fun TestScope.fixture(
        resyncNeverReturns: Boolean = false,
        resyncFailure: Throwable? = null,
        foldSucceeds: Boolean = true,
    ): Fixture {
        val source = MutableSharedFlow<GatewayEvent>()
        val connection = GatewayConnection()
        val api = mockk<GatewayApi>(relaxed = true)
        coEvery { api.resync(any(), any()) } coAnswers {
            when {
                resyncNeverReturns -> awaitCancellation()
                resyncFailure != null -> throw resyncFailure
                else -> OkDto(ok = true)
            }
        }
        val sessions = mockk<SessionRepository>(relaxed = true)
        coEvery { sessions.appendProvisionalTurn(any(), any()) } returns foldSucceeds

        val stream = object : EventStreamClient {
            override fun events(profile: ConnectionProfile): Flow<GatewayEvent> = source
        }
        val foreground = AppForeground().apply { foreground = true }
        val repository = GatewayStreamRepository(
            factory = EventStreamClientFactory { stream },
            connection = connection,
            api = api,
            sessions = sessions,
            toaster = Toaster(),
            notifier = mockk(relaxed = true),
            questionOverlay = mockk(relaxed = true),
            appForeground = foreground,
            settingsStore = mockk(relaxed = true),
            // A real recorder: it stays disabled unless a Connected(debug=true)
            // turns it on, so it is a no-op here rather than another mock.
            recorder = StreamRecorder(api, backgroundScope),
            appContext = mockk<Context>(relaxed = true),
            scope = backgroundScope,
        )

        return Fixture(source, connection, api, sessions, repository)
    }

    private suspend fun TestScope.connect(fixture: Fixture) {
        fixture.connection.update(PROFILE)
        runCurrent()
        fixture.source.emit(GatewayEvent.Connected())
        runCurrent()
    }

    private data class Fixture(
        val source: MutableSharedFlow<GatewayEvent>,
        val connection: GatewayConnection,
        val api: GatewayApi,
        val sessions: SessionRepository,
        val repository: GatewayStreamRepository,
    )

    private companion object {
        const val SESSION_ID = "session-1"
        val PROFILE = ConnectionProfile("https://wakili.example", "token")
    }
}
