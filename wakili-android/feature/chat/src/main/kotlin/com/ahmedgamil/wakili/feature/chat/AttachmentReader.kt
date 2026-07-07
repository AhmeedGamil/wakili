package com.ahmedgamil.wakili.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves a picked content Uri into upload-ready data, off the UI layer. */
class AttachmentReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Result(val name: String, val bytes: ByteArray, val image: Boolean)

    suspend fun read(uri: Uri): Result? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        val name = displayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "file"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        val fullName = if (name.contains('.') || ext == null) name else "$name.$ext"
        Result(name = fullName, bytes = bytes, image = type.startsWith("image/"))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
