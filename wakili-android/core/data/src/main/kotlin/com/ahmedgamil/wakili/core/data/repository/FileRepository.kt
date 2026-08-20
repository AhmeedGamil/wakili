package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.DeleteUploadBody
import com.ahmedgamil.wakili.core.network.dto.UploadBody
import com.ahmedgamil.wakili.core.network.dto.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

data class UploadedFile(val name: String, val path: String, val url: String)

/**
 * Registry-delete outcome.
 *
 * There is deliberately no "old gateway" case. A 404 used to be read as "no
 * such route", and the user was told to restart the server — but a CURRENT
 * gateway also answers 404 for a file that is no longer in its registry
 * (server.mjs: `no such file`). The two are indistinguishable from the status
 * code, so the old reading gave wrong advice for the far more common one.
 */
enum class DeleteFileOutcome { DELETED, FAILED }

/** File registry + uploads — the web FilesPage and Composer attachments. */
@Singleton
class FileRepository @Inject constructor(
    private val api: GatewayApi,
    private val json: Json,
) {
    suspend fun files(): List<FileEntry> = api.files().map { it.toModel() }

    suspend fun upload(
        name: String,
        bytes: ByteArray,
        sessionId: String?,
        onProgress: (Float) -> Unit = {},
    ): UploadedFile {
        val payload = json.encodeToString(
            UploadBody(
                name = name,
                dataBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
                sessionId = sessionId,
            ),
        ).encodeToByteArray()
        val result = api.upload(
            ProgressRequestBody(payload, onProgress),
        )
        return UploadedFile(name = result.name, path = result.path, url = result.url)
    }

    suspend fun deleteUpload(path: String) {
        api.deleteUpload(DeleteUploadBody(path))
    }

    suspend fun deleteFile(token: String): DeleteFileOutcome = try {
        api.deleteFile(token)
        DeleteFileOutcome.DELETED
    } catch (e: Exception) {
        DeleteFileOutcome.FAILED
    }
}

/** Reports bytes actually written to OkHttp's request sink, throttled to 1%. */
private class ProgressRequestBody(
    private val bytes: ByteArray,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        if (bytes.isEmpty()) {
            onProgress(1f)
            return
        }

        var offset = 0
        var lastPercent = -1
        while (offset < bytes.size) {
            val count = minOf(UPLOAD_CHUNK_SIZE, bytes.size - offset)
            sink.write(bytes, offset, count)
            offset += count

            val percent = (offset.toLong() * 100L / bytes.size).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent / 100f)
            }
        }
    }

    private companion object {
        const val UPLOAD_CHUNK_SIZE = 8 * 1024
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
