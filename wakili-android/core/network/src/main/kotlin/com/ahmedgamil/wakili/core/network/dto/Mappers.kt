package com.ahmedgamil.wakili.core.network.dto

import com.ahmedgamil.wakili.core.model.AgentCommand
import com.ahmedgamil.wakili.core.model.AgentControl
import com.ahmedgamil.wakili.core.model.AgentControlOption
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.model.Attachment
import com.ahmedgamil.wakili.core.model.AutostartState
import com.ahmedgamil.wakili.core.model.ChatMessage
import com.ahmedgamil.wakili.core.model.ExecResult
import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.model.FileSource
import com.ahmedgamil.wakili.core.model.FolderEntry
import com.ahmedgamil.wakili.core.model.FolderListing
import com.ahmedgamil.wakili.core.model.GatewayEndpoint
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.PowerState
import com.ahmedgamil.wakili.core.model.SessionDetail
import com.ahmedgamil.wakili.core.model.SessionSummary
import kotlinx.serialization.json.JsonPrimitive

fun AgentDto.toModel() = AgentManifest(
    id = id,
    label = label.ifEmpty { id },
    description = description,
    controls = controls.mapValues { (_, c) ->
        AgentControl(
            label = c.label,
            default = c.defaultValue,
            options = c.options.map { AgentControlOption(it.value, it.label.ifEmpty { it.value }, it.description) },
        )
    },
    commands = commands.map { AgentCommand(it.name, it.desc) },
)

fun SessionDto.toSummary() = SessionSummary(
    id = id,
    title = title,
    agentId = agentId,
    model = model,
    cwd = cwd,
    effectiveCwd = effectiveCwd,
    updatedAt = updatedAt,
    busy = busy,
    pending = pending,
)

fun SessionDto.toDetail() = SessionDetail(
    summary = toSummary(),
    messages = messages.orEmpty().mapNotNull { it.toModel() },
    controls = controls.orEmpty().mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.content?.let { k to it }
    }.toMap(),
    allowedTools = allowedTools.orEmpty(),
)

fun MessageDto.toModel(): ChatMessage? = when (role) {
    "user" -> ChatMessage.User(
        text = text.orEmpty(),
        attachments = attachments.orEmpty().map { Attachment(it.name, it.url, it.image) },
    )
    "assistant" -> ChatMessage.Assistant(
        // Old sessions may store assistant replies as plain {role, text}.
        parts = parts?.mapNotNull { it.toModel() }
            ?: text?.let { listOf(Part.Text(it)) }
            ?: emptyList(),
    )
    else -> null
}

fun PartDto.toModel(): Part? = when (type) {
    "text" -> Part.Text(text.orEmpty())
    "thinking" -> Part.Thinking(text.orEmpty())
    "tool" -> Part.Tool(
        id = id,
        name = name.orEmpty(),
        input = input,
        output = output,
        isError = isError == true,
    )
    "file" -> Part.File(
        name = name.orEmpty(),
        caption = caption.orEmpty(),
        url = url,
        token = token,
    )
    else -> null
}

fun EndpointDto.toModel() = GatewayEndpoint(label = label, host = host, url = url)

fun FileEntryDto.toModel() = FileEntry(
    token = token,
    sessionId = sessionId,
    source = if (source == "user") FileSource.USER else FileSource.AGENT,
    name = name,
    caption = caption,
    image = image,
    url = url,
)

fun FoldersDto.toModel() = FolderListing(
    path = path,
    parent = parent,
    dirs = dirs.map { FolderEntry(it.name, it.path) },
    error = error,
)

fun PowerDto.toModel() = PowerState(platform = platform, keepAwake = keepAwake)

fun AutostartDto.toModel() = AutostartState(supported = supported, on = on, method = method, error = error)

fun ExecResultDto.toModel() = ExecResult(ok = ok, code = code, output = output, cwd = cwd)
