package com.ahmedgamil.wakili.feature.files

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.data.repository.FileRepository
import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilesUiState(
    val loading: Boolean = true,
    val files: List<FileEntry> = emptyList(),
) {
    val images: List<FileEntry> get() = files.filter { it.image }
    val documents: List<FileEntry> get() = files.filter { !it.image }
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val connection: GatewayConnection,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val files = runCatching { fileRepository.files() }.getOrDefault(emptyList())
            _uiState.update { it.copy(loading = false, files = files.reversed()) }
        }
    }

    /** File URLs are capability URLs (no auth header needed) — just absolutize. */
    fun absoluteUrl(file: FileEntry): String {
        val base = connection.profile.value?.baseUrl.orEmpty().trimEnd('/')
        return if (file.url.startsWith("http")) file.url else base + file.url
    }

    /** Hands the file to the system DownloadManager (visible notification). */
    fun download(file: FileEntry) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching {
            manager.enqueue(
                DownloadManager.Request(Uri.parse(absoluteUrl(file)))
                    .setTitle(file.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.name),
            )
        }
    }
}
