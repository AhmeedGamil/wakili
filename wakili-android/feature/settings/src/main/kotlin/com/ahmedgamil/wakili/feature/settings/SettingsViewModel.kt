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
    val endpoints: List<GatewayEndpoint> = emptyList(),
    val currentHost: String = "",
    val power: PowerState? = null,
    val autostart: AutostartState? = null,
    val switching: Boolean = false,
    val switchError: Boolean = false,
    val deviceNotice: String? = null,
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
        connectionRepository.profile,
    ) { state, settings, profile ->
        state.copy(settings = settings, currentHost = profile?.baseUrl.orEmpty())
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

    /** Per-app locale (works below Android 13 thanks to AppCompatDelegate). */
    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    // ---- device ----

    fun lockScreen() = deviceAction { deviceRepository.lockScreen() }
    fun screenOff() = deviceAction { deviceRepository.screenOff() }
    fun lockAndOff() = deviceAction { deviceRepository.lockAndOff() }

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

    private fun deviceAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { block() }.isSuccess
            local.update { it.copy(deviceNotice = if (ok) "ok" else "fail") }
        }
    }

    fun clearNotice() = local.update { it.copy(deviceNotice = null) }

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
