package ai.openclaw.imapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 开机后启动保活服务（不启动 Activity）
            ImappKeepAliveService.start(context)
        }
    }
}
