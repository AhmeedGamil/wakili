package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.DeleteUploadBody
import com.ahmedgamil.wakili.core.network.dto.UploadBody
import com.ahmedgamil.wakili.core.network.dto.toModel
import javax.inject.Inject
import javax.inject.Singleton

data class UploadedFile(val name: String, val path: String, val url: String)

/** File registry + uploads — the web FilesPage and Composer attachments. */
@Singleton
class FileRepository @Inject constructor(
    private val api: GatewayApi,
) {
    suspend fun files(): List<FileEntry> = api.files().map { it.toModel() }

    suspend fun upload(name: String, bytes: ByteArray, sessionId: String?): UploadedFile {
        val result = api.upload(
            UploadBody(
                name = name,
                dataBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
                sessionId = sessionId,
            ),
        )
        return UploadedFile(name = result.name, path = result.path, url = result.url)
    }

    suspend fun deleteUpload(path: String) {
        api.deleteUpload(DeleteUploadBody(path))
    }
}
