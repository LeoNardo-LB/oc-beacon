package dev.leonardo.ocbeacon.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    /** v1 → v2：新增冷存桶表（热表三表不动）。 */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
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
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_buckets_sessionId_bucketEnd` ON `archive_buckets` (`sessionId`, `bucketEnd`)")
        }
    }

    /**
     * v2 → v3（2026-08-16，快速定位性能）：导航/翻页查询索引（sessionId +
     * created + id，与 userMessages 的 ORDER BY 对齐）。Room 2.8 Index 注解
     * 不支持部分索引（WHERE），故为普通复合索引（role 过滤在索引结果上做，
     * sessionId 前缀等值已把扫描范围缩到单会话）。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cached_messages_sessionId_created_id` " +
                    "ON `cached_messages` (`sessionId`, `created`, `id`)",
            )
        }
    }

    /** v3 → v4（2026-08-20，堆积消息）：turn 结束后待发送消息的本地暂存表。 */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_messages_sessionId_position` " +
                    "ON `pending_messages` (`sessionId`, `position`)",
            )
        }
    }
}
