package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.data.di.AppScope
import com.ahmedgamil.wakili.core.data.stream.GatewayStreamRepository
import com.ahmedgamil.wakili.core.model.GatewayEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-session queued messages, exactly like the web client's `queued` map:
 * typed while a turn is running, dispatched one per turn_end.
 *
 * The ON-SCREEN session's queue is dispatched by its ChatViewModel instead
 * (inside its own TurnEnd handling), the way the web's flushQueued delivers
 * through the same controller that renders — the user bubble and working
 * pulse paint the moment the turn ends, not a fetch cycle later. This
 * repository covers the sessions without a live screen.
 */
@Singleton
class QueueRepository @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val outboxRepository: OutboxRepository,
    private val streamRepository: GatewayStreamRepository,
    @AppScope scope: CoroutineScope,
) {

    private val _queued = MutableStateFlow<Map<String, List<QueuedSend>>>(emptyMap())
    val queued: StateFlow<Map<String, List<QueuedSend>>> = _queued.asStateFlow()

    init {
        scope.launch {
            streamRepository.events
                .filterIsInstance<GatewayEvent.TurnEnd>()
                .collect { event ->
                    // Open session: its ChatViewModel owns this dispatch.
                    if (streamRepository.liveTurnGeneration(event.sessionId) != null) return@collect
                    dispatchNext(event.sessionId)
                }
        }
    }

    fun enqueue(sessionId: String, send: QueuedSend) {
        _queued.update { it + (sessionId to (it[sessionId].orEmpty() + send)) }
    }

    fun cancelAll(sessionId: String) {
        _queued.update { it - sessionId }
    }

    /** Pop the oldest queued send, if any — the caller delivers it. */
    fun take(sessionId: String): QueuedSend? {
        var next: QueuedSend? = null
        _queued.update { map ->
            val list = map[sessionId].orEmpty()
            next = list.firstOrNull()
            val rest = list.drop(1)
            if (rest.isEmpty()) map - sessionId else map + (sessionId to rest)
        }
        return next
    }

    private suspend fun dispatchNext(sessionId: String) {
        val next = take(sessionId) ?: return
        val result = runCatching {
            sessionRepository.send(
                id = sessionId,
                text = next.text,
                controls = next.controls,
                agentId = next.agentId,
                attachments = next.attachments,
                clientMessageId = next.clientMessageId,
            )
        }
        when (result.getOrNull()) {
            SendResult.Busy -> enqueue(sessionId, next) // raced a new turn; requeue
            is SendResult.Accepted -> Unit
            // Send failed outright. A dispatch nobody was watching must not
            // vanish — keep it as a Retry row (the web outbox), like the
            // ChatViewModel's own failed sends.
            null -> outboxRepository.add(
                sessionId,
                next.text,
                next.controls.orEmpty(),
                next.agentId,
                next.attachments,
                next.clientMessageId,
            )
        }
    }
}

data class QueuedSend(
    val text: String,
    val controls: Map<String, String>? = null,
    val agentId: String? = null,
    val attachments: List<SendAttachment> = emptyList(),
    val clientMessageId: String,
)
