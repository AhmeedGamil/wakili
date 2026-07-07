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
 */
@Singleton
class QueueRepository @Inject constructor(
    private val sessionRepository: SessionRepository,
    streamRepository: GatewayStreamRepository,
    @AppScope scope: CoroutineScope,
) {

    private val _queued = MutableStateFlow<Map<String, List<QueuedSend>>>(emptyMap())
    val queued: StateFlow<Map<String, List<QueuedSend>>> = _queued.asStateFlow()

    init {
        scope.launch {
            streamRepository.events
                .filterIsInstance<GatewayEvent.TurnEnd>()
                .collect { event -> dispatchNext(event.sessionId) }
        }
    }

    fun enqueue(sessionId: String, send: QueuedSend) {
        _queued.update { it + (sessionId to (it[sessionId].orEmpty() + send)) }
    }

    fun cancelAll(sessionId: String) {
        _queued.update { it - sessionId }
    }

    private suspend fun dispatchNext(sessionId: String) {
        val next = _queued.value[sessionId]?.firstOrNull() ?: return
        _queued.update { map ->
            val rest = map[sessionId].orEmpty().drop(1)
            if (rest.isEmpty()) map - sessionId else map + (sessionId to rest)
        }
        runCatching {
            when (sessionRepository.send(sessionId, next.text, next.controls, next.agentId, next.attachments)) {
                SendResult.Busy -> enqueue(sessionId, next) // raced a new turn; requeue
                SendResult.Accepted -> Unit
            }
        }
    }
}

data class QueuedSend(
    val text: String,
    val controls: Map<String, String>? = null,
    val agentId: String? = null,
    val attachments: List<SendAttachment> = emptyList(),
)
