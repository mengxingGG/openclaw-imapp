package ai.openclaw.imapp.data.api

import ai.openclaw.imapp.data.model.*
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ImappApiService {

    // ==================== Auth ====================

    @POST("auth/qrcode")
    suspend fun getQrCode(@Body request: QrCodeRequest): Response<QrCodeResponse>

    @POST("auth/poll")
    suspend fun pollStatus(@Body request: PollRequest): Response<PollResponse>

    @POST("auth/get-token")
    suspend fun getTokenBySessionKey(@Body request: GetTokenRequest): Response<GetTokenResponse>

    @POST("auth/verify")
    suspend fun verifySession(@Body request: VerifyRequest): Response<VerifyResponse>

    @POST("auth/verify")
    suspend fun verifyToken(@Body request: VerifyTokenRequest): Response<VerifyTokenResponse>

    // ==================== Messages ====================

    @POST("messages/history")
    suspend fun getHistory(
        @Header("Authorization") auth: String,
        @Body request: MessageHistoryRequest,
    ): Response<MessageHistoryResponse>

    // ==================== Devices ====================

    @GET("devices")
    suspend fun getDevices(@Header("Authorization") auth: String): Response<DeviceListResponse>

    @DELETE("devices/{deviceId}")
    suspend fun revokeDevice(
        @Header("Authorization") auth: String,
        @Path("deviceId") deviceId: String,
    ): Response<Map<String, Boolean>>

    // ==================== Media ====================

    @POST("media/upload-url")
    suspend fun getUploadUrl(
        @Header("Authorization") auth: String,
        @Body request: UploadUrlRequest,
    ): Response<UploadUrlResponse>

    @POST("media/upload/{fileId}")
    suspend fun uploadMedia(
        @Header("Authorization") auth: String,
        @Header("Content-Type") contentType: String,
        @Path("fileId") fileId: String,
        @Body body: RequestBody,
    ): Response<UploadResponse>

    // ==================== FCM ====================

    @POST("fcm/register")
    suspend fun registerFcmToken(
        @Header("Authorization") auth: String,
        @Body request: FcmRegisterRequest,
    ): Response<Map<String, Boolean>>

    // ==================== Health ====================

    @GET("health")
    suspend fun health(): Response<HealthResponse>
}
