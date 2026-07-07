package com.ahmedgamil.wakili.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire shapes of server.mjs responses. Field names must match the JSON exactly;
// everything optional-with-default so a server a version ahead never crashes us.

@Serializable
data class AgentDto(
    val id: String,
    val label: String = "",
    val description: String? = null,
    val controls: Map<String, ControlDto> = emptyMap(),
    val commands: List<CommandDto> = emptyList(),
)

@Serializable
data class ControlDto(
    val label: String = "",
    @SerialName("default") val defaultValue: String? = null,
    val options: List<ControlOptionDto> = emptyList(),
)

@Serializable
data class ControlOptionDto(
    val value: String,
    val label: String = "",
    val description: String? = null,
)

@Serializable
data class CommandDto(
    val name: String,
    val desc: String? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String = "",
    val agentId: String = "claude",
    val model: String? = null,
    val cwd: String? = null,
    val effectiveCwd: String? = null,
    val updatedAt: Long = 0,
    val busy: Boolean = false,
    val pending: Int = 0,
    val messages: List<MessageDto>? = null,
    val controls: Map<String, JsonElement>? = null,
    val allowedTools: List<String>? = null,
    val resumeId: String? = null,
)

@Serializable
data class MessageDto(
    val role: String,
    val text: String? = null,
    val attachments: List<AttachmentDto>? = null,
    val parts: List<PartDto>? = null,
)

@Serializable
data class AttachmentDto(
    val name: String = "",
    val url: String = "",
    val image: Boolean = false,
)

/**
 * Flat union of every part shape (text/thinking/tool/file) — discriminated by
 * [type] in the mapper. Flat beats polymorphic serializers here: the server may
 * add fields anytime and unknown keys must never break parsing.
 */
@Serializable
data class PartDto(
    val type: String,
    val text: String? = null,
    // tool
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val output: String? = null,
    val isError: Boolean? = null,
    // file
    val caption: String? = null,
    val token: String? = null,
    val url: String? = null,
)

@Serializable
data class EndpointDto(
    val label: String,
    val host: String = "",
    val url: String,
)

@Serializable
data class FileEntryDto(
    val token: String,
    val sessionId: String = "",
    val source: String = "agent",
    val name: String = "",
    val caption: String = "",
    val image: Boolean = false,
    val url: String = "",
)

@Serializable
data class FoldersDto(
    val path: String = "",
    val parent: String? = null,
    val dirs: List<FolderEntryDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class FolderEntryDto(
    val name: String,
    val path: String,
)

@Serializable
data class PowerDto(
    val platform: String = "",
    val keepAwake: Boolean = false,
)

@Serializable
data class AutostartDto(
    val supported: Boolean = false,
    val on: Boolean = false,
    val method: String? = null,
    val error: String? = null,
)

@Serializable
data class OkDto(
    val ok: Boolean = false,
    val stopped: Boolean? = null,
)

@Serializable
data class ExecResultDto(
    val ok: Boolean = false,
    val code: Int? = null,
    val output: String = "",
    val cwd: String? = null,
)

@Serializable
data class UploadResultDto(
    val path: String,
    val name: String,
    val url: String = "",
)

@Serializable
data class CreatedFolderDto(
    val path: String,
)

@Serializable
data class ErrorDto(
    val error: String = "",
)
