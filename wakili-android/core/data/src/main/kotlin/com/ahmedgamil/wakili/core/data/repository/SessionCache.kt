package com.ahmedgamil.wakili.core.data.repository

import android.content.Context
import android.util.AtomicFile
import com.ahmedgamil.wakili.core.common.Dispatcher
import com.ahmedgamil.wakili.core.common.WakiliDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Last-known-good session snapshots. Entries are isolated by gateway
 * credential, held in memory after the first read, and atomically persisted
 * for process-death and offline recovery. Detail files are LRU-bounded so a
 * long-running install cannot grow the cache without limit.
 */
@Singleton
class SessionCache @Inject constructor(
    @ApplicationContext context: Context,
    @Dispatcher(WakiliDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val root = File(context.filesDir, CACHE_DIRECTORY)
    private val memory = ConcurrentHashMap<String, String>()
    private val diskMutex = Mutex()

    suspend fun readSessions(gatewayKey: String): String? =
        read(gatewayKey, SESSIONS_FILE)

    suspend fun writeSessions(gatewayKey: String, json: String) =
        write(gatewayKey, SESSIONS_FILE, json)

    suspend fun readDetail(gatewayKey: String, id: String): String? =
        read(gatewayKey, detailFileName(id))

    suspend fun writeDetail(gatewayKey: String, id: String, json: String) {
        write(gatewayKey, detailFileName(id), json)
        pruneDetails(gatewayKey)
    }

    suspend fun removeDetail(gatewayKey: String, id: String) = withContext(ioDispatcher) {
        diskMutex.withLock {
            val fileName = detailFileName(id)
            memory.remove(memoryKey(gatewayKey, fileName))
            runCatching { file(gatewayKey, fileName).delete() }
        }
    }

    private suspend fun read(gatewayKey: String, fileName: String): String? =
        withContext(ioDispatcher) {
            val key = memoryKey(gatewayKey, fileName)
            memory[key]?.let { return@withContext it }
            diskMutex.withLock {
                memory[key]?.let { return@withLock it }
                val target = file(gatewayKey, fileName)
                val value = runCatching {
                    AtomicFile(target).openRead().bufferedReader().use { it.readText() }
                }.getOrNull()
                if (value != null) {
                    memory[key] = value
                    runCatching { target.setLastModified(System.currentTimeMillis()) }
                }
                value
            }
        }

    private suspend fun write(gatewayKey: String, fileName: String, json: String) =
        withContext(ioDispatcher) {
            diskMutex.withLock {
                val atomic = AtomicFile(file(gatewayKey, fileName))
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = atomic.startWrite()
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    atomic.finishWrite(stream)
                    memory[memoryKey(gatewayKey, fileName)] = json
                } catch (_: Exception) {
                    stream?.let(atomic::failWrite)
                }
            }
        }

    private suspend fun pruneDetails(gatewayKey: String) = withContext(ioDispatcher) {
        diskMutex.withLock {
            val details = gatewayDirectory(gatewayKey).listFiles { candidate ->
                candidate.isFile &&
                    candidate.name.startsWith(DETAIL_PREFIX) &&
                    candidate.name.endsWith(JSON_SUFFIX)
            }?.sortedByDescending(File::lastModified).orEmpty()
            var totalBytes = details.sumOf(File::length)
            details.forEachIndexed { index, candidate ->
                if (index >= MAX_DETAIL_FILES || totalBytes > MAX_DETAIL_BYTES) {
                    totalBytes -= candidate.length()
                    memory.remove(memoryKey(gatewayKey, candidate.name))
                    runCatching { candidate.delete() }
                }
            }
        }
    }

    private fun file(gatewayKey: String, fileName: String): File =
        File(gatewayDirectory(gatewayKey), fileName)

    private fun gatewayDirectory(gatewayKey: String): File =
        File(root, digest(gatewayKey).take(GATEWAY_HASH_LENGTH)).apply { mkdirs() }

    private fun detailFileName(id: String): String {
        val readable = id.replace(NON_FILE_NAME, "_").take(DETAIL_NAME_LENGTH)
        return "$DETAIL_PREFIX$readable-${digest(id).take(DETAIL_HASH_LENGTH)}$JSON_SUFFIX"
    }

    private fun memoryKey(gatewayKey: String, fileName: String): String =
        "${digest(gatewayKey).take(GATEWAY_HASH_LENGTH)}/$fileName"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val CACHE_DIRECTORY = "session-cache-v2"
        const val SESSIONS_FILE = "sessions.json"
        const val DETAIL_PREFIX = "session-"
        const val JSON_SUFFIX = ".json"
        const val GATEWAY_HASH_LENGTH = 24
        const val DETAIL_HASH_LENGTH = 12
        const val DETAIL_NAME_LENGTH = 48
        const val MAX_DETAIL_FILES = 200
        const val MAX_DETAIL_BYTES = 64L * 1024L * 1024L
        val NON_FILE_NAME = Regex("[^\\w-]")
    }
}
