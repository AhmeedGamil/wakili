package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.DeleteUploadBody
import com.ahmedgamil.wakili.core.network.dto.toModel
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
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
) {
    suspend fun files(): List<FileEntry> = api.files().map { it.toModel() }

    /**
     * Uploads by STREAMING the file into the request: the bytes are the body,
     * the name and session ride in headers, and [openStream] is opened when
     * OkHttp is ready to write (again, if it retries the request).
     *
     * Nothing here ever holds the file. The old shape — read the file, base64
     * it, wrap that in JSON — cost roughly four times the file's size in heap
     * at once (the bytes, a base64 String at 2 bytes per char, the JSON String,
     * then its UTF-8 bytes), so a 40MB pick hit OutOfMemoryError and killed the
     * process before the first packet went out.
     *
     * [sizeBytes] may be -1 when the picker won't say how big the file is: the
     * request then goes out chunked, and progress simply stays at 0 until the
     * last byte lands.
     */
    suspend fun upload(
        name: String,
        sizeBytes: Long,
        sessionId: String?,
        onProgress: (Float) -> Unit = {},
        openStream: () -> InputStream,
    ): UploadedFile {
        val result = api.upload(
            name = encodeHeaderName(name),
            sessionId = sessionId.orEmpty(),
            body = StreamingUploadBody(sizeBytes, openStream, onProgress),
        )
        return UploadedFile(name = result.name, path = result.path, url = result.url)
    }

    /** Same upload for bytes already in hand (a pasted screenshot). */
    suspend fun upload(
        name: String,
        bytes: ByteArray,
        sessionId: String?,
        onProgress: (Float) -> Unit = {},
    ): UploadedFile = upload(name, bytes.size.toLong(), sessionId, onProgress) {
        ByteArrayInputStream(bytes)
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

/**
 * Header values are ASCII-only (OkHttp rejects anything else outright), and
 * attachment names are routinely Arabic, emoji, or spaced — so the name travels
 * percent-encoded and the gateway decodeURIComponent()s it back.
 */
private fun encodeHeaderName(name: String): String =
    URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")

/**
 * Copies the file into OkHttp's sink one chunk at a time, reporting progress no
 * more than once per percent. Peak memory is one chunk, whatever the file size.
 */
private class StreamingUploadBody(
    private val sizeBytes: Long,
    private val openStream: () -> InputStream,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = OCTET_STREAM

    // -1 (size unknown) makes OkHttp send it chunked, which the gateway pipes
    // to disk exactly the same way.
    override fun contentLength(): Long = sizeBytes

    override fun writeTo(sink: BufferedSink) {
        var sent = 0L
        var lastPercent = -1
        // Opened per attempt, not once: OkHttp may write the body again when it
        // retries a request, and a consumed stream would upload nothing.
        openStream().use { input ->
            val buffer = ByteArray(UPLOAD_CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                sent += read
                if (sizeBytes <= 0) continue
                val percent = (sent * 100L / sizeBytes).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent / 100f)
                }
            }
        }
        onProgress(1f)
    }

    private companion object {
        const val UPLOAD_CHUNK_SIZE = 64 * 1024
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
