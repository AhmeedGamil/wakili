package com.ahmedgamil.wakili.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.data.repository.ConnectionRepository
import com.ahmedgamil.wakili.core.data.repository.DeviceRepository
import com.ahmedgamil.wakili.core.data.repository.EndpointRepository
import com.ahmedgamil.wakili.core.datastore.Settings
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import com.ahmedgamil.wakili.core.datastore.ThemeMode
import com.ahmedgamil.wakili.core.model.AutostartState
import com.ahmedgamil.wakili.core.model.GatewayEndpoint
import com.ahmedgamil.wakili.core.model.PowerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: Settings = Settings(),
    val notifications: Boolean = false,
    /** Floating question alerts over other apps (questions only). */
    val floatingQuestions: Boolean = false,
    val endpoints: List<GatewayEndpoint> = emptyList(),
    val currentHost: String = "",
    val power: PowerState? = null,
    val autostart: AutostartState? = null,
    val switching: Boolean = false,
    val switchError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val deviceRepository: DeviceRepository,
    private val connectionRepository: ConnectionRepository,
    private val endpointRepository: EndpointRepository,
) : ViewModel() {

    private val local = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = combine(
        local,
        settingsStore.settings,
        settingsStore.notifications,
        settingsStore.floatingQuestions,
        connectionRepository.profile,
    ) { state, settings, notifications, floatingQuestions, profile ->
        state.copy(
            settings = settings,
            notifications = notifications,
            floatingQuestions = floatingQuestions,
            currentHost = profile?.baseUrl.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        viewModelScope.launch {
            runCatching { endpointRepository.endpoints() }.getOrNull()?.let { list ->
                local.update { it.copy(endpoints = list) }
            }
        }
        viewModelScope.launch {
            runCatching { deviceRepository.power() }.getOrNull()?.let { p ->
                local.update { it.copy(power = p) }
            }
        }
        viewModelScope.launch {
            runCatching { deviceRepository.autostart() }.getOrNull()?.let { a ->
                local.update { it.copy(autostart = a) }
            }
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsStore.setTheme(mode) }
    fun setAccent(hex: String) = viewModelScope.launch { settingsStore.setAccent(hex) }
    fun setMarkdown(on: Boolean) = viewModelScope.launch { settingsStore.setMarkdown(on) }
    fun setChatFontSize(sp: Int) = viewModelScope.launch { settingsStore.setChatFontSize(sp) }
    fun setNotifications(on: Boolean) = viewModelScope.launch { settingsStore.setNotifications(on) }
    fun setFloatingQuestions(on: Boolean) = viewModelScope.launch { settingsStore.setFloatingQuestions(on) }

    /** Per-app locale (works below Android 13 thanks to AppCompatDelegate). */
    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    // ---- device ----

    // One-shot actions. Each returns whether it worked so the row that was
    // tapped reports its OWN result inline (web .dev-status), rather than a
    // single notice at the bottom of the tab standing in for all of them.
    suspend fun lockScreen(): Boolean = runCatching { deviceRepository.lockScreen() }.isSuccess
    suspend fun screenOff(): Boolean = runCatching { deviceRepository.screenOff() }.isSuccess

    /** The gateway answers {ok:false} when no shutdown command worked, so a
     *  successful round trip is not on its own a successful shutdown. */
    suspend fun shutdown(): Boolean = runCatching { deviceRepository.shutdown() }.getOrDefault(false)

    fun keepAwake(on: Boolean) {
        viewModelScope.launch {
            runCatching { deviceRepository.keepAwake(on) }.getOrNull()?.let { p ->
                local.update { it.copy(power = p) }
            }
        }
    }

    fun setAutostart(on: Boolean) {
        viewModelScope.launch {
            runCatching { deviceRepository.setAutostart(on) }.getOrNull()?.let { a ->
                local.update { it.copy(autostart = a) }
            }
        }
    }

    // ---- connection switching ----

    fun switchTo(endpoint: GatewayEndpoint) {
        local.update { it.copy(switching = true, switchError = false) }
        viewModelScope.launch {
            try {
                connectionRepository.switchEndpoint(endpoint.url)
                local.update { it.copy(switching = false) }
            } catch (e: Exception) {
                local.update { it.copy(switching = false, switchError = true) }
            }
        }
    }
}
