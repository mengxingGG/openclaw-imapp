package ai.openclaw.imapp.data.repository

import ai.openclaw.imapp.data.api.ApiClientFactory
import ai.openclaw.imapp.data.api.ImappWebSocketManager
import ai.openclaw.imapp.data.api.ServerConfigStore
import ai.openclaw.imapp.data.local.MessageStore
import ai.openclaw.imapp.data.model.*
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.InputStream
import java.io.OutputStream
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

    /** 安全解析 Response body，避免 body()!! NPE */
    private inline fun <T> parseBody(response: Response<T>): T {
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code()}: ${response.message()}")
        return response.body() ?: throw RuntimeException("Empty response body")
    }

    // ==================== Auth ====================

    suspend fun verifySession(token: String): Result<VerifyResponse> = runCatching {
        parseBody(api().verifySession(VerifyRequest(token)))
    }

    suspend fun verifyToken(token: String, deviceCode: String? = null, serverUrl: String? = null): Result<VerifyTokenResponse> = runCatching {
        val baseUrl = serverUrl ?: configStore.serverUrl.first() ?: ""
        // 重置 API 缓存，确保用最新的 baseUrl 构建
        clientFactory.resetApi()
        parseBody(clientFactory.getApi(baseUrl).verifyToken(VerifyTokenRequest(token, deviceCode)))
    }

    suspend fun saveSession(token: String, userId: String, userName: String) {
        configStore.saveSession(token, userId, userName)
    }

    suspend fun clearSession() = configStore.clearSession()

    suspend fun clearLocalSessionData() {
        MessageStore.clear(context)
        configStore.clearSession()
    }

    // ==================== Messages ====================

    suspend fun getHistory(limit: Int = 50, before: Long? = null, after: Long? = null): Result<MessageHistoryResponse> = runCatching {
        parseBody(api().getHistory(authHeader(), MessageHistoryRequest(limit, before?.toString(), after)))
    }

    // ==================== Devices ====================

    suspend fun getDevices(): Result<DeviceListResponse> = runCatching {
        parseBody(api().getDevices(authHeader()))
    }

    suspend fun revokeDevice(deviceId: String): Result<Boolean> = runCatching {
        api().revokeDevice(authHeader(), deviceId).isSuccessful
    }

    // ==================== Media ====================

    suspend fun uploadMedia(uri: Uri, mimeType: String): Result<UploadResponse> = runCatching {
        val auth = authHeader()
        val urlResp = parseBody(api().getUploadUrl(auth, UploadUrlRequest(mimeType = mimeType)))
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val body = inputStream.readBytes().toRequestBody(mimeType.toMediaTypeOrNull())
            parseBody(api().uploadMedia(auth, mimeType, urlResp.fileId, body))
        } ?: throw RuntimeException("Cannot open file")
    }

    suspend fun uploadBytes(bytes: ByteArray, mimeType: String): Result<UploadResponse> = runCatching {
        val auth = authHeader()
        val urlResp = parseBody(api().getUploadUrl(auth, UploadUrlRequest(mimeType = mimeType)))
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        parseBody(api().uploadMedia(auth, mimeType, urlResp.fileId, body))
    }

    fun mediaUrl(serverUrl: String, token: String, fileId: String): String {
        return "${serverUrl.trimEnd('/')}/imapp/media/$fileId?token=$token"
    }

    /**
     * 下载媒体文件到设备 Downloads 目录（流式写入，避免大文件 OOM）
     */
    suspend fun downloadMedia(fileId: String, filename: String, mimeType: String = "*/*"): Result<Uri> = runCatching {
        withContext(Dispatchers.IO) {
            val serverUrl = configStore.serverUrl.first() ?: error("No server URL")
            val token = configStore.sessionToken.first() ?: error("No session token")
            val url = "${serverUrl.trimEnd('/')}/imapp/media/$fileId?token=$token"

            val client = clientFactory.okHttpClient
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) error("Download failed: ${response.code}")

            val resolvedMime = response.header("Content-Type")?.substringBefore(';')?.trim()
                ?: mimeType.ifBlank { "application/octet-stream" }

            val responseBody = response.body ?: error("Empty response body")
            saveToDownloads(responseBody.byteStream(), filename, resolvedMime)
        }
    }

    /** 流式写入文件，避免整体读入内存 */
    private fun saveToDownloads(inputStream: InputStream, filename: String, mimeType: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create download entry")
            resolver.openOutputStream(uri)?.use { output ->
                copyStream(inputStream, output)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = java.io.File(dir, filename)
            file.outputStream().use { output ->
                copyStream(inputStream, output)
            }
            Uri.fromFile(file)
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
    }

    // ==================== FCM ====================

    suspend fun registerFcmToken(fcmToken: String, deviceName: String?): Result<Boolean> = runCatching {
        api().registerFcmToken(authHeader(), FcmRegisterRequest(fcmToken, deviceName)).isSuccessful
    }

    suspend fun syncCurrentFcmToken(deviceName: String? = Build.MODEL): Result<Boolean> = runCatching {
        val fcmToken = awaitFirebaseToken() ?: return@runCatching false
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

    private suspend fun awaitFirebaseToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (continuation.isActive) {
                    continuation.resume(token, onCancellation = null)
                }
            }
            .addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(null, onCancellation = null)
                }
            }
    }
}
