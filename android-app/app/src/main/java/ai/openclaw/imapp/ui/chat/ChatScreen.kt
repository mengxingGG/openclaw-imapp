package ai.openclaw.imapp.ui.chat

import ai.openclaw.imapp.data.api.ConnectionState
import ai.openclaw.imapp.data.model.Message
import android.Manifest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import androidx.compose.material3.ExperimentalMaterial3Api
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val dateFmt = SimpleDateFormat("M月d日", Locale.getDefault())
private val todayFmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showMediaSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 权限
    val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // 媒体选择器
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.sendImageMessage(it) }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { u ->
            val mime = context.contentResolver.getType(u) ?: "video/mp4"
            viewModel.sendFileMessage(u, mime, "视频")
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { u ->
            val mime = context.contentResolver.getType(u) ?: "application/octet-stream"
            val cursor = context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            val filename = cursor?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "文件"
            viewModel.sendFileMessage(u, mime, filename)
        }
    }

    // 录音状态
    var isRecording by remember { mutableStateOf(false) }
    var recorder: MediaRecorder? by remember { mutableStateOf(null) }
    var recordFile: File? by remember { mutableStateOf(null) }
    var recordStartMs by remember { mutableStateOf(0L) }

    // 当有消息被追加到顶部时，保持当前可见位置（防止滚动跳动）
    LaunchedEffect(state.prependedCount) {
        if (state.prependedCount > 0) {
            // 追加了 n 条，当前第一条可见项的 index 偏移了 n
            val newIndex = listState.firstVisibleItemIndex + state.prependedCount
            listState.scrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
            viewModel.consumePrependedCount()
        }
    }

    // 当滚动到顶部时加载更多历史消息
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && !state.isLoadingMore && state.hasMoreHistory) {
            viewModel.loadMoreHistory()
        }
    }

    // 新消息自动滚动到底部
    LaunchedEffect(state.messages.size, state.isTyping) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size + (if (state.isTyping) 1 else 0))
        }
    }

    // 显示错误和信息 snackbar
    LaunchedEffect(state.errorMsg) {
        state.errorMsg?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.infoMsg) {
        state.infoMsg?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.dismissInfo()
        }
    }

    // 将消息列表按日期分组（用于日期分隔线）
    val messagesWithDates = remember(state.messages) {
        buildMessageListWithDateSeparators(state.messages)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.ime,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "OpenClaw",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = when (state.connectionState) {
                                ConnectionState.CONNECTED -> Color(0x1410A37F)
                                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0x1494A3B8)
                                ConnectionState.DISCONNECTED -> Color(0x14EF4444)
                            },
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                            when (state.connectionState) {
                                ConnectionState.CONNECTED -> "已连接"
                                ConnectionState.CONNECTING -> "连接中..."
                                ConnectionState.RECONNECTING -> "重连中..."
                                ConnectionState.DISCONNECTED -> "未连接"
                            },
                                fontSize = 10.sp,
                                color = when (state.connectionState) {
                                    ConnectionState.CONNECTED -> Color(0xFF10A37F)
                                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFF64748B)
                                    ConnectionState.DISCONNECTED -> Color(0xFFDC2626)
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ) {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 历史消息加载中
            AnimatedVisibility(state.isLoadingHistory) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // 加载更多历史消息的 loading 指示器
                if (state.isLoadingMore) {
                    item(key = "loading_more") {
                        Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                items(messagesWithDates, key = { item ->
                    when (item) {
                        is MessageListItem.DateSeparator -> "date_${item.label}"
                        is MessageListItem.MessageItem -> item.message.id
                    }
                }) { item ->
                    when (item) {
                        is MessageListItem.DateSeparator -> DateSeparator(item.label)
                        is MessageListItem.MessageItem -> MessageBubble(
                            message = item.message,
                            serverUrl = state.serverUrl,
                            token = state.sessionToken,
                            downloadingFileId = state.downloadingFileId,
                            onDownload = viewModel::downloadFile,
                        )
                    }
                }
                if (state.messages.isEmpty() && !state.isLoadingHistory) {
                    item(key = "empty_state") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 32.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(28.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("开始第一条对话", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "你可以发送文本、图片、语音、视频或文件，消息会通过插件安全转发到 OpenClaw。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                }
                if (state.isTyping) {
                    item(key = "typing_indicator") {
                        TypingIndicator(typingStartedAt = state.typingStartedAt)
                    }
                }
            }

            // 上传进度
            AnimatedVisibility(state.isUploading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()

            // 输入栏
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(onClick = { showMediaSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "附件", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = viewModel::onInputChange,
                        placeholder = { Text(if (isRecording) "录音中，再点一次结束" else "输入消息...", color = Color.LightGray) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )

                    Spacer(Modifier.width(6.dp))

                    if (state.inputText.isNotEmpty()) {
                        IconButton(
                            onClick = viewModel::sendTextMessage,
                            modifier = Modifier
                                .size(46.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "发送", tint = Color.White)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (!isRecording) {
                                    if (audioPermission.status.isGranted) {
                                        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.amr")
                                        recordFile = file
                                        recordStartMs = System.currentTimeMillis()
                                        @Suppress("DEPRECATION")
                                        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            MediaRecorder(context)
                                        } else {
                                            MediaRecorder()
                                        }
                                        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                                        mr.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                                        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                                        mr.setOutputFile(file.absolutePath)
                                        mr.prepare()
                                        mr.start()
                                        recorder = mr
                                        isRecording = true
                                    } else {
                                        audioPermission.launchPermissionRequest()
                                    }
                                } else {
                                    val durationMs = System.currentTimeMillis() - recordStartMs
                                    recorder?.stop()
                                    recorder?.release()
                                    recorder = null
                                    isRecording = false
                                    recordFile?.let { file ->
                                        if (file.exists() && durationMs > 500) {
                                            viewModel.sendVoiceMessage(file.readBytes(), durationMs)
                                            file.delete()
                                        } else {
                                            file.delete()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .background(
                                    if (isRecording) Color(0xFFEF4444) else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                ),
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = if (isRecording) "停止录音" else "录音",
                                tint = if (isRecording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // 媒体选择底部弹窗
    if (showMediaSheet) {
        ModalBottomSheet(onDismissRequest = { showMediaSheet = false }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("选择发送内容", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MediaOption(icon = Icons.Default.Image, label = "图片") {
                        showMediaSheet = false
                        imagePicker.launch("image/*")
                    }
                    MediaOption(icon = Icons.Default.VideoLibrary, label = "视频") {
                        showMediaSheet = false
                        videoPicker.launch("video/*")
                    }
                    MediaOption(icon = Icons.Default.AttachFile, label = "文件") {
                        showMediaSheet = false
                        filePicker.launch("*/*")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 日期分隔线 */
@Composable
private fun DateSeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(Color(0x20888888), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 3.dp),
        ) {
            Text(label, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

/** 消息列表项（消息或日期分隔线） */
sealed class MessageListItem {
    data class DateSeparator(val label: String) : MessageListItem()
    data class MessageItem(val message: Message) : MessageListItem()
}

/** 在消息列表中插入日期分隔线 */
private fun buildMessageListWithDateSeparators(messages: List<Message>): List<MessageListItem> {
    val result = mutableListOf<MessageListItem>()
    val today = todayFmt.format(Date())
    var lastDateKey = ""

    for (message in messages) {
        val dateKey = todayFmt.format(Date(message.timestamp))
        if (dateKey != lastDateKey) {
            val label = if (dateKey == today) "今天" else dateFmt.format(Date(message.timestamp))
            result.add(MessageListItem.DateSeparator(label))
            lastDateKey = dateKey
        }
        result.add(MessageListItem.MessageItem(message))
    }
    return result
}

@Composable
fun MediaOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(8.dp),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp)
    }
}
