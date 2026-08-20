package com.ahmedgamil.wakili.feature.files

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.common.Toaster
import com.ahmedgamil.wakili.core.data.repository.DeleteFileOutcome
import com.ahmedgamil.wakili.core.data.repository.FileRepository
import com.ahmedgamil.wakili.core.data.repository.FileDownloader
import com.ahmedgamil.wakili.core.data.repository.MediaClipboard
import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.model.FileSource
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Source filter over the registry — the web Files page's All / By you / By agent chips. */
enum class FileFilter(val source: FileSource?) {
    ALL(null),
    USER(FileSource.USER),
    AGENT(FileSource.AGENT),
}

data class FilesUiState(
    val loading: Boolean = true,
    /** Newest-first (the registry is append-ordered; the load reverses it). */
    val files: List<FileEntry> = emptyList(),
    val filter: FileFilter = FileFilter.ALL,
    /** Tokens mid-delete: red indicator until the server confirms. */
    val deleting: Set<String> = emptySet(),
) {
    private val filtered: List<FileEntry>
        get() = filter.source?.let { s -> files.filter { it.source == s } } ?: files

    val images: List<FileEntry> get() = filtered.filter { it.image }
    val documents: List<FileEntry> get() = filtered.filter { !it.image }
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val fileDownloader: FileDownloader,
    private val mediaClipboard: MediaClipboard,
    private val toaster: Toaster,
    private val connection: GatewayConnection,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val files = runCatching { fileRepository.files() }.getOrDefault(emptyList())
            _uiState.update { it.copy(loading = false, files = files.reversed()) }
        }
    }

    fun setFilter(filter: FileFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /** File URLs are capability URLs (no auth header needed) — just absolutize. */
    fun absoluteUrl(file: FileEntry): String {
        val base = connection.profile.value?.baseUrl.orEmpty().trimEnd('/')
        return if (file.url.startsWith("http")) file.url else base + file.url
    }

    /** Hands the file to the system DownloadManager (visible notification). */
    fun download(file: FileEntry) {
        fileDownloader.enqueue(absoluteUrl(file), file.name)
    }

    /** Long-press → Copy: image pixels on the clipboard, or the link. */
    fun copy(file: FileEntry) {
        viewModelScope.launch { mediaClipboard.copy(absoluteUrl(file), file.name, file.image) }
    }

    /**
     * Confirmed delete (web Files page): red spinner until the server answers,
     * then the row goes NOW — the background refetch keeps the list
     * authoritative. Failure clears the spinner and says why.
     */
    fun delete(file: FileEntry) {
        if (file.token in _uiState.value.deleting) return
        _uiState.update { it.copy(deleting = it.deleting + file.token) }
        viewModelScope.launch {
            val outcome = fileRepository.deleteFile(file.token)
            if (outcome != DeleteFileOutcome.DELETED) {
                toaster.show(context.getString(R.string.files_delete_failed))
                _uiState.update { it.copy(deleting = it.deleting - file.token) }
                return@launch
            }
            toaster.show(context.getString(R.string.files_deleted, file.name))
            _uiState.update {
                it.copy(
                    deleting = it.deleting - file.token,
                    files = it.files.filterNot { f -> f.token == file.token },
                )
            }
            runCatching { fileRepository.files() }
                .onSuccess { fresh -> _uiState.update { it.copy(files = fresh.reversed()) } }
        }
    }
}
