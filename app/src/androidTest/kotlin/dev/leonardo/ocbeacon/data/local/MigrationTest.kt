package dev.leonardo.ocbeacon.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移插桩测试（v1 → v2）。
 *
 * **背景**：[OcBeaconDatabase] `exportSchema = false` → Room 的 [androidx.room.testing.MigrationTestHelper]
 * 无法读取 v1 schema JSON（`createDatabase` / `runMigrationsAndValidate` 均依赖编译期导出的
 * `schemas/<db>.json`），两条 Room 原生迁移测试路径在此项目都不可用。
 *
 * **采用路径**：手动 v1 重建。因 MIGRATION_1_2 为纯加表（不动 cached_messages/cached_parts/logs
 * 三基表，其 DDL 在 v1/v2 一致），故从 fresh v2 库提取三基表 DDL，在空 DB 文件重建 v1（建表 + 置
 * user_version=1 + 种入校验数据），再以 v2 builder + [Migrations.MIGRATION_1_2] 重开 Room，
 * Room 检测到 1→2 即执行迁移。断言：
 *   1. 迁移产生的 `archive_buckets` 建表 DDL 与 Room 自动生成的完全一致（捕获"手写 SQL 略偏"——
 *      这正是迁移失败的最高风险点，IllegalStateException 崩溃全量存量用户）；
 *   2. 归档索引 DDL 一致；
 *   3. 三基表种子数据存活（迁移不丢数据）。
 *
 * 放 androidTest/：依赖真实 Room/SQLite（同 [ArchiveBucketDaoTest] 模式）。运行需 connectedAndroidTest。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)  // 清理 db/-wal/-shm 残留，确保干净起点
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate1To2_addsArchiveBucketsTableAndPreservesData() {
        // ---- 1. 从 fresh v2 库提取基表 DDL（迁移纯加表，基表 v1/v2 一致）+ 期望归档表/索引 DDL ----
        val fresh = Room.inMemoryDatabaseBuilder(context, OcBeaconDatabase::class.java).build()
        val freshDb = fresh.openHelper.readableDatabase
        fun tableSql(name: String): String? = freshDb
            .query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$name'")
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        fun indexSql(name: String): String? = freshDb
            .query("SELECT sql FROM sqlite_master WHERE type='index' AND name='$name'")
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

        val expectedArchiveSql = tableSql("archive_buckets")
        val expectedIndexSql = indexSql("index_archive_buckets_sessionId_bucketEnd")
        val baseTables = listOf("cached_messages", "cached_parts", "logs").mapNotNull { tableSql(it) }
        val baseIndexSqls = freshDb
            .query(
                "SELECT sql FROM sqlite_master WHERE type='index' AND sql IS NOT NULL " +
                    "AND tbl_name IN ('cached_messages','cached_parts','logs')",
            )
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        // room_master_table：Room 用其 identityHash 在 onOpen 判断是否需要 onCreate。若 v1 手工库缺
        // 此表，Room 会走 createAllTables（建全 v2 表）而非 onUpgrade（迁移）→ 测不到 MIGRATION_1_2。
        // 这里复制 v2 的 identityHash 行，使 Room 认为已就绪（hash 匹配）→ 仅按 version 1→2 跑迁移。
        val roomMasterSql = tableSql("room_master_table")
        val roomMasterRow: List<Pair<Int, String>> = freshDb
            .query("SELECT id, identityHash FROM room_master_table")
            .use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) } }
        fresh.close()

        assertNotNull("fresh v2 must define archive_buckets table", expectedArchiveSql)
        assertNotNull("fresh v2 must define archive index", expectedIndexSql)
        assertNotNull("fresh v2 must define room_master_table", roomMasterSql)

        // ---- 2. 手工构造 v1 DB 文件：建三基表 + 其索引 + room_master_table（v2 hash），置 user_version=1 ----
        val dbFile = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlite ->
            baseTables.forEach { ddl -> sqlite.execSQL(ddl) }
            baseIndexSqls.forEach { ddl -> sqlite.execSQL(ddl) }
            sqlite.execSQL(roomMasterSql!!)
            roomMasterRow.forEach { (id, hash) ->
                sqlite.execSQL("INSERT INTO room_master_table(id, identityHash) VALUES($id, ?)", arrayOf(hash))
            }
            sqlite.execSQL(
                "INSERT INTO cached_messages(id, sessionId, created, role, payload) " +
                    "VALUES('seed_msg', 'ses_1', 100, 'user', '{}')",
            )
            sqlite.version = 1  // PRAGMA user_version = 1 → Room 视其为 v1
        }

        // ---- 3. 以 v2 builder + MIGRATION_1_2 重开 → Room 检测 1→2 并执行迁移 ----
        val migrated = Room.databaseBuilder(context, OcBeaconDatabase::class.java, DB_NAME)
            .addMigrations(Migrations.MIGRATION_1_2)
            .build()
        val migratedDb = migrated.openHelper.readableDatabase  // 触发打开（即迁移）

        // ---- 4. 断言：迁移产生的归档表/索引 DDL 与 Room 自动生成的完全一致 ----
        val actualArchiveSql = migratedDb
            .query("SELECT sql FROM sqlite_master WHERE type='table' AND name='archive_buckets'")
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val actualIndexSql = migratedDb
            .query("SELECT sql FROM sqlite_master WHERE type='index' AND name='index_archive_buckets_sessionId_bucketEnd'")
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertEquals("archive_buckets DDL must match Room-generated", expectedArchiveSql, actualArchiveSql)
        assertEquals("archive index DDL must match Room-generated", expectedIndexSql, actualIndexSql)

        // ---- 5. 断言：三基表种子数据存活（迁移不丢数据）----
        val survived = migratedDb
            .query("SELECT id FROM cached_messages WHERE id='seed_msg'")
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertEquals("seed_msg", survived)

        migrated.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
