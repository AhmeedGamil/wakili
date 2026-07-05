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

    val profile: Flow<ConnectionProfile?> = context.dataStore.data.map { prefs ->
        toProfile(prefs)
    }

    suspend fun save(profile: ConnectionProfile) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = profile.baseUrl
            prefs[keyToken] = profile.token
        }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(keyBaseUrl)
            prefs.remove(keyToken)
        }
    }

    private fun toProfile(prefs: Preferences): ConnectionProfile? {
        val baseUrl = prefs[keyBaseUrl] ?: return null
        val token = prefs[keyToken] ?: return null
        return ConnectionProfile(baseUrl = baseUrl, token = token)
    }
}
