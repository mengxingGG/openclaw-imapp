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
    val status: String = "idle",
    val errorMsg: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: ImappRepository,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()
    private val _loginSuccess = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginSuccess: SharedFlow<Unit> = _loginSuccess.asSharedFlow()

    // null = still checking, true = valid session, false = no session
    val hasValidSession: Flow<Boolean?> = flow {
        emit(null)
        val token = configStore.sessionToken.first()
        val url = configStore.serverUrl.first()
        if (token.isNullOrEmpty() || url.isNullOrEmpty()) {
            emit(false)
            return@flow
        }
        try {
            val result = withTimeoutOrNull(10_000) {
                repository.verifySession(token)
            }
            emit(result?.getOrNull()?.valid == true)
        } catch (_: Exception) {
            emit(false)
        }
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
                // 先保存服务器地址（必须等写完再读取）
                configStore.saveServerUrl(serverUrl)

                // 验证 Token（直接传入 serverUrl，避免从 DataStore 读取竞态）
                val result = repository.verifyToken(token, serverUrl = serverUrl)
                if (result.isSuccess) {
                    val response = result.getOrThrow()
                    val isValid = response.valid || (response.success && (response.user != null || response.userId != null))
                    if (isValid) {
                        repository.saveSession(
                            response.sessionToken ?: token,
                            response.userId ?: response.user?.id ?: "",
                            response.userName ?: response.user?.name ?: ""
                        )
                        viewModelScope.launch(Dispatchers.IO) {
                            withTimeoutOrNull(5_000) {
                                repository.syncCurrentFcmToken()
                            }
                        }
                        _uiState.update { it.copy(status = "idle", errorMsg = null) }
                        _loginSuccess.tryEmit(Unit)
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
