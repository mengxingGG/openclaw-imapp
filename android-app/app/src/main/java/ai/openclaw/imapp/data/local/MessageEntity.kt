package ai.openclaw.imapp.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["timestamp"]), Index(value = ["from"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val from: String,           // "user" | "agent"
    val timestamp: Long,
    val contentType: String,    // "text" | "image" | "voice" | "video" | "file"
    val text: String?,
    val url: String?,
    val fileId: String?,
    val filename: String?,
    val fileSize: Long?,
    val durationMs: Long?,
    val synced: Boolean = true, // 是否已与服务端同步
)
