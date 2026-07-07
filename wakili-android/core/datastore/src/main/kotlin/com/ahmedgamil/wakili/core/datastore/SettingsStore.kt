package com.ahmedgamil.wakili.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "wakili_settings")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accentHex: String = "", // "" = theme default
    val markdown: Boolean = true,
    val autoAllow: Boolean = false,
)

/**
 * App preferences — the DataStore twin of the web client's localStorage keys
 * (ra-theme, ra-accent, ra-markdown, ra-auto-allow, ra-last-config, ra-perm-mode).
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val keyTheme = stringPreferencesKey("theme")
    private val keyAccent = stringPreferencesKey("accent")
    private val keyMarkdown = booleanPreferencesKey("markdown")
    private val keyAutoAllow = booleanPreferencesKey("auto_allow")
    private val keyLastAgent = stringPreferencesKey("last_agent")
    private val keyLastControls = stringPreferencesKey("last_controls_json")
    private val keyPermMode = stringPreferencesKey("perm_mode")

    val settings: Flow<Settings> = context.settingsStore.data.map { p ->
        Settings(
            theme = runCatching { ThemeMode.valueOf(p[keyTheme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            accentHex = p[keyAccent].orEmpty(),
            markdown = p[keyMarkdown] ?: true,
            autoAllow = p[keyAutoAllow] ?: false,
        )
    }

    /** Last-used agent + controls JSON — defaults for new chats (ra-last-config). */
    val lastConfig: Flow<Pair<String, String>?> = context.settingsStore.data.map { p ->
        val agent = p[keyLastAgent] ?: return@map null
        agent to (p[keyLastControls] ?: "{}")
    }

    /** Remembered global permission mode (ra-perm-mode). */
    val permMode: Flow<String?> = context.settingsStore.data.map { p -> p[keyPermMode] }

    private val keySessionView = stringPreferencesKey("session_view")

    /** "project" | "all" — the sidebar view toggle (ra-session-view). */
    val sessionView: Flow<String> = context.settingsStore.data.map { p ->
        p[keySessionView] ?: "project"
    }

    suspend fun setSessionView(view: String) = edit { it[keySessionView] = view }

    suspend fun setTheme(mode: ThemeMode) = edit { it[keyTheme] = mode.name }
    suspend fun setAccent(hex: String) = edit { it[keyAccent] = hex }
    suspend fun setMarkdown(on: Boolean) = edit { it[keyMarkdown] = on }
    suspend fun setAutoAllow(on: Boolean) = edit { it[keyAutoAllow] = on }
    suspend fun setPermMode(mode: String) = edit { it[keyPermMode] = mode }
    suspend fun setLastConfig(agentId: String, controlsJson: String) = edit {
        it[keyLastAgent] = agentId
        it[keyLastControls] = controlsJson
    }

    private val keyTermHistory = stringPreferencesKey("term_history")

    /** Terminal command history, newest first, capped at 200 (ra-term-history). */
    val termHistory: Flow<List<String>> = context.settingsStore.data.map { p ->
        p[keyTermHistory]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun addTermHistory(command: String) = edit { prefs ->
        val current = prefs[keyTermHistory]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        val updated = (listOf(command) + current.filterNot { it == command }).take(200)
        prefs[keyTermHistory] = updated.joinToString("\n")
    }

    // Per-session drafts (uiState map in the web main.js, but persisted).
    suspend fun saveDraft(sessionId: String, text: String) = edit {
        val key = stringPreferencesKey("draft_$sessionId")
        if (text.isBlank()) it.remove(key) else it[key] = text
    }

    fun draft(sessionId: String): Flow<String> = context.settingsStore.data.map { p ->
        p[stringPreferencesKey("draft_$sessionId")].orEmpty()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit(block)
    }
}
