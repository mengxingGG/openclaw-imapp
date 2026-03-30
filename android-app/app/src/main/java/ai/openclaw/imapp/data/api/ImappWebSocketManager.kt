package ai.openclaw.imapp.data.api

import ai.openclaw.imapp.data.model.WsIncomingMessage
import ai.openclaw.imapp.data.model.WsOutgoingMessage
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

@Singleton
class ImappWebSocketManager @Inject constructor(
    private val gson: Gson,
    private val clientFactory: ApiClientFactory,
) {
    private val _messages = MutableSharedFlow<WsIncomingMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<WsIncomingMessage> = _messages

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _onConnected = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val onConnected: SharedFlow<Unit> = _onConnected

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectWatchdog: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 连接标识，防止旧连接回调污染新连接
    private val connectionId = AtomicInteger(0)
    @Volatile private var activeConnectionId = 0

    private var serverUrl: String = ""
    private var token: String = ""
    private var userId: String = ""
    private var reconnectAttempts = 0
    private val maxReconnectDelay = 60_000L

    fun connect(serverUrl: String, token: String, userId: String) {
        if (serverUrl == this.serverUrl && token == this.token &&
            (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING)) {
            return // 已经在连接或已连接，参数相同，跳过
        }
        this.serverUrl = serverUrl
        this.token = token
        this.userId = userId
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        // 取消旧连接
        webSocket?.cancel()
        webSocket = null
        reconnectJob?.cancel()
        connectWatchdog?.cancel()

        val myId = connectionId.incrementAndGet()
        activeConnectionId = myId

        _connectionState.value = if (reconnectAttempts > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING

        val wsUrl = buildWsUrl()
        val request = Request.Builder().url(wsUrl).build()

        // 15秒连接超时看门狗
        connectWatchdog = scope.launch {
            delay(15_000)
            if (activeConnectionId == myId && _connectionState.value != ConnectionState.CONNECTED) {
                webSocket?.cancel()
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect(myId)
            }
        }

        webSocket = clientFactory.okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (activeConnectionId != myId) return // 忽略旧连接的回调
                connectWatchdog?.cancel()
                reconnectAttempts = 0
                _connectionState.value = ConnectionState.CONNECTED
                startPing()
                scope.launch { _onConnected.emit(Unit) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (activeConnectionId != myId) return
                if (_connectionState.value != ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.CONNECTED
                }
                try {
                    val msg = gson.fromJson(text, WsIncomingMessage::class.java)
                    scope.launch { _messages.emit(msg) }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (activeConnectionId != myId) return // 忽略旧连接的回调
                connectWatchdog?.cancel()
                _connectionState.value = ConnectionState.DISCONNECTED
                stopPing()
                scheduleReconnect(myId)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (activeConnectionId != myId) return
                connectWatchdog?.cancel()
                _connectionState.value = ConnectionState.DISCONNECTED
                stopPing()
                if (code != 1000) scheduleReconnect(myId)
            }
        })
    }

    fun disconnect() {
        reconnectJob?.cancel()
        pingJob?.cancel()
        connectWatchdog?.cancel()
        // 用 cancel 而非 close，确保快速释放
        webSocket?.cancel()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendMessage(msg: WsOutgoingMessage): Boolean {
        val ws = webSocket ?: return false
        return ws.send(gson.toJson(msg))
    }

    fun sendRaw(json: String): Boolean = webSocket?.send(json) ?: false

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val ws = webSocket
                if (ws != null && _connectionState.value == ConnectionState.CONNECTED) {
                    val ok = ws.send("""{"type":"ping"}""")
                    if (!ok) {
                        // ping 失败，触发重连
                        ws.cancel()
                    }
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect(myId: Int) {
        // 只有当前活跃连接才允许重连
        if (myId != activeConnectionId) return
        if (serverUrl.isEmpty() || token.isEmpty()) return
        reconnectJob?.cancel()
        val delay = minOf(1000L * (1 shl minOf(reconnectAttempts, 6)), maxReconnectDelay)
        reconnectAttempts++
        reconnectJob = scope.launch {
            delay(delay)
            if (myId == activeConnectionId) doConnect()
        }
    }

    private fun buildWsUrl(): String {
        val base = serverUrl.trimEnd('/')
        val wsBase = base.replace("http://", "ws://").replace("https://", "wss://")
        return "$wsBase/imapp/ws?token=$token&user_id=$userId"
    }
}
