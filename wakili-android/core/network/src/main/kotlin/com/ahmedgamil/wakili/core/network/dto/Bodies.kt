package com.ahmedgamil.wakili.core.network.dto

import kotlinx.serialization.Serializable

// Request bodies — shapes taken from what the web client posts (services/api.js).

@Serializable
data class CreateSessionBody(
    val agentId: String,
    val model: String? = null,
    val cwd: String? = null,
)

@Serializable
data class PatchSessionBody(
    val title: String? = null,
    val cwd: String? = null,
)

@Serializable
data class SendMessageBody(
    val text: String,
    val controls: Map<String, String>? = null,
    val attachments: List<SendAttachmentBody>? = null,
    val agentId: String? = null,
)

@Serializable
data class SendAttachmentBody(
    val name: String,
    val path: String,
    val url: String? = null,
)

@Serializable
data class PermissionAnswerBody(
    val id: String,
    val decision: String? = null, // "allow" | "deny" | "allow_session"
    val tool: String? = null,
    val answer: String? = null, // question replies carry the answer instead
)

@Serializable
data class ResyncBody(
    val client: String,
)

@Serializable
data class ExecBody(
    val command: String,
)

@Serializable
data class TermBody(
    val command: String,
    val cwd: String? = null,
)

@Serializable
data class UploadBody(
    val name: String,
    val dataBase64: String,
    val sessionId: String? = null,
)

@Serializable
data class DeleteUploadBody(
    val path: String,
)

@Serializable
data class CreateFolderBody(
    val parent: String,
    val name: String,
)

@Serializable
data class KeepAwakeBody(
    val on: Boolean,
)

@Serializable
data class AutostartBody(
    val on: Boolean,
)
