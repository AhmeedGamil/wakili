package com.ahmedgamil.wakili.core.model

/**
 * Agent manifest from GET /api/agents. The controls map is rendered generically
 * (a dropdown per control) — new agents/controls on the server need no client change.
 */
data class AgentManifest(
    val id: String,
    val label: String,
    val description: String?,
    val controls: Map<String, AgentControl>,
    val commands: List<AgentCommand>,
)

data class AgentControl(
    val label: String,
    val default: String?,
    val options: List<AgentControlOption>,
)

data class AgentControlOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

data class AgentCommand(
    val name: String,
    val desc: String?,
)
