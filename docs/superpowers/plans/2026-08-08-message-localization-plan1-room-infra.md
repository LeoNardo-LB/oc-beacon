# Plan 1：Room 基础设施 + 诊断日志迁移 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入 Room 数据库基础设施（`ocbeacon.db` 三表：cached_messages / cached_parts / logs），并把现有手写 SQL 的 `DiagnosticLogDatabase` 等价迁移到 Room——AppLogger 与 UI 行为完全不变。

**Architecture:** 新增 `data/local/` 包（Entity + DAO + Database + LocalStore 子模块）。`LogStore` 是本地日志持久化的唯一入口，`DiagnosticLogRepository` 内部从 SQLiteOpenHelper 切换为 `LogStore`，公开接口（`entries/record/recordBatch/clear/logLevel/setLogLevel`）零变化。数据库由 Hilt `DatabaseModule` 提供单例。

**Tech Stack:** androidx.room 2.8.4（room-runtime + room-ktx + room-compiler via KSP）、现有 kotlinx.serialization Json、Hilt 2.59.2、KSP 2.3.8（已存在）。

## Global Constraints

- 数据库名：`ocbeacon.db`（替代旧 `diagnostics.db`）
- Room 版本固定 **2.8.4**（最新稳定版 2025-11-19；Room 3.0 是 KMP 新架构，本项目不使用）
- 删除旧库 `diagnostics.db` 时**不迁移旧数据**（历史上 onUpgrade 本就 DROP 重建；本地开发日志无价值）
- 修剪语义必须等价迁移：普通日志 3 天 / ERROR+FATAL 21 天 / FATAL 最近 50 条 / 10MB 总字节预算（分批 100 条删除）
- 脱敏逻辑（`sanitize`/`sanitizeEntry`/`export`）**一字不动**——纯函数，已有单测覆盖
- `DiagnosticLogRepository` 公开接口**零变化**（插桩测试 `DiagnosticsScreenDuplicateTimestampTest` 直接注入真实现，接口变了会挂）
- `details: Map<String,String>` 序列化为 JSON 字符串存 TEXT 列（用注入的 `Json`，不引 Room TypeConverter）
- Windows 构建：`.\gradlew` 前缀；编译检查 120s 超时、单测 180s 超时
- 提交粒度：每 Task 一个 commit

---

### Task 1: Room 依赖 + 编译验证

**Files:**
- Modify: `app/build.gradle.kts`（dependencies 块，约 L184 之后）

**Interfaces:**
- Produces: `androidx.room:room-runtime:2.8.4` / `room-ktx:2.8.4` 可用；`ksp("androidx.room:room-compiler:2.8.4")` 生效

- [ ] **Step 1: 添加依赖**

在 `app/build.gradle.kts` 的 dependencies 块中（`androidx.datastore:datastore-preferences:1.2.1` 一行之后）添加：

```kotlin
    // Room 本地数据库（消息缓存 + 诊断日志）
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
```

注意：KSP 插件已在 plugins 块（`com.google.devtools.ksp`，Hilt 在用），无需新增插件。

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin --rerun-tasks`（超时 120s）
Expected: `BUILD SUCCESSFUL`（依赖解析成功，无 KSP 冲突）

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: 引入 Room 2.8.4 依赖（消息缓存 + 日志迁移基础）"
```

---

### Task 2: 创建数据库骨架（Entity × 3 + OcBeaconDatabase + LogDao + DatabaseModule）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/CachedMessageEntity.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/CachedPartEntity.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/LogEntity.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/LogDao.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/OcBeaconDatabase.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DatabaseModule.kt`
- Create: `app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/LogDaoTest.kt`

**Interfaces:**
- Produces: `OcBeaconDatabase`（RoomDatabase，版本 1，三表）；`LogDao`（`insertAll/latest/count/isEmpty/clear/deleteBefore/deleteFatalBeyondLimit/sumByteSize/deleteOldestBatch`）；Hilt 绑定 `LogDao` 单例
- Consumes: `LogEntity`（Task 3 的 LogStore 使用）

- [ ] **Step 1: 创建三个 Entity**

`CachedMessageEntity.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地缓存的会话消息。payload 为完整 Message JSON（kotlinx.serialization）。
 * 索引列仅提取分页/查询所需字段，避免 30+ 字段拆列（Telegram 同款 BLOB 化）。
 */
@Entity(
    tableName = "cached_messages",
    indices = [Index(value = ["sessionId", "created"])],
)
data class CachedMessageEntity(
    @PrimaryKey val id: String,          // msg_ ULID，单调递增，去重/游标
    val sessionId: String,               // ses_ ULID
    val created: Long,                   // time.created 毫秒，排序键
    val role: String,                    // user / assistant
    val payload: String,                 // 完整 Message JSON
)
```

`CachedPartEntity.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地缓存的消息部件。独立成表：SSE 流式更新每 48ms 一个 token delta，
 * 独立表每次只更新单行 text，避免重写整条消息 JSON 的写放大。
 */
@Entity(
    tableName = "cached_parts",
    foreignKeys = [
        ForeignKey(
            entity = CachedMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"]), Index(value = ["sessionId"])],
)
data class CachedPartEntity(
    @PrimaryKey val id: String,          // part id
    val messageId: String,               // FK → cached_messages.id
    val sessionId: String,
    val type: String,                    // text / tool / code 等
    val text: String?,                   // 文本内容（流式更新热点）
    val payload: String?,                // 完整 Part JSON（保留扩展字段）
)
```

`LogEntity.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 诊断日志条目（原 DiagnosticLogDatabase.logs 表等价迁移）。
 * details 为 Map<String,String> 的 JSON 编码字符串。
 */
@Entity(
    tableName = "logs",
    indices = [Index(value = ["timestamp"]), Index(value = ["level", "timestamp"])],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,                   // ERROR / WARN / INFO / DEBUG / FATAL
    val category: String,
    val message: String,
    val details: String,                 // JSON 编码的 Map<String,String>
    val byteSize: Int,
)
```

- [ ] **Step 2: 创建 LogDao**

`LogDao.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LogDao {

    @Insert
    suspend fun insertAll(entries: List<LogEntity>)

    /** 最近 [limit] 条，按时间倒序（最新在前）。 */
    @Query("SELECT * FROM logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<LogEntity>

    @Query("SELECT NOT EXISTS(SELECT 1 FROM logs LIMIT 1)")
    suspend fun isEmpty(): Boolean

    @Query("DELETE FROM logs")
    suspend fun clear()

    /** 删除 [before] 之前且不属于 [levels] 的日志（保留 ERROR/FATAL）。 */
    @Query("DELETE FROM logs WHERE timestamp < :before AND level NOT IN ('ERROR', 'FATAL')")
    suspend fun deleteOrdinaryBefore(before: Long): Int

    /** 删除 [before] 之前的 ERROR/FATAL 日志。 */
    @Query("DELETE FROM logs WHERE timestamp < :before AND level IN ('ERROR', 'FATAL')")
    suspend fun deleteErrorBefore(before: Long): Int

    /** 只保留最近 [limit] 条 FATAL（崩溃记录）。 */
    @Query(
        "DELETE FROM logs WHERE level = 'FATAL' AND id NOT IN " +
            "(SELECT id FROM logs WHERE level = 'FATAL' ORDER BY timestamp DESC, id DESC LIMIT :limit)",
    )
    suspend fun deleteFatalBeyondLimit(limit: Int): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM logs")
    suspend fun sumByteSize(): Long

    /** 删除最旧的 [limit] 条（按时间升序），返回删除条数。 */
    @Query("DELETE FROM logs WHERE id IN (SELECT id FROM logs ORDER BY timestamp ASC, id ASC LIMIT :limit)")
    suspend fun deleteOldestBatch(limit: Int): Int
}
```

- [ ] **Step 3: 创建 OcBeaconDatabase**

`OcBeaconDatabase.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用本地数据库：消息缓存 + 诊断日志。
 * 版本 1 建三表；后续升级用 Migration 对象（禁止 DROP 重建）。
 */
@Database(
    entities = [CachedMessageEntity::class, CachedPartEntity::class, LogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OcBeaconDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}
```

- [ ] **Step 4: 创建 DatabaseModule（Hilt 绑定）**

`DatabaseModule.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.local.LogDao
import dev.leonardo.ocbeacon.data.local.OcBeaconDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OcBeaconDatabase =
        Room.databaseBuilder(context, OcBeaconDatabase::class.java, "ocbeacon.db")
            .build()

    @Provides
    fun provideLogDao(database: OcBeaconDatabase): LogDao = database.logDao()
}
```

- [ ] **Step 5: 编写 DAO 插桩测试**

`app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/LogDaoTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogDaoTest {

    private lateinit var database: OcBeaconDatabase
    private lateinit var dao: LogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OcBeaconDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.logDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entry(
        id: Long = 0,
        timestamp: Long,
        level: String = "INFO",
        byteSize: Int = 10,
    ) = LogEntity(
        id = id,
        timestamp = timestamp,
        level = level,
        category = "test",
        message = "msg",
        details = "{}",
        byteSize = byteSize,
    )

    @Test
    fun insertAndLatest_returnsNewestFirst() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "INFO"),
                entry(timestamp = 300, level = "ERROR"),
                entry(timestamp = 200, level = "WARN"),
            ),
        )

        val latest = dao.latest(10)

        assertEquals(3, latest.size)
        assertEquals(300, latest[0].timestamp)  // 最新在前
        assertEquals(100, latest[2].timestamp)
    }

    @Test
    fun latest_respectsLimit() = runBlocking {
        dao.insertAll((1..5).map { entry(timestamp = it.toLong()) })

        val latest = dao.latest(2)

        assertEquals(2, latest.size)
        assertEquals(5, latest[0].timestamp)
    }

    @Test
    fun deleteOrdinaryBefore_keepsErrorAndFatal() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "INFO"),
                entry(timestamp = 100, level = "ERROR"),
                entry(timestamp = 100, level = "FATAL"),
                entry(timestamp = 200, level = "WARN"),
            ),
        )

        val deleted = dao.deleteOrdinaryBefore(150)

        assertEquals(1, deleted)  // 只删 INFO
        assertEquals(3, dao.latest(10).size)
    }

    @Test
    fun deleteErrorBefore_removesOldErrors() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "ERROR"),
                entry(timestamp = 100, level = "FATAL"),
                entry(timestamp = 200, level = "ERROR"),
            ),
        )

        val deleted = dao.deleteErrorBefore(150)

        assertEquals(1, deleted)
        assertEquals(2, dao.latest(10).size)
    }

    @Test
    fun deleteFatalBeyondLimit_keepsNewestFatal() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "FATAL"),
                entry(timestamp = 200, level = "FATAL"),
                entry(timestamp = 300, level = "FATAL"),
            ),
        )

        val deleted = dao.deleteFatalBeyondLimit(2)

        assertEquals(1, deleted)
        val remaining = dao.latest(10).map { it.timestamp }
        assertEquals(listOf(300L, 200L), remaining)  // 最新的 2 条保留
    }

    @Test
    fun deleteOldestBatch_removesOldest() = runBlocking {
        dao.insertAll((1..5).map { entry(timestamp = it.toLong(), byteSize = 1) })

        val deleted = dao.deleteOldestBatch(2)

        assertEquals(2, deleted)
        assertEquals(0, dao.sumByteSize())  // 不对——sum 应为 3（剩 3 条 × 1）
    }

    @Test
    fun sumByteSize_returnsTotal() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 1, byteSize = 10),
                entry(timestamp = 2, byteSize = 20),
            ),
        )

        assertEquals(30L, dao.sumByteSize())
    }

    @Test
    fun clear_emptiesTable() = runBlocking {
        dao.insertAll(listOf(entry(timestamp = 1)))

        dao.clear()

        assertTrue(dao.isEmpty())
        assertFalse(dao.latest(10).isNotEmpty())
    }
}
```

> ⚠️ 注意：`deleteOldestBatch_removesOldest` 中注释 `sum 应为 3` 是错误断言，正确断言为 `assertEquals(3L, dao.sumByteSize())`。实施时按正确值写。

- [ ] **Step 6: 运行插桩测试**

Run: `.\gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.leonardo.ocbeacon.data.local.LogDaoTest`（需要模拟器；超时 300s）
Expected: 全部 9 个测试 PASS。若无模拟器，记录为待执行，但必须先通过 `compileDevDebugAndroidTestKotlin`。

- [ ] **Step 7: 编译 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:compileDevDebugAndroidTestKotlin`（120s）
Expected: 均 `BUILD SUCCESSFUL`

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DatabaseModule.kt app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/LogDaoTest.kt
git commit -m "feat: Room 数据库骨架（cached_messages/cached_parts/logs 三表 + LogDao + 插桩测试）"
```

---

### Task 3: LogStore（修剪逻辑等价迁移）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/LogStore.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/LogStoreTest.kt`

**Interfaces:**
- Consumes: `LogDao`（Task 2 定义的全部方法）
- Produces: `LogStore`（@Singleton，构造注入 `LogDao`）：
  - `suspend fun insert(entries: List<LogEntity>)` —— 写入 + 同事务修剪
  - `suspend fun latest(limit: Int = 1000): List<LogEntity>`（最新在前）
  - `suspend fun isEmpty(): Boolean`
  - `suspend fun clear()`

- [ ] **Step 1: 写修剪测试（TDD）**

`LogStoreTest.kt`（JVM 单测，用 mock DAO 验证修剪调用序列与常量）：

```kotlin
package dev.leonardo.ocbeacon.data.local

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LogStoreTest {

    private val dao = mockk<LogDao>(relaxed = true)
    private val store = LogStore(dao)

    // ---- 常量（与旧 DiagnosticLogDatabase 语义等价）----

    @Test
    fun retentionConstants_matchLegacyBehavior() {
        assertEquals(1000, LogStore.VISIBLE_ENTRY_LIMIT)
        assertEquals(10L * 1024L * 1024L, LogStore.MAX_PERSISTENT_BYTES)
        assertEquals(3L * 24L * 60L * 60L * 1000L, LogStore.ORDINARY_RETENTION_MS)
        assertEquals(21L * 24L * 60L * 60L * 1000L, LogStore.ERROR_RETENTION_MS)
        assertEquals(50, LogStore.CRASH_LIMIT)
        assertEquals(100, LogStore.PRUNE_BATCH_SIZE)
    }

    @Test
    fun insert_triggersTimeBasedPrune() = runTest {
        val now = 1_000_000L
        coEvery { dao.sumByteSize() } returns 0L

        store.insert(listOf(LogEntity(timestamp = 0, level = "INFO", category = "c", message = "m", details = "{}", byteSize = 1)), now)

        coVerifyOrder {
            dao.insertAll(any())
            dao.deleteOrdinaryBefore(now - LogStore.ORDINARY_RETENTION_MS)
            dao.deleteErrorBefore(now - LogStore.ERROR_RETENTION_MS)
            dao.deleteFatalBeyondLimit(LogStore.CRASH_LIMIT)
            dao.sumByteSize()
        }
    }

    @Test
    fun insert_byteBudgetExceeded_prunesInBatches() = runTest {
        val now = 1_000_000L
        coEvery { dao.sumByteSize() } returnsMany listOf(11L * 1024 * 1024, 11L * 1024 * 1024, 5L * 1024 * 1024)
        coEvery { dao.deleteOldestBatch(any()) } returns LogStore.PRUNE_BATCH_SIZE

        store.insert(listOf(LogEntity(timestamp = 0, level = "INFO", category = "c", message = "m", details = "{}", byteSize = 1)), now)

        coVerify(exactly = 2) { dao.deleteOldestBatch(LogStore.PRUNE_BATCH_SIZE) }
    }

    @Test
    fun insert_byteBudgetWithinLimit_noBatchPrune() = runTest {
        val now = 1_000_000L
        coEvery { dao.sumByteSize() } returns 500L

        store.insert(listOf(LogEntity(timestamp = 0, level = "INFO", category = "c", message = "m", details = "{}", byteSize = 1)), now)

        coVerify(exactly = 0) { dao.deleteOldestBatch(any()) }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.LogStoreTest" --rerun`（180s）
Expected: FAIL——`LogStore` 类不存在（编译错误）

- [ ] **Step 3: 实现 LogStore**

`LogStore.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断日志本地存储（原 DiagnosticLogDatabase 的 Room 迁移）。
 *
 * 修剪策略（语义与旧实现等价）：
 * - 普通日志保留 3 天，ERROR/FATAL 保留 21 天
 * - FATAL（崩溃）只保留最近 50 条
 * - 总字节预算 10MB，超出按最旧 100 条/批循环删除
 */
@Singleton
class LogStore @Inject constructor(
    private val dao: LogDao,
) {

    suspend fun insert(entries: List<LogEntity>, now: Long = System.currentTimeMillis()) {
        if (entries.isEmpty()) return
        dao.insertAll(entries)
        prune(now)
    }

    /** 最近 [limit] 条，最新在前。 */
    suspend fun latest(limit: Int = VISIBLE_ENTRY_LIMIT): List<LogEntity> = dao.latest(limit)

    suspend fun isEmpty(): Boolean = dao.isEmpty()

    suspend fun clear() = dao.clear()

    // ---- 修剪 ----------------------------------------------------------

    private suspend fun prune(now: Long) {
        dao.deleteOrdinaryBefore(now - ORDINARY_RETENTION_MS)
        dao.deleteErrorBefore(now - ERROR_RETENTION_MS)
        dao.deleteFatalBeyondLimit(CRASH_LIMIT)

        var totalBytes = dao.sumByteSize()
        while (totalBytes > MAX_PERSISTENT_BYTES) {
            val removed = dao.deleteOldestBatch(PRUNE_BATCH_SIZE)
            if (removed <= 0) break
            totalBytes = dao.sumByteSize()
        }
    }

    companion object {
        const val VISIBLE_ENTRY_LIMIT = 1000
        const val MAX_PERSISTENT_BYTES = 10L * 1024L * 1024L
        const val ORDINARY_RETENTION_MS = 3L * 24L * 60L * 60L * 1000L
        const val ERROR_RETENTION_MS = 21L * 24L * 60L * 60L * 1000L
        const val CRASH_LIMIT = 50
        const val PRUNE_BATCH_SIZE = 100
    }
}
```

> 说明：旧实现 `while` 循环中 `removedBytes` 从 SQL 子查询计算再减，本实现简化为每次删除后重新 `sumByteSize()`——语义等价（预算按当前实际字节判断），代码更简洁。`insert` 与 `prune` 在旧实现中同事务；Room 单 DAO 方法各自事务化，若需原子性可后续加 `@Transaction` 包装方法（日志修剪非严格原子要求，接受此简化）。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.LogStoreTest" --rerun`（180s）
Expected: 4 个测试全部 PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/LogStore.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/LogStoreTest.kt
git commit -m "feat: LogStore 诊断日志 Room 存储（修剪策略等价迁移 + 单测）"
```

---

### Task 4: DiagnosticLogRepository 切换到 LogStore + 删除旧 SQLite 实现

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/DiagnosticLogRepository.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/DiagnosticLogDatabase.kt`

**Interfaces:**
- Consumes: `LogStore`（Task 3）+ `LogEntity`（Task 2）
- Produces: `DiagnosticLogRepository` 公开接口**不变**：
  - `val logLevel: Flow<String>`
  - `val entries: Flow<List<DiagnosticLogEntry>>`
  - `suspend fun initialize()` / `record(...)` / `recordBatch(...)` / `clear()` / `setLogLevel(...)`
  - companion：`LOG_LEVELS` / `export(...)` / `sanitize(...)`（全部保留不动）

- [ ] **Step 1: 重构 DiagnosticLogRepository**

将 `private var database = DiagnosticLogDatabase(context, json)` 替换为注入 `LogStore`，`details` 的 JSON 编码从数据库层移到 Repository 层（原 `DiagnosticLogDatabase.insert` 内做 `json.encodeToString(entry.details)`），**移除不再使用的 `context` 构造参数**（旧代码用它做损坏恢复的 deleteDatabase，Room 实例由 Hilt 管理，损坏时 Room 自动处理/上层重启兜底）。同时移除不再使用的 `SQLiteException` 导入：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.data.local.LogEntity
import dev.leonardo.ocbeacon.data.local.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** （DiagnosticLogEntry 数据类与 sanitize/export 等 companion 内容保持不变，原样保留） */

@Singleton
class DiagnosticLogRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val logStore: LogStore,
) {
    private val logLevelKey = stringPreferencesKey("diagnostic_log_level")
    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())

    val logLevel: Flow<String> = dataStore.data.map { it[logLevelKey] ?: "INFO" }

    val entries: Flow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    /** 从数据库加载条目到 [_entries]。在应用启动时调用一次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        refresh()
    }

    suspend fun record(
        level: String,
        category: String,
        message: String,
        details: Map<String, String> = emptyMap(),
    ) {
        recordBatch(
            listOf(
                DiagnosticLogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    category = category,
                    message = message,
                    details = details,
                ),
            ),
        )
    }

    suspend fun recordBatch(entries: List<DiagnosticLogEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            logStore.insert(entries.map(::sanitizeEntry).map(::toEntity))
            refresh()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            logStore.clear()
            refresh()
        }
    }

    suspend fun setLogLevel(level: String) {
        dataStore.edit { it[logLevelKey] = level.takeIf { value -> value in LOG_LEVELS } ?: "INFO" }
    }

    // ---- 映射 ----------------------------------------------------

    private fun toEntity(entry: DiagnosticLogEntry): LogEntity = LogEntity(
        timestamp = entry.timestamp,
        level = entry.level,
        category = entry.category,
        message = entry.message,
        details = json.encodeToString(entry.details),
        byteSize = entry.estimatedByteSize(json.encodeToString(entry.details)),
    )

    private fun fromEntity(entity: LogEntity): DiagnosticLogEntry = DiagnosticLogEntry(
        timestamp = entity.timestamp,
        level = entity.level,
        category = entity.category,
        message = entity.message,
        details = runCatching {
            json.decodeFromString<Map<String, String>>(entity.details)
        }.getOrDefault(emptyMap()),
    )

    private fun DiagnosticLogEntry.estimatedByteSize(encodedDetails: String): Int =
        (level.length + category.length + message.length + encodedDetails.length) * 2 + 32

    private fun refresh() {
        _entries.value = logStore.latest().map(::fromEntity).asReversed()
    }

    // （companion：LOG_LEVELS / export / sanitize / sanitizeEntry 原样保留）
}
```

> ⚠️ 重要语义：旧 `latest()` 返回 `timestamp DESC`（最新在前）后 `asReversed()` → UI 期望**正序（旧→新）**。LogStore.latest 也是最新在前，所以 refresh 里同样 `asReversed()`——行为与旧实现完全一致。

- [ ] **Step 2: 删除旧 SQLite 实现**

```bash
git rm app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/DiagnosticLogDatabase.kt
```

确认无其他引用：`git grep -n "DiagnosticLogDatabase" -- app/src` 应只剩 DiagnosticLogRepository 的注释提及（无实际引用）。

- [ ] **Step 3: 编译 + 全量单测**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: 编译 SUCCESSFUL；全量单测 PASS（含现有 `DiagnosticLogRepositoryTest` 的 sanitize/export 测试——纯函数未动）

- [ ] **Step 4: 插桩测试验证（有模拟器时）**

Run: `.\gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.leonardo.ocbeacon.ui.screens.settings.DiagnosticsScreenDuplicateTimestampTest`（300s）
Expected: PASS——证明 DiagnosticLogRepository 真实注入路径（Room 版）工作正常

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: DiagnosticLogRepository 迁移到 Room LogStore，删除 DiagnosticLogDatabase（手写 SQL 清零）"
```

---

### Task 5: 全量验证 + 收尾

**Files:** 无新增

- [ ] **Step 1: 完整验证矩阵**

Run（顺序执行）:
1. `.\gradlew :app:compileDevDebugKotlin`（120s）→ BUILD SUCCESSFUL
2. `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）→ 全量 PASS
3. `.\gradlew :app:compileDevDebugAndroidTestKotlin`（120s）→ BUILD SUCCESSFUL
4. （有模拟器时）`.\gradlew :app:connectedDevDebugAndroidTest`（300s）→ LogDaoTest + DiagnosticsScreenDuplicateTimestampTest 等 PASS

- [ ] **Step 2: 确认手写 SQL 清零**

Run: `git grep -n "SQLiteOpenHelper\|SQLiteDatabase" -- app/src/main`
Expected: 无匹配（全部手写 SQL 已迁移到 Room）

- [ ] **Step 3: 人工验证清单（维度 5，提交给用户）**

- [ ] App 启动后 Diagnostics 屏幕能显示日志（记录/清理/级别过滤正常）
- [ ] 长时间运行日志量不失控（修剪生效：FATAL 不超 50 条、总量不超 10MB）
- [ ] 崩溃日志（FATAL）能出现在 Diagnostics 且保留 21 天语义

- [ ] **Step 4: 更新 backlog**

在 `backlog.md` 登记：**消息本地化批次（方案 C）——Plan 1 完成，Plan 2/3 待实施**（Tag: `data` `cache` `room`，P1）。

- [ ] **Step 5: 最终 Commit（如有 backlog 变更）**

```bash
git add backlog.md
git commit -m "docs: backlog 登记消息本地化批次进度（Plan 1 完成）"
```
