package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    /** 保护上限淘汰候选：最久未访问 [limit] 桶（升序；id ASC 兜底，稳定排序避免同时间戳抖动）。 */
    @Query("SELECT * FROM archive_buckets WHERE sessionId = :sessionId ORDER BY lastAccessedAt ASC, id ASC LIMIT :limit")
    suspend fun leastAccessed(sessionId: String, limit: Int): List<ArchiveBucketEntity>

    @Query("DELETE FROM archive_buckets WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("DELETE FROM archive_buckets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE archive_buckets SET lastAccessedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)
}
