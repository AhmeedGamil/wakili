package com.ahmedgamil.wakili.core.data.stream

import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.model.Part

/**
 * Pure reducer that rebuilds a session's in-progress turn from stream events —
 * the client-side twin of the `parts[]` assembly in server.mjs runTurn plus the
 * rendering rules of chatController.js. Given the same events, it must produce
 * exactly the parts the server persists (the golden tests assert this).
 */
object TurnReducer {

    fun reduce(parts: List<Part>, event: GatewayEvent.SessionEvent): List<Part> = when (event) {
        is GatewayEvent.TurnStart -> emptyList()

        is GatewayEvent.Snapshot -> event.parts

        is GatewayEvent.TextDelta -> appendText(parts, event.text)

        is GatewayEvent.ThinkingDelta -> appendThinking(parts, event.text)

        is GatewayEvent.ToolUses -> event.tools.fold(parts) { acc, tool -> addTool(acc, tool) }

        // A gated tool the gateway auto-approved. The tool_use part usually
        // already arrived via the assistant event — only add if it's new.
        is GatewayEvent.AutoApprovedTool -> addTool(parts, event.tool)

        is GatewayEvent.ToolResult -> attachOutput(parts, event)

        is GatewayEvent.FileDelivered -> parts + event.file

        // These affect cards/badges/state, not the turn's parts.
        is GatewayEvent.TurnEnd,
        is GatewayEvent.Stopped,
        is GatewayEvent.PermissionRequest,
        is GatewayEvent.QuestionRequest,
        is GatewayEvent.RequestResolved,
        is GatewayEvent.Stderr,
        -> parts
    }

    private fun appendText(parts: List<Part>, text: String): List<Part> {
        val last = parts.lastOrNull()
        return if (last is Part.Text) {
            parts.dropLast(1) + last.copy(text = last.text + text)
        } else {
            parts + Part.Text(text)
        }
    }

    private fun appendThinking(parts: List<Part>, text: String): List<Part> {
        val last = parts.lastOrNull()
        return if (last is Part.Thinking) {
            parts.dropLast(1) + last.copy(text = last.text + text)
        } else {
            parts + Part.Thinking(text)
        }
    }

    private fun addTool(parts: List<Part>, tool: Part.Tool): List<Part> {
        val exists = tool.id != null && parts.any { it is Part.Tool && it.id == tool.id }
        return if (exists) parts else parts + tool
    }

    private fun attachOutput(parts: List<Part>, result: GatewayEvent.ToolResult): List<Part> {
        // Attach by id; FIFO fallback (first tool still without output) matches
        // the web client's toolCard attachment order.
        val index = when {
            result.id != null -> parts.indexOfFirst { it is Part.Tool && it.id == result.id }
            else -> parts.indexOfFirst { it is Part.Tool && it.output == null }
        }
        if (index < 0) return parts
        val tool = parts[index] as Part.Tool
        return parts.toMutableList().apply {
            set(index, tool.copy(output = result.output, isError = result.isError))
        }
    }
}
