package com.ahmedgamil.wakili.core.model

import kotlinx.serialization.json.JsonObject

/**
 * One ordered segment of an assistant turn — mirrors the `parts[]` the gateway
 * assembles in runTurn (server.mjs): text / thinking / tool / file. History
 * replays these in order, and live turns stream into the same shapes.
 */
sealed interface Part {
    data class Text(val text: String) : Part

    data class Thinking(val text: String) : Part

    data class Tool(
        val id: String?,
        val name: String,
        val input: JsonObject?,
        val output: String? = null,
        val isError: Boolean = false,
    ) : Part

    data class File(
        val name: String,
        val caption: String = "",
        val url: String? = null,
        val token: String? = null,
    ) : Part
}
