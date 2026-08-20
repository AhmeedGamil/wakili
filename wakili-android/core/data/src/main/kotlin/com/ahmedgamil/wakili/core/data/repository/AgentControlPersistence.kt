package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.data.di.AppScope
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App-lifetime, ordered persistence for picker changes. Unlike a ViewModel job,
 * these writes survive a fast chat switch; FIFO processing guarantees the last
 * selection wins even when an earlier PATCH is slow.
 */
@Singleton
class AgentControlPersistence @Inject constructor(
    private val settingsStore: SettingsStore,
    private val sessionRepository: SessionRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private data class GlobalUpdate(
        val config: AgentConfigSnapshot,
        val permissionMode: String?,
    )

    private data class SessionUpdate(
        val sessionId: String,
        val config: AgentConfigSnapshot,
        val completion: CompletableDeferred<Result<Unit>>? = null,
    )

    private val globalUpdates = Channel<GlobalUpdate>(Channel.UNLIMITED)
    private val sessionUpdates = ConcurrentHashMap<String, Channel<SessionUpdate>>()
    private val latestLock = Any()

    @Volatile
    private var latestConfig: AgentConfigSnapshot? = null

    @Volatile
    private var latestPermissionMode: String? = null

    init {
        scope.launch {
            for (update in globalUpdates) {
                runCatching {
                    settingsStore.setLastConfig(
                        update.config.agentId,
                        encodeAgentControls(update.config.controls),
                    )
                }
                update.permissionMode?.let { mode ->
                    runCatching { settingsStore.setPermMode(mode) }
                }
            }
        }
    }

    /** Synchronous in-memory update plus durable app-scope persistence. */
    fun remember(
        agentId: String,
        controls: Map<String, String>,
        permissionMode: String? = null,
    ) {
        val config = AgentConfigSnapshot(agentId, controls.toMap())
        latestConfig = config
        if (permissionMode != null) latestPermissionMode = permissionMode
        globalUpdates.trySend(GlobalUpdate(config, permissionMode))
    }

    /** Persist this exact snapshot to both last-used defaults and the session. */
    fun persistSession(
        sessionId: String,
        agentId: String,
        controls: Map<String, String>,
        permissionMode: String? = null,
    ) {
        val config = AgentConfigSnapshot(agentId, controls.toMap())
        remember(agentId, config.controls, permissionMode)
        updatesFor(sessionId).trySend(SessionUpdate(sessionId, config))
    }

    /** Creation-time pin: do not expose the new chat until its config is saved. */
    suspend fun pinSession(
        sessionId: String,
        agentId: String,
        controls: Map<String, String>,
    ) {
        val config = AgentConfigSnapshot(agentId, controls.toMap())
        remember(agentId, config.controls)
        val completion = CompletableDeferred<Result<Unit>>()
        updatesFor(sessionId).send(SessionUpdate(sessionId, config, completion))
        completion.await().getOrThrow()
    }

    suspend fun snapshot(): AgentConfigSnapshot? {
        latestConfig?.let { return it }
        val stored = decodeAgentConfig(settingsStore.lastConfig.first())
        return synchronized(latestLock) {
            if (latestConfig == null) latestConfig = stored
            latestConfig
        }
    }

    suspend fun permissionMode(): String? {
        latestPermissionMode?.let { return it }
        val stored = settingsStore.permMode.first()
        return synchronized(latestLock) {
            if (latestPermissionMode == null) latestPermissionMode = stored
            latestPermissionMode
        }
    }

    private fun updatesFor(sessionId: String): Channel<SessionUpdate> =
        sessionUpdates.computeIfAbsent(sessionId) {
            Channel<SessionUpdate>(Channel.UNLIMITED).also { updates ->
                scope.launch {
                    for (update in updates) {
                        val result = runCatching {
                            sessionRepository.saveControls(
                                update.sessionId,
                                update.config.agentId,
                                update.config.controls,
                            )
                        }
                        update.completion?.complete(result)
                    }
                }
            }
        }
}
