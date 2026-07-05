package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.FolderListing
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.CreateFolderBody
import com.ahmedgamil.wakili.core.network.dto.toModel
import javax.inject.Inject
import javax.inject.Singleton

/** Laptop filesystem browsing for the project (cwd) picker. */
@Singleton
class FolderRepository @Inject constructor(
    private val api: GatewayApi,
) {
    /** Empty/null path lists the roots (home + drives). */
    suspend fun list(path: String?): FolderListing = api.folders(path).toModel()

    suspend fun create(parent: String, name: String): String = api.createFolder(CreateFolderBody(parent, name)).path
}
