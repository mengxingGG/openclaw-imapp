package ai.openclaw.imapp.ui.login

import ai.openclaw.imapp.data.api.ServerConfigStore
import ai.openclaw.imapp.data.repository.ImappRepository
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val token: String = "",
    val status: String = "idle",  // idle | loading | confirmed | error
    val errorMsg: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: ImappRepository,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // null = still checking, true = valid session, false = no session
    val hasValidSession: Flow<Boolean?> = flow {
        emit(null)
        val token = configStore.sessionToken.first()
        val url = configStore.serverUrl.first()
        if (token.isNullOrEmpty() || url.isNullOrEmpty()) {
            emit(false)
            return@flow
        }
        val result = repository.verifySession(token)
        emit(result.getOrNull()?.valid == true)
    }.flowOn(Dispatchers.IO)

    init {
        viewModelScope.launch {
            configStore.serverUrl.collect { url ->
                _uiState.update { it.copy(serverUrl = url ?: "") }
            }
        }
    }

    fun onServerUrlChange(url: String) {
        _uiState.update { it.copy(serverUrl = url, errorMsg = null) }
    }

    fun onTokenChange(token: String) {
        _uiState.update { it.copy(token = token, errorMsg = null) }
    }

    fun saveServerUrl() {
        viewModelScope.launch {
            configStore.saveServerUrl(_uiState.value.serverUrl)
        }
    }

    /**
     * 使用 Token 登录
     */
    fun loginWithToken() {
        val serverUrl = _uiState.value.serverUrl.trim()
        val token = _uiState.value.token.trim()

        if (serverUrl.isEmpty()) {
            _uiState.update { it.copy(errorMsg = "请输入服务器地址") }
            return
        }
        if (token.isEmpty()) {
            _uiState.update { it.copy(errorMsg = "请输入Token") }
            return
        }

        _uiState.update { it.copy(status = "loading", errorMsg = null) }

        viewModelScope.launch {
            try {
                // 保存服务器地址
                configStore.saveServerUrl(serverUrl)

                // 验证 Token
                val result = repository.verifyToken(token)
                if (result.isSuccess) {
                    val response = result.getOrThrow()
                    val isValid = response.valid || (response.success && (response.user != null || response.userId != null))
                    if (isValid) {
                        repository.saveSession(
                            response.sessionToken ?: token,
                            response.userId ?: response.user?.id ?: "",
                            response.userName ?: response.user?.name ?: ""
                        )
                        _uiState.update { it.copy(status = "confirmed") }
                    } else {
                        _uiState.update { it.copy(status = "error", errorMsg = response.errorMsg ?: response.error ?: "验证失败") }
                    }
                } else {
                    _uiState.update { it.copy(status = "error", errorMsg = result.exceptionOrNull()?.message ?: "验证失败") }
                }
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Login failed: ${e.message}", e)
                _uiState.update { it.copy(status = "error", errorMsg = "登录失败: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
