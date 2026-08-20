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
import com.ahmedgamil.wakili.core.model.ScheduledJob
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
            options = c.options.map { it.toModel() },
            optionsFor = c.optionsFor.mapValues { (_, list) -> list.map { it.toModel() } },
        )
    },
    commands = commands.map { AgentCommand(it.name, it.desc) },
)

private fun ControlOptionDto.toModel() =
    AgentControlOption(value, label.ifEmpty { value }, description)

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
    liveParts = parts.orEmpty().mapNotNull { it.toModel() },
    liveClientMessageId = activeClientMessageId,
    controls = controls.orEmpty().mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.content?.let { k to it }
    }.toMap(),
    allowedTools = allowedTools.orEmpty(),
    autoAllow = autoAllow,
    autoContinue = autoContinue,
    scheduled = scheduled,
)

fun ScheduleJobDto.toModel() = ScheduledJob(
    id = id,
    text = text,
    at = at,
    attachmentNames = attachments.map { it.name },
    sessionId = sessionId,
    sessionTitle = sessionTitle,
)

fun MessageDto.toModel(): ChatMessage? = when (role) {
    "user" -> ChatMessage.User(
        text = text.orEmpty(),
        attachments = attachments.orEmpty().map { Attachment(it.name, it.url, it.image) },
        clientMessageId = clientMessageId,
    )
    "assistant" -> ChatMessage.Assistant(
        // Old sessions may store assistant replies as plain {role, text}.
        parts = parts?.mapNotNull { it.toModel() }
            ?: text?.let { listOf(Part.Text(it)) }
            ?: emptyList(),
        clientMessageId = clientMessageId,
    )
    else -> null
}

/** Model → wire/cache shape, for folding stream-built parts into the cache. */
fun Part.toDto(): PartDto = when (this) {
    is Part.Text -> PartDto(type = "text", text = text)
    is Part.Thinking -> PartDto(type = "thinking", text = text)
    is Part.Tool -> PartDto(
        type = "tool",
        id = id,
        name = name,
        input = input,
        output = output,
        isError = isError.takeIf { it },
    )
    is Part.File -> PartDto(type = "file", name = name, caption = caption, url = url, token = token)
    is Part.Qa -> PartDto(type = "qa", answer = answer)
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
    "qa" -> Part.Qa(answer = answer.orEmpty())
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
