package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(entities: List<CachedMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParts(entities: List<CachedPartEntity>)

    /** 分页读：按 created DESC 取 [limit] 条；beforeId 非空时只取比它更早的。 */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId " +
            "AND (:beforeId IS NULL OR id < :beforeId) " +  // ULID 字典序 = 时间序
            "ORDER BY created DESC, id DESC LIMIT :limit",
    )
    suspend fun messagesForSession(sessionId: String, limit: Int, beforeId: String?): List<CachedMessageEntity>

    /** Room Flow：本地库变化 → 自动发新值。 */
    @Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created DESC, id DESC")
    fun observeMessages(sessionId: String): Flow<List<CachedMessageEntity>>

    @Query("SELECT * FROM cached_parts WHERE messageId IN (:messageIds)")
    suspend fun partsForMessages(messageIds: List<String>): List<CachedPartEntity>

    @Query("SELECT id FROM cached_messages WHERE sessionId = :sessionId ORDER BY created ASC, id ASC LIMIT 1")
    suspend fun oldestMessageId(sessionId: String): String?

    @Query("SELECT created FROM cached_messages WHERE id = :messageId")
    suspend fun messageCreatedAt(messageId: String): Long?

    /** 当前会话热表消息数（算 overflow 用）。 */
    @Query("SELECT COUNT(*) FROM cached_messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    /** 待 prune 的最老消息（created ASC 前 [limit] 条）——归档前查询。 */
    @Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created ASC, id ASC LIMIT :limit")
    suspend fun oldestMessages(sessionId: String, limit: Int): List<CachedMessageEntity>

    @Query(
        "DELETE FROM cached_messages WHERE sessionId = :sessionId AND id NOT IN " +
            "(SELECT id FROM cached_messages WHERE sessionId = :sessionId " +
            "ORDER BY created DESC, id DESC LIMIT :limit)",
    )
    suspend fun pruneToLimit(sessionId: String, limit: Int): Int

    @Query("DELETE FROM cached_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
