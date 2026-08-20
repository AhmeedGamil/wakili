package com.ahmedgamil.wakili.core.model

/** Sidebar row — what GET /api/sessions returns per session (withMeta). */
data class SessionSummary(
    val id: String,
    val title: String,
    val agentId: String,
    val model: String?,
    val cwd: String?,
    val effectiveCwd: String?,
    val updatedAt: Long,
    val busy: Boolean,
    val pending: Int,
)

/** Full session — GET /api/sessions/:id (summary + transcript + controls). */
data class SessionDetail(
    val summary: SessionSummary,
    val messages: List<ChatMessage>,
    /** In-progress assistant parts returned by GET /api/sessions/:id. */
    val liveParts: List<Part> = emptyList(),
    val liveClientMessageId: String? = null,
    val controls: Map<String, String>,
    val allowedTools: List<String>,
    /** "Allow always" — kept on the gateway so approvals survive backgrounding. */
    val autoAllow: Boolean = false,
    /** "Auto continue" — the gateway re-drives a limit-killed turn once quota
     *  returns. On unless the chat has explicitly turned it off. */
    val autoContinue: Boolean = true,
    /** Pending scheduled tasks targeting this chat. */
    val scheduled: Int = 0,
)

/** One pending scheduled task (gateway-side, survives restarts). */
data class ScheduledJob(
    val id: String,
    val text: String,
    val at: Long,
    /** Names of the files scheduled with the task. */
    val attachmentNames: List<String> = emptyList(),
    /** Which chat the task runs in (filled by the all-tasks listing). */
    val sessionId: String = "",
    val sessionTitle: String = "",
)

sealed interface ChatMessage {
    data class User(
        val text: String,
        val attachments: List<Attachment> = emptyList(),
        /** Stable identity preserved across retry, queue, cache, and history. */
        val clientMessageId: String? = null,
    ) : ChatMessage

    data class Assistant(
        val parts: List<Part>,
        /** Identity of the user message that started this assistant turn. */
        val clientMessageId: String? = null,
    ) : ChatMessage
}

data class Attachment(
    val name: String,
    val url: String,
    val image: Boolean,
)
