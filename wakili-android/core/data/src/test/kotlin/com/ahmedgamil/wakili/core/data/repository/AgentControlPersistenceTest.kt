package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.datastore.SettingsStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentControlPersistenceTest {

    @Test
    fun `rapid effort changes persist in selection order and latest snapshot is immediate`() = runTest {
        val settingsStore = mockk<SettingsStore>()
        val sessionRepository = mockk<SessionRepository>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        every { settingsStore.lastConfig } returns flowOf(null)
        every { settingsStore.permMode } returns flowOf(null)
        coEvery { settingsStore.setLastConfig(any(), any()) } returns Unit
        coEvery { settingsStore.setPermMode(any()) } returns Unit
        coEvery {
            sessionRepository.saveControls("session", "codex", match { it["effort"] == "low" })
        } coAnswers {
            calls += "low"
            releaseFirst.await()
        }
        coEvery {
            sessionRepository.saveControls("session", "codex", match { it["effort"] == "high" })
        } coAnswers {
            calls += "high"
        }
        val persistence = AgentControlPersistence(
            settingsStore = settingsStore,
            sessionRepository = sessionRepository,
            scope = backgroundScope,
        )

        persistence.persistSession("session", "codex", mapOf("effort" to "low"))
        persistence.persistSession("session", "codex", mapOf("effort" to "high"))
        runCurrent()

        assertEquals(listOf("low"), calls)
        assertEquals("high", persistence.snapshot()?.controls?.get("effort"))

        releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(listOf("low", "high"), calls)
    }
}
