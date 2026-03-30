package ai.openclaw.imapp.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaged(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE timestamp > :afterTimestamp ORDER BY timestamp ASC")
    suspend fun getMessagesAfter(afterTimestamp: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesBefore(beforeTimestamp: Long, limit: Int): List<MessageEntity>

    @Query("SELECT MAX(timestamp) FROM messages")
    suspend fun getLastTimestamp(): Long?

    @Query("SELECT MIN(timestamp) FROM messages")
    suspend fun getFirstTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @Query("DELETE FROM messages WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
