package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.AgentManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Last agent configuration remembered globally for the next new chat. */
data class AgentConfigSnapshot(
    val agentId: String,
    val controls: Map<String, String>,
)

private val agentControlsJson = Json { ignoreUnknownKeys = true }
private val legacyClaudeModel = Regex("^claude-([a-z]+)-")

fun decodeAgentConfig(raw: Pair<String, String>?): AgentConfigSnapshot? {
    val (agentId, controlsJson) = raw ?: return null
    val controls = runCatching {
        agentControlsJson.parseToJsonElement(controlsJson).jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.content
        }
    }.getOrDefault(emptyMap())
    return AgentConfigSnapshot(agentId, controls)
}

fun encodeAgentControls(controls: Map<String, String>): String =
    agentControlsJson.encodeToString(
        JsonObject.serializer(),
        JsonObject(controls.mapValues { JsonPrimitive(it.value) }),
    )

/**
 * Web-compatible defaultsFor + normalizeControls:
 * declared defaults -> matching last-used config -> global permission mode ->
 * this session's saved controls, followed by option validation/healing.
 */
fun resolveAgentControls(
    agent: AgentManifest,
    lastConfig: AgentConfigSnapshot?,
    permissionMode: String?,
    sessionControls: Map<String, String> = emptyMap(),
): Map<String, String> {
    val resolved = linkedMapOf<String, String>()
    agent.controls.forEach { (key, control) -> resolved[key] = control.default.orEmpty() }
    if (lastConfig?.agentId == agent.id) {
        agent.controls.keys.forEach { key ->
            lastConfig.controls[key]?.let { resolved[key] = it }
        }
    }
    if ("permissionMode" in agent.controls && !permissionMode.isNullOrBlank()) {
        resolved["permissionMode"] = permissionMode
    }
    resolved.putAll(sessionControls)

    agent.controls.forEach { (key, control) ->
        val options = control.optionsFor[resolved["model"]] ?: control.options
        val current = resolved[key]
        if (options.any { it.value == current }) return@forEach
        val alias = current
            ?.let(legacyClaudeModel::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { family -> control.options.firstOrNull { it.value == family }?.value }
        resolved[key] = alias ?: control.default.orEmpty()
    }
    return resolved
}
