package dev.leonardo.ocbeacon.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #546：bundled driver（setDriver 路径）下 v9→v10 迁移的仪器实证（真机）。
 *
 * 背景：Room 2.8.4 的 Migration.migrate(SQLiteConnection) 基类对非 Support
 * 连接抛 NotImplementedError——全部迁移已改双签名（SqlMigration / 9_10 双
 * override）。本测试证明 driver 路径真迁移（v9→v10：FTS5 DDL + 触发器 +
 * 回填 + 标量查询埋点）在 bundled 引擎上完整可跑，防「存量 v<10 用户升级
 * 即崩」回归。范围注记：仅覆盖 9→10（其余 1..9 链共享 SqlMigration 同一
 * 机制，语句更简单；全链留待未来 schema 11 迁移时随发版回归）。
 *
 * 手法：driver 模式建 v10 库并写入两条 text part（数据注入与断言均用
 * BundledSQLiteDriver 直连，Room 侧只负责开库触发建 schema/迁移）→ 降级到
 * v9（DROP FTS 影子表 + user_version=9；v9/v10 的 Room identity hash 相同——
 * #478 只动 Room schema 外的手工 FTS 表）→ driver 模式重开触发
 * MIGRATION_9_10 → 断言 user_version=10 且 FTS MATCH 回填行可查。
 */
@RunWith(AndroidJUnit4::class)
class BundledMigrationTest {

    private val dbName = "test-bundled-migration-9-10.db"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbFile get() = context.getDatabasePath(dbName)

    @Before
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    /** 直连执行任意语句（prepare+step）。 */
    private fun execAll(vararg sqls: String) {
        BundledSQLiteDriver().open(dbFile.absolutePath).use { raw ->
            sqls.forEach { raw.prepare(it).use { it.step() } }
        }
    }

    /** 直连标量查询。 */
    private fun scalarLong(sql: String): Long {
        BundledSQLiteDriver().open(dbFile.absolutePath).use { raw ->
            raw.prepare(sql).use { s ->
                assertTrue(s.step())
                return s.getLong(0)
            }
        }
        error("unreachable")
    }

    @Test
    fun migration9to10_onBundledDriver_rebuildsFts() {
        // 1) driver 模式建 v10 库（DAO 查询触发 Room 打开直建最新 schema），随即关库
        val db1 = Room.databaseBuilder(context, OcBeaconDatabase::class.java, dbName)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            runBlocking { db1.logDao().latest(1) } // 触发打开 + 建库
        } finally {
            db1.close()
        }

        // 2) 直连注入回填数据源（两条 text part；FK 约束先 messages 后 parts）
        execAll(
            "INSERT INTO cached_messages(id, sessionId, role, created, payload) VALUES " +
                "('m1','s1','user',1000,'{}'),('m2','s1','assistant',2000,'{}')",
            "INSERT INTO cached_parts(id, messageId, sessionId, type, text) VALUES " +
                "('p1','m1','s1','text','the quick brown fox')," +
                "('p2','m2','s1','text','jumped over the lazy dog')",
        )

        // 3) 降级到 v9：DROP FTS 全家（主表级联影子表，逐个清防御）+ user_version=9
        execAll(
            "DROP TABLE IF EXISTS message_fts_data",
            "DROP TABLE IF EXISTS message_fts_idx",
            "DROP TABLE IF EXISTS message_fts_docsize",
            "DROP TABLE IF EXISTS message_fts_config",
            "DROP TABLE IF EXISTS message_fts",
            "PRAGMA user_version = 9",
        )
        assertEquals(9L, scalarLong("PRAGMA user_version"))

        // 4) driver 模式重开 → Room 触发 MIGRATION_9_10（双签名的 SQLiteConnection
        //    分支——若迁移未覆写该签名，基类在此抛 NotImplementedError 使测试失败）
        val db2 = Room.databaseBuilder(context, OcBeaconDatabase::class.java, dbName)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(
                Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4,
                Migrations.MIGRATION_4_5, OcBeaconDatabase.MIGRATION_5_6, Migrations.MIGRATION_6_7,
                OcBeaconDatabase.MIGRATION_7_8, OcBeaconDatabase.MIGRATION_8_9, OcBeaconDatabase.MIGRATION_9_10,
            )
            .build()
        try {
            runBlocking { db2.logDao().latest(1) } // 触发打开 → 迁移执行
        } finally {
            db2.close()
        }

        // 5) 迁移产物：schema 版本 + FTS 表重建 + 过滤回填（仅 text 行）MATCH 可查
        assertEquals(10L, scalarLong("PRAGMA user_version"))
        assertEquals(
            2L,
            scalarLong("SELECT COUNT(*) FROM message_fts WHERE message_fts MATCH '\"the\"'"),
        )
    }
}
