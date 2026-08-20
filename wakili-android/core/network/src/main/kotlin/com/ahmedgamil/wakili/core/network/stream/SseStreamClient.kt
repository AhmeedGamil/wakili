package com.ahmedgamil.wakili.core.network.stream

import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE transport for GET /api/stream. EventSource can't send headers from a
 * browser, so the server accepts ?t= — we do the same for parity (and because
 * reconnecting proxies replay the URL, not headers).
 */
@Singleton
class SseStreamClient @Inject constructor(
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
        val url = profile.baseUrl.trimEnd('/') + "/api/stream?t=" + profile.token
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            // Keep remote gateways from compressing/coalescing tiny SSE frames.
            // Browser EventSource streams them immediately; Android must request
            // the same no-transform path explicitly through reverse proxies.
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .build()

        val source = EventSources.createFactory(streamingClient()).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    parser.parse(data)?.let { trySendBlocking(it) }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    close(StreamClosedException(t))
                }

                override fun onClosed(eventSource: EventSource) {
                    close(StreamClosedException(null))
                }
            },
        )

        awaitClose { source.cancel() }
    }

    private fun streamingClient(): OkHttpClient =
        client.newBuilder()
            // A dedicated HTTP/1.1 connection preserves SSE chunk boundaries on
            // hosted gateways instead of allowing HTTP/2 proxy-side coalescing.
            .protocols(listOf(Protocol.HTTP_1_1))
            // The server pings every 15s, so a 45s read silence means the socket
            // died without a FIN (doze, wifi→cellular). An infinite timeout sat
            // on such a corpse for minutes while deltas silently went nowhere;
            // timing out turns it into onFailure → backoff → reconnect → resync.
            .readTimeout(java.time.Duration.ofSeconds(45))
            .build()
}

internal class StreamClosedException(cause: Throwable?) : Exception(cause)

internal fun backoffMillis(attempt: Int): Long {
    // 1.5s base like cf-shim.js, doubling to a 30s cap.
    val exp = (1500L shl (attempt - 1).coerceIn(0, 4))
    return exp.coerceAtMost(30_000L)
}
