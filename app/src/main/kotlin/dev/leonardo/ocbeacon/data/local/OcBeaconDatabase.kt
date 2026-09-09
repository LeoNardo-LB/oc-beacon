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
 * v7（#306）：cached_sessions——会话列表缓存表（断连/冷启动兜底展示，
 * 对齐消息流 Room 种子化模式）。
 * v8（#385b）：DSH 消息 id 会话命名空间化（seq-{sessionId}-{n}）——清空旧裸
 * seq-{n} 行（跨会话碰撞已串味，不可信缓存；各会话下次进入时经历史重放自愈）。
 */
@Database(
    entities = [CachedMessageEntity::class, CachedPartEntity::class, LogEntity::class, ArchiveBucketEntity::class, SessionSyncEntity::class, CachedSessionEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class OcBeaconDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun messageDao(): MessageDao
    abstract fun archiveBucketDao(): ArchiveBucketDao
    abstract fun sessionSyncDao(): SessionSyncDao
    abstract fun cachedSessionDao(): CachedSessionDao

    companion object {
        /** #289：v5→v6 堆积消息表移除。 */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS pending_messages")
            }
        }

        /**
         * #385b：v7→v8 DSH 裸 seq id 存量清理。旧形态 "seq-{n}"（前缀后直接是数字）
         * 在多会话并发推流时互相覆盖已串味，不可信——整批删除（消息+部件；FTS 由
         * Room 触发器随内容表删除同步）。新形态 "seq-{sessionId}-{n}" 前缀后为
         * 's'（session-…），不落本 GLOB。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM cached_messages WHERE id GLOB 'seq-[0-9]*'")
                db.execSQL("DELETE FROM cached_parts WHERE messageId GLOB 'seq-[0-9]*'")
            }
        }
    }
}
