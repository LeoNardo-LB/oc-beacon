package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 冷存桶全库聚合统计（设置页「存储占用」区）。 */
data class ArchiveStats(
    val bucketCount: Long,
    val messageCount: Long,
    val bytes: Long,
)

@Dao
interface ArchiveBucketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bucket: ArchiveBucketEntity)

    /** 批量 upsert（事务内与 [MessageStore] 的裁剪同事务，避免热表/归档并存）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(buckets: List<ArchiveBucketEntity>)

    /**
     * 翻页：桶内可能含有比 beforeEnd 更早消息的桶（bucketStart < beforeEnd）——
     * #72 根治：原 bucketEnd < beforeEnd 按桶边界比较，游标推进到消息级后
     * 桶内剩余更旧消息被整个跳过（永久读不出）。改按 bucketStart 相交，
     * 桶内消息级过滤由 [MessageStore.loadArchivedRange] 解码后执行。
     */
    @Query("SELECT * FROM archive_buckets WHERE sessionId = :sessionId AND bucketStart < :beforeEnd ORDER BY bucketEnd DESC LIMIT :limit")
    suspend fun latestBefore(sessionId: String, beforeEnd: Long, limit: Int): List<ArchiveBucketEntity>

    @Query("SELECT COUNT(*) FROM archive_buckets WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int

    /** #271 设置页统计：全库桶数/消息条数/压缩字节（全部会话合计）。 */
    @Query(
        "SELECT COUNT(*) AS bucketCount, COALESCE(SUM(messageCount), 0) AS messageCount, " +
            "COALESCE(SUM(length(payload)), 0) AS bytes FROM archive_buckets"
    )
    suspend fun globalStats(): ArchiveStats?

    /** #271 手动清理兜底：清空全部冷存桶（设置页「清理」按钮）。 */
    @Query("DELETE FROM archive_buckets")
    suspend fun clearAll()

    @Query("DELETE FROM archive_buckets WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("DELETE FROM archive_buckets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE archive_buckets SET lastAccessedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)
}
