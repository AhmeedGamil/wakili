package com.ahmedgamil.wakili.core.data.repository

import com.ahmedgamil.wakili.core.common.Dispatcher
import com.ahmedgamil.wakili.core.common.WakiliDispatchers
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.ScheduledJob
import com.ahmedgamil.wakili.core.model.SessionDetail
import com.ahmedgamil.wakili.core.model.SessionSummary
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.api.GatewayConnection
import com.ahmedgamil.wakili.core.network.dto.AttachmentDto
import com.ahmedgamil.wakili.core.network.dto.CreateSessionBody
import com.ahmedgamil.wakili.core.network.dto.HandoffBody
import com.ahmedgamil.wakili.core.network.dto.MessageDto
import com.ahmedgamil.wakili.core.network.dto.PatchScheduleBody
import com.ahmedgamil.wakili.core.network.dto.PatchSessionBody
import com.ahmedgamil.wakili.core.network.dto.SessionDto
import com.ahmedgamil.wakili.core.network.dto.PermissionAnswerBody
import com.ahmedgamil.wakili.core.network.dto.ScheduleBody
import com.ahmedgamil.wakili.core.network.dto.SendAttachmentBody
import com.ahmedgamil.wakili.core.network.dto.SendMessageBody
import com.ahmedgamil.wakili.core.network.dto.toDetail
import com.ahmedgamil.wakili.core.network.dto.toDto
import com.ahmedgamil.wakili.core.network.dto.toModel
import com.ahmedgamil.wakili.core.network.dto.toSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import retrofit2.HttpException

sealed interface SendResult {
    /** 202 — the turn is running. */
    data class Accepted(val alreadyAccepted: Boolean = false) : SendResult

    /** 409 — a turn is already in flight (the web client queues on this). */
    data object Busy : SendResult
}

data class SendAttachment(
    val name: String,
    val path: String,
    val url: String?,
)

/** Agent handoff: the fresh session for the new agent + the transcript file to attach. */
data class HandoffResult(
    val session: SessionSummary,
    val file: SendAttachment,
)

/**
 * Sessions and turns, with a last-known-good snapshot cache in front: reads
 * come from the network and refresh the cache; when the gateway is
 * unreachable the cache answers instead, so chats stay browsable offline.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val api: GatewayApi,
    private val cache: SessionCache,
    private val connection: GatewayConnection,
    @Dispatcher(WakiliDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SessionDto.serializer())
    private val listCacheMutex = Mutex()

    /**
     * Serializes every read-modify-write of a session's detail cache. Fetch
     * merges, send folds and turn folds all race one another; without this a
     * refresh that snapshotted the cache BEFORE a send landed wrote its merge
     * over the folded message — your own message vanished from the reopened
     * chat until the next fetch ("sometimes my message not shown").
     */
    private val detailMutex = Mutex()

    // Pinged when this client changes the session list outside the usual
    // turn events (e.g. a handoff created a chat) — the sidebar re-fetches.
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val invalidations: SharedFlow<Unit> = _invalidations

    /** Fast cache read used before a network refresh to paint the sidebar. */
    suspend fun cachedSessions(): List<SessionSummary>? = cachedSessionDtos()?.map { it.toSummary() }

    /** Authoritative network refresh. A successful response replaces the snapshot. */
    suspend fun refreshSessions(): List<SessionSummary> {
        val dtos = api.sessions()
        listCacheMutex.withLock { writeSessionDtos(dtos) }
        return dtos.map { it.toSummary() }
    }

    /** Cache-first detail used for instant/offline chat opening. */
    suspend fun cachedDetail(id: String): SessionDetail? = cachedDetailDto(id)?.toDetail()

    /**
     * Authoritative detail refresh; callers decide whether stale UI stays visible
     * on failure. Reconciled against the cached copy (the web client's session
     * cache): `since` lets the server answer `{ unchanged }` (~60 bytes) and
     * `after` a messages delta instead of the full transcript; the merged result
     * replaces the cache entry, so the cache never lies.
     */
    suspend fun refreshDetail(id: String): SessionDetail {
        // Summary-shaped cache entries (offline mutations) hold no transcript —
        // they can't anchor a delta, so they take the full fetch path.
        val cached = cachedDetailDto(id)?.takeIf { it.messages != null }
        // Provisional messages (folded from the live stream, see
        // appendProvisionalTurn) are not part of the server's numbering: the
        // cursor anchors on the confirmed prefix so the server's copy of the
        // same turn replaces them instead of landing after them.
        val confirmed = cached?.messages?.filterNot { it.provisional == true }
        val conditional = api.session(id, since = cached?.updatedAt, after = confirmed?.size)
        // A conforming gateway only returns `unchanged` when `since` was sent.
        // Refetch if a stale/malformed local cache disappeared between deciding
        // the request and merging it; never persist the metadata-less stub.
        val dto = if (conditional.unchanged && cached == null) {
            api.session(id, since = null, after = null)
        } else {
            conditional
        }
        // Merge and persist under the mutation lock, against a RE-READ copy:
        // anything folded while the fetch was on the wire (a sent message, a
        // completed turn) lives only in the cache, and merging against the
        // pre-fetch snapshot silently erased it.
        val merged = detailMutex.withLock {
            val current = cachedDetailDto(id)?.takeIf { it.messages != null } ?: cached
            val m = when {
                // Conditional stub: retain the complete cached transcript and
                // metadata, but adopt the current in-progress state carried by
                // the response. The route id remains authoritative because the
                // stub intentionally has no id of its own.
                dto.unchanged && current != null -> current.copy(
                    id = id,
                    updatedAt = dto.updatedAt.takeIf { it > 0 } ?: current.updatedAt,
                    busy = dto.busy,
                    pending = dto.pending,
                    parts = dto.parts,
                    activeClientMessageId = dto.activeClientMessageId,
                )
                // Delta: the server sent only the messages past our cursor
                // (computed from the PRE-fetch snapshot, which anchored it).
                dto.delta && cached != null ->
                    dto.copy(
                        id = dto.id.ifBlank { id },
                        messages = withSurvivingProvisionals(
                            server = confirmed.orEmpty().take(dto.after ?: 0) + dto.messages.orEmpty(),
                            current = current,
                            newFromServer = !dto.messages.isNullOrEmpty(),
                        ),
                    )
                else -> dto.copy(
                    id = dto.id.ifBlank { id },
                    messages = withSurvivingProvisionals(
                        server = dto.messages.orEmpty(),
                        current = current,
                        newFromServer = true,
                    ),
                )
            }.copy(unchanged = false, delta = false, after = null) // transient wire flags, never persisted
            cache.writeDetail(
                cacheKey(),
                id,
                withContext(ioDispatcher) { json.encodeToString(SessionDto.serializer(), m) },
            )
            m
        }
        updateCachedSummary(merged)
        return merged.toDetail()
    }

    /**
     * Server truth replaces provisional copies — but only the ones it actually
     * covers. A provisional folded while the fetch was on the wire (a send the
     * server snapshot predates, or a turn it hasn't saved yet) survives to the
     * next fetch instead of being erased.
     */
    private fun withSurvivingProvisionals(
        server: List<MessageDto>,
        current: SessionDto?,
        newFromServer: Boolean,
    ): List<MessageDto> {
        val tail = current?.messages.orEmpty().takeLastWhile { it.provisional == true }
        if (tail.isEmpty()) return server
        val survivors = tail.filter { p ->
            when {
                // A completed-turn fold: any new server message means the
                // server got past that turn — its own copy is in `server`.
                p.role != "user" -> !newFromServer &&
                    server.none { it.role == "assistant" && it.parts == p.parts }
                p.clientMessageId != null -> server.none { it.clientMessageId == p.clientMessageId }
                else -> server.none { it.role == "user" && it.text == p.text }
            }
        }
        return server + survivors
    }

    /**
     * Fold a turn that completed while its chat had no screen into the cached
     * transcript. The parts already reached this phone over the stream; with no
     * ChatViewModel alive nothing else persists them, and dropping them was the
     * "switch away during streaming loses the reply" bug. The message is marked
     * provisional: refreshDetail anchors its delta cursor on the confirmed
     * prefix, so the server's own copy of the turn replaces it on the next
     * successful fetch. Returns true when the cache now holds the turn (or
     * already did); false when there is no cached transcript to anchor it to.
     */
    suspend fun appendProvisionalTurn(id: String, parts: List<Part>): Boolean {
        if (parts.isEmpty()) return true
        val key = cacheKeyOrNull() ?: return false
        return detailMutex.withLock { appendProvisionalTurnLocked(key, id, parts) }
    }

    private suspend fun appendProvisionalTurnLocked(key: String, id: String, parts: List<Part>): Boolean {
        val cached = cachedDetailDto(id)?.takeIf { it.messages != null } ?: return false
        val msgs = cached.messages.orEmpty()
        // A send that raced this fold may already sit at the tail (see
        // cacheSentMessage) — the completed turn belongs BEFORE those user
        // messages, in the order the server will replay.
        val trailingSends = msgs.takeLastWhile { it.provisional == true && it.role == "user" }
        val head = msgs.dropLast(trailingSends.size)
        // Already reconciled (a detail refresh landed the server's copy first).
        val anchor = head.lastOrNull()
        if (anchor?.role == "assistant" && anchor.parts.orEmpty().mapNotNull { it.toModel() } == parts) {
            return true
        }
        val updated = cached.copy(
            messages = head +
                MessageDto(role = "assistant", parts = parts.map { it.toDto() }, provisional = true) +
                trailingSends,
            // The turn is over — never resurrect its live-turn copy beside the
            // folded message on the next open. updatedAt stays put on purpose:
            // it is the `since` cursor, and moving it could make the server
            // answer `unchanged` for a transcript we only hold provisionally.
            parts = null,
            busy = false,
            activeClientMessageId = null,
        )
        cache.writeDetail(
            key,
            id,
            withContext(ioDispatcher) { json.encodeToString(SessionDto.serializer(), updated) },
        )
        return true
    }

    suspend fun create(agentId: String = "claude", cwd: String? = null): SessionSummary {
        val dto = api.createSession(CreateSessionBody(agentId = agentId, cwd = cwd))
        cacheMutation(dto)
        return dto.toSummary()
    }

    suspend fun rename(id: String, title: String) {
        cacheMutation(api.patchSession(id, PatchSessionBody(title = title)))
    }

    /**
     * "Auto continue" — ON by default, and kept on the GATEWAY: a turn this
     * chat loses to the usage limit is re-driven by the gateway once quota
     * returns, while the phone sleeps. Only an explicit off is stored.
     */
    suspend fun setAutoContinue(id: String, on: Boolean) {
        cacheMutation(api.patchSession(id, PatchSessionBody(autoContinue = on)))
    }

    // ---- scheduled tasks (gateway-side, survive restarts) ----

    /** Returns the created job; the gateway also flips autoContinue on. */
    suspend fun schedule(
        id: String,
        text: String,
        at: Long,
        attachments: List<SendAttachment> = emptyList(),
    ): ScheduledJob {
        val r = api.schedule(
            id,
            ScheduleBody(
                text = text,
                at = at,
                attachments = attachments.map { SendAttachmentBody(name = it.name, path = it.path, url = it.url) },
            ),
        )
        r.session?.let { cacheMutation(it) }
        return r.job.toModel()
    }

    suspend fun scheduledJobs(id: String): List<ScheduledJob> =
        api.scheduledJobs(id).jobs.map { it.toModel() }

    /** Every pending task across every chat, soonest first (Scheduled tasks page). */
    suspend fun allScheduledJobs(): List<ScheduledJob> =
        api.allScheduledJobs().jobs.map { it.toModel() }

    /** Edit a pending task's text/time; its attachments stay as scheduled. */
    suspend fun updateScheduled(jobId: String, text: String, at: Long): ScheduledJob =
        api.patchScheduled(jobId, PatchScheduleBody(text = text, at = at)).job.toModel()

    suspend fun cancelScheduled(jobId: String) {
        api.cancelScheduled(jobId)
    }

    /**
     * Persist the picker state (agent + controls) the moment it changes — not
     * just at send time (web persistControls): a choice made without sending
     * afterwards must survive the next session switch, and the gateway reads
     * the saved permissionMode per permission card.
     */
    suspend fun saveControls(id: String, agentId: String, controls: Map<String, String>) {
        cacheMutation(api.patchSession(id, PatchSessionBody(agentId = agentId, controls = controls)))
    }

    suspend fun delete(id: String) {
        api.deleteSession(id)
        val key = cacheKey()
        listCacheMutex.withLock {
            val remaining = cachedSessionDtos().orEmpty().filterNot { it.id == id }
            writeSessionDtos(remaining)
        }
        cache.removeDetail(key, id)
    }

    suspend fun send(
        id: String,
        text: String,
        controls: Map<String, String>? = null,
        agentId: String? = null,
        attachments: List<SendAttachment> = emptyList(),
        clientMessageId: String? = null,
    ): SendResult {
        val response = api.sendMessage(
            id,
            SendMessageBody(
                text = text,
                controls = controls,
                agentId = agentId,
                clientMessageId = clientMessageId,
                attachments = attachments
                    .takeIf { it.isNotEmpty() }
                    ?.map { SendAttachmentBody(name = it.name, path = it.path, url = it.url) },
            ),
        )
        return when {
            response.code() == 409 -> SendResult.Busy
            response.isSuccessful -> {
                // The gateway persisted this message at turn start, but the
                // cache only learns of it on the next successful detail fetch.
                // Until then the screen's optimistic copy is the ONLY local
                // one — leave the chat and come back and your own message was
                // missing until a network round trip landed. Fold it in now
                // (provisional; the server's copy replaces it), best effort.
                runCatching { cacheSentMessage(id, text, attachments, clientMessageId) }
                SendResult.Accepted(
                    alreadyAccepted = response.body()?.duplicate == true,
                )
            }
            else -> throw HttpException(response)
        }
    }

    private suspend fun cacheSentMessage(
        id: String,
        text: String,
        attachments: List<SendAttachment>,
        clientMessageId: String?,
    ) = detailMutex.withLock {
        val key = cacheKeyOrNull() ?: return@withLock
        val cached = cachedDetailDto(id)?.takeIf { it.messages != null } ?: return@withLock
        // A retry the server already accepted (or a fetch that landed first).
        if (clientMessageId != null && cached.messages.orEmpty().any { it.clientMessageId == clientMessageId }) {
            return
        }
        val message = MessageDto(
            role = "user",
            text = text,
            attachments = attachments.takeIf { it.isNotEmpty() }?.map {
                AttachmentDto(name = it.name, url = it.url.orEmpty(), image = IMAGE_NAME.containsMatchIn(it.name))
            },
            clientMessageId = clientMessageId,
            provisional = true,
        )
        val updated = cached.copy(messages = cached.messages.orEmpty() + message, busy = true)
        cache.writeDetail(
            key,
            id,
            withContext(ioDispatcher) { json.encodeToString(SessionDto.serializer(), updated) },
        )
    }

    suspend fun stop(id: String) {
        api.stop(id)
    }

    /**
     * Switching agent on a non-empty chat can't carry the native thread across
     * (resume ids are per-agent): the server exports the transcript to a handoff
     * file and creates a NEW session for the new agent in the same folder.
     */
    suspend fun handoff(id: String, agentId: String): HandoffResult {
        val dto = api.handoff(id, HandoffBody(agentId = agentId))
        cacheMutation(dto.session)
        _invalidations.tryEmit(Unit)
        return HandoffResult(
            session = dto.session.toSummary(),
            file = SendAttachment(name = dto.file.name, path = dto.file.path, url = dto.file.url),
        )
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

    private fun cacheKey(): String = connection.require().token

    // Cache reads can run before any gateway is configured (fresh install: the
    // sidebar's ViewModel spins up behind the connect gate) — no host means no
    // cache namespace, so they answer null instead of require() throwing.
    private fun cacheKeyOrNull(): String? = connection.profile.value?.token

    // Cached transcripts run to megabytes; (de)serializing them on the callers'
    // dispatcher (viewModelScope = Main) froze the UI at chat open.
    private suspend fun cachedSessionDtos(): List<SessionDto>? {
        val key = cacheKeyOrNull() ?: return null
        val cached = cache.readSessions(key) ?: return null
        return withContext(ioDispatcher) {
            runCatching { json.decodeFromString(listSerializer, cached) }.getOrNull()
        }
    }

    private suspend fun cachedDetailDto(id: String): SessionDto? {
        val key = cacheKeyOrNull() ?: return null
        val cached = cache.readDetail(key, id) ?: return null
        return withContext(ioDispatcher) {
            runCatching { json.decodeFromString(SessionDto.serializer(), cached) }.getOrNull()
        }
    }

    private suspend fun writeSessionDtos(dtos: List<SessionDto>) {
        cache.writeSessions(
            cacheKey(),
            withContext(ioDispatcher) {
                json.encodeToString(listSerializer, dtos.map { it.asSummaryDto() })
            },
        )
    }

    private suspend fun updateCachedSummary(dto: SessionDto) {
        listCacheMutex.withLock {
            val updated = (listOf(dto.asSummaryDto()) + cachedSessionDtos().orEmpty()
                .filterNot { it.id == dto.id })
                .sortedByDescending(SessionDto::updatedAt)
            writeSessionDtos(updated)
        }
    }

    /** Keeps mutations visible offline without replacing a full cached transcript with a summary. */
    private suspend fun cacheMutation(dto: SessionDto) {
        updateCachedSummary(dto)
        detailMutex.withLock {
            val current = cachedDetailDto(dto.id)
            // Local capture: dto.messages is a cross-module public property, so
            // Kotlin won't smart-cast it to non-null after the null check.
            val serverMessages = dto.messages
            val detail = if (serverMessages != null) {
                dto.copy(
                    messages = withSurvivingProvisionals(
                        server = serverMessages,
                        current = current,
                        newFromServer = true,
                    ),
                )
            } else {
                current?.copy(
                    title = dto.title,
                    agentId = dto.agentId,
                    model = dto.model,
                    cwd = dto.cwd,
                    effectiveCwd = dto.effectiveCwd,
                    updatedAt = dto.updatedAt,
                    busy = dto.busy,
                    pending = dto.pending,
                    autoAllow = dto.autoAllow,
                ) ?: dto
            }
            cache.writeDetail(
                cacheKey(),
                dto.id,
                withContext(ioDispatcher) { json.encodeToString(SessionDto.serializer(), detail) },
            )
        }
    }

    private companion object {
        /** Mirrors the server's IMG_RE — attachment thumbnails vs plain files. */
        val IMAGE_NAME = Regex("\\.(png|jpe?g|gif|webp|svg|bmp|heic)$", RegexOption.IGNORE_CASE)
    }

    private fun SessionDto.asSummaryDto(): SessionDto = copy(
        messages = null,
        parts = null,
        activeClientMessageId = null,
        controls = null,
        allowedTools = null,
        resumeId = null,
    )
}
