package dev.leonardo.ocbeacon.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.local.ArchiveBucketDao
import dev.leonardo.ocbeacon.data.local.CachedSessionDao
import dev.leonardo.ocbeacon.data.local.LogDao
import dev.leonardo.ocbeacon.data.local.MessageDao
import dev.leonardo.ocbeacon.data.local.Migrations
import dev.leonardo.ocbeacon.data.local.OcBeaconDatabase
import dev.leonardo.ocbeacon.data.local.SessionSyncDao
import dev.leonardo.ocbeacon.data.local.scalarLong
import dev.leonardo.ocbeacon.data.local.scalarText
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** #478：freelist 回收阈值（低于此值不值得 VACUUM 重写整个库文件）。 */
    private const val VACUUM_FREE_BYTES_THRESHOLD = 64L * 1024 * 1024

    private const val DATABASE_NAME = "ocbeacon.db"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OcBeaconDatabase =
        // WAL 模式：driver 路径下由 Room 主动配置（journal_mode=WAL +
        // synchronous=NORMAL + busy_timeout=3000 + 读写连接池，Room 2.8.4
        // 字节码实证）——并发读写模型与 requery 时代等价（读写分离连接池）；
        // 可观测差异：busy_timeout 由 requery 连接池的 8s 收紧为 3000ms
        // （WAL 下写冲突罕见，真机流式+检索并发实测无影响）。
        // #272/#546：捆绑 SQLite（androidx sqlite-bundled，含 FTS5 模块）——
        // 小米等 ROM 系统 SQLite 无 fts5，BM25 检索需全设备可用的 FTS5。
        // #546 由 requery openHelperFactory 迁来（上游 16KB 对齐 OPEN 无期）；
        // Room 2.8 兼容模式下 Migrations/Callback（SupportSQLiteDatabase 签名）
        // 经适配层照常生效，存量库文件（requery 3.45 建）由 bundled 前向兼容打开。
        Room.databaseBuilder(context, OcBeaconDatabase::class.java, DATABASE_NAME)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5, OcBeaconDatabase.MIGRATION_5_6, Migrations.MIGRATION_6_7, OcBeaconDatabase.MIGRATION_7_8, OcBeaconDatabase.MIGRATION_8_9, OcBeaconDatabase.MIGRATION_9_10)
            // #478③：freelist 阈值 VACUUM 兜底（onOpen，迁移已完成）。
            // 常态静默：auto_vacuum=FULL 下 DROP/DELETE 的页即时归还 OS——存量库
            // 固化在文件头（requery 时代实测），新建库同样 FULL（androidx bundled
            // 2.6.2 编译带 SQLITE_DEFAULT_AUTOVACUUM=1；真机 pm clear 实测
            // auto_vacuum=1，与 requery 时代行为一致，.so 编译选项表佐证）。
            // 此回调仅防御异常残留（freelist>64MB 时重写文件），阈值滤掉日常
            // prune 的 MB 级波动。失败仅告警不阻断，下次启动重试。
            //
            // 取舍记录（2026-09-30 真机实证，requery 时代）：page_size 1024→4096
            // 升级**放弃**——WAL 下 PRAGMA page_size 静默无效，须临时切
            // journal_mode=DELETE，而当时 requery 连接池架构下切换永远无法独占库
            // （busy_timeout 8s 仍 code 5 锁死）；#546 bundled 后未复测独占可行性，
            // 维持放弃——external-content 化后稳态库 ~60MB 级，小页开销
            // 收益仅 ~5MB，不值得 VACUUM INTO+文件替换的破坏性方案。
            .addCallback(object : RoomDatabase.Callback() {
                // #546：driver 路径（bundled）回调入口是 onOpen(SQLiteConnection)——
                // 基类对 onOpen(SupportSQLiteDatabase) 的转发仅在连接为
                // SupportSQLiteConnection 包装时发生（Room 2.8.4 字节码实证，真机
                // 13:25 首装实测 Support 签名未触发），bundled 原生连接不转发。
                // 故覆写 SQLiteConnection 签名：Support 路径同样收敛到该入口
                // （经基类转发链），两路通吃；也是 Room 3.0 的目标签名。
                override fun onOpen(connection: SQLiteConnection) {
                    // #546 引擎诊断（每次 open 一行，进程级低频）：journal 模式 /
                    // SQLite 版本 / auto_vacuum / user_version——真机验收 V4 的
                    // logcat 断言锚点。日志走 android.util.Log：onOpen 阶段
                    // AppLogger 持久化写库会重入（与 [478-vacuum] 埋点同款豁免）。
                    try {
                        android.util.Log.i(
                            "OcBeaconDB",
                            "[546-engine] journal=" + (connection.scalarText("PRAGMA journal_mode") ?: "?") +
                                ", sqlite=" + (connection.scalarText("SELECT sqlite_version()") ?: "?") +
                                ", auto_vacuum=" + connection.scalarLong("PRAGMA auto_vacuum") +
                                ", user_version=" + connection.scalarLong("PRAGMA user_version"),
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("OcBeaconDB", "[546-engine] diag failed: " + e.message)
                    }
                    try {
                        val pageSize = connection.scalarLong("PRAGMA page_size") ?: return
                        val freePages = connection.scalarLong("PRAGMA freelist_count") ?: return
                        val freeBytes = pageSize * freePages
                        if (freeBytes < VACUUM_FREE_BYTES_THRESHOLD) return
                        val t0 = System.currentTimeMillis()
                        val dbFile = context.getDatabasePath(DATABASE_NAME)
                        val beforeBytes = if (dbFile.exists()) dbFile.length() else 0L
                        connection.prepare("VACUUM").use { it.step() }
                        val afterBytes = if (dbFile.exists()) dbFile.length() else 0L
                        // android.util.Log：onOpen 阶段 AppLogger 写库会重入
                        android.util.Log.i(
                            "OcBeaconDB",
                            "[478-vacuum] reclaimed: freelist=" + (freeBytes / 1048576) + "MB, file " +
                                (beforeBytes / 1048576) + "MB→" + (afterBytes / 1048576) + "MB, " +
                                (System.currentTimeMillis() - t0) + "ms",
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("OcBeaconDB", "[478-vacuum] skipped (retry next open): " + e.message)
                    }
                }

            })
            .build()

    @Provides
    fun provideLogDao(database: OcBeaconDatabase): LogDao = database.logDao()

    @Provides
    fun provideMessageDao(database: OcBeaconDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideArchiveBucketDao(database: OcBeaconDatabase): ArchiveBucketDao = database.archiveBucketDao()

    @Provides
    fun provideSessionSyncDao(database: OcBeaconDatabase): SessionSyncDao = database.sessionSyncDao()

    @Provides
    fun provideCachedSessionDao(database: OcBeaconDatabase): CachedSessionDao = database.cachedSessionDao()

    /** 时钟源（冷存桶时间戳用）。生产用系统时钟；测试经 MessageStore 构造参数注入固定值。 */
    @Provides
    @Singleton
    fun provideClock(): () -> Long = System::currentTimeMillis
}
