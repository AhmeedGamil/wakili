package com.ahmedgamil.wakili.core.model

data class PowerState(
    val platform: String,
    val keepAwake: Boolean,
)

data class AutostartState(
    val supported: Boolean,
    val on: Boolean,
    val method: String? = null,
    val error: String? = null,
)

data class FolderListing(
    val path: String,
    val parent: String?,
    val dirs: List<FolderEntry>,
    val error: String? = null,
)

data class FolderEntry(
    val name: String,
    val path: String,
)

data class FileEntry(
    val token: String,
    val sessionId: String,
    val source: FileSource,
    val name: String,
    val caption: String,
    val image: Boolean,
    val url: String,
)

enum class FileSource { USER, AGENT }

data class ExecResult(
    val ok: Boolean,
    val code: Int?,
    val output: String,
    val cwd: String? = null,
)
