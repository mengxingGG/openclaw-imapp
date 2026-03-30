package ai.openclaw.imapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.openclaw.imapp.MainActivity
import ai.openclaw.imapp.R

/**
 * 通知管理器
 * 负责创建通知渠道、发送消息通知
 */
object NotificationHelper {

    const val CHANNEL_MESSAGES = "imapp_messages"

    /** App 启动时调用，确保通知渠道已创建 */
    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 消息通知渠道（高优先级，有声音）
        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "消息通知",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "收到 OpenClaw 助手回复时通知"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
        }

        nm.createNotificationChannel(messageChannel)
    }

    /** 发送消息通知（收到 Agent 回复时调用） */
    fun showMessageNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 检查通知权限
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = "OPEN_CHAT"
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setGroup("imapp_messages")
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    /** 检查通知权限是否已开启 */
    fun isNotificationEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
