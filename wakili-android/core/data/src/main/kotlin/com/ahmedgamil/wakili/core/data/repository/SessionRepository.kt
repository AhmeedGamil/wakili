package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.model.SessionDetail
import com.ahmedgamil.wakili.core.model.SessionSummary
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.CreateSessionBody
import com.ahmedgamil.wakili.core.network.dto.PatchSessionBody
import com.ahmedgamil.wakili.core.network.dto.PermissionAnswerBody
import com.ahmedgamil.wakili.core.network.dto.SendAttachmentBody
import com.ahmedgamil.wakili.core.network.dto.SendMessageBody
import com.ahmedgamil.wakili.core.network.dto.toDetail
import com.ahmedgamil.wakili.core.network.dto.toSummary
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

sealed interface SendResult {
    /** 202 — the turn is running. */
    data object Accepted : SendResult

    /** 409 — a turn is already in flight (the web client queues on this). */
    data object Busy : SendResult
}

data class SendAttachment(
    val name: String,
    val path: String,
    val url: String?,
)

/**
 * Sessions and turns. Phase 3 adds the Room cache in front; the surface stays.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val api: GatewayApi,
) {

    suspend fun sessions(): List<SessionSummary> =
        api.sessions().map { it.toSummary() }

    suspend fun detail(id: String): SessionDetail =
        api.session(id).toDetail()

    suspend fun create(agentId: String = "claude", cwd: String? = null): SessionSummary =
        api.createSession(CreateSessionBody(agentId = agentId, cwd = cwd)).toSummary()

    suspend fun rename(id: String, title: String) {
        api.patchSession(id, PatchSessionBody(title = title))
    }

    suspend fun delete(id: String) {
        api.deleteSession(id)
    }

    suspend fun send(
        id: String,
        text: String,
        controls: Map<String, String>? = null,
        agentId: String? = null,
        attachments: List<SendAttachment> = emptyList(),
    ): SendResult {
        val response = api.sendMessage(
            id,
            SendMessageBody(
                text = text,
                controls = controls,
                agentId = agentId,
                attachments = attachments
                    .takeIf { it.isNotEmpty() }
                    ?.map { SendAttachmentBody(name = it.name, path = it.path, url = it.url) },
            ),
        )
        return when {
            response.code() == 409 -> SendResult.Busy
            response.isSuccessful -> SendResult.Accepted
            else -> throw HttpException(response)
        }
    }

    suspend fun stop(id: String) {
        api.stop(id)
    }

    /** decision: "allow" | "deny" | "allow_session" (Always). */
    suspend fun answerPermission(sessionId: String, cardId: String, decision: String, tool: String) {
        api.answerPermission(
            sessionId,
            PermissionAnswerBody(id = cardId, decision = decision, tool = tool),
        )
    }

    suspend fun answerQuestion(sessionId: String, cardId: String, answer: String) {
        api.answerPermission(
            sessionId,
            PermissionAnswerBody(id = cardId, answer = answer),
        )
    }
}
