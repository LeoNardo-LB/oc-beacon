package dev.leonardo.ocbeacon.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
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
 * v10（#478）：FTS5 external-content 重建——旧 message_fts 全文镜像（影子表
 * _content 613MB + 倒排 162MB = 库 92%）DROP 后按 content=cached_parts 重建，
 * 同步挂触发器；rebuild 只从现存热表行构建，18 万孤儿行（prune 不删的冷数据）
 * 随重建丢弃。文件级回收由 DatabaseModule onOpen 阈值 VACUUM 完成（迁移
 * 事务内禁 VACUUM）。
 */
@Database(
    entities = [CachedMessageEntity::class, CachedPartEntity::class, LogEntity::class, ArchiveBucketEntity::class, SessionSyncEntity::class, CachedSessionEntity::class],
    version = 10,
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
        val MIGRATION_5_6: Migration = object : SqlMigration(5, 6) {
            override val statements = listOf("DROP TABLE IF EXISTS pending_messages")
        }

        /**
         * #385b：v7→v8 DSH 裸 seq id 存量清理。旧形态 "seq-{n}"（前缀后直接是数字）
         * 在多会话并发推流时互相覆盖已串味，不可信——整批删除（消息+部件；FTS 由
         * Room 触发器随内容表删除同步）。新形态 "seq-{sessionId}-{n}" 前缀后为
         * 's'（session-…），不落本 GLOB。
         *
         * **水位必须同步重置**：session_sync 的 throughSeq 仍指向已删数据的高水位，
         * 不重置则重进会话时增量同步判定「无新事件」跳过拉取——被清空的早期事件
         * （会话开场注入/首轮消息）永久缺失（ack-four 实测：seq-9..12 不再入库）。
         * 全表重置：各会话下次进入走全量重放（幂等，代价一次分页拉取）。
         */
        val MIGRATION_7_8: Migration = object : SqlMigration(7, 8) {
            override val statements = listOf(
                "DELETE FROM cached_messages WHERE id GLOB 'seq-[0-9]*'",
                "DELETE FROM cached_parts WHERE messageId GLOB 'seq-[0-9]*'",
                "DELETE FROM session_sync_state",
            )
        }

        /**
         * #385b：v8→v9 水位补重置。首版 v8 迁移（已装机设备）清了裸 seq 行但
         * 未重置 session_sync——增量同步判定「无新事件」跳过拉取，被清空的早期
         * 事件永久缺失（ack-four 实测）。v9 对已迁设备补一次水位重置。
         */
        val MIGRATION_8_9: Migration = object : SqlMigration(8, 9) {
            override val statements = listOf("DELETE FROM session_sync_state")
        }

        /**
         * #478：FTS5 external-content 重建（837MB 库 → 预计稳态 ~230MB 根修）。
         *
         * 复合根因（2026-09-29 真机 dbstat 定罪）：①FTS5 建表未用外部内容表，
         * 全文在库内双份（message_fts_content 613MB）；②FTS 行不随热表修剪
         * （181,682 行 vs cached_parts 6.6k 行，冷数据未压缩文本永驻）；③
         * page_size=1024 放大 btree/溢出链开销（→onOpen VACUUM 顺带 4096）。
         *
         * 本迁移治①②存量：DROP 旧表（影子表级联清除）→ external-content 新表
         * + 触发器（增量一致性）→ 从 cached_parts 现存 text 行回填（孤儿行自然
         * 丢弃；过滤回填与触发器同语义，见 MessageFtsSchema.BACKFILL）。
         * ③与文件回收见 DatabaseModule 的阈值 VACUUM。
         *
         * 埋点走 android.util.Log：迁移期间 AppLogger 持久化写库会重入死锁。
         *
         * #546：双引擎签名（driver/Support 两路径分派规则见 [SqlMigration]）——
         * 执行器与标量查询注入 [rebuild]，SQL 语义零漂移。
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            private fun rebuild(exec: (String) -> Unit, scalar: (String) -> Long?) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                // 旧库可能从未建过 FTS（从未触发搜索的设备）——COUNT 容错，DROP IF EXISTS 兜底
                val oldRows = try {
                    scalar("SELECT COUNT(*) FROM ${MessageFtsSchema.TABLE}") ?: 0L
                } catch (e: Exception) {
                    0L
                }
                exec("DROP TABLE IF EXISTS ${MessageFtsSchema.TABLE}")
                exec(MessageFtsSchema.CREATE)
                MessageFtsSchema.TRIGGERS.forEach(exec)
                exec(MessageFtsSchema.BACKFILL)
                // external-content 表 COUNT(*)=content 表行数（含 reasoning 等
                // 非索引行），真实倒排行数以 _docsize 影子表计
                val newRows = scalar("SELECT COUNT(*) FROM ${MessageFtsSchema.TABLE}_docsize") ?: 0L
                android.util.Log.i(
                    "OcBeaconDB",
                    "[478-migration] FTS external-content rebuilt: old=$oldRows indexed=$newRows rows, " +
                        (android.os.SystemClock.elapsedRealtime() - t0) + "ms",
                )
            }

            override fun migrate(connection: SQLiteConnection) {
                rebuild(connection::exec, connection::scalarLong)
            }

            override fun migrate(db: SupportSQLiteDatabase) {
                rebuild(db::execSQL) { sql ->
                    db.query(sql).use { if (it.moveToFirst()) it.getLong(0) else null }
                }
            }
        }
    }
}
