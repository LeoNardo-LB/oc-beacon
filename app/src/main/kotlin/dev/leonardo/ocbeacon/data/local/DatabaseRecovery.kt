package dev.leonardo.ocbeacon.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DatabaseRecovery"

/**
 * Room 数据库损坏自愈。
 *
 * 捕获 SQLite 损坏异常 → 删除数据库文件 → Room 下次访问时自动重建空库
 * （SQLiteOpenHelper 检测到文件不存在即走 onCreate）。
 * 与旧 SQLiteOpenHelper 版 withDatabaseRecovery 语义等价，但无需重建实例
 * （Room 内部管理连接生命周期）。
 */
@Singleton
class DatabaseRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 执行 [block]；若抛 SQLiteException（含损坏），删除数据库并返回 null。
     * 调用方收到 null 时应优雅降级（日志/内存视图不受影响）。
     */
    suspend fun <T> withCorruptionRecovery(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: SQLiteException) {
            AppLogger.e(TAG, "SQLite error, recovering: ${e.message}")
            runCatching { context.deleteDatabase(DATABASE_NAME) }
                .onFailure { del -> AppLogger.e(TAG, "deleteDatabase failed", del) }
            null
        }
    }

    companion object {
        const val DATABASE_NAME = "ocbeacon.db"
    }
}
