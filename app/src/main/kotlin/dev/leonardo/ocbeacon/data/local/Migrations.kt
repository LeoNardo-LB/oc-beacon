package dev.leonardo.ocbeacon.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * #546：SQLiteConnection 便捷执行（prepare+step）——androidx.sqlite 2.6.2 的
 * SQLiteConnection 仅暴露 prepare（execute 扩展 2.7.0 才并入主 artifact）。
 * DDL/DML 经 prepare+step 执行，语义等价 SupportSQLiteDatabase.execSQL。
 */
internal fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

/**
 * #546：双引擎迁移基类。Room 2.8 driver 模式（setDriver/bundled）下
 * Migration.migrate(SQLiteConnection) 基类对非 Support 连接直接抛
 * NotImplementedError("...requires overriding the migrate(SQLiteConnection)
 * function")（room-runtime 2.8.4 字节码实证）——Support 签名单覆写已不足：
 * - driver 路径调 migrate(SQLiteConnection)，基类不回转 Support 签名；
 * - Support 路径（框架 openHelper / Robolectric 单测）经 SupportSQLiteConnection
 *   包装链仍调 migrate(SupportSQLiteDatabase)。
 * 故两条签名都覆写，共享同一语句列表，SQL 语义零漂移。
 */
abstract class SqlMigration(startVersion: Int, endVersion: Int) : Migration(startVersion, endVersion) {
    protected abstract val statements: List<String>

    override fun migrate(connection: SQLiteConnection) {
        statements.forEach(connection::exec)
    }

    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }
}

object Migrations {
    /** v1 → v2：新增冷存桶表（热表三表不动）。 */
    val MIGRATION_1_2: Migration = object : SqlMigration(1, 2) {
        override val statements = listOf(
            "CREATE TABLE IF NOT EXISTS `archive_buckets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`bucketStart` INTEGER NOT NULL, " +
                "`bucketEnd` INTEGER NOT NULL, " +
                "`messageCount` INTEGER NOT NULL, " +
                "`uncompressedSize` INTEGER NOT NULL, " +
                "`payload` BLOB NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastAccessedAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_archive_buckets_sessionId_bucketEnd` ON `archive_buckets` (`sessionId`, `bucketEnd`)",
        )
    }

    /**
     * v2 → v3（2026-08-16，快速定位性能）：导航/翻页查询索引（sessionId +
     * created + id，与 userMessages 的 ORDER BY 对齐）。Room 2.8 Index 注解
     * 不支持部分索引（WHERE），故为普通复合索引（role 过滤在索引结果上做，
     * sessionId 前缀等值已把扫描范围缩到单会话）。
     */
    val MIGRATION_2_3: Migration = object : SqlMigration(2, 3) {
        override val statements = listOf(
            "CREATE INDEX IF NOT EXISTS `index_cached_messages_sessionId_created_id` " +
                "ON `cached_messages` (`sessionId`, `created`, `id`)",
        )
    }

    /** v3 → v4（2026-08-20，堆积消息）：turn 结束后待发送消息的本地暂存表。 */
    val MIGRATION_3_4: Migration = object : SqlMigration(3, 4) {
        override val statements = listOf(
            "CREATE TABLE IF NOT EXISTS `pending_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_pending_messages_sessionId_position` " +
                "ON `pending_messages` (`sessionId`, `position`)",
        )
    }

    /** v4 → v5（#271，2026-08-30，全量保留）：会话历史同步状态表。 */
    val MIGRATION_4_5: Migration = object : SqlMigration(4, 5) {
        override val statements = listOf(
            "CREATE TABLE IF NOT EXISTS `session_sync_state` (" +
                "`sessionId` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`lastSyncAt` INTEGER, " +
                "`errorMessage` TEXT, " +
                "PRIMARY KEY(`sessionId`))",
        )
    }

    /** v6 → v7（#306，2026-09-03）：会话列表缓存表——断连/冷启动兜底展示。 */
    val MIGRATION_6_7: Migration = object : SqlMigration(6, 7) {
        override val statements = listOf(
            "CREATE TABLE IF NOT EXISTS `cached_sessions` (" +
                "`id` TEXT NOT NULL, " +
                "`serverId` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`payload` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_cached_sessions_serverId_updatedAt` " +
                "ON `cached_sessions` (`serverId`, `updatedAt`)",
        )
    }
}
