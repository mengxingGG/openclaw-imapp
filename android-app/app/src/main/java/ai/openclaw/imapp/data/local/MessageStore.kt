package ai.openclaw.imapp.data.local

import ai.openclaw.imapp.data.model.Message
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.msgDataStore: DataStore<Preferences> by preferencesDataStore(name = "imapp_messages")

/**
 * 极简消息存储：DataStore + JSON 序列化
 * 零数据库依赖，不可能出 SQLite 相关的崩溃
 */
object MessageStore {

    private val KEY_MESSAGES = stringPreferencesKey("cached_messages")
    private val KEY_LAST_SYNC = stringPreferencesKey("last_sync_ts")
    private val MAX_MESSAGES = 500

    private fun normalize(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        val deduped = LinkedHashMap<String, Message>()
        messages
            .sortedBy { it.timestamp }
            .forEach { message ->
                val key = message.id.ifBlank {
                    listOf(
                        message.from,
                        message.timestamp.toString(),
                        message.content.type,
                        message.content.text.orEmpty(),
                        message.content.fileId.orEmpty(),
                    ).joinToString("|")
                }
                deduped[key] = message
            }
        return deduped.values.toList().takeLast(MAX_MESSAGES)
    }

    private suspend fun writeMessages(context: Context, gson: Gson, messages: List<Message>): List<Message> {
        val normalized = normalize(messages)
        context.msgDataStore.edit { it[KEY_MESSAGES] = gson.toJson(normalized) }
        return normalized
    }

    /** 从本地加载消息列表 */
    suspend fun loadAll(context: Context, gson: Gson): List<Message> {
        val json = context.msgDataStore.data.first()[KEY_MESSAGES] ?: "[]"
        return try {
            val type = object : TypeToken<List<Message>>() {}.type
            normalize(gson.fromJson<List<Message>>(json, type) ?: emptyList())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存消息列表（滚动覆盖） */
    suspend fun saveAll(context: Context, gson: Gson, messages: List<Message>): List<Message> {
        return writeMessages(context, gson, messages)
    }

    /** 追加新消息 */
    suspend fun append(context: Context, gson: Gson, existing: List<Message>, newMessages: List<Message>): List<Message> {
        return writeMessages(context, gson, existing + newMessages)
    }

    /** 将更老的历史消息合并到前面，并自动去重裁剪 */
    suspend fun prepend(context: Context, gson: Gson, existing: List<Message>, olderMessages: List<Message>): List<Message> {
        return writeMessages(context, gson, olderMessages + existing)
    }

    /** 获取上次同步时间戳 */
    suspend fun getLastSyncTimestamp(context: Context): Long {
        return context.msgDataStore.data.first()[KEY_LAST_SYNC]?.toLongOrNull() ?: 0L
    }

    /** 更新同步时间戳 */
    suspend fun setLastSyncTimestamp(context: Context, timestamp: Long) {
        context.msgDataStore.edit { it[KEY_LAST_SYNC] = timestamp.toString() }
    }

    suspend fun touchLastSyncTimestamp(context: Context, timestamp: Long) {
        if (timestamp <= 0L) return
        val current = getLastSyncTimestamp(context)
        if (timestamp > current) {
            setLastSyncTimestamp(context, timestamp)
        }
    }

    /** 清空本地消息 */
    suspend fun clear(context: Context) {
        context.msgDataStore.edit {
            it.remove(KEY_MESSAGES)
            it.remove(KEY_LAST_SYNC)
        }
    }
}
