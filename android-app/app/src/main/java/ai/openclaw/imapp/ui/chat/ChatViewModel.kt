package ai.openclaw.imapp.ui.chat

import ai.openclaw.imapp.data.api.ConnectionState
import ai.openclaw.imapp.data.api.ServerConfigStore
import ai.openclaw.imapp.data.local.MessageStore
import ai.openclaw.imapp.data.model.*
import ai.openclaw.imapp.data.repository.ImappRepository
import ai.openclaw.imapp.service.NotificationHelper
import android.app.Application
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
    val prependedCount: Int = 0,
    val errorMsg: String? = null,
    val infoMsg: String? = null,
    val serverUrl: String = "",
    val sessionToken: String = "",
    val userName: String = "",
    val downloadingFileId: String? = null,
    val streamingMessageId: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ImappRepository,
    private val configStore: ServerConfigStore,
    private val gson: Gson,
    private val application: Application,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val ctx = application
    private var typingTimeoutJob: Job? = null
    private val receivedPackets = mutableSetOf<String>()
    private var syncJob: Job? = null

    init {
        NotificationHelper.createChannels(application)

        // 加载配置
        viewModelScope.launch {
            combine(
                configStore.serverUrl,
                configStore.sessionToken,
                configStore.userName,
            ) { url, token, name -> Triple(url, token, name) }.collect { (url, token, name) ->
                _uiState.update {
                    it.copy(serverUrl = url ?: "", sessionToken = token ?: "", userName = name ?: "")
                }
            }
        }

        // 启动：加载本地缓存（不主动拉历史，等欢迎消息建立同步锚点）
        viewModelScope.launch(Dispatchers.IO) {
            val cached = MessageStore.loadAll(ctx, gson)
            if (cached.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(messages = cached, hasMoreHistory = true) }
                }
                // 有本地缓存时才做增量同步
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoadingHistory = true) }
                }
                syncIncremental()
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoadingHistory = false) }
                }
            }
        }

        // 连接 WebSocket
        viewModelScope.launch { repository.connectWebSocket() }

        // 监听连接状态
        viewModelScope.launch {
            repository.wsManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        // 监听 WebSocket 消息
        viewModelScope.launch {
            repository.wsManager.messages.collect { wsMsg -> handleWsMessage(wsMsg) }
        }

        // 连接成功时增量同步（仅在有本地缓存时）
        viewModelScope.launch {
            repository.wsManager.onConnected.collect {
                val cached = _uiState.value.messages
                if (cached.isNotEmpty()) {
                    syncIncremental()
                }
            }
        }
    }

    /** 增量同步（防并发：同时只允许一个同步任务） */
    private suspend fun syncIncremental() {
        val runningJob = syncJob
        if (runningJob?.isActive == true) {
            runningJob.join()
            return
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            val lastTs = MessageStore.getLastSyncTimestamp(ctx)
            val result = repository.getHistory(limit = 20, after = lastTs)
            result.getOrNull()?.let { resp ->
                val merged = MessageStore.append(ctx, gson, _uiState.value.messages, resp.messages)
                val newestTs = resp.messages.maxOfOrNull { it.timestamp } ?: lastTs
                MessageStore.touchLastSyncTimestamp(ctx, newestTs)
                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        state.copy(
                            messages = merged,
                            hasMoreHistory = if (lastTs == 0L) resp.hasMore else state.hasMoreHistory,
                        )
                    }
                }
            }
        }
        syncJob = job
        job.join()
    }

    /** 向上加载更多历史 */
    fun loadMoreHistory() {
        if (_uiState.value.isLoadingMore) return
        val firstTs = _uiState.value.messages.firstOrNull()?.timestamp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _uiState.update { it.copy(isLoadingMore = true, prependedCount = 0) } }
            val result = repository.getHistory(limit = 30, before = firstTs)
            result.getOrNull()?.let { resp ->
                val older = resp.messages.filter { it.timestamp < firstTs }
                if (older.isNotEmpty()) {
                    val merged = MessageStore.prepend(ctx, gson, _uiState.value.messages, older)
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                messages = merged,
                                isLoadingMore = false,
                                hasMoreHistory = resp.hasMore,
                                prependedCount = older.size,
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isLoadingMore = false, hasMoreHistory = resp.hasMore) }
                    }
                }
            } ?: withContext(Dispatchers.Main) { _uiState.update { it.copy(isLoadingMore = false) } }
        }
    }

    fun consumePrependedCount() { _uiState.update { it.copy(prependedCount = 0) } }

    private fun handleWsMessage(msg: WsIncomingMessage) {
        markConnected()
        when (msg.type) {
            "message" -> {
                // 系统欢迎消息：记录时间戳作为同步锚点，不持久化
                if (msg.from == "system") {
                    msg.timestamp?.let { ts ->
                        viewModelScope.launch(Dispatchers.IO) {
                            MessageStore.touchLastSyncTimestamp(ctx, ts)
                        }
                    }
                    return
                }
                if (msg.from == "agent" && msg.content != null) {
                    val msgId = msg.id ?: ""
                    val packetKey = buildPacketKey(msg)
                    if (packetKey != null && !receivedPackets.add(packetKey)) return
                    handleAgentMessage(msgId.ifEmpty { UUID.randomUUID().toString() }, msg)
                }
            }
            "typing" -> {
                val isTyping = msg.status == "typing"
                if (isTyping) {
                    _uiState.update { s ->
                        s.copy(
                            isTyping = true,
                            typingStartedAt = if (!s.isTyping) System.currentTimeMillis() else s.typingStartedAt,
                            streamingMessageId = null,
                        )
                    }
                    scheduleTypingTimeout()
                } else {
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

    private fun handleAgentMessage(messageId: String, msg: WsIncomingMessage) {
        val timestamp = msg.timestamp ?: System.currentTimeMillis()
        var persistedMessage: Message? = null
        var shouldPersist = false

        _uiState.update { state ->
            val existingIndex = state.messages.indexOfFirst { it.id == messageId }
            val updatedMessages = state.messages.toMutableList()
            val nextMessage = when (msg.stream) {
                "continue" -> {
                    val base = if (existingIndex >= 0) updatedMessages[existingIndex] else Message(
                        id = messageId,
                        from = "agent",
                        timestamp = timestamp,
                        content = msg.content ?: MessageContent(type = "text"),
                    )
                    base.copy(
                        timestamp = maxOf(base.timestamp, timestamp),
                        content = base.content.copy(
                            text = (base.content.text ?: "") + (msg.content?.text ?: "")
                        ),
                    )
                }
                "end" -> Message(
                    id = messageId,
                    from = "agent",
                    timestamp = timestamp,
                    content = msg.content ?: MessageContent(type = "text"),
                )
                else -> Message(
                    id = messageId,
                    from = "agent",
                    timestamp = timestamp,
                    content = msg.content ?: MessageContent(type = "text"),
                )
            }

            if (existingIndex >= 0) {
                updatedMessages[existingIndex] = nextMessage
            } else {
                updatedMessages += nextMessage
            }

            shouldPersist = msg.stream != "continue"
            persistedMessage = nextMessage

            val isFinalMessage = msg.stream != "continue"
            state.copy(
                messages = updatedMessages,
                streamingMessageId = if (msg.stream == "continue") messageId else null,
                isTyping = if (isFinalMessage) false else state.isTyping,
                // 收到内容时刷新 typingStartedAt，避免显示"已等待 X 秒"（实际是距离上次 typing 开始而非距离最新内容到达）
                typingStartedAt = if (isFinalMessage) null else if (msg.stream == "continue") System.currentTimeMillis() else state.typingStartedAt,
            )
        }

        if (shouldPersist) {
            // 最终消息到达，强制清除 typing 状态
            viewModelScope.launch {
                delay(500)
                _uiState.update { it.copy(isTyping = false, typingStartedAt = null) }
            }
            persistMessages(_uiState.value.messages, timestamp)
        }
        notifyIfNeeded(msg, persistedMessage)
    }

    private fun scheduleTypingTimeout() {
        typingTimeoutJob?.cancel()
        typingTimeoutJob = viewModelScope.launch {
            delay(10 * 60 * 1000L)
            _uiState.update { it.copy(isTyping = false, typingStartedAt = null) }
        }
    }

    private fun buildPacketKey(msg: WsIncomingMessage): String? {
        val id = msg.id ?: return null
        return when (msg.stream) {
            "continue", "end" -> "$id:${msg.stream}:${msg.content?.text.orEmpty()}"
            else -> "$id:${msg.stream ?: "single"}"
        }
    }

    private fun notifyIfNeeded(msg: WsIncomingMessage, message: Message?) {
        if (message == null) return
        if (msg.stream == "continue") return
        val preview = message.content.text?.take(80)?.trim().orEmpty()
        if (preview.isNotBlank()) {
            NotificationHelper.showMessageNotification(application, "OpenClaw", preview)
        }
    }

    private fun markConnected() {
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) {
            _uiState.update { it.copy(connectionState = ConnectionState.CONNECTED) }
        }
    }

    private fun persistMessages(messages: List<Message>, latestTimestamp: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            MessageStore.saveAll(ctx, gson, messages)
            latestTimestamp?.let { MessageStore.touchLastSyncTimestamp(ctx, it) }
        }
    }

    fun onInputChange(text: String) { _uiState.update { it.copy(inputText = text) } }

    fun sendTextMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return
        _uiState.update { it.copy(inputText = "") }
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val userMsg = Message(id = msgId, from = "user", timestamp = now, content = MessageContent("text", text))
        _uiState.update { it.copy(messages = it.messages + userMsg) }
        persistMessages(_uiState.value.messages)
        repository.wsManager.sendMessage(WsOutgoingMessage(id = msgId, content = MessageContent("text", text)))
    }

    fun sendImageMessage(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true) }
            val result = repository.uploadMedia(uri, "image/jpeg")
            _uiState.update { it.copy(isUploading = false) }
            result.getOrNull()?.let { upload ->
                val msgId = UUID.randomUUID().toString()
                val content = MessageContent(type = "image", fileId = upload.fileId, url = upload.url)
                val userMsg = Message(msgId, "user", System.currentTimeMillis(), content)
                _uiState.update { it.copy(messages = it.messages + userMsg) }
                persistMessages(_uiState.value.messages)
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
                val content = MessageContent(type = "voice", fileId = upload.fileId, url = upload.url, durationMs = durationMs)
                val userMsg = Message(msgId, "user", System.currentTimeMillis(), content)
                _uiState.update { it.copy(messages = it.messages + userMsg) }
                persistMessages(_uiState.value.messages)
                repository.wsManager.sendMessage(WsOutgoingMessage(id = msgId, content = content))
                _uiState.update { it.copy(infoMsg = "语音已发送") }
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
                    fileId = upload.fileId, url = upload.url, filename = filename, size = upload.size,
                )
                val userMsg = Message(msgId, "user", System.currentTimeMillis(), content)
                _uiState.update { it.copy(messages = it.messages + userMsg) }
                persistMessages(_uiState.value.messages)
                repository.wsManager.sendMessage(WsOutgoingMessage(id = msgId, content = content))
                if (isVideo) _uiState.update { it.copy(infoMsg = "视频已发送") }
            } ?: _uiState.update { it.copy(errorMsg = "文件上传失败") }
        }
    }

    fun downloadFile(fileId: String, filename: String, mimeType: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingFileId = fileId) }
            val result = repository.downloadMedia(fileId, filename, mimeType)
            _uiState.update { it.copy(downloadingFileId = null) }
            result.fold(
                onSuccess = { _uiState.update { s -> s.copy(infoMsg = "已保存: $filename") } },
                onFailure = { e -> _uiState.update { s -> s.copy(errorMsg = "下载失败: ${e.message}") } },
            )
        }
    }

    fun dismissError() { _uiState.update { it.copy(errorMsg = null) } }
    fun dismissInfo() { _uiState.update { it.copy(infoMsg = null) } }

    override fun onCleared() {
        super.onCleared()
        typingTimeoutJob?.cancel()
        repository.disconnectWebSocket()
    }
}
