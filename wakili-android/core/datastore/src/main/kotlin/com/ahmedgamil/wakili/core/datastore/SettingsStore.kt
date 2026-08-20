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
    private val keyLastAgent = stringPreferencesKey("last_agent")
    private val keyLastControls = stringPreferencesKey("last_controls_json")
    private val keyPermMode = stringPreferencesKey("perm_mode")

    val settings: Flow<Settings> = context.settingsStore.data.map { p ->
        Settings(
            theme = runCatching { ThemeMode.valueOf(p[keyTheme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            accentHex = p[keyAccent].orEmpty(),
            markdown = p[keyMarkdown] ?: true,
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

    private val keySessionModel = booleanPreferencesKey("session_model")

    /** Show each session's model name in the list (ra-session-model). */
    val showModel: Flow<Boolean> = context.settingsStore.data.map { p ->
        p[keySessionModel] ?: false
    }

    suspend fun setShowModel(on: Boolean) = edit { it[keySessionModel] = on }

    private val keyHomeAgent = stringPreferencesKey("home_agent")

    /** The agent tab selected on the home page — new chats use it and the list
     *  filters by it (web ra-home-agent). Empty until the user picks one. */
    val homeAgent: Flow<String> = context.settingsStore.data.map { p ->
        p[keyHomeAgent].orEmpty()
    }

    suspend fun setHomeAgent(id: String) = edit { it[keyHomeAgent] = id }

    private val keyCollapsedGroups = stringPreferencesKey("collapsed_groups")

    /** Collapsed project groups in the "By project" list, keyed by cwd ("" =
     *  default project); persisted so the sidebar opens the way it was left
     *  (web ra-side-groups). Stored one cwd per line (a cwd can't contain \n). */
    val collapsedGroups: Flow<Set<String>> = context.settingsStore.data.map { p ->
        p[keyCollapsedGroups]?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    suspend fun toggleCollapsedGroup(cwd: String) = edit { prefs ->
        val current = prefs[keyCollapsedGroups]?.split('\n')?.filter { it.isNotEmpty() }?.toMutableSet()
            ?: mutableSetOf()
        if (!current.add(cwd)) current.remove(cwd)
        prefs[keyCollapsedGroups] = current.joinToString("\n")
    }

    suspend fun setTheme(mode: ThemeMode) = edit { it[keyTheme] = mode.name }
    suspend fun setAccent(hex: String) = edit { it[keyAccent] = hex }
    suspend fun setMarkdown(on: Boolean) = edit { it[keyMarkdown] = on }

    // "Allow always" moved to the gateway (session.autoAllow via PATCH): the
    // server auto-approves, so it works while the app is backgrounded.

    private val keyNotifications = booleanPreferencesKey("notifications")

    /** Notify on turn_end / permission / question while backgrounded (ON by default). */
    val notifications: Flow<Boolean> = context.settingsStore.data.map { p ->
        p[keyNotifications] ?: true
    }

    suspend fun setNotifications(on: Boolean) = edit { it[keyNotifications] = on }

    private val keyFloatingQuestions = booleanPreferencesKey("floating_questions")

    /**
     * Floating question alerts: when the agent asks something while another
     * app is open, show a persistent overlay card (Facebook-chat-heads style)
     * on top of it — a heads-up notification retreats after seconds and is
     * easily missed. Questions ONLY, by design. ON by default, but inert
     * until the OS "display over other apps" permission is granted.
     */
    val floatingQuestions: Flow<Boolean> = context.settingsStore.data.map { p ->
        p[keyFloatingQuestions] ?: true
    }

    suspend fun setFloatingQuestions(on: Boolean) = edit { it[keyFloatingQuestions] = on }
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

    // Uploaded-but-unsent attachments ride along with the draft, as JSON.
    suspend fun saveDraftAttachments(sessionId: String, jsonList: String) = edit {
        val key = stringPreferencesKey("draft_att_$sessionId")
        if (jsonList.isEmpty() || jsonList == "[]") it.remove(key) else it[key] = jsonList
    }

    fun draftAttachments(sessionId: String): Flow<String> = context.settingsStore.data.map { p ->
        p[stringPreferencesKey("draft_att_$sessionId")] ?: "[]"
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit(block)
    }
}
