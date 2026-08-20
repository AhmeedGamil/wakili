package com.ahmedgamil.wakili.feature.chat

import com.ahmedgamil.wakili.core.model.Part
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Plain text used by message and tool-card copy actions. */
internal fun Part.asPlainText(): String = when (this) {
    is Part.Text -> text
    is Part.Thinking -> text
    is Part.Tool -> buildList {
        add(name)
        input?.entries
            ?.joinToString("\n") { (key, value) -> "$key: ${value.asPlainText()}" }
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
        output?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("\n\n")
    is Part.File -> buildList {
        add(name)
        caption.takeIf { it.isNotBlank() }?.let(::add)
        url?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("\n")
    is Part.Qa -> answer
}

private fun JsonElement.asPlainText(): String =
    (this as? JsonPrimitive)?.content ?: toString()
