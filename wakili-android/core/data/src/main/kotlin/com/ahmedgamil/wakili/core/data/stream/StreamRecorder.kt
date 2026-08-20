package com.ahmedgamil.wakili.core.data.stream

import com.ahmedgamil.wakili.core.data.di.AppScope
import com.ahmedgamil.wakili.core.model.GatewayEvent
import com.ahmedgamil.wakili.core.network.api.GatewayApi
import com.ahmedgamil.wakili.core.network.dto.DebugCounterBody
import com.ahmedgamil.wakili.core.network.dto.DebugEntryBody
import com.ahmedgamil.wakili.core.network.dto.DebugReportBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Stream flight recorder — the phone half of src/recorder.mjs.
 *
 * Deliberately NOT a logger. One `Log.d` per token would be the worst possible
 * instrument here: logcat rate-limits and silently DROPS lines under load, so
 * the burst we most need to see is the one least likely to be recorded, and the
 * per-delta string building lands on top of an already allocation-heavy reduce.
 * Instead lifecycle events go into a small in-memory ring and content deltas are
 * only COUNTED — one integer add each.
 *
 * Off unless the gateway says otherwise: the `connected` event carries `debug`,
 * mirroring WAKILI_DEBUG_STREAM on the laptop, so there is one switch for all
 * three sides rather than a toggle to find on each device. When an anomaly shows
 * up (currently: a snapshot stamped older than state already applied) the ring
 * uploads itself, so a single read of the laptop's /api/debug/stream shows the
 * phone's and the gateway's view of the same turn side by side.
 */
@Singleton
class StreamRecorder @Inject constructor(
    private val api: GatewayApi,
    @AppScope private val scope: CoroutineScope,
) {

    private val clientId = "and-" + UUID.randomUUID().toString().take(8)
    private val lock = Any()
    private val ring = ArrayDeque<DebugEntryBody>()
    private val counters = mutableMapOf<String, Counter>()

    @Volatile private var enabled = false
    private var lastUploadAt = 0L

    /** Driven by the gateway's `connected` event. */
    fun enable(on: Boolean) {
        if (enabled == on) return
        enabled = on
        if (!on) synchronized(lock) { ring.clear(); counters.clear() }
    }

    /** One stream event as the repository sees it. Deltas counted, rest ringed. */
    fun event(event: GatewayEvent.SessionEvent) {
        if (!enabled) return
        synchronized(lock) {
            val c = counters.getOrPut(event.sessionId) { Counter() }
            event.seq?.let { c.lastSeq = it }
            when (event) {
                is GatewayEvent.TextDelta -> { c.deltas++; c.chars += event.text.length; return }
                is GatewayEvent.ThinkingDelta -> { c.deltas++; c.chars += event.text.length; return }
                is GatewayEvent.TurnStart -> { c.turns++; c.deltas = 0; c.chars = 0 }
                else -> Unit
            }
            push(
                DebugEntryBody(
                    at = System.currentTimeMillis(),
                    kind = "event",
                    sessionId = event.sessionId,
                    ev = event::class.simpleName,
                    seq = event.seq,
                    parts = (event as? GatewayEvent.Snapshot)?.parts?.size,
                    busy = (event as? GatewayEvent.Snapshot)?.busy,
                    client = (event as? GatewayEvent.Snapshot)?.client,
                    deltas = if (event is GatewayEvent.TurnEnd) c.deltas else null,
                    chars = if (event is GatewayEvent.TurnEnd) c.chars else null,
                ),
            )
        }
    }

    /** Anything worth a ring entry that isn't a stream event (opens, resyncs). */
    fun note(
        kind: String,
        sessionId: String? = null,
        seq: Long? = null,
        detail: String? = null,
    ) {
        if (!enabled) return
        synchronized(lock) {
            push(
                DebugEntryBody(
                    at = System.currentTimeMillis(),
                    kind = kind,
                    sessionId = sessionId,
                    seq = seq,
                    detail = detail,
                ),
            )
        }
    }

    /**
     * Something arrived that shouldn't have. Recorded, then uploaded — rate
     * limited, and never allowed to throw into the stream collector.
     */
    fun anomaly(
        kind: String,
        sessionId: String,
        snapshotSeq: Long?,
        appliedSeq: Long?,
        parts: Int?,
        client: String?,
    ) {
        if (!enabled) return
        val report = synchronized(lock) {
            push(
                DebugEntryBody(
                    at = System.currentTimeMillis(),
                    kind = "ANOMALY:$kind",
                    sessionId = sessionId,
                    seq = snapshotSeq,
                    appliedSeq = appliedSeq,
                    parts = parts,
                    client = client,
                ),
            )
            val now = System.currentTimeMillis()
            if (now - lastUploadAt < UPLOAD_COOLDOWN_MILLIS) return
            lastUploadAt = now
            snapshotLocked()
        }
        scope.launch { runCatching { api.debugReport(report) } }
    }

    /** The ring as it stands, for an explicit upload or a test. */
    fun dump(): DebugReportBody = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked() = DebugReportBody(
        source = "android",
        clientId = clientId,
        now = System.currentTimeMillis(),
        entries = ring.toList(),
        counters = counters.mapValues {
            DebugCounterBody(it.value.deltas, it.value.chars, it.value.lastSeq, it.value.turns)
        },
    )

    private fun push(entry: DebugEntryBody) {
        ring.addLast(entry)
        while (ring.size > MAX_ENTRIES) ring.removeFirst()
    }

    private class Counter {
        var deltas = 0
        var chars = 0
        var lastSeq = 0L
        var turns = 0
    }

    private companion object {
        const val MAX_ENTRIES = 300
        const val UPLOAD_COOLDOWN_MILLIS = 30_000L
    }
}
