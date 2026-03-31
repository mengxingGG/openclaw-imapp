package ai.openclaw.imapp.ui.chat

import ai.openclaw.imapp.data.model.Message
import ai.openclaw.imapp.data.model.MessageContent
import ai.openclaw.imapp.ui.theme.AgentBubbleColor
import ai.openclaw.imapp.ui.theme.UserBubbleColor
import android.media.MediaPlayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun MessageBubble(
    message: Message,
    serverUrl: String,
    token: String,
    downloadingFileId: String?,
    onDownload: (fileId: String, filename: String, mimeType: String) -> Unit,
) {
    val isUser = message.from == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("AI", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            BubbleContent(
                content = message.content,
                isUser = isUser,
                serverUrl = serverUrl,
                token = token,
                downloadingFileId = downloadingFileId,
                onDownload = onDownload,
            )
            Text(
                fmt.format(Date(message.timestamp)),
                fontSize = 10.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
fun BubbleContent(
    content: MessageContent,
    isUser: Boolean,
    serverUrl: String,
    token: String,
    downloadingFileId: String?,
    onDownload: (fileId: String, filename: String, mimeType: String) -> Unit,
) {
    val bubbleBg = if (isUser) UserBubbleColor else AgentBubbleColor
    val textColor = if (isUser) Color.Black else Color.Black
    val shape = if (isUser)
        RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
    else
        RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

    when (content.type) {
        "text" -> {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(bubbleBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                MarkdownText(content.text ?: "", fontSize = MaterialTheme.typography.bodyMedium)
            }
        }

        "image" -> {
            val imageUrl = buildMediaUrl(serverUrl, token, content)
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .clip(shape)
                    .widthIn(max = 220.dp)
                    .heightIn(max = 280.dp),
                loading = {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(Color(0xFFEEEEEE)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(shape)
                            .background(Color(0xFFEEEEEE)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.Gray)
                            Text("图片加载失败", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                },
            )
            // 仅 Agent 发送的图片显示下载按钮
            if (!isUser && content.fileId != null) {
                val fileId = content.fileId
                val isDownloading = downloadingFileId == fileId
                TextButton(
                    onClick = { onDownload(fileId, "image_$fileId.jpg", "image/jpeg") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("保存图片", fontSize = 12.sp)
                }
            }
        }

        "voice" -> {
            VoiceBubble(
                content = content,
                isUser = isUser,
                bubbleBg = bubbleBg,
                shape = shape,
                serverUrl = serverUrl,
                token = token,
            )
        }

        "video" -> {
            val fileId = content.fileId
            val isDownloading = fileId != null && downloadingFileId == fileId
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(bubbleBg)
                    .clickable(enabled = !isUser && fileId != null) {
                        fileId?.let { onDownload(it, content.filename ?: "video_$it.mp4", "video/mp4") }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("🎬", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(content.filename ?: "视频", fontSize = 14.sp)
                        if (!isUser) {
                            Row {
                                if (content.size != null) {
                                    Text(formatFileSize(content.size!!), fontSize = 11.sp, color = Color.Gray)
                                    Text(" · ", fontSize = 11.sp, color = Color.Gray)
                                }
                                Text("点击下载", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        "file" -> {
            val fileId = content.fileId
            val isDownloading = fileId != null && downloadingFileId == fileId
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(bubbleBg)
                    .clickable(enabled = !isUser && fileId != null) {
                        fileId?.let { onDownload(it, content.filename ?: "file_$it", "application/octet-stream") }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .widthIn(min = 160.dp, max = 260.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            content.filename ?: "文件",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                        )
                        content.size?.let {
                            Text(formatFileSize(it), fontSize = 11.sp, color = Color.Gray)
                        }
                        if (!isUser) {
                            Text("点击下载", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(bubbleBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(content.text ?: "[消息]", fontSize = 15.sp)
            }
        }
    }
}

/** 可播放的语音消息气泡 */
@Composable
fun VoiceBubble(
    content: MessageContent,
    isUser: Boolean,
    bubbleBg: Color,
    shape: androidx.compose.ui.graphics.Shape,
    serverUrl: String,
    token: String,
) {
    val durationSec = content.durationMs?.let { it / 1000 } ?: 0L
    val durationLabel = if (durationSec > 0) "${durationSec}\"" else ""

    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // 当 Composable 离开组合树时释放 MediaPlayer
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    val audioUrl = buildMediaUrl(serverUrl, token, content)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(bubbleBg)
            .clickable {
                if (isPlaying) {
                    mediaPlayer?.pause()
                    isPlaying = false
                } else {
                    try {
                        val mp = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
                        if (mp.isPlaying) {
                            mp.pause()
                        } else {
                            // 如果还没设置 data source，或已播放完毕，重新设置
                            try {
                                mp.reset()
                                mp.setDataSource(audioUrl)
                                mp.prepareAsync()
                                mp.setOnPreparedListener { player ->
                                    player.start()
                                    isPlaying = true
                                }
                                mp.setOnCompletionListener {
                                    isPlaying = false
                                }
                                mp.setOnErrorListener { _, _, _ ->
                                    isPlaying = false
                                    true
                                }
                            } catch (_: Exception) {
                                isPlaying = false
                            }
                        }
                    } catch (_: Exception) {
                        isPlaying = false
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放语音",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("语音消息", fontSize = 14.sp)
            if (durationLabel.isNotEmpty()) {
                Text(durationLabel, fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

/** 构建完整的媒体 URL（处理相对路径和绝对路径） */
fun buildMediaUrl(serverUrl: String, token: String, content: MessageContent): String {
    val raw = content.url ?: "/imapp/media/${content.fileId}"
    val base = if (raw.startsWith("http")) raw else "${serverUrl.trimEnd('/')}$raw"
    return if (base.contains("?")) "$base&token=$token" else "$base?token=$token"
}

/** 格式化文件大小 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
fun TypingIndicator(typingStartedAt: Long?) {
    // 每秒更新一次，使经过时间实时刷新
    var elapsedSec by remember { mutableStateOf(0L) }
    LaunchedEffect(typingStartedAt) {
        if (typingStartedAt == null) {
            elapsedSec = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedSec = (System.currentTimeMillis() - typingStartedAt) / 1000
            delay(1000)
        }
    }
    // 防御性检查：typingStartedAt 为 null 时不显示"等待"文字
    if (typingStartedAt == null) return

    val subText = when {
        elapsedSec >= 60 -> "已等待 ${elapsedSec / 60} 分 ${elapsedSec % 60} 秒..."
        elapsedSec >= 20 -> "处理时间较长，请稍候..."
        else -> null
    }

    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("AI", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(AgentBubbleColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                AnimatedDots()
                subText?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    }
}

/** 三个弹跳小圆点动画 */
@Composable
fun AnimatedDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val delay = index * 150
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0.6f at 0 + delay
                        1f at 200 + delay
                        0.6f at 400 + delay
                        0.6f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot_$index",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .background(Color.Gray, CircleShape),
            )
        }
    }
}
