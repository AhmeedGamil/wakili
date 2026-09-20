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
    /** "Allow always": the gateway auto-approves this session's permissions. */
    val autoAllow: Boolean? = null,
    /** "Auto continue": re-drive a limit-killed turn when quota returns. */
    val autoContinue: Boolean? = null,
    /** Picker state, persisted on change — not just at send (web saveControls). */
    val agentId: String? = null,
    val controls: Map<String, String>? = null,
)

@Serializable
data class SendMessageBody(
    val text: String,
    val controls: Map<String, String>? = null,
    val attachments: List<SendAttachmentBody>? = null,
    val agentId: String? = null,
    /** Optional for backward compatibility; Android retries reuse the same UUID. */
    val clientMessageId: String? = null,
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
data class HandoffBody(
    val agentId: String,
)

/** POST /api/sessions/:id/schedule — one-shot task at epoch-millis [at]. */
@Serializable
data class ScheduleBody(
    val text: String,
    val at: Long,
    /** Files scheduled with the task — the same uploaded shape a send carries. */
    val attachments: List<SendAttachmentBody> = emptyList(),
)

/** PATCH /api/schedule/:jobId — edit a pending task's text and/or time. */
@Serializable
data class PatchScheduleBody(
    val text: String? = null,
    val at: Long? = null,
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

// --- stream diagnostics (see core/data/stream/StreamRecorder.kt) -------------
// Uploaded when the phone spots an out-of-order snapshot, so one read of the
// laptop's /api/debug/stream shows both sides of the same turn. The gateway
// drops these unless WAKILI_DEBUG_STREAM=1.

@Serializable
data class DebugReportBody(
    val source: String,
    val clientId: String,
    val now: Long,
    val entries: List<DebugEntryBody>,
    val counters: Map<String, DebugCounterBody>,
)

@Serializable
data class DebugEntryBody(
    val at: Long,
    val kind: String,
    val sessionId: String? = null,
    val ev: String? = null,
    val seq: Long? = null,
    val appliedSeq: Long? = null,
    val parts: Int? = null,
    val busy: Boolean? = null,
    val client: String? = null,
    val deltas: Int? = null,
    val chars: Int? = null,
    val detail: String? = null,
)

@Serializable
data class DebugCounterBody(
    val deltas: Int,
    val chars: Int,
    val lastSeq: Long,
    val turns: Int,
)
