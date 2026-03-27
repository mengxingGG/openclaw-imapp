package ai.openclaw.imapp.ui.settings

import ai.openclaw.imapp.data.api.ServerConfigStore
import ai.openclaw.imapp.data.model.Device
import ai.openclaw.imapp.data.repository.ImappRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val userName: String = "",
    val devices: List<Device> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ImappRepository,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(configStore.serverUrl, configStore.userName) { url, name ->
                url to name
            }.collect { (url, name) ->
                _uiState.update { it.copy(serverUrl = url ?: "", userName = name ?: "") }
            }
        }
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.getDevices()
            _uiState.update {
                it.copy(
                    devices = result.getOrNull()?.devices ?: emptyList(),
                    isLoading = false,
                )
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            repository.revokeDevice(deviceId)
            loadDevices()
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.disconnectWebSocket()
            repository.clearSession()
            onDone()
        }
    }
}
