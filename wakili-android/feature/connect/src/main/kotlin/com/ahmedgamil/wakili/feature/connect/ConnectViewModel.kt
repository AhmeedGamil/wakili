package com.ahmedgamil.wakili.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.data.repository.ConnectionRepository
import com.ahmedgamil.wakili.core.data.repository.InvalidGatewayUrlException
import com.ahmedgamil.wakili.core.model.GatewayAuthException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectError { INVALID_URL, UNAUTHORIZED, UNREACHABLE }

data class ConnectUiState(
    val manualUrl: String = "",
    val canSubmit: Boolean = false,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val error: ConnectError? = null,
    val connected: Boolean = false,
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    fun onManualUrlChange(value: String) {
        _uiState.update {
            it.copy(
                manualUrl = value,
                canSubmit = ConnectionRepository.parseGatewayUrl(value) != null,
                error = null,
            )
        }
    }

    fun onSubmitManual() = connect(_uiState.value.manualUrl)

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
