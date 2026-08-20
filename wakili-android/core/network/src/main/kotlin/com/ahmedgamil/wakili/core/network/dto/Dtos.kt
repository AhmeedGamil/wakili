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
    // Model-scoped option lists (e.g. Codex's effort ladder: ultra only on the
    // models that take it). Keyed by model value; `options` is the union fallback.
    val optionsFor: Map<String, List<ControlOptionDto>> = emptyMap(),
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
    // Conditional detail responses intentionally omit the session metadata.
    // The repository restores the route id and cached metadata before mapping
    // or persisting one of those responses.
    val id: String = "",
    val title: String = "",
    val agentId: String = "claude",
    val model: String? = null,
    val cwd: String? = null,
    val effectiveCwd: String? = null,
    val updatedAt: Long = 0,
    val busy: Boolean = false,
    val pending: Int = 0,
    val messages: List<MessageDto>? = null,
    /** Optimistic in-progress turn snapshot returned by session detail GET. */
    val parts: List<PartDto>? = null,
    /** Stable id for the currently running turn, when sent by a newer gateway. */
    val activeClientMessageId: String? = null,
    val controls: Map<String, JsonElement>? = null,
    val allowedTools: List<String>? = null,
    val autoAllow: Boolean = false,
    /** "Auto continue": the gateway re-drives a limit-killed turn once quota
     *  returns. Defaults ON — only an explicit false from the gateway (the user
     *  turned it off) counts as off, so a chat written before the field existed
     *  still resumes. */
    val autoContinue: Boolean = true,
    /** Pending scheduled tasks targeting this chat (withMeta). */
    val scheduled: Int = 0,
    val resumeId: String? = null,
    // Conditional/delta GET flags (never persisted — the repository strips them):
    // { unchanged } when our ?since= matches; { delta, after } when ?after= let
    // the server send only the messages past our cursor.
    val unchanged: Boolean = false,
    val delta: Boolean = false,
    val after: Int? = null,
)

/** POST /api/sessions/:id/handoff — the fresh session plus the transcript file. */
@Serializable
data class HandoffDto(
    val session: SessionDto,
    val file: HandoffFileDto,
)

@Serializable
data class HandoffFileDto(
    val name: String = "",
    val path: String = "",
    val url: String? = null,
)

@Serializable
data class MessageDto(
    val role: String,
    val text: String? = null,
    val attachments: List<AttachmentDto>? = null,
    val parts: List<PartDto>? = null,
    val clientMessageId: String? = null,
    /**
     * Client-side only, never sent by the server: a turn folded into the cache
     * from the live stream while its chat had no screen open. Excluded from
     * the delta-fetch cursor so the server's own copy replaces it.
     */
    val provisional: Boolean? = null,
)

@Serializable
data class AttachmentDto(
    val name: String = "",
    val url: String = "",
    val image: Boolean = false,
)

/**
 * Flat union of every part shape (text/thinking/tool/file/qa) — discriminated by
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
    // qa
    val answer: String? = null,
)

@Serializable
data class EndpointDto(
    val label: String,
    val host: String = "",
    val url: String,
)

/** /api/host — the gateway computer's own name (os.hostname) + platform. */
@Serializable
data class HostDto(
    val hostname: String = "",
    val platform: String = "",
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
    val duplicate: Boolean = false,
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

// ---- scheduled tasks (src/schedule.mjs) -------------------------------------

@Serializable
data class ScheduleJobDto(
    val id: String,
    val sessionId: String = "",
    val text: String = "",
    val at: Long = 0,
    val createdAt: Long = 0,
    /** Files riding the job ({name, path, url} on the wire; path is ignored). */
    val attachments: List<AttachmentDto> = emptyList(),
    /** Only on GET /api/schedule — which chat the task will run in. */
    val sessionTitle: String = "",
)

/** PATCH /api/schedule/:jobId — the updated job. */
@Serializable
data class SchedulePatchDto(
    val job: ScheduleJobDto,
)

/** GET /api/sessions/:id/schedule */
@Serializable
data class ScheduleListDto(
    val jobs: List<ScheduleJobDto> = emptyList(),
)

/** POST /api/sessions/:id/schedule — the job plus the session (autoContinue now on). */
@Serializable
data class SchedulePostDto(
    val job: ScheduleJobDto,
    val session: SessionDto? = null,
)
