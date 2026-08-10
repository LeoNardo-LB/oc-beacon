package dev.leonardo.ocbeacon.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DatabaseRecovery"

/**
 * Room 数据库损坏自愈。
 *
 * 仅当检测到真正的数据库损坏（[SQLiteDatabaseCorruptException]）时，删除数据库文件 →
 * Room 下次访问时自动重建空库（SQLiteOpenHelper 检测到文件不存在即走 onCreate）。
 *
 * **不**对其他 SQLite 异常触发删库——磁盘满（SQLiteFullException）、锁竞争
 * （SQLiteDatabaseLockedException）、约束冲突（SQLiteConstraintException）、磁盘 IO
 * （SQLiteDiskIOException）等可恢复或可重试的异常应原样抛出交由调用方处理，否则会
 * 误删全库造成灾难性数据丢失（缓存消息 + 归档桶 + 日志全部清空）。
 *
 * 与旧 SQLiteOpenHelper 版 withDatabaseRecovery 语义等价，但无需重建实例
 * （Room 内部管理连接生命周期）。
 */
@Singleton
class DatabaseRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 执行 [block]；若抛出表示数据库损坏的异常（含 cause 链中的包装），
     * 删除数据库并返回 null。调用方收到 null 时应优雅降级（日志/内存视图不受影响）。
     *
     * 非损坏的 [SQLiteException] 子类（Full/Locked/Constraint/DiskIO 等）原样抛出，
     * 不触发删库。
     */
    suspend fun <T> withCorruptionRecovery(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: SQLiteException) {
            if (!isCorruption(e)) throw e
            AppLogger.e(TAG, "Database corruption detected, recovering: ${e.message}")
            runCatching { context.deleteDatabase(DATABASE_NAME) }
                .onFailure { del -> AppLogger.e(TAG, "deleteDatabase failed", del) }
            null
        }
    }

    /**
     * 判断异常（含 cause 链）是否表示数据库损坏。
     * Room/Android 在实际损坏时直接抛 [SQLiteDatabaseCorruptException]，
     * 但仍检查 cause 链以防御框架未来的包装行为。
     */
    private fun isCorruption(e: Throwable?): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is SQLiteDatabaseCorruptException) return true
            current = current.cause
        }
        return false
    }

    companion object {
        const val DATABASE_NAME = "ocbeacon.db"
    }
}
