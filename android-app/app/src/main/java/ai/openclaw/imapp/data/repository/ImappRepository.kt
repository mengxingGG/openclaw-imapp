package ai.openclaw.imapp.data.repository

import ai.openclaw.imapp.data.api.ApiClientFactory
import ai.openclaw.imapp.data.api.ImappWebSocketManager
import ai.openclaw.imapp.data.api.ServerConfigStore
import ai.openclaw.imapp.data.model.*
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImappRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ServerConfigStore,
    private val clientFactory: ApiClientFactory,
    val wsManager: ImappWebSocketManager,
) {
    private suspend fun api() = clientFactory.getApi(configStore.serverUrl.first() ?: "")
    private suspend fun authHeader() = "Bearer ${configStore.sessionToken.first() ?: ""}"

    // ==================== Auth ====================

    suspend fun getQrCode(deviceName: String, deviceId: String?): Result<QrCodeResponse> = runCatching {
        api().getQrCode(QrCodeRequest(deviceName, deviceId)).body()!!
    }

    suspend fun pollStatus(sessionKey: String): Result<PollResponse> = runCatching {
        api().pollStatus(PollRequest(sessionKey)).body()!!
    }

    suspend fun getTokenBySessionKey(sessionKey: String): Result<GetTokenResponse> = runCatching {
        api().getTokenBySessionKey(GetTokenRequest(sessionKey)).body()!!
    }

    suspend fun verifySession(token: String): Result<VerifyResponse> = runCatching {
        api().verifySession(VerifyRequest(token)).body()!!
    }

    suspend fun verifyToken(token: String, deviceCode: String? = null): Result<VerifyTokenResponse> = runCatching {
        api().verifyToken(VerifyTokenRequest(token, deviceCode)).body()!!
    }

    suspend fun saveSession(token: String, userId: String, userName: String) {
        configStore.saveSession(token, userId, userName)
    }

    suspend fun clearSession() = configStore.clearSession()

    // ==================== Messages ====================

    suspend fun getHistory(limit: Int = 50, before: String? = null): Result<MessageHistoryResponse> = runCatching {
        api().getHistory(authHeader(), MessageHistoryRequest(limit, before)).body()!!
    }

    // ==================== Devices ====================

    suspend fun getDevices(): Result<DeviceListResponse> = runCatching {
        api().getDevices(authHeader()).body()!!
    }

    suspend fun revokeDevice(deviceId: String): Result<Boolean> = runCatching {
        api().revokeDevice(authHeader(), deviceId).isSuccessful
    }

    // ==================== Media ====================

    suspend fun uploadMedia(uri: Uri, mimeType: String): Result<UploadResponse> = runCatching {
        val auth = authHeader()
        val urlResp = api().getUploadUrl(auth, UploadUrlRequest(mimeType = mimeType)).body()!!
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        api().uploadMedia(auth, mimeType, urlResp.fileId, body).body()!!
    }

    suspend fun uploadBytes(bytes: ByteArray, mimeType: String): Result<UploadResponse> = runCatching {
        val auth = authHeader()
        val urlResp = api().getUploadUrl(auth, UploadUrlRequest(mimeType = mimeType)).body()!!
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        api().uploadMedia(auth, mimeType, urlResp.fileId, body).body()!!
    }

    fun mediaUrl(serverUrl: String, token: String, fileId: String): String {
        return "${serverUrl.trimEnd('/')}/imapp/media/$fileId?token=$token"
    }

    /**
     * 下载媒体文件到设备 Downloads 目录
     * Returns the URI of the saved file or throws
     */
    suspend fun downloadMedia(fileId: String, filename: String, mimeType: String = "*/*"): Result<Uri> = runCatching {
        withContext(Dispatchers.IO) {
            val serverUrl = configStore.serverUrl.first() ?: error("No server URL")
            val token = configStore.sessionToken.first() ?: error("No session token")
            val url = "${serverUrl.trimEnd('/')}/imapp/media/$fileId?token=$token"

            val client = OkHttpClient()
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) error("Download failed: ${response.code}")

            val bytes = response.body!!.bytes()
            val resolvedMime = response.header("Content-Type")?.substringBefore(';')?.trim()
                ?: mimeType.ifBlank { "application/octet-stream" }

            saveToDownloads(bytes, filename, resolvedMime)
        }
    }

    private fun saveToDownloads(bytes: ByteArray, filename: String, mimeType: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create download entry")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = java.io.File(dir, filename)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
    }

    // ==================== FCM ====================

    suspend fun registerFcmToken(fcmToken: String, deviceName: String?): Result<Boolean> = runCatching {
        api().registerFcmToken(authHeader(), FcmRegisterRequest(fcmToken, deviceName)).isSuccessful
    }

    // ==================== WebSocket ====================

    suspend fun connectWebSocket() {
        val url = configStore.serverUrl.first() ?: return
        val token = configStore.sessionToken.first() ?: return
        val userId = configStore.userId.first() ?: return
        wsManager.connect(url, token, userId)
    }

    fun disconnectWebSocket() = wsManager.disconnect()

    // ==================== Health ====================

    suspend fun checkHealth(serverUrl: String): Boolean = runCatching {
        clientFactory.getApi(serverUrl).health().isSuccessful
    }.getOrDefault(false)
}

