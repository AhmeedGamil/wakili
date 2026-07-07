package com.ahmedgamil.wakili

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.data.repository.ConnectionRepository
import com.ahmedgamil.wakili.core.datastore.Settings
import com.ahmedgamil.wakili.core.datastore.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppStartState {
    data object Loading : AppStartState
    data object NeedsConnection : AppStartState
    data object Connected : AppStartState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _startState = MutableStateFlow<AppStartState>(AppStartState.Loading)
    val startState: StateFlow<AppStartState> = _startState.asStateFlow()

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    init {
        viewModelScope.launch {
            val profile = connectionRepository.restore()
            _startState.value =
                if (profile == null) AppStartState.NeedsConnection else AppStartState.Connected
        }
    }
}
