package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.AgentControl
import com.ahmedgamil.wakili.core.model.AgentControlOption
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.model.SessionSummary
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NewSessionFactoryTest {

    @Test
    fun `created chat is pinned to inherited effort before it is returned`() = runTest {
        val sessions = mockk<SessionRepository>()
        val agents = mockk<AgentRepository>()
        val persistence = mockk<AgentControlPersistence>(relaxed = true)
        val inherited = AgentConfigSnapshot(
            "codex",
            mapOf("model" to "gpt-large", "effort" to "high"),
        )
        val created = session("new-session")
        coEvery { persistence.snapshot() } returns inherited
        coEvery { persistence.permissionMode() } returns "default"
        coEvery { sessions.create("codex", "D:/work") } returns created
        coEvery { persistence.pinSession(any(), any(), any()) } returns Unit
        val factory = NewSessionFactory(sessions, agents, persistence)

        val result = factory.create("D:/work", listOf(codexAgent()))

        assertEquals(created, result)
        coVerifyOrder {
            sessions.create("codex", "D:/work")
            persistence.pinSession(
                "new-session",
                "codex",
                match { controls ->
                    controls["model"] == "gpt-large" &&
                        controls["effort"] == "high" &&
                        controls["approval"] == "workspace-write"
                },
            )
        }
    }

    private fun codexAgent() = AgentManifest(
        id = "codex",
        label = "Codex",
        description = null,
        controls = linkedMapOf(
            "model" to control("gpt-small", "gpt-small", "gpt-large"),
            "effort" to control("medium", "low", "medium", "high"),
            "approval" to control("workspace-write", "read-only", "workspace-write"),
        ),
        commands = emptyList(),
    )

    private fun control(default: String, vararg values: String) = AgentControl(
        label = "",
        default = default,
        options = values.map { AgentControlOption(it, it) },
    )

    private fun session(id: String) = SessionSummary(
        id = id,
        title = "New chat",
        agentId = "codex",
        model = null,
        cwd = "D:/work",
        effectiveCwd = "D:/work",
        updatedAt = 1L,
        busy = false,
        pending = 0,
    )
}
