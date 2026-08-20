package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.model.SessionSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Creates a blank chat and pins the exact controls it inherited before navigation. */
@Singleton
class NewSessionFactory @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val agentRepository: AgentRepository,
    private val controlPersistence: AgentControlPersistence,
) {
    suspend fun create(
        cwd: String?,
        knownAgents: List<AgentManifest> = emptyList(),
        // The home page's selected agent tab. When set it wins over the last-used
        // agent, so a chat is always born under the tab you started it from.
        agentIdOverride: String? = null,
    ): SessionSummary {
        val inherited = controlPersistence.snapshot()
        val agentId = agentIdOverride?.takeIf { it.isNotBlank() } ?: inherited?.agentId ?: "claude"
        val agent = knownAgents.firstOrNull { it.id == agentId }
            ?: runCatching { agentRepository.agent(agentId) }.getOrNull()
        val controls = if (agent == null) {
            inherited?.controls.orEmpty()
        } else {
            resolveAgentControls(
                agent = agent,
                lastConfig = inherited,
                permissionMode = controlPersistence.permissionMode(),
            )
        }
        val created = sessionRepository.create(agentId = agentId, cwd = cwd)
        runCatching {
            controlPersistence.pinSession(created.id, agentId, controls)
        }.onFailure {
            // Keep the chat usable; the app-lifetime writer retries after this
            // creation coroutine returns and survives the navigation itself.
            controlPersistence.persistSession(created.id, agentId, controls)
        }
        return created
    }
}
