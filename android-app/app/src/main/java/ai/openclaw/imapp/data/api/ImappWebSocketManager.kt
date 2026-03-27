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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverUrl: String = ""
    private var token: String = ""
    private var userId: String = ""
    private var reconnectAttempts = 0
    private val maxReconnectDelay = 60_000L

    fun connect(serverUrl: String, token: String, userId: String) {
        this.serverUrl = serverUrl
        this.token = token
        this.userId = userId
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        _connectionState.value = if (reconnectAttempts > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        val wsUrl = buildWsUrl()
        val request = Request.Builder().url(wsUrl).build()

        webSocket = clientFactory.okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectAttempts = 0
                _connectionState.value = ConnectionState.CONNECTED
                startPing()
                scope.launch { _onConnected.emit(Unit) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = gson.fromJson(text, WsIncomingMessage::class.java)
                    scope.launch { _messages.emit(msg) }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.DISCONNECTED
                stopPing()
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                stopPing()
                if (code != 1000) scheduleReconnect()  // 非正常关闭则重连
            }
        })
    }

    fun disconnect() {
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "User disconnected")
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
                webSocket?.send("""{"type":"ping"}""")
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect() {
        if (serverUrl.isEmpty() || token.isEmpty()) return
        reconnectJob?.cancel()
        val delay = minOf(1000L * (1 shl minOf(reconnectAttempts, 6)), maxReconnectDelay)
        reconnectAttempts++
        reconnectJob = scope.launch {
            delay(delay)
            doConnect()
        }
    }

    private fun buildWsUrl(): String {
        val base = serverUrl.trimEnd('/')
        val wsBase = base.replace("http://", "ws://").replace("https://", "wss://")
        return "$wsBase/imapp/ws?token=$token&user_id=$userId"
    }
}
