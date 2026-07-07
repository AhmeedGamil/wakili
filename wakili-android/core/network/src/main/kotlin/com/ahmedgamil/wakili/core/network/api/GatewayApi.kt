package com.ahmedgamil.wakili.core.network.api

import com.ahmedgamil.wakili.core.network.dto.AgentDto
import com.ahmedgamil.wakili.core.network.dto.AutostartBody
import com.ahmedgamil.wakili.core.network.dto.AutostartDto
import com.ahmedgamil.wakili.core.network.dto.CreateFolderBody
import com.ahmedgamil.wakili.core.network.dto.CreateSessionBody
import com.ahmedgamil.wakili.core.network.dto.CreatedFolderDto
import com.ahmedgamil.wakili.core.network.dto.DeleteUploadBody
import com.ahmedgamil.wakili.core.network.dto.EndpointDto
import com.ahmedgamil.wakili.core.network.dto.ExecBody
import com.ahmedgamil.wakili.core.network.dto.ExecResultDto
import com.ahmedgamil.wakili.core.network.dto.FileEntryDto
import com.ahmedgamil.wakili.core.network.dto.FoldersDto
import com.ahmedgamil.wakili.core.network.dto.KeepAwakeBody
import com.ahmedgamil.wakili.core.network.dto.OkDto
import com.ahmedgamil.wakili.core.network.dto.PatchSessionBody
import com.ahmedgamil.wakili.core.network.dto.PermissionAnswerBody
import com.ahmedgamil.wakili.core.network.dto.PowerDto
import com.ahmedgamil.wakili.core.network.dto.ResyncBody
import com.ahmedgamil.wakili.core.network.dto.SendMessageBody
import com.ahmedgamil.wakili.core.network.dto.SessionDto
import com.ahmedgamil.wakili.core.network.dto.TermBody
import com.ahmedgamil.wakili.core.network.dto.UploadBody
import com.ahmedgamil.wakili.core.network.dto.UploadResultDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Typed mirror of every endpoint in server.mjs the phone client uses. */
interface GatewayApi {

    @GET("api/agents")
    suspend fun agents(): List<AgentDto>

    // ---- sessions ----

    @GET("api/sessions")
    suspend fun sessions(): List<SessionDto>

    @POST("api/sessions")
    suspend fun createSession(@Body body: CreateSessionBody): SessionDto

    @GET("api/sessions/{id}")
    suspend fun session(@Path("id") id: String): SessionDto

    @PATCH("api/sessions/{id}")
    suspend fun patchSession(@Path("id") id: String, @Body body: PatchSessionBody): SessionDto

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): OkDto

    // ---- messaging ----

    /** 202 = accepted, 409 = busy (queue client-side), 400 = empty. */
    @POST("api/sessions/{id}/messages")
    suspend fun sendMessage(@Path("id") id: String, @Body body: SendMessageBody): Response<OkDto>

    @POST("api/sessions/{id}/stop")
    suspend fun stop(@Path("id") id: String): OkDto

    @POST("api/sessions/{id}/exec")
    suspend fun exec(@Path("id") id: String, @Body body: ExecBody): ExecResultDto

    @POST("api/sessions/{id}/term")
    suspend fun term(@Path("id") id: String, @Body body: TermBody): ExecResultDto

    @POST("api/sessions/{id}/permission")
    suspend fun answerPermission(@Path("id") id: String, @Body body: PermissionAnswerBody): OkDto

    @POST("api/sessions/{id}/resync")
    suspend fun resync(@Path("id") id: String, @Body body: ResyncBody): OkDto

    // ---- environment ----

    @GET("api/endpoints")
    suspend fun endpoints(): List<EndpointDto>

    @GET("api/folders")
    suspend fun folders(@Query("path") path: String?): FoldersDto

    @POST("api/folders")
    suspend fun createFolder(@Body body: CreateFolderBody): CreatedFolderDto

    // ---- files ----

    @GET("api/files")
    suspend fun files(): List<FileEntryDto>

    @POST("api/upload")
    suspend fun upload(@Body body: UploadBody): UploadResultDto

    @POST("api/upload/delete")
    suspend fun deleteUpload(@Body body: DeleteUploadBody): OkDto

    // ---- device / power ----

    @GET("api/power")
    suspend fun power(): PowerDto

    @POST("api/lock-screen")
    suspend fun lockScreen(): OkDto

    @POST("api/screen-off")
    suspend fun screenOff(): OkDto

    @POST("api/lock-off")
    suspend fun lockOff(): OkDto

    @POST("api/keep-awake")
    suspend fun keepAwake(@Body body: KeepAwakeBody): PowerDto

    @GET("api/autostart")
    suspend fun autostart(): AutostartDto

    @POST("api/autostart")
    suspend fun setAutostart(@Body body: AutostartBody): AutostartDto
}
