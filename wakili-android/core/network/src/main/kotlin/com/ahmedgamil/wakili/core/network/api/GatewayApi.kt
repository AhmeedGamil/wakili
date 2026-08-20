package com.ahmedgamil.wakili.core.network.api

import com.ahmedgamil.wakili.core.network.dto.AgentDto
import com.ahmedgamil.wakili.core.network.dto.AutostartBody
import com.ahmedgamil.wakili.core.network.dto.AutostartDto
import com.ahmedgamil.wakili.core.network.dto.CreateFolderBody
import com.ahmedgamil.wakili.core.network.dto.HostDto
import com.ahmedgamil.wakili.core.network.dto.CreateSessionBody
import com.ahmedgamil.wakili.core.network.dto.CreatedFolderDto
import com.ahmedgamil.wakili.core.network.dto.DebugReportBody
import com.ahmedgamil.wakili.core.network.dto.DeleteUploadBody
import com.ahmedgamil.wakili.core.network.dto.EndpointDto
import com.ahmedgamil.wakili.core.network.dto.ExecBody
import com.ahmedgamil.wakili.core.network.dto.ExecResultDto
import com.ahmedgamil.wakili.core.network.dto.FileEntryDto
import com.ahmedgamil.wakili.core.network.dto.FoldersDto
import com.ahmedgamil.wakili.core.network.dto.HandoffBody
import com.ahmedgamil.wakili.core.network.dto.HandoffDto
import com.ahmedgamil.wakili.core.network.dto.KeepAwakeBody
import com.ahmedgamil.wakili.core.network.dto.OkDto
import com.ahmedgamil.wakili.core.network.dto.PatchScheduleBody
import com.ahmedgamil.wakili.core.network.dto.PatchSessionBody
import com.ahmedgamil.wakili.core.network.dto.PermissionAnswerBody
import com.ahmedgamil.wakili.core.network.dto.PowerDto
import com.ahmedgamil.wakili.core.network.dto.ResyncBody
import com.ahmedgamil.wakili.core.network.dto.ScheduleBody
import com.ahmedgamil.wakili.core.network.dto.ScheduleListDto
import com.ahmedgamil.wakili.core.network.dto.SchedulePatchDto
import com.ahmedgamil.wakili.core.network.dto.SchedulePostDto
import com.ahmedgamil.wakili.core.network.dto.SendMessageBody
import com.ahmedgamil.wakili.core.network.dto.SessionDto
import com.ahmedgamil.wakili.core.network.dto.TermBody
import com.ahmedgamil.wakili.core.network.dto.UploadResultDto
import okhttp3.RequestBody
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

    /**
     * Conditional/delta GET: `since` = the cached updatedAt (server answers a
     * tiny `{ unchanged }` stub when nothing moved); `after` = how many
     * messages we already hold (server sends only the ones past that cursor,
     * flagged `{ delta, after }`). Null omits the query — a full fetch.
     */
    @GET("api/sessions/{id}")
    suspend fun session(
        @Path("id") id: String,
        @Query("since") since: Long? = null,
        @Query("after") after: Int? = null,
    ): SessionDto

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

    /** Continue with a different agent: exports the transcript, returns a fresh session. */
    @POST("api/sessions/{id}/handoff")
    suspend fun handoff(@Path("id") id: String, @Body body: HandoffBody): HandoffDto

    // ---- scheduled tasks ----

    /** One-shot task at epoch-millis; also flips the chat's autoContinue on. */
    @POST("api/sessions/{id}/schedule")
    suspend fun schedule(@Path("id") id: String, @Body body: ScheduleBody): SchedulePostDto

    @GET("api/sessions/{id}/schedule")
    suspend fun scheduledJobs(@Path("id") id: String): ScheduleListDto

    /** Every pending task across every chat (rows carry the chat's title). */
    @GET("api/schedule")
    suspend fun allScheduledJobs(): ScheduleListDto

    @PATCH("api/schedule/{jobId}")
    suspend fun patchScheduled(@Path("jobId") jobId: String, @Body body: PatchScheduleBody): SchedulePatchDto

    @DELETE("api/schedule/{jobId}")
    suspend fun cancelScheduled(@Path("jobId") jobId: String): OkDto

    // ---- environment ----

    @GET("api/endpoints")
    suspend fun endpoints(): List<EndpointDto>

    @GET("api/host")
    suspend fun host(): HostDto

    @GET("api/folders")
    suspend fun folders(@Query("path") path: String?): FoldersDto

    @POST("api/folders")
    suspend fun createFolder(@Body body: CreateFolderBody): CreatedFolderDto

    // ---- files ----

    @GET("api/files")
    suspend fun files(): List<FileEntryDto>

    /** Remove one file from the gateway's registry (web Files page delete). */
    @DELETE("api/files/{token}")
    suspend fun deleteFile(@Path("token") token: String): OkDto

    @POST("api/upload")
    suspend fun upload(@Body body: RequestBody): UploadResultDto

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

    @POST("api/shutdown")
    suspend fun shutdown(): OkDto

    @POST("api/keep-awake")
    suspend fun keepAwake(@Body body: KeepAwakeBody): PowerDto

    @GET("api/autostart")
    suspend fun autostart(): AutostartDto

    @POST("api/autostart")
    suspend fun setAutostart(@Body body: AutostartBody): AutostartDto

    // ---- diagnostics ----

    /** Upload this phone's stream flight recorder; the gateway drops it unless
     *  WAKILI_DEBUG_STREAM=1. Diagnostics only — never on the hot path. */
    @POST("api/debug/report")
    suspend fun debugReport(@Body body: DebugReportBody): OkDto
}
