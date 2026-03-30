package ai.openclaw.imapp.ui.settings

import ai.openclaw.imapp.data.model.Device
import ai.openclaw.imapp.service.NotificationHelper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(52.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(state.userName.ifEmpty { "未命名设备" }, style = MaterialTheme.typography.titleLarge)
                                Text("当前已连接到 OpenClaw IMApp", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        InfoRow("服务器", state.serverUrl)
                        InfoRow("设备数量", state.devices.size.toString())
                    }
                }
            }

            item {
                // ==================== 通知与权限设置 ====================
                Text("通知与权限", style = MaterialTheme.typography.titleMedium)
                Text(
                    "确保消息通知正常接收，建议完成以下设置。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            item { NotificationPermissionCard() }

            item { BatteryOptimizationCard() }

            item { AutoStartCard() }

            item {
                Spacer(Modifier.height(12.dp))
                Text("设备管理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "查看已登录设备，及时清理不再使用的终端。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            if (state.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(state.devices) { device ->
                    DeviceCard(device = device, onRevoke = { viewModel.revokeDevice(device.id) })
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("退出登录")
                }
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("确认退出") },
            text = { Text("退出后需要重新输入服务器地址和 Token") },
            confirmButton = {
                TextButton(onClick = { viewModel.logout(onLogout) }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value.ifEmpty { "-" }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DeviceCard(device: Device, onRevoke: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var showConfirm by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = if (device.isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (device.isCurrent) Icons.Default.PhoneAndroid else Icons.Default.Devices,
                        contentDescription = null,
                        tint = if (device.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name + if (device.isCurrent) " · 当前设备" else "", fontSize = 15.sp)
                Text("最后活跃: ${fmt.format(Date(device.lastActive))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!device.isCurrent) {
                IconButton(onClick = { showConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "移除", tint = Color.Red)
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("移除设备") },
            text = { Text("确认移除 ${device.name}？") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onRevoke() }) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("取消") } },
        )
    }
}

// ==================== 权限引导卡片 ====================

/** 通知权限卡片 */
@Composable
private fun NotificationPermissionCard() {
    val context = LocalContext.current
    val isEnabled = NotificationHelper.isNotificationEnabled(context)

    PermissionGuideCard(
        icon = if (isEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
        iconTint = if (isEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
        title = "消息通知",
        description = if (isEnabled) "通知权限已开启" else "通知权限未开启，将无法收到消息提醒",
        actionLabel = if (isEnabled) null else "去开启",
        onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            } else {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
        },
        isOk = isEnabled,
    )
}

/** 电池优化白名单卡片 */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current

    PermissionGuideCard(
        icon = Icons.Default.BatteryAlert,
        iconTint = Color(0xFFFF9800),
        title = "电池优化",
        description = "建议将 OpenClaw 加入电池优化白名单，避免后台被清理",
        actionLabel = "去设置",
        onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
        },
        isOk = false,
    )
}

/** 自启动权限卡片 */
@Composable
private fun AutoStartCard() {
    val context = LocalContext.current
    val manufacturer = Build.MANUFACTURER.lowercase()

    PermissionGuideCard(
        icon = Icons.Default.PowerSettingsNew,
        iconTint = Color(0xFF2196F3),
        title = "自启动权限",
        description = "允许 OpenClaw 开机后自动启动并保持连接",
        actionLabel = "去设置",
        onClick = {
            val intent = when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        putExtra("extra_pkgname", context.packageName)
                    }
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    }
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                    }
                }
                manufacturer.contains("vivo") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    }
                }
                else -> {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
        },
        isOk = false,
    )
}

/** 通用权限引导卡片 */
@Composable
private fun PermissionGuideCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    actionLabel: String?,
    onClick: () -> Unit,
    isOk: Boolean,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = iconTint.copy(alpha = 0.12f),
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            }
            if (actionLabel != null) {
                TextButton(onClick = onClick) {
                    Text(actionLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Icon(Icons.Default.CheckCircle, contentDescription = "已完成", tint = Color(0xFF4CAF50))
            }
        }
    }
}
