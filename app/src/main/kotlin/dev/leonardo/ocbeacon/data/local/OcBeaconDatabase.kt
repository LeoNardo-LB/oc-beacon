package dev.leonardo.ocbeacon.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用本地数据库：消息缓存 + 诊断日志 + 冷存桶（v2）+ 会话同步状态（v5）。
 * 版本 1 建三表；v2 新增 archive_buckets；后续升级用 Migration 对象（禁止 DROP 重建）。
 * v6（#289）：堆积消息管线整体拆除——pending_messages 表 DROP（enqueue 入口随
 * 忙时双键裁决移除后链路为死码，表恒空，丢弃无数据损失）。
 */
@Database(
    entities = [CachedMessageEntity::class, CachedPartEntity::class, LogEntity::class, ArchiveBucketEntity::class, SessionSyncEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class OcBeaconDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun messageDao(): MessageDao
    abstract fun archiveBucketDao(): ArchiveBucketDao
    abstract fun sessionSyncDao(): SessionSyncDao

    companion object {
        /** #289：v5→v6 堆积消息表移除。 */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS pending_messages")
            }
        }
    }
}
