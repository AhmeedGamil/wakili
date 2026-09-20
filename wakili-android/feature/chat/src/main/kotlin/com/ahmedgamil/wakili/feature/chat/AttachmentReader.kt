package com.ahmedgamil.wakili.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a picked content Uri into what an upload needs: a name, a size, and
 * whether it is an image.
 *
 * It deliberately does NOT read the bytes. Uploads stream straight from the Uri
 * (see FileRepository.upload), so picking a 40MB file costs nothing in heap —
 * reading it into a ByteArray first is what used to kill the app.
 */
class AttachmentReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Info(val name: String, val size: Long, val image: Boolean)

    /** Null when the Uri can't be resolved at all (revoked permission, gone). */
    suspend fun describe(uri: Uri): Info? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val type = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        val meta = queryMeta(uri)
        val name = meta?.first?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "file"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        val fullName = if (name.contains('.') || ext == null) name else "$name.$ext"
        // A stream that won't even open is worth catching here, at pick time,
        // rather than as a failed chip after the upload starts.
        val opens = runCatching { resolver.openInputStream(uri)?.use { true } }.getOrNull() ?: false
        if (!opens) return@withContext null
        Info(name = fullName, size = meta?.second ?: -1L, image = type.startsWith("image/"))
    }

    /**
     * A fresh stream over the picked Uri. Called per upload ATTEMPT — OkHttp
     * writes the body again when it retries a request, and a stream that was
     * already drained would send an empty file.
     */
    fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("cannot open $uri")

    /** DISPLAY_NAME + SIZE in one query; either column may be absent or null. */
    private fun queryMeta(uri: Uri): Pair<String?, Long?>? = runCatching {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
            name to size
        }
    }.getOrNull()
}
