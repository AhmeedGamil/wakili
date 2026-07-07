package com.ahmedgamil.wakili.core.network.stream

import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayEvent
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Cloudflare transport: Cloudflare buffers SSE, so cf-bridge.mjs exposes the
 * same stream over a WebSocket at /cf-ws?path=<encoded SSE path>, one JSON
 * payload per text frame — exactly what public/cf-shim.js consumes.
 */
@Singleton
class CfWebSocketStreamClient @Inject constructor(
    private val client: OkHttpClient,
    private val parser: GatewayEventParser,
) : EventStreamClient {

    override fun events(profile: ConnectionProfile): Flow<GatewayEvent> = flow {
        var attempt = 0
        while (true) {
            try {
                singleConnection(profile).collect { event ->
                    if (event is GatewayEvent.Connected) attempt = 0
                    emit(event)
                }
            } catch (_: StreamClosedException) {
                // fall through to backoff + reconnect
            }
            attempt++
            emit(GatewayEvent.Disconnected)
            delay(backoffMillis(attempt))
        }
    }

    private fun singleConnection(profile: ConnectionProfile): Flow<GatewayEvent> = callbackFlow {
        // OkHttp takes http(s) URLs for WebSockets and upgrades via headers.
        val ssePath = "/api/stream?t=" + profile.token
        val url = profile.baseUrl.trimEnd('/') +
            "/cf-ws?path=" + URLEncoder.encode(ssePath, Charsets.UTF_8.name())

        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    parser.parse(text)?.let { trySendBlocking(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(StreamClosedException(t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close(StreamClosedException(null))
                }
            },
        )

        awaitClose { socket.cancel() }
    }
}
