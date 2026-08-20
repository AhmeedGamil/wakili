package com.ahmedgamil.wakili.core.model

import kotlinx.serialization.json.JsonObject

/**
 * Neutral domain events, one-to-one with what public/js/core/streamParser.js
 * produces — the rest of the app never sees a vendor-specific event shape.
 * Events from the multiplexed stream carry the [sessionId] they belong to;
 * [Connected] is stream-level and has none.
 */
sealed interface GatewayEvent {
    /**
     * Stream (re)opened. First one after subscribe = fresh; later = reconnect.
     * [debug] mirrors the gateway's WAKILI_DEBUG_STREAM, so one env var on the
     * laptop also turns this client's stream flight recorder on.
     */
    data class Connected(val debug: Boolean = false) : GatewayEvent

    /** Transport dropped; the client is backing off before reconnecting. */
    data object Disconnected : GatewayEvent

    sealed interface SessionEvent : GatewayEvent {
        val sessionId: String

        /**
         * The gateway's monotonic ordering stamp (src/sse.mjs). Lets a client
         * tell a snapshot that is NEWER than the state it already applied from
         * one that is older: two resyncs can be answered out of order, and the
         * late/older one must not rewind a live turn. Null on gateways that
         * predate it — every consumer treats that as "unknown, behave as before".
         */
        val seq: Long?
    }

    data class TurnStart(
        override val sessionId: String,
        val clientMessageId: String? = null,
        override val seq: Long? = null,
    ) : SessionEvent

    data class TurnEnd(
        override val sessionId: String,
        val title: String?,
        val clientMessageId: String? = null,
        override val seq: Long? = null,
    ) : SessionEvent

    data class Stopped(
        override val sessionId: String,
        override val seq: Long? = null,
    ) : SessionEvent

    /** The gateway persisted session metadata/transcript and invalidated caches. */
    data class SessionUpdated(
        override val sessionId: String,
        val updatedAt: Long?,
        val title: String?,
        override val seq: Long? = null,
    ) : SessionEvent

    /** Resync replay: in-progress turn parts + open cards, tagged by requester. */
    data class Snapshot(
        override val sessionId: String,
        val parts: List<Part>,
        val busy: Boolean,
        val pending: List<PendingCard>,
        val client: String,
        val clientMessageId: String? = null,
        override val seq: Long? = null,
    ) : SessionEvent

    data class TextDelta(
        override val sessionId: String,
        val text: String,
        override val seq: Long? = null,
    ) : SessionEvent

    data class ThinkingDelta(
        override val sessionId: String,
        val text: String,
        override val seq: Long? = null,
    ) : SessionEvent

    /** tool_use blocks from the agent (chips / expandable cards). */
    data class ToolUses(
        override val sessionId: String,
        val tools: List<Part.Tool>,
        override val seq: Long? = null,
    ) : SessionEvent

    /** A gated tool the gateway auto-approved — surfaced so the action is visible. */
    data class AutoApprovedTool(
        override val sessionId: String,
        val tool: Part.Tool,
        override val seq: Long? = null,
    ) : SessionEvent

    data class ToolResult(
        override val sessionId: String,
        val id: String?,
        val output: String,
        val isError: Boolean,
        override val seq: Long? = null,
    ) : SessionEvent

    data class PermissionRequest(
        override val sessionId: String,
        val id: String,
        val tool: String,
        val input: JsonObject?,
        override val seq: Long? = null,
    ) : SessionEvent

    data class QuestionRequest(
        override val sessionId: String,
        val id: String,
        val questions: List<Question>,
        override val seq: Long? = null,
    ) : SessionEvent

    /** Answered elsewhere or timed out — drop the card without archiving. */
    data class RequestResolved(
        override val sessionId: String,
        val id: String,
        override val seq: Long? = null,
    ) : SessionEvent

    data class FileDelivered(
        override val sessionId: String,
        val file: Part.File,
        override val seq: Long? = null,
    ) : SessionEvent

    data class Stderr(
        override val sessionId: String,
        val text: String,
        override val seq: Long? = null,
    ) : SessionEvent
}

/** A permission/question card still awaiting an answer (replayed on snapshot). */
sealed interface PendingCard {
    val id: String

    data class Permission(
        override val id: String,
        val tool: String,
        val input: JsonObject?,
    ) : PendingCard

    data class Question(
        override val id: String,
        val questions: List<com.ahmedgamil.wakili.core.model.Question>,
    ) : PendingCard
}

data class Question(
    val header: String?,
    val question: String,
    val multiSelect: Boolean,
    val options: List<QuestionOption>,
)

data class QuestionOption(
    val label: String,
    val description: String? = null,
)
