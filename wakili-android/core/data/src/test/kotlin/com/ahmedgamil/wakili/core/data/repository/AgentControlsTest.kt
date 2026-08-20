package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.AgentControl
import com.ahmedgamil.wakili.core.model.AgentControlOption
import com.ahmedgamil.wakili.core.model.AgentManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentControlsTest {

    @Test
    fun `new chat resolves a complete normalized snapshot before pinning`() {
        val resolved = resolveAgentControls(
            agent = codexAgent(),
            lastConfig = AgentConfigSnapshot(
                agentId = "codex",
                controls = mapOf("model" to "gpt-large", "effort" to "high"),
            ),
            permissionMode = "plan",
        )

        assertEquals(
            mapOf(
                "model" to "gpt-large",
                "effort" to "high",
                "approval" to "workspace-write",
                "permissionMode" to "plan",
            ),
            resolved,
        )
    }

    @Test
    fun `session controls win and invalid model-scoped effort heals`() {
        val resolved = resolveAgentControls(
            agent = codexAgent(),
            lastConfig = AgentConfigSnapshot(
                agentId = "codex",
                controls = mapOf("model" to "gpt-large", "effort" to "ultra"),
            ),
            permissionMode = "default",
            sessionControls = mapOf("model" to "gpt-small", "effort" to "ultra"),
        )

        assertEquals("gpt-small", resolved["model"])
        assertEquals("medium", resolved["effort"])
    }

    @Test
    fun `stored controls round trip`() {
        val original = mapOf("model" to "gpt-large", "effort" to "high")
        val decoded = decodeAgentConfig("codex" to encodeAgentControls(original))

        assertEquals(AgentConfigSnapshot("codex", original), decoded)
    }

    private fun codexAgent() = AgentManifest(
        id = "codex",
        label = "Codex",
        description = null,
        controls = linkedMapOf(
            "model" to control(
                default = "gpt-small",
                options = listOf("gpt-small", "gpt-large"),
            ),
            "effort" to control(
                default = "medium",
                options = listOf("low", "medium", "high", "ultra"),
                optionsFor = mapOf(
                    "gpt-small" to listOf("low", "medium", "high").map(::option),
                    "gpt-large" to listOf("low", "medium", "high", "ultra").map(::option),
                ),
            ),
            "approval" to control(
                default = "workspace-write",
                options = listOf("read-only", "workspace-write"),
            ),
            "permissionMode" to control(
                default = "default",
                options = listOf("default", "plan"),
            ),
        ),
        commands = emptyList(),
    )

    private fun control(
        default: String,
        options: List<String>,
        optionsFor: Map<String, List<AgentControlOption>> = emptyMap(),
    ) = AgentControl(
        label = "",
        default = default,
        options = options.map(::option),
        optionsFor = optionsFor,
    )

    private fun option(value: String) = AgentControlOption(value, value)
}
