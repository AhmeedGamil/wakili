package com.ahmedgamil.wakili.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ahmedgamil.wakili.core.model.ConnectionProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wakili_connection")

/**
 * Persisted gateway connection — the Android twin of the web client's
 * localStorage `remoteAgentToken` (plus the base URL, which the browser gets
 * for free from location.origin).
 */
@Singleton
class ConnectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyToken = stringPreferencesKey("token")
    private val keyHosts = stringPreferencesKey("hosts")
    private val keyHostNames = stringPreferencesKey("host_names")

    val profile: Flow<ConnectionProfile?> = context.dataStore.data.map { prefs ->
        toProfile(prefs)
    }

    /** Every gateway ever connected to (the react-native saved-hosts list). */
    val hosts: Flow<List<ConnectionProfile>> = context.dataStore.data.map { prefs ->
        decodeHosts(prefs)
    }

    /** User-chosen display names, keyed by baseUrl. Absent = fall back to the
     *  derived "hostname-lan" name. Editable on the hosts page. */
    val hostNames: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        decodeNames(prefs)
    }

    suspend fun setHostName(baseUrl: String, name: String) {
        context.dataStore.edit { prefs ->
            val names = decodeNames(prefs).toMutableMap()
            val trimmed = name.trim()
            if (trimmed.isEmpty()) names.remove(baseUrl) else names[baseUrl] = trimmed
            prefs[keyHostNames] = encodeNames(names)
        }
    }

    suspend fun save(profile: ConnectionProfile) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = profile.baseUrl
            // The token is the only credential — Keystore-encrypted at rest.
            prefs[keyToken] = TokenCipher.encrypt(profile.token)
            // Connecting also remembers the host for the switcher (upsert).
            val others = decodeHosts(prefs).filterNot { it.baseUrl == profile.baseUrl }
            prefs[keyHosts] = encodeHosts(listOf(profile) + others)
        }
    }

    suspend fun removeHost(baseUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[keyHosts] = encodeHosts(decodeHosts(prefs).filterNot { it.baseUrl == baseUrl })
            // Drop its custom name too, so a re-added host starts fresh.
            val names = decodeNames(prefs).toMutableMap().apply { remove(baseUrl) }
            prefs[keyHostNames] = encodeNames(names)
        }
    }

    /** Forget the ACTIVE connection only — saved hosts stay for reconnecting. */
    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(keyBaseUrl)
            prefs.remove(keyToken)
        }
    }

    private fun toProfile(prefs: Preferences): ConnectionProfile? {
        val baseUrl = prefs[keyBaseUrl] ?: return null
        val token = prefs[keyToken]?.let(TokenCipher::decrypt) ?: return null
        return ConnectionProfile(baseUrl = baseUrl, token = token)
    }

    // One host per line, url and encrypted token split by U+0001 (neither can
    // contain it). No schema, no library — trivially forward-compatible.
    private fun decodeHosts(prefs: Preferences): List<ConnectionProfile> =
        prefs[keyHosts]?.lineSequence()?.mapNotNull { line ->
            val sep = line.indexOf(HOST_SEP)
            if (sep <= 0) return@mapNotNull null
            val token = TokenCipher.decrypt(line.substring(sep + 1)) ?: return@mapNotNull null
            ConnectionProfile(baseUrl = line.substring(0, sep), token = token)
        }?.toList().orEmpty()

    private fun encodeHosts(hosts: List<ConnectionProfile>): String =
        hosts.joinToString("\n") { it.baseUrl + HOST_SEP + TokenCipher.encrypt(it.token) }

    // Same line/U+0001 scheme as hosts: baseUrl U+0001 name (neither contains it).
    private fun decodeNames(prefs: Preferences): Map<String, String> =
        prefs[keyHostNames]?.lineSequence()?.mapNotNull { line ->
            val sep = line.indexOf(HOST_SEP)
            if (sep <= 0 || sep == line.length - 1) return@mapNotNull null
            line.substring(0, sep) to line.substring(sep + 1)
        }?.toMap().orEmpty()

    private fun encodeNames(names: Map<String, String>): String =
        names.entries.joinToString("\n") { it.key + HOST_SEP + it.value }

    private companion object {
        const val HOST_SEP = '\u0001'
    }
}
