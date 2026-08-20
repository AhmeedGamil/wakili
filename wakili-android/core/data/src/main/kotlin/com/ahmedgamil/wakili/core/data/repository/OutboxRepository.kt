package com.ahmedgamil.wakili.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class OutboxStatus { Failed, Retrying }

/** A send that failed outright (network/server error) — kept for Retry/Discard. */
data class OutboxEntry(
    val id: Int,
    val clientMessageId: String,
    val text: String,
    val controls: Map<String, String>,
    val agentId: String?,
    val attachments: List<SendAttachment>,
    val status: OutboxStatus = OutboxStatus.Failed,
)

/**
 * Failed sends per session, the web chatController's `outbox` map: nothing is
 * silently dropped — each entry renders as a failed row until retried or
 * discarded. In-memory, like the web's.
 */
@Singleton
class OutboxRepository @Inject constructor() {

    private val _outbox = MutableStateFlow<Map<String, List<OutboxEntry>>>(emptyMap())
    val outbox: StateFlow<Map<String, List<OutboxEntry>>> = _outbox.asStateFlow()

    private var nextId = 0

    fun add(
        sessionId: String,
        text: String,
        controls: Map<String, String>,
        agentId: String?,
        attachments: List<SendAttachment>,
        clientMessageId: String,
    ): OutboxEntry {
        var result: OutboxEntry? = null
        _outbox.update { map ->
            val current = map[sessionId].orEmpty()
            val existing = current.firstOrNull { it.clientMessageId == clientMessageId }
            if (existing != null) {
                result = existing
                map
            } else {
                val created = OutboxEntry(
                    id = ++nextId,
                    clientMessageId = clientMessageId,
                    text = text,
                    controls = controls,
                    agentId = agentId,
                    attachments = attachments,
                )
                result = created
                map + (sessionId to (current + created))
            }
        }
        return requireNotNull(result)
    }

    /** Atomically arms one retry; a fast second tap observes Retrying and stops. */
    fun markRetrying(sessionId: String, id: Int): Boolean {
        var changed = false
        _outbox.update { map ->
            val current = map[sessionId].orEmpty()
            val updated = current.map { entry ->
                if (entry.id == id && entry.status == OutboxStatus.Failed) {
                    changed = true
                    entry.copy(status = OutboxStatus.Retrying)
                } else {
                    entry
                }
            }
            if (changed) map + (sessionId to updated) else map
        }
        return changed
    }

    fun markFailed(sessionId: String, id: Int) {
        _outbox.update { map ->
            val current = map[sessionId].orEmpty()
            if (current.none { it.id == id }) return@update map
            map + (sessionId to current.map { entry ->
                if (entry.id == id) entry.copy(status = OutboxStatus.Failed) else entry
            })
        }
    }

    fun remove(sessionId: String, id: Int) {
        _outbox.update { map ->
            val rest = map[sessionId].orEmpty().filterNot { it.id == id }
            if (rest.isEmpty()) map - sessionId else map + (sessionId to rest)
        }
    }
}
