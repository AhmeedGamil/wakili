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
    val controls: Map<String, String>,
    val allowedTools: List<String>,
)

sealed interface ChatMessage {
    data class User(
        val text: String,
        val attachments: List<Attachment> = emptyList(),
    ) : ChatMessage

    data class Assistant(
        val parts: List<Part>,
    ) : ChatMessage
}

data class Attachment(
    val name: String,
    val url: String,
    val image: Boolean,
)
