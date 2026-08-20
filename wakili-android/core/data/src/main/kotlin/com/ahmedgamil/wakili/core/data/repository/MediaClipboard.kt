package com.ahmedgamil.wakili.core.data.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import com.ahmedgamil.wakili.core.common.Toaster
import com.ahmedgamil.wakili.core.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Long-press → Copy for media (web media.js copyMedia): an image puts REAL
 * pixels on the system clipboard — fetched into a cache file and exposed
 * through a FileProvider — so it pastes anywhere; anything else (or a failed
 * image fetch) copies the absolute link instead. Toasts mirror the web's.
 */
@Singleton
class MediaClipboard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toaster: Toaster,
) {

    /**
     * URL currently being fetched for the clipboard. Copying an image means a
     * network download that can take seconds on a phone link — the media card
     * showing this url overlays a progress indicator so the silence between
     * tap and toast doesn't read as "nothing happened".
     */
    private val _copying = MutableStateFlow<String?>(null)
    val copying: StateFlow<String?> = _copying.asStateFlow()

    suspend fun copy(url: String, name: String, image: Boolean) {
        _copying.value = url
        try {
            if (image && runCatching { copyImage(url, name) }.getOrDefault(false)) {
                toaster.show(context.getString(R.string.data_copied_image))
                return
            }
            runCatching { copyText(name.ifBlank { url }, url) }
                .onSuccess { toaster.show(context.getString(R.string.data_copied_link)) }
                .onFailure { toaster.show(context.getString(R.string.data_copy_failed)) }
        } finally {
            _copying.value = null
        }
    }

    private suspend fun copyImage(url: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val bytes = URL(url).openStream().use { it.readBytes() }
        if (bytes.isEmpty()) return@withContext false
        val dir = File(context.cacheDir, CLIP_DIR).apply { mkdirs() }
        // Previous copies are stale the moment a new one lands — drop them so
        // the cache never accumulates screenshots.
        dir.listFiles()?.forEach { it.delete() }
        val ext = name.substringAfterLast('.', "").ifEmpty { "png" }
        val file = File(dir, "clip-${System.currentTimeMillis()}.$ext")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        clipboard().setPrimaryClip(ClipData.newUri(context.contentResolver, name, uri))
        true
    }

    private fun copyText(label: String, text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private companion object {
        const val CLIP_DIR = "clipboard"
        const val AUTHORITY_SUFFIX = ".clipboard"
    }
}
