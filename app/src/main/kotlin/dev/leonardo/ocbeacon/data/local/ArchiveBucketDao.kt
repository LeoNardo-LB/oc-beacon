package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArchiveBucketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bucket: ArchiveBucketEntity)

    /** 翻页：bucketEnd < beforeEnd 的最新 [limit] 桶（降序）。 */
    @Query("SELECT * FROM archive_buckets WHERE sessionId = :sessionId AND bucketEnd < :beforeEnd ORDER BY bucketEnd DESC LIMIT :limit")
    suspend fun latestBefore(sessionId: String, beforeEnd: Long, limit: Int): List<ArchiveBucketEntity>

    @Query("SELECT COUNT(*) FROM archive_buckets WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int

    /** 保护上限淘汰候选：最久未访问 [limit] 桶（升序）。 */
    @Query("SELECT * FROM archive_buckets WHERE sessionId = :sessionId ORDER BY lastAccessedAt ASC LIMIT :limit")
    suspend fun leastAccessed(sessionId: String, limit: Int): List<ArchiveBucketEntity>

    @Query("DELETE FROM archive_buckets WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("DELETE FROM archive_buckets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE archive_buckets SET lastAccessedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)
}
