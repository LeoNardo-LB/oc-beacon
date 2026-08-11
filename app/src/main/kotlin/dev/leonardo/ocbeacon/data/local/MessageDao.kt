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

    /** 分页读：最新 limit 条（无游标）。 */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId " +
            "ORDER BY created DESC, id DESC LIMIT :limit",
    )
    suspend fun messagesForSession(sessionId: String, limit: Int): List<CachedMessageEntity>

    /** 分页读：取比 beforeId 更早的 limit 条（游标分页）。 */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId AND id < :beforeId " +  // ULID 字典序 = 时间序
            "ORDER BY created DESC, id DESC LIMIT :limit",
    )
    suspend fun messagesBefore(sessionId: String, beforeId: String, limit: Int): List<CachedMessageEntity>

    /** Room Flow：本地库变化 → 自动发新值。 */
    @Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created DESC, id DESC")
    fun observeMessages(sessionId: String): Flow<List<CachedMessageEntity>>

    @Query("SELECT * FROM cached_parts WHERE messageId IN (:messageIds)")
    suspend fun partsForMessages(messageIds: List<String>): List<CachedPartEntity>

    /**
     * 分块 IN 查询（#59：Room 单条 @Query 无法处理 SQLite IN 999 变量上限，
     * 原分块逻辑散落 MessageStore 业务层——下沉 DAO 统一封装）。
     */
    suspend fun partsForMessagesChunked(messageIds: List<String>): List<CachedPartEntity> {
        if (messageIds.isEmpty()) return emptyList()
        if (messageIds.size <= SQLITE_IN_VARIABLE_LIMIT) {
            return partsForMessages(messageIds)
        }
        return messageIds.chunked(SQLITE_IN_VARIABLE_LIMIT)
            .flatMap { chunk -> partsForMessages(chunk) }
    }

    companion object {
        /** SQLite 默认 SQLITE_MAX_VARIABLE_NUMBER=999；留余量取 900。 */
        const val SQLITE_IN_VARIABLE_LIMIT = 900
    }

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
