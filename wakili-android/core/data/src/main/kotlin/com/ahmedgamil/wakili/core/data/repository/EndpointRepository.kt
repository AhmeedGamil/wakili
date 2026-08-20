package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.GatewayEndpoint
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.toModel
import javax.inject.Inject
import javax.inject.Singleton

/** The gateway's reachable endpoints (LAN / Tailscale / Cloudflare). */
@Singleton
class EndpointRepository @Inject constructor(
    private val api: GatewayApi,
) {
    suspend fun endpoints(): List<GatewayEndpoint> = api.endpoints().map { it.toModel() }
}
