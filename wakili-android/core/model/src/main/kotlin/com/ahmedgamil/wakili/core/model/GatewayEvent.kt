package com.ahmedgamil.wakili.core.model

import kotlinx.serialization.json.JsonObject

/**
 * Neutral domain events, one-to-one with what public/js/core/streamParser.js
 * produces — the rest of the app never sees a vendor-specific event shape.
 * Events from the multiplexed stream carry the [sessionId] they belong to;
 * [Connected] is stream-level and has none.
 */
sealed interface GatewayEvent {
    /** Stream (re)opened. First one after subscribe = fresh; later = reconnect. */
    data object Connected : GatewayEvent

    /** Transport dropped; the client is backing off before reconnecting. */
    data object Disconnected : GatewayEvent

    sealed interface SessionEvent : GatewayEvent {
        val sessionId: String
    }

    data class TurnStart(override val sessionId: String) : SessionEvent

    data class TurnEnd(override val sessionId: String, val title: String?) : SessionEvent

    data class Stopped(override val sessionId: String) : SessionEvent

    /** Resync replay: in-progress turn parts + open cards, tagged by requester. */
    data class Snapshot(
        override val sessionId: String,
        val parts: List<Part>,
        val busy: Boolean,
        val pending: List<PendingCard>,
        val client: String,
    ) : SessionEvent

    data class TextDelta(override val sessionId: String, val text: String) : SessionEvent

    data class ThinkingDelta(override val sessionId: String, val text: String) : SessionEvent

    /** tool_use blocks from the agent (chips / expandable cards). */
    data class ToolUses(override val sessionId: String, val tools: List<Part.Tool>) : SessionEvent

    /** A gated tool the gateway auto-approved — surfaced so the action is visible. */
    data class AutoApprovedTool(
        override val sessionId: String,
        val tool: Part.Tool,
    ) : SessionEvent

    data class ToolResult(
        override val sessionId: String,
        val id: String?,
        val output: String,
        val isError: Boolean,
    ) : SessionEvent

    data class PermissionRequest(
        override val sessionId: String,
        val id: String,
        val tool: String,
        val input: JsonObject?,
    ) : SessionEvent

    data class QuestionRequest(
        override val sessionId: String,
        val id: String,
        val questions: List<Question>,
    ) : SessionEvent

    /** Answered elsewhere or timed out — drop the card without archiving. */
    data class RequestResolved(override val sessionId: String, val id: String) : SessionEvent

    data class FileDelivered(
        override val sessionId: String,
        val file: Part.File,
    ) : SessionEvent

    data class Stderr(override val sessionId: String, val text: String) : SessionEvent
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
