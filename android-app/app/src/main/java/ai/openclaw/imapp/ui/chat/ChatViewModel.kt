package ai.openclaw.imapp.ui.chat

import ai.openclaw.imapp.data.api.ConnectionState
import ai.openclaw.imapp.data.api.ServerConfigStore
import ai.openclaw.imapp.data.model.*
import ai.openclaw.imapp.data.repository.ImappRepository
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isTyping: Boolean = false,
    val typingStartedAt: Long? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val inputText: String = "",
    val isUploading: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val prependedCount: Int = 0,            // 上次 loadMore 追加的条数，用于维持滚动位置
    val errorMsg: String? = null,
    val infoMsg: String? = null,
    val serverUrl: String = "",
    val sessionToken: String = "",
    val userName: String = "",
    val downloadingFileId: String? = null,
    val streamingMessageId: String? = null,   // 正在流式传输的消息 ID
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ImappRepository,
    private val configStore: ServerConfigStore,
    private val gson: Gson,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // typing 超时任务：超过 10 分钟无响应时清除 typing 状态
    private var typingTimeoutJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                configStore.serverUrl,
                configStore.sessionToken,
                configStore.userName,
            ) { url, token, name ->
                Triple(url, token, name)
            }.collect { (url, token, name) ->
                _uiState.update {
                    it.copy(
                        serverUrl = url ?: "",
                        sessionToken = token ?: "",
                        userName = name ?: "",
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.connectWebSocket()
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingHistory = true) }
            loadHistory()
            _uiState.update { it.copy(isLoadingHistory = false) }
        }

        viewModelScope.launch {
            repository.wsManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        viewModelScope.launch {
            repository.wsManager.messages.collect { wsMsg ->
                handleWsMessage(wsMsg)
            }
        }

        // WebSocket 重连后自动刷新历史消息
        viewModelScope.launch {
            repository.wsManager.onConnected.collect {
                refreshHistory()
            }
        }
    }

    private suspend fun loadHistory() {
        val result = repository.getHistory(limit = 50)
        result.getOrNull()?.let { resp ->
            val msgs = resp.messages.reversed()
            _uiState.update { it.copy(messages = msgs, hasMoreHistory = resp.hasMore) }
        }
    }

    /** 重连后刷新历史，只追加新消息，不丢失已有的 */
    private suspend fun refreshHistory() {
        val result = repository.getHistory(limit = 50)
        result.getOrNull()?.let { resp ->
            val latestTs = _uiState.value.messages.lastOrNull()?.timestamp ?: 0
            val newMsgs = resp.messages.reversed().filter { it.timestamp > latestTs }
            if (newMsgs.isNotEmpty()) {
                _uiState.update { it.copy(messages = it.messages + newMsgs) }
            }
        }
    }

    /** 向上滚动到顶部时加载更多历史消息 */
    fun loadMoreHistory() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMoreHistory) return
        val oldestId = _uiState.value.messages.firstOrNull()?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, prependedCount = 0) }
            val result = repository.getHistory(limit = 30, before = oldestId)
            result.getOrNull()?.let { resp ->
                val older = resp.messages.reversed()
                _uiState.update { s ->
                    s.copy(
                        messages = older + s.messages,
                        hasMoreHistory = resp.hasMore,
                        isLoadingMore = false,
                        prependedCount = older.size,  // 通知 UI 有多少条被追加，以便修复滚动位置
                    )
                }
            } ?: _uiState.update { it.copy(isLoadingMore = false) }
        }
    }

    fun consumePrependedCount() {
        _uiState.update { it.copy(prependedCount = 0) }
    }

    private fun handleWsMessage(msg: WsIncomingMessage) {
        when (msg.type) {
            "message" -> {
                if (msg.from == "agent" && msg.content != null) {
                    val streamingId = _uiState.value.streamingMessageId

                    if (streamingId != null && msg.stream == "continue") {
                        // 流式更新：累积文本到已有消息
                        _uiState.update { s ->
                            val updated = s.messages.map { m ->
                                if (m.id == streamingId) {
                                    val existingText = m.content.text ?: ""
                                    val newText = existingText + (msg.content.text ?: "")
                                    m.copy(content = m.content.copy(text = newText))
                                } else m
                            }
                            s.copy(messages = updated)
                        }
                    } else {
                        // 新消息（流式开始或非流式）
                        val messageId = msg.id ?: UUID.randomUUID().toString()
                        _uiState.update { s ->
                            s.copy(
                                messages = s.messages + Message(
                                    id = messageId,
                                    from = "agent",
                                    timestamp = msg.timestamp ?: System.currentTimeMillis(),
                                    content = msg.content,
                                ),
                                streamingMessageId = messageId,
                            )
                        }
                    }
                }
            }
            "typing" -> {
                val isTyping = msg.status == "typing"
                if (isTyping) {
                    // 新的 typing 周期开始 → 结束上一条流式消息
                    _uiState.update { s ->
                        s.copy(
                            isTyping = true,
                            typingStartedAt = if (!s.isTyping) System.currentTimeMillis() else s.typingStartedAt,
                            streamingMessageId = null,
                        )
                    }
                    scheduleTypingTimeout()
                } else {
                    // typing:idle → 回复结束
                    typingTimeoutJob?.cancel()
                    _uiState.update { it.copy(isTyping = false, typingStartedAt = null, streamingMessageId = null) }
                }
            }
            "error" -> {
                _uiState.update { it.copy(errorMsg = msg.error?.message, isTyping = false, typingStartedAt = null) }
                typingTimeoutJob?.cancel()
            }
        }
    }

    /** 超过 10 分钟 typing 未结束则强制清除，避免界面卡死 */
    private fun scheduleTypingTimeout() {
        typingTimeoutJob?.cancel()
        typingTimeoutJob = viewModelScope.launch {
            delay(10 * 60 * 1000L)
            _uiState.update { it.copy(isTyping = false, typingStartedAt = null) }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendTextMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return
        _uiState.update { it.copy(inputText = "") }

        val msgId = UUID.randomUUID().toString()
        val userMsg = Message(
            id = msgId,
            from = "user",
            timestamp = System.currentTimeMillis(),
            content = MessageContent("text", text),
        )
        _uiState.update { it.copy(messages = it.messages + userMsg) }

        val wsMsg = WsOutgoingMessage(id = msgId, content = MessageContent("text", text))
        repository.wsManager.sendMessage(wsMsg)
    }

    fun sendImageMessage(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true) }
            val result = repository.uploadMedia(uri, "image/jpeg")
            _uiState.update { it.copy(isUploading = false) }
            result.getOrNull()?.let { upload ->
                val msgId = UUID.randomUUID().toString()
                val content = MessageContent(
                    type = "image",
                    fileId = upload.fileId,
                    url = upload.url,
                )
                val userMsg = Message(msgId, "user", System.currentTimeMillis(), content)
                _uiState.update { it.copy(messages = it.messages + userMsg) }
                repository.wsManager.sendMessage(WsOutgoingMessage(id = msgId, content = content))
            } ?: _uiState.update { it.copy(errorMsg = "图片上传失败") }
        }
    }

    fun sendVoiceMessage(audioBytes: ByteArray, durationMs: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true) }
            val result = repository.uploadBytes(audioBytes, "audio/amr")
            _uiState.update { it.copy(isUploading = false) }
            result.getOrNull()?.let { upload ->
                val msgId = UUID.randomUUID().toString()
                val content = MessageContent(
                    type = "voice",
                    fileId = upload.fileId,
                    url = upload.url,
                    durationMs = durationMs,
                )
                val userMsg = Message(msgId, "user", System.currentTimeMillis(), content)
                _uiState.update { it.copy(messages = it.messages + userMsg) }
                repository.wsManager.sendMessage(WsOutgoingMessage(id = msgId, content = content))
                // 提示用户语音仅传送，Agent 将以文字回复
                _uiState.update { it.copy(infoMsg = "语音已发送，Agent 将以文字形式回复") }
            } ?: _uiState.update { it.copy(errorMsg = "语音上传失败") }
        }
    }

    fun sendFileMessage(uri: Uri, mimeType: String, filename: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true) }
            val result = repository.uploadMedia(uri, mimeType)
            _uiState.update { it.copy(isUploading = false) }
            result.getOrNull()?.let { upload ->
                val isVideo = mimeType.startsWith("video")
                val msgId = UUID.randomUUID().toString()
                val content = MessageContent(
                    type = if (isVideo) "video" else "file",
                    fileId = upload.fileId,
                    url = upload.url,
                    filename = filename,
                    size = upload.size,
                )
                val userMsg = Message(msgId, "user", System.currentTimeMillis(), content)
                _uiState.update { it.copy(messages = it.messages + userMsg) }
                repository.wsManager.sendMessage(WsOutgoingMessage(id = msgId, content = content))
                if (isVideo) {
                    _uiState.update { it.copy(infoMsg = "视频已发送，Agent 仅能获取下载链接") }
                }
            } ?: _uiState.update { it.copy(errorMsg = "文件上传失败") }
        }
    }

    fun downloadFile(fileId: String, filename: String, mimeType: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingFileId = fileId) }
            val result = repository.downloadMedia(fileId, filename, mimeType)
            _uiState.update { it.copy(downloadingFileId = null) }
            result.fold(
                onSuccess = { _uiState.update { s -> s.copy(infoMsg = "已保存到下载目录: $filename") } },
                onFailure = { e -> _uiState.update { s -> s.copy(errorMsg = "下载失败: ${e.message}") } },
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMsg = null) }
    }

    fun dismissInfo() {
        _uiState.update { it.copy(infoMsg = null) }
    }

    override fun onCleared() {
        super.onCleared()
        typingTimeoutJob?.cancel()
        repository.disconnectWebSocket()
    }
}
