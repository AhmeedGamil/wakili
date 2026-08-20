package com.ahmedgamil.wakili.core.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One native download path shared by chat cards, the image viewer, and Files. */
@Singleton
class FileDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun enqueue(url: String, name: String): Long? = runCatching {
        val source = Uri.parse(url).let { uri ->
            if (uri.getQueryParameter(DOWNLOAD_QUERY) == DOWNLOAD_VALUE) {
                uri
            } else {
                uri.buildUpon().appendQueryParameter(DOWNLOAD_QUERY, DOWNLOAD_VALUE).build()
            }
        }
        val fileName = URLUtil.guessFileName(source.toString(), null, null)
            .takeIf { name.isBlank() }
            ?: name.substringAfterLast('/').substringAfterLast('\\')
        val request = DownloadManager.Request(source)
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
    }.getOrNull()

    private companion object {
        const val DOWNLOAD_QUERY = "dl"
        const val DOWNLOAD_VALUE = "1"
    }
}
