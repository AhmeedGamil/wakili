package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Agent manifests (controls/commands), fetched once per app run. */
@Singleton
class AgentRepository @Inject constructor(
    private val api: GatewayApi,
) {
    private var cache: List<AgentManifest>? = null
    private val mutex = Mutex()

    suspend fun agents(refresh: Boolean = false): List<AgentManifest> = mutex.withLock {
        if (refresh) cache = null
        cache ?: api.agents().map { it.toModel() }.also { cache = it }
    }

    suspend fun agent(id: String): AgentManifest? = agents().firstOrNull { it.id == id }
}
