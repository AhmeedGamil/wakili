package com.ahmedgamil.wakili.core.network.stream

import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.model.Question
import com.ahmedgamil.wakili.core.model.QuestionOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Translates one raw stream payload (the JSON of an SSE `data:` line, or of a
 * cf-ws frame) into a neutral [GatewayEvent]. Faithful port of
 * public/js/core/streamParser.js — every branch there exists here; anything
 * unrecognized returns null (the web client's "ignore").
 */
@Singleton
class GatewayEventParser @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(payload: String): GatewayEvent? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        return parse(root)
    }

    fun parse(root: JsonObject): GatewayEvent? {
        val type = root.str("type") ?: return null
        // Multiplexed events are tagged; "connected" is stream-level (no session).
        val sessionId = root.str("sessionId")

        if (type == "_gateway") {
            val subtype = root.str("subtype") ?: return null
            if (subtype == "connected") return GatewayEvent.Connected
            val sid = sessionId ?: return null
            return when (subtype) {
                "turn_start" -> GatewayEvent.TurnStart(sid)
                "turn_end" -> GatewayEvent.TurnEnd(sid, root.str("title"))
                "stopped" -> GatewayEvent.Stopped(sid)
                "snapshot" -> GatewayEvent.Snapshot(
                    sessionId = sid,
                    parts = root.arr("parts").mapNotNull { parsePart(it) },
                    busy = root.bool("busy"),
                    pending = root.arr("pending").mapNotNull { parsePendingCard(it) },
                    client = root.str("client") ?: "",
                )
                "permission_request" -> GatewayEvent.PermissionRequest(
                    sessionId = sid,
                    id = root.str("id") ?: return null,
                    tool = root.str("tool") ?: "",
                    input = root.obj("input"),
                )
                "question_request" -> GatewayEvent.QuestionRequest(
                    sessionId = sid,
                    id = root.str("id") ?: return null,
                    questions = parseQuestions(root.obj("input")),
                )
                "request_resolved" -> GatewayEvent.RequestResolved(sid, root.str("id") ?: return null)
                "tool" -> GatewayEvent.AutoApprovedTool(
                    sessionId = sid,
                    tool = Part.Tool(
                        id = root.str("id"),
                        name = root.str("tool") ?: "",
                        input = root.obj("input"),
                    ),
                )
                "tool_result" -> GatewayEvent.ToolResult(
                    sessionId = sid,
                    id = root.str("id"),
                    output = root.str("output") ?: "",
                    isError = root.bool("isError"),
                )
                "file" -> GatewayEvent.FileDelivered(
                    sessionId = sid,
                    file = Part.File(
                        name = root.str("name") ?: "",
                        caption = root.str("caption") ?: "",
                        url = root.str("url"),
                        token = root.str("token"),
                    ),
                )
                "stderr" -> GatewayEvent.Stderr(sid, root.str("text") ?: "")
                else -> null
            }
        }

        val sid = sessionId ?: return null

        if (type == "stream_event") {
            val delta = root.obj("event")?.obj("delta") ?: return null
            return when (delta.str("type")) {
                "text_delta" -> GatewayEvent.TextDelta(sid, delta.str("text") ?: "")
                "thinking_delta" -> GatewayEvent.ThinkingDelta(sid, delta.str("thinking") ?: "")
                else -> null
            }
        }

        if (type == "assistant") {
            val message = root.obj("message") ?: return null
            val content = message.arr("content")
            // Slash-command output arrives whole as a "<synthetic>" message.
            if (message.str("model") == "<synthetic>") {
                val text = content
                    .mapNotNull { it as? JsonObject }
                    .filter { it.str("type") == "text" }
                    .mapNotNull { it.str("text") }
                    .joinToString("")
                return if (text.isNotEmpty()) GatewayEvent.TextDelta(sid, text) else null
            }
            val tools = content
                .mapNotNull { it as? JsonObject }
                .filter { it.str("type") == "tool_use" }
                .map {
                    Part.Tool(
                        id = it.str("id"),
                        name = it.str("name") ?: "",
                        input = it.obj("input"),
                    )
                }
            return if (tools.isNotEmpty()) GatewayEvent.ToolUses(sid, tools) else null
        }

        return null
    }

    fun parsePart(element: JsonElement): Part? {
        val obj = element as? JsonObject ?: return null
        return when (obj.str("type")) {
            "text" -> Part.Text(obj.str("text") ?: "")
            "thinking" -> Part.Thinking(obj.str("text") ?: "")
            "tool" -> Part.Tool(
                id = obj.str("id"),
                name = obj.str("name") ?: "",
                input = obj.obj("input"),
                output = obj.str("output"),
                isError = obj.bool("isError"),
            )
            "file" -> Part.File(
                name = obj.str("name") ?: "",
                caption = obj.str("caption") ?: "",
                url = obj.str("url"),
                token = obj.str("token"),
            )
            else -> null
        }
    }

    private fun parsePendingCard(element: JsonElement): PendingCard? {
        val obj = element as? JsonObject ?: return null
        val id = obj.str("id") ?: return null
        return when (obj.str("subtype")) {
            "permission_request" -> PendingCard.Permission(
                id = id,
                tool = obj.str("tool") ?: "",
                input = obj.obj("input"),
            )
            "question_request" -> PendingCard.Question(
                id = id,
                questions = parseQuestions(obj.obj("input")),
            )
            else -> null
        }
    }

    /** input.questions[] — options are plain strings or {label, description}. */
    private fun parseQuestions(input: JsonObject?): List<Question> {
        val questions = input?.arr("questions") ?: return emptyList()
        return questions.mapNotNull { q ->
            val obj = q as? JsonObject ?: return@mapNotNull null
            Question(
                header = obj.str("header"),
                question = obj.str("question") ?: "",
                multiSelect = obj.bool("multiSelect"),
                options = obj.arr("options").mapNotNull { opt ->
                    when (opt) {
                        is JsonPrimitive -> QuestionOption(label = opt.content)
                        is JsonObject -> QuestionOption(
                            label = opt.str("label") ?: return@mapNotNull null,
                            description = opt.str("description"),
                        )
                        else -> null
                    }
                },
            )
        }
    }
}

// --- tiny JsonObject accessors: tolerate absent/mistyped fields everywhere ---

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content == "true"

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray =
    (this[key] as? JsonArray) ?: JsonArray(emptyList())
