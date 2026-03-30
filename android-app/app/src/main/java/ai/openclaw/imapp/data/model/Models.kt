package ai.openclaw.imapp.data.model

import com.google.gson.annotations.SerializedName

// ==================== Auth Models ====================

data class UserInfo(
    val id: String,
    val name: String,
    val role: String,
)

data class VerifyRequest(
    @SerializedName("session_token") val sessionToken: String,
)

data class VerifyResponse(
    val valid: Boolean,
    @SerializedName("user_id") val userId: String? = null,
)

data class VerifyTokenRequest(
    val token: String,
    @SerializedName("device_code") val deviceCode: String? = null,
)

data class VerifyTokenResponse(
    val success: Boolean,
    val valid: Boolean = false,
    val error: String? = null,
    val errorMsg: String? = null,
    val user: UserInfo? = null,
    @SerializedName("session_token") val sessionToken: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val deviceCode: String? = null,
)

// ==================== Message Models ====================

data class MessageHistoryRequest(
    val limit: Int = 50,
    val before: String? = null,
    val after: Long? = null,  // 增量同步：只返回 timestamp > after 的消息
)

data class MessageHistoryResponse(
    val messages: List<Message>,
    @SerializedName("has_more") val hasMore: Boolean,
)

data class Message(
    val id: String,
    val from: String,          // "user" | "agent"
    val timestamp: Long,
    val content: MessageContent,
    val read: Boolean = false,
)

data class MessageContent(
    val type: String,          // text | image | voice | video | file
    val text: String? = null,
    val url: String? = null,
    @SerializedName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerializedName("duration_ms") val durationMs: Long? = null,
)

// ==================== Device Models ====================

data class DeviceListResponse(
    val devices: List<Device>,
)

data class Device(
    val id: String,
    val name: String,
    @SerializedName("last_active") val lastActive: Long,
    @SerializedName("is_current") val isCurrent: Boolean,
)

// ==================== Media Models ====================

data class UploadUrlRequest(
    val type: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null,
)

data class UploadUrlResponse(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("upload_url") val uploadUrl: String,
    @SerializedName("expires_at") val expiresAt: Long,
    val type: String,
)

data class UploadResponse(
    @SerializedName("file_id") val fileId: String,
    val url: String,
    @SerializedName("mime_type") val mimeType: String,
    val size: Long,
)

// ==================== FCM Models ====================

data class FcmRegisterRequest(
    @SerializedName("fcm_token") val fcmToken: String,
    @SerializedName("device_name") val deviceName: String? = null,
)

// ==================== Health ====================

data class HealthResponse(
    val status: String,
    @SerializedName("online_clients") val onlineClients: Int,
    val timestamp: Long,
)

// ==================== WebSocket Message Models ====================

data class WsOutgoingMessage(
    val type: String = "message",
    val id: String,
    val content: MessageContent,
)

data class WsIncomingMessage(
    val type: String,   // message | pong | read | typing | error | system
    val id: String? = null,
    val from: String? = null,
    val timestamp: Long? = null,
    val content: MessageContent? = null,
    @SerializedName("message_ids") val messageIds: List<String>? = null,
    val status: String? = null,
    val error: WsError? = null,
    val stream: String? = null,   // "start" | "continue" | null
)

data class WsError(
    val code: String,
    val message: String,
)

// ==================== Local Config ====================

data class ServerConfig(
    val serverUrl: String,       // e.g. http://122.51.4.46:3100
    val sessionToken: String?,
    val userId: String?,
    val userName: String?,
    val deviceId: String,
)
