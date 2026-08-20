package com.ahmedgamil.wakili.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.data.repository.ConnectionRepository
import com.ahmedgamil.wakili.core.data.repository.InvalidGatewayUrlException
import com.ahmedgamil.wakili.core.data.repository.MissingGatewayTokenException
import com.ahmedgamil.wakili.core.model.ConnectionProfile
import com.ahmedgamil.wakili.core.model.GatewayAuthException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectError { INVALID_URL, UNAUTHORIZED, UNREACHABLE, NEEDS_TOKEN }

/** A saved gateway with a derived display name (react-native networks.js). */
data class SavedHostUi(
    val profile: ConnectionProfile,
    val name: String,
    val host: String,
    val active: Boolean,
)

data class ConnectUiState(
    val manualUrl: String = "",
    val canSubmit: Boolean = false,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val error: ConnectError? = null,
    val connected: Boolean = false,
    // Saved-hosts dropdown: Connect acts on the selection.
    val hosts: List<SavedHostUi> = emptyList(),
    val selectedBaseUrl: String? = null,
) {
    val selected: SavedHostUi? get() = hosts.firstOrNull { it.profile.baseUrl == selectedBaseUrl } ?: hosts.firstOrNull()
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                connectionRepository.savedHosts,
                connectionRepository.profile,
                connectionRepository.hostNames,
            ) { hosts, active, names ->
                hosts.map { h ->
                    SavedHostUi(
                        profile = h,
                        // User-chosen name wins; otherwise the derived "host-lan".
                        name = names[h.baseUrl]?.takeIf { it.isNotBlank() } ?: hostName(h.baseUrl),
                        host = h.baseUrl.substringAfter("://"),
                        active = h.baseUrl == active?.baseUrl,
                    )
                }
            }.collect { hosts ->
                _uiState.update { state ->
                    state.copy(
                        hosts = hosts,
                        selectedBaseUrl = state.selectedBaseUrl
                            ?: hosts.firstOrNull { it.active }?.profile?.baseUrl,
                    )
                }
            }
        }
    }

    fun selectHost(baseUrl: String) {
        _uiState.update { it.copy(selectedBaseUrl = baseUrl, error = null) }
    }

    /** Connect acts on the dropdown selection (retry current == switch other). */
    fun connectSelected() {
        val host = _uiState.value.selected ?: return
        if (_uiState.value.connecting) return
        _uiState.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            try {
                connectionRepository.connectTo(host.profile)
                _uiState.update { it.copy(connecting = false, connected = true) }
            } catch (e: GatewayAuthException) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.UNAUTHORIZED) }
            } catch (e: Exception) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.UNREACHABLE) }
            }
        }
    }

    fun removeHost(baseUrl: String) {
        viewModelScope.launch { connectionRepository.removeHost(baseUrl) }
        _uiState.update {
            if (it.selectedBaseUrl == baseUrl) it.copy(selectedBaseUrl = null) else it
        }
    }

    fun renameHost(baseUrl: String, name: String) {
        viewModelScope.launch { connectionRepository.renameHost(baseUrl, name) }
    }

    fun onManualUrlChange(value: String) {
        _uiState.update {
            it.copy(
                manualUrl = value,
                // ANY non-blank text arms Go. This used to gate on
                // normalizeUrl(), which is java.net.URI underneath — and URI
                // returns a null host for anything not strictly RFC-2396, most
                // notably hostnames containing an underscore (common in Windows
                // and mDNS machine names) and anything with a stray space. Those
                // left the button permanently dead with nothing on screen saying
                // why, which is the exact failure the comment below already
                // rejected for missing tokens. Validation belongs at submit,
                // where connectManual() has somewhere to show the reason.
                canSubmit = value.isNotBlank(),
                error = null,
            )
        }
    }

    fun onSubmitManual() = connectManual(_uiState.value.manualUrl)

    /** Typed URLs go through the lenient path (scheme optional, token resolved). */
    private fun connectManual(rawUrl: String) {
        if (_uiState.value.connecting) return
        _uiState.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            try {
                connectionRepository.connectManual(rawUrl)
                _uiState.update { it.copy(connecting = false, connected = true) }
            } catch (e: MissingGatewayTokenException) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.NEEDS_TOKEN) }
            } catch (e: InvalidGatewayUrlException) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.INVALID_URL) }
            } catch (e: GatewayAuthException) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.UNAUTHORIZED) }
            } catch (e: Exception) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.UNREACHABLE) }
            }
        }
    }

    fun onScanRequested() {
        _uiState.update { it.copy(scanning = true, error = null) }
    }

    fun onScanCancelled() {
        _uiState.update { it.copy(scanning = false) }
    }

    fun onQrScanned(payload: String) {
        // The camera fires repeatedly; take the first gateway-shaped QR only.
        if (_uiState.value.connecting || !_uiState.value.scanning) return
        if (ConnectionRepository.parseGatewayUrl(payload) == null) return
        _uiState.update { it.copy(scanning = false) }
        connect(payload)
    }

    private fun connect(rawUrl: String) {
        if (_uiState.value.connecting) return
        _uiState.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            try {
                connectionRepository.connect(rawUrl)
                _uiState.update { it.copy(connecting = false, connected = true) }
            } catch (e: InvalidGatewayUrlException) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.INVALID_URL) }
            } catch (e: GatewayAuthException) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.UNAUTHORIZED) }
            } catch (e: Exception) {
                _uiState.update { it.copy(connecting = false, error = ConnectError.UNREACHABLE) }
            }
        }
    }
}

/** "hostname-lan" / "hostname-tailscale" / "name-cloudflare", like RN networks.js. */
private fun hostName(baseUrl: String): String {
    val host = baseUrl.substringAfter("://").substringBefore(":").substringBefore("/")
    val kind = when {
        host.endsWith("trycloudflare.com") -> "cloudflare"
        host.startsWith("100.") -> "tailscale"
        else -> "lan"
    }
    val stem = if (kind == "cloudflare") host.substringBefore(".") else host
    return "$stem-$kind"
}
