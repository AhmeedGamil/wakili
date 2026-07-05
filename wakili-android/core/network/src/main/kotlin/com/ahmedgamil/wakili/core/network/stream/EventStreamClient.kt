package com.ahmedgamil.wakili.core.network.stream

import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayEvent
import kotlinx.coroutines.flow.Flow

/**
 * One live event stream to the gateway. Implementations differ only in
 * transport (SSE vs the Cloudflare WebSocket bridge); both deliver the same
 * JSON payloads and reconnect forever with backoff until the collector cancels.
 */
interface EventStreamClient {
    fun events(profile: ConnectionProfile): Flow<GatewayEvent>
}

/** Picks the transport for a profile — Cloudflare buffers SSE, so it gets WS. */
fun interface EventStreamClientFactory {
    fun forProfile(profile: ConnectionProfile): EventStreamClient
}
