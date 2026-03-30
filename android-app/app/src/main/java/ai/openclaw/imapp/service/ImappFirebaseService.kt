package ai.openclaw.imapp.service

import ai.openclaw.imapp.data.api.ServerConfigStore
import ai.openclaw.imapp.data.repository.ImappRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import ai.openclaw.imapp.MainActivity
import ai.openclaw.imapp.R

@AndroidEntryPoint
class ImappFirebaseService : FirebaseMessagingService() {

    @Inject lateinit var repository: ImappRepository
    @Inject lateinit var configStore: ServerConfigStore

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch {
            try {
                // 向服务器注册新的 FCM token
                val sessionToken = configStore.sessionToken.first()
                if (!sessionToken.isNullOrEmpty()) {
                    repository.registerFcmToken(token, android.os.Build.MODEL)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "OpenClaw IMApp"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["text"]
            ?: "您有新消息"

        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "imapp_messages"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 创建通知渠道（Android 8+）
        val channel = NotificationChannel(
            channelId,
            "消息通知",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "来自 OpenClaw 助手的消息"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)

        // 点击通知打开聊天界面
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_CHAT"
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
