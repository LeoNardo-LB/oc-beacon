package dev.leonardo.ocbeacon.data.local

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断日志本地存储（原 DiagnosticLogDatabase 的 Room 迁移）。
 *
 * 修剪策略（语义与旧实现等价）：
 * - 普通日志保留 3 天，ERROR/FATAL 保留 21 天
 * - FATAL（崩溃）只保留最近 50 条
 * - 总字节预算 10MB，超出按最旧 100 条/批循环删除
 */
@Singleton
class LogStore @Inject constructor(
    private val dao: LogDao,
    private val databaseRecovery: DatabaseRecovery,
) {

    suspend fun insert(entries: List<LogEntity>, now: Long = System.currentTimeMillis()) {
        if (entries.isEmpty()) return
        databaseRecovery.withCorruptionRecovery {
            dao.insertAll(entries)
            prune(now)
        }
    }

    /** 最近 [limit] 条，最新在前。 */
    suspend fun latest(limit: Int = VISIBLE_ENTRY_LIMIT): List<LogEntity> =
        databaseRecovery.withCorruptionRecovery { dao.latest(limit) } ?: emptyList()

    suspend fun isEmpty(): Boolean =
        databaseRecovery.withCorruptionRecovery { dao.isEmpty() } ?: true

    /** 最近一条 FATAL 崩溃（#154a 启动提示检测；无 → null）。 */
    suspend fun latestFatal(): LogEntity? =
        databaseRecovery.withCorruptionRecovery { dao.latestFatal() }

    suspend fun clear() {
        databaseRecovery.withCorruptionRecovery { dao.clear() }
    }

    // ---- 修剪 ----------------------------------------------------------

    private suspend fun prune(now: Long) {
        dao.deleteOrdinaryBefore(now - ORDINARY_RETENTION_MS)
        dao.deleteErrorBefore(now - ERROR_RETENTION_MS)
        dao.deleteFatalBeyondLimit(CRASH_LIMIT)

        var totalBytes = dao.sumByteSize()
        while (totalBytes > MAX_PERSISTENT_BYTES) {
            val removed = dao.deleteOldestBatch(PRUNE_BATCH_SIZE)
            if (removed <= 0) break
            totalBytes = dao.sumByteSize()
        }
    }

    companion object {
        const val VISIBLE_ENTRY_LIMIT = 1000
        const val MAX_PERSISTENT_BYTES = 10L * 1024L * 1024L
        const val ORDINARY_RETENTION_MS = 3L * 24L * 60L * 60L * 1000L
        const val ERROR_RETENTION_MS = 21L * 24L * 60L * 60L * 1000L
        const val CRASH_LIMIT = 50
        const val PRUNE_BATCH_SIZE = 100
    }
}
