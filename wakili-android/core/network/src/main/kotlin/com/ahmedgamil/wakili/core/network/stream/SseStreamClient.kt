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
            .readTimeout(java.time.Duration.ZERO) // SSE stays open; pings keep it warm
            .build()
}

internal class StreamClosedException(cause: Throwable?) : Exception(cause)

internal fun backoffMillis(attempt: Int): Long {
    // 1.5s base like cf-shim.js, doubling to a 30s cap.
    val exp = (1500L shl (attempt - 1).coerceIn(0, 4))
    return exp.coerceAtMost(30_000L)
}
