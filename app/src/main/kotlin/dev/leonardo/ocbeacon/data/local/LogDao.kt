package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LogDao {

    @Insert
    suspend fun insertAll(entries: List<LogEntity>)

    /** 最近 [limit] 条，按时间倒序（最新在前）。 */
    @Query("SELECT * FROM logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<LogEntity>

    @Query("SELECT NOT EXISTS(SELECT 1 FROM logs LIMIT 1)")
    suspend fun isEmpty(): Boolean

    @Query("DELETE FROM logs")
    suspend fun clear()

    /** 删除 [before] 之前且不属于 [levels] 的日志（保留 ERROR/FATAL）。 */
    @Query("DELETE FROM logs WHERE timestamp < :before AND level NOT IN ('ERROR', 'FATAL')")
    suspend fun deleteOrdinaryBefore(before: Long): Int

    /** 删除 [before] 之前的 ERROR/FATAL 日志。 */
    @Query("DELETE FROM logs WHERE timestamp < :before AND level IN ('ERROR', 'FATAL')")
    suspend fun deleteErrorBefore(before: Long): Int

    /** 只保留最近 [limit] 条 FATAL（崩溃记录）。 */
    @Query(
        "DELETE FROM logs WHERE level = 'FATAL' AND id NOT IN " +
            "(SELECT id FROM logs WHERE level = 'FATAL' ORDER BY timestamp DESC, id DESC LIMIT :limit)",
    )
    suspend fun deleteFatalBeyondLimit(limit: Int): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM logs")
    suspend fun sumByteSize(): Long

    /** 删除最旧的 [limit] 条（按时间升序），返回删除条数。 */
    @Query("DELETE FROM logs WHERE id IN (SELECT id FROM logs ORDER BY timestamp ASC, id ASC LIMIT :limit)")
    suspend fun deleteOldestBatch(limit: Int): Int
}
