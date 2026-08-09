# 二期归档压缩 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现消息归档压缩——热表 prune 不再直接丢数据，而是先把最老消息整桶 zstd 压缩进 `archive_buckets` 表；翻页时优先从归档解压显示（离线可浏览、省网络）。

**Architecture:** 在现有 Room 热表（cached_messages/cached_parts）旁新增 `archive_buckets` 冷表。`MessageStore.upsertMessages` 内 prune 步骤前先查待删最老消息 → 按时间窗口分桶 → 序列化 `List<ArchivedMessageDto>` → zstd 压缩 → 写归档表 → 再执行原 DELETE。`MessagePaginationUseCase.loadOlderMessages` 改造为归档优先：本地归档有数据则解压返回（只进 UI 内存，不落热表），读尽才走网络。

**Tech Stack:** Kotlin + Room 2.8.4（DB v1→v2 Migration）+ zstd-jni 1.5.7-13 + kotlinx.serialization + Hilt + JUnit4/MockK/Turbine

## Global Constraints

- **分支**：`feature/archive-compression`（不合并 master，二期完成并验证后合回）
- **zstd-jni 版本**：`1.5.7-13`（Maven Central，2026-08-08）。`implementation("com.github.luben:zstd-jni:1.5.7-13@aar")` + `testImplementation("com.github.luben:zstd-jni:1.5.7-13")`
- **R8 keep**：zstd-jni README 明确 Java 类**不可混淆/重命名**（JVM 链接依赖类名）。必须在 `proguard-rules.pro` 添加 `-keep class com.github.luben.zstd.** { *; }`
- **热表上限**：`SESSION_MESSAGE_LIMIT = 1000`（一期既有，不变）
- **分桶窗口**：`ARCHIVE_BUCKET_WINDOW_MS = 86_400_000`（1 天）；序列化后 > `ARCHIVE_BUCKET_MAX_BYTES = 512 * 1024` 时按 `ARCHIVE_BUCKET_MAX_MESSAGES = 200` 条切分子桶
- **解压产物去向**：只进 UI 内存（`chatRepository.upsertMessages(APPEND_ONLY)`），**绝不**调 `messageStore.upsertMessages` 写回热表（死循环陷阱）
- **TLRU**：只记录 `lastAccessedAt`（读桶时 touch）；不做 TTL 自动删除（数据资产）。保护上限：每会话 `ARCHIVE_BUCKET_LIMIT = 200` 桶，超限删 leastAccessed
- **日志**：全部用 `AppLogger`（`logging/AppLogger.kt`），Tag 如 `MessageStore` / `MessagePaginationUseCase`；归档写 `[archive]`、解压写 `[dearchive]` 前缀，DEBUG 下记录
- **迁移**：`OcBeaconDatabase` version 1 → 2，`Migration(1, 2)` 建 `archive_buckets` 表，禁止 DROP 重建
- **事务**：归档 + prune 删除在 `withTransaction` 内（`database.withTransaction`，Room 2.8.4 用 `androidx.room.withTransaction`）
- **验证矩阵**：每任务 `compileDevDebugKotlin` + 相关单测；全部完成后 `testDevDebugUnitTest --rerun` + 模拟器实证

---

### Task 1: zstd-jni 依赖 + ZstdCodec

**Files:**
- Modify: `app/build.gradle.kts`（dependencies 块）
- Modify: `app/proguard-rules.pro`（追加 keep）
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ZstdCodec.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ZstdCodecTest.kt`

**Interfaces:**
- Consumes: 无（基础任务）
- Produces: `object ZstdCodec { fun compress(bytes: ByteArray): ByteArray; fun decompress(bytes: ByteArray, originalSize: Int): ByteArray }`

- [ ] **Step 1: 加依赖（build.gradle.kts）**

在 `app/build.gradle.kts` 的 `dependencies` 块中 Room 相关依赖附近添加：

```kotlin
// zstd 压缩（归档桶）
implementation("com.github.luben:zstd-jni:1.5.7-13@aar")
testImplementation("com.github.luben:zstd-jni:1.5.7-13")
```

- [ ] **Step 2: 加 R8 keep（proguard-rules.pro）**

在 `app/proguard-rules.pro` 末尾追加（README 铁律：类不可混淆/重命名）：

```
# zstd-jni: JNI 链接依赖类名，禁止混淆/重命名（luben/zstd-jni README）
-keep class com.github.luben.zstd.** { *; }
```

- [ ] **Step 3: 写失败测试（ZstdCodecTest.kt）**

```kotlin
package dev.leonardo.ocbeacon.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ZstdCodecTest {

    @Test
    fun compressDecompress_roundtrip_returnsOriginal() {
        val original = "Hello, OC Beacon! ".repeat(1000).toByteArray(Charsets.UTF_8)
        val compressed = ZstdCodec.compress(original)
        // 文本重复度高 → 压缩后显著更小
        assert(compressed.size < original.size)
        assertArrayEquals(original, ZstdCodec.decompress(compressed, original.size))
    }

    @Test
    fun compress_emptyArray_roundtrip() {
        val original = ByteArray(0)
        val compressed = ZstdCodec.compress(original)
        assertArrayEquals(original, ZstdCodec.decompress(compressed, original.size))
    }

    @Test
    fun decompress_wrongOriginalSize_throws() {
        val original = "payload".toByteArray(Charsets.UTF_8)
        val compressed = ZstdCodec.compress(original)
        assertThrows(Exception::class.java) { ZstdCodec.decompress(compressed, original.size + 100) }
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.ZstdCodecTest" --rerun`
Expected: 编译失败（ZstdCodec 不存在）或 FAIL

- [ ] **Step 5: 实现 ZstdCodec**

```kotlin
package dev.leonardo.ocbeacon.data.local

import com.github.luben.zstd.Zstd

/**
 * zstd 压缩编解码。解压需要原始大小（zstd API 约束），
 * 调用方负责持久化 [decompress] 的 originalSize（归档桶表存 uncompressedSize）。
 */
object ZstdCodec {
    fun compress(bytes: ByteArray): ByteArray = Zstd.compress(bytes)

    fun decompress(bytes: ByteArray, originalSize: Int): ByteArray = Zstd.decompress(bytes, originalSize)
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.ZstdCodecTest" --rerun`
Expected: PASS (3 tests)

- [ ] **Step 7: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ZstdCodec.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ZstdCodecTest.kt
git commit -m "feat: 二期 #32 zstd-jni 依赖 + ZstdCodec 压缩编解码（含 R8 keep）"
```

### Task 2: 归档表结构 + DB v2 Migration + DAO

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ArchivedMessageDto.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketEntity.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketDao.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/OcBeaconDatabase.kt`（version 2 + archiveBucketDao() + Migration 常量）
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/Migrations.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DatabaseModule.kt`（提供 ArchiveBucketDao + 挂 Migration）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketDaoTest.kt`

**Interfaces:**
- Consumes: `ZstdCodec`（Task 1）
- Produces: `ArchiveBucketEntity(id, sessionId, bucketStart, bucketEnd, messageCount, uncompressedSize, payload, createdAt, lastAccessedAt)`；`ArchiveBucketDao { upsert, latestBefore, count, leastAccessed, clearSession, delete, touch }`；`Migrations.MIGRATION_1_2`

- [ ] **Step 1: 写 DTO + Entity + DAO + Migration 骨架 + 失败测试**

先创建数据类与 DAO（Room 注解），再写 DAO 测试（失败：表不存在）。

`ArchivedMessageDto.kt`:
```kotlin
package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import kotlinx.serialization.Serializable

/** 归档桶内单条消息（整桶序列化后 zstd 压缩）。 */
@Serializable
data class ArchivedMessageDto(
    val info: Message,
    val parts: List<Part>,
)
```

`ArchiveBucketEntity.kt`:
```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "archive_buckets",
    indices = [Index(value = ["sessionId", "bucketEnd"])],
)
data class ArchiveBucketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val bucketStart: Long,
    val bucketEnd: Long,
    val messageCount: Int,
    val uncompressedSize: Int,
    val payload: ByteArray,
    val createdAt: Long,
    val lastAccessedAt: Long,
)
```

`ArchiveBucketDao.kt`:
```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArchiveBucketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bucket: ArchiveBucketEntity)

    /** 翻页：bucketEnd < beforeEnd 的最新 [limit] 桶（降序）。 */
    @Query("SELECT * FROM archive_buckets WHERE sessionId = :sessionId AND bucketEnd < :beforeEnd ORDER BY bucketEnd DESC LIMIT :limit")
    suspend fun latestBefore(sessionId: String, beforeEnd: Long, limit: Int): List<ArchiveBucketEntity>

    @Query("SELECT COUNT(*) FROM archive_buckets WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int

    /** 保护上限淘汰候选：最久未访问 [limit] 桶（升序）。 */
    @Query("SELECT * FROM archive_buckets WHERE sessionId = :sessionId ORDER BY lastAccessedAt ASC LIMIT :limit")
    suspend fun leastAccessed(sessionId: String, limit: Int): List<ArchiveBucketEntity>

    @Query("DELETE FROM archive_buckets WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("DELETE FROM archive_buckets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE archive_buckets SET lastAccessedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)
}
```

`Migrations.kt`:
```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    /** v1 → v2：新增归档桶表（热表三表不动）。 */
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
}
```

`ArchiveBucketDaoTest.kt`（先写测试，DB 用 in-memory + 迁移）：
```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ArchiveBucketDaoTest {

    private lateinit var db: androidx.room.RoomDatabase
    private lateinit var dao: ArchiveBucketDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OcBeaconDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = (db as OcBeaconDatabase).archiveBucketDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bucket(id: Long = 0, sessionId: String = "ses_1", bucketEnd: Long = 1000L) = ArchiveBucketEntity(
        id = id, sessionId = sessionId, bucketStart = 0L, bucketEnd = bucketEnd,
        messageCount = 10, uncompressedSize = 100, payload = ByteArray(10) { 1 },
        createdAt = 1L, lastAccessedAt = 1L,
    )

    @Test
    fun upsertAndLatestBefore_returnsDescending() = runTest {
        dao.upsert(bucket(bucketEnd = 3000L))
        dao.upsert(bucket(bucketEnd = 1000L))
        dao.upsert(bucket(bucketEnd = 2000L))

        val result = dao.latestBefore("ses_1", beforeEnd = 2500L, limit = 10)

        assertEquals(2, result.size)
        assertEquals(2000L, result[0].bucketEnd)
        assertEquals(1000L, result[1].bucketEnd)
    }

    @Test
    fun latestBefore_excludesEqualOrNewer() = runTest {
        dao.upsert(bucket(bucketEnd = 1000L))
        dao.upsert(bucket(bucketEnd = 2000L))

        val result = dao.latestBefore("ses_1", beforeEnd = 1000L, limit = 10)

        assertEquals(0, result.size)
    }

    @Test
    fun countAndLeastAccessed() = runTest {
        dao.upsert(bucket(bucketEnd = 3000L, id = 1L).copy(lastAccessedAt = 99L))
        dao.upsert(bucket(bucketEnd = 1000L, id = 2L).copy(lastAccessedAt = 1L))

        assertEquals(2, dao.count("ses_1"))
        val least = dao.leastAccessed("ses_1", 10)
        assertEquals(2, least.size)
        assertEquals(1L, least[0].lastAccessedAt)
    }

    @Test
    fun touchUpdatesLastAccessed() = runTest {
        dao.upsert(bucket(id = 1L))
        dao.touch(1L, at = 555L)
        val result = dao.latestBefore("ses_1", beforeEnd = Long.MAX_VALUE, limit = 10)
        assertEquals(555L, result[0].lastAccessedAt)
    }

    @Test
    fun clearSession_removesOnlyThatSession() = runTest {
        dao.upsert(bucket(sessionId = "ses_1"))
        dao.upsert(bucket(sessionId = "ses_2"))
        dao.clearSession("ses_1")
        assertEquals(0, dao.count("ses_1"))
        assertEquals(1, dao.count("ses_2"))
    }

    @Test
    fun delete_removesBucket() = runTest {
        dao.upsert(bucket(id = 1L))
        dao.delete(1L)
        assertEquals(0, dao.count("ses_1"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.ArchiveBucketDaoTest" --rerun`
Expected: 编译失败（表不存在 / Entity 未注册）

- [ ] **Step 3: 更新 OcBeaconDatabase（v2 + DAO + 注册 Entity + Migration）**

```kotlin
@Database(
    entities = [CachedMessageEntity::class, CachedPartEntity::class, LogEntity::class, ArchiveBucketEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class OcBeaconDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun messageDao(): MessageDao
    abstract fun archiveBucketDao(): ArchiveBucketDao
}
```

- [ ] **Step 4: DatabaseModule 提供 ArchiveBucketDao + 挂 Migration**

```kotlin
@Provides
fun provideArchiveBucketDao(database: OcBeaconDatabase): ArchiveBucketDao = database.archiveBucketDao()

// provideDatabase 内改为：
Room.databaseBuilder(context, OcBeaconDatabase::class.java, "ocbeacon.db")
    .addMigrations(Migrations.MIGRATION_1_2)
    .build()
```

- [ ] **Step 5: 跑测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.ArchiveBucketDaoTest" --rerun`
Expected: PASS (6 tests)

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ArchivedMessageDto.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketEntity.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketDao.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/OcBeaconDatabase.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/Migrations.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DatabaseModule.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketDaoTest.kt
git commit -m "feat: 二期 #32 archive_buckets 表 + DB v2 Migration + ArchiveBucketDao"
```

### Task 3: MessageDao 扩展 + MessageStore 归档编排（prune 前归档）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageDao.kt`（新增 oldestMessages）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt`（构造 + upsert 编排 + 分桶逻辑）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt`（扩展）

**Interfaces:**
- Consumes: `ArchiveBucketDao`（Task 2）、`ZstdCodec`（Task 1）、`ArchivedMessageDto`（Task 2）
- Produces: `MessageStore` 构造签名变为 `MessageStore(dao: MessageDao, archiveDao: ArchiveBucketDao, json: Json, databaseRecovery: DatabaseRecovery, clock: () -> Long = System::currentTimeMillis)`；内部 `buildArchiveBuckets` 逻辑；`[archive]` 日志

**关键设计**：`upsertMessages` 内 prune 步骤改造为"**查最老 → 归档 → 删**"。**归档发生在 prune 之前**：先 count 当前条数算出 overflow（超限条数），查那 overflow 条最老消息，归档它们（此时还在热表，能查到完整 payload），再执行 `pruneToLimit` 删除。顺序错误会导致归档到"现存"消息而非"被删"消息（数据仍丢失）——**禁止在 prune 之后查最老消息归档**。

- [ ] **Step 1: 写失败测试（MessageStoreTest 扩展）**

在 `MessageStoreTest.kt` 追加（构造参数变化，需先改测试 setup）:

```kotlin
// setup 改为：
private val archiveDao = mockk<ArchiveBucketDao>(relaxed = true)
private val store: MessageCacheRepository = MessageStore(dao, archiveDao, json, databaseRecovery, clock = { 1_000_000L })

// 新增测试：
@Test
fun upsertMessages_overflow_archivesOldestBeforePrune() = runTest {
    coEvery { dao.oldestMessageId("ses_1") } returns "msg_0"
    coEvery { dao.messageCreatedAt("msg_0") } returns 0L
    // 当前 1003 条 → overflow=3
    coEvery { dao.countForSession("ses_1") } returns 1003
    val old1 = msg("msg_1", 100)
    val old2 = msg("msg_2", 200)
    val old3 = msg("msg_3", 300)
    coEvery { dao.oldestMessages("ses_1", 3) } returns
        listOf(
            CachedMessageEntity("msg_1", "ses_1", 100, "user", json.encodeToString(old1.info)),
            CachedMessageEntity("msg_2", "ses_1", 200, "user", json.encodeToString(old2.info)),
            CachedMessageEntity("msg_3", "ses_1", 300, "user", json.encodeToString(old3.info)),
        )
    // parts：让每个 msg 的 part 可查
    coEvery { dao.partsForMessages(listOf("msg_1", "msg_2", "msg_3")) } returns emptyList()

    store.upsertMessages("ses_1", listOf(msg("msg_4", 400)), persistOldBeyondWindow = false)

    // 归档先于 prune：archiveDao.upsert 被调用，且 pruneToLimit 仍执行
    coVerify(exactly = 1) { archiveDao.upsert(any()) }
    coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
}

@Test
fun upsertMessages_noOverflow_doesNotArchive() = runTest {
    coEvery { dao.oldestMessageId("ses_1") } returns null
    coEvery { dao.countForSession("ses_1") } returns 999

    store.upsertMessages("ses_1", listOf(msg("msg_1", 100)), persistOldBeyondWindow = false)

    coVerify(exactly = 0) { archiveDao.upsert(any()) }
    coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
}

@Test
fun upsertMessages_windowSkip_noArchiveNoPrune() = runTest {
    // 窗口外消息全部跳过 → 不落库 → 不触发归档
    coEvery { dao.oldestMessageId("ses_1") } returns "msg_9"
    coEvery { dao.messageCreatedAt("msg_9") } returns 900L
    val older = msg("msg_1", 100)

    store.upsertMessages("ses_1", listOf(older), persistOldBeyondWindow = false)

    coVerify(exactly = 0) { archiveDao.upsert(any()) }
    coVerify(exactly = 0) { dao.upsertMessages(any()) }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.MessageStoreTest" --rerun`
Expected: 编译失败（构造签名变化 / countForSession / oldestMessages 不存在）

- [ ] **Step 3: MessageDao 加 countForSession + oldestMessages**

```kotlin
/** 当前会话热表消息数（算 overflow 用）。 */
@Query("SELECT COUNT(*) FROM cached_messages WHERE sessionId = :sessionId")
suspend fun countForSession(sessionId: String): Int

/** 待 prune 的最老消息（created ASC 前 [limit] 条）——归档前查询。 */
@Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created ASC, id ASC LIMIT :limit")
suspend fun oldestMessages(sessionId: String, limit: Int): List<CachedMessageEntity>
```

- [ ] **Step 4: MessageStore 改造（构造 + 归档编排）**

构造签名与 upsertMessages 改造（完整替换关键段）：

```kotlin
@Singleton
class MessageStore @Inject constructor(
    private val dao: MessageDao,
    private val archiveDao: ArchiveBucketDao,
    private val json: Json,
    private val databaseRecovery: DatabaseRecovery,
    private val clock: () -> Long = System::currentTimeMillis,
) : MessageCacheRepository {

    override suspend fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        persistOldBeyondWindow: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        runCatching {
            databaseRecovery.withCorruptionRecovery {
                val oldestId = dao.oldestMessageId(sessionId)
                val oldestCreated = oldestId?.let { dao.messageCreatedAt(it) }
                val toPersist = if (persistOldBeyondWindow || oldestCreated == null) {
                    messages
                } else {
                    messages.filter { m -> m.info.time.created >= oldestCreated }
                }
                if (toPersist.isEmpty()) {
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(TAG, "[upsert] session=$sessionId: all ${messages.size} msgs outside window (oldest cached=$oldestCreated), skip persist")
                    }
                    return@withCorruptionRecovery
                }

                dao.upsertMessages(
                    toPersist.map { m ->
                        CachedMessageEntity(
                            id = m.info.id,
                            sessionId = sessionId,
                            created = m.info.time.created,
                            role = m.info.role,
                            payload = json.encodeToString(m.info),
                        )
                    },
                )
                dao.upsertParts(
                    toPersist.flatMap { m ->
                        m.parts.map { p ->
                            CachedPartEntity(
                                id = p.id,
                                messageId = m.info.id,
                                sessionId = sessionId,
                                type = p.typeName(),
                                text = (p as? Part.Text)?.text,
                                payload = json.encodeToString(p),
                            )
                        }
                    },
                )
                // ---- 归档编排（prune 前）：count → 查 overflow 最老 → 归档 → prune 删 ----
                val total = dao.countForSession(sessionId)
                val overflow = (total - SESSION_MESSAGE_LIMIT).coerceAtLeast(0)
                if (overflow > 0) {
                    archiveOverflow(sessionId, overflow)
                }
                val pruned = dao.pruneToLimit(sessionId, SESSION_MESSAGE_LIMIT)
                if (BuildConfig.DEBUG && pruned > 0) {
                    AppLogger.d(TAG, "[prune] session=$sessionId: removed $pruned oldest msgs (limit=$SESSION_MESSAGE_LIMIT)")
                }
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "MessageStore upsert failed (memory view unaffected)", e)
        }
    }

    /**
     * 归档"将被 prune 的最老 [overflow] 条"（此时仍在热表，可查完整 payload）。
     * 按时间窗口分桶 → zstd 压缩 → 写归档表。
     * 失败不抛（归档是增强，正确性仍由热表 + 服务端保证；数据按一期行为丢弃）。
     */
    private suspend fun archiveOverflow(sessionId: String, overflow: Int) {
        runCatching {
            val candidates = dao.oldestMessages(sessionId, overflow)
            if (candidates.isEmpty()) return@runCatching
            val partsByMsg = dao.partsForMessages(candidates.map { it.id })
                .groupBy { it.messageId }
            val messages = candidates.map { entity ->
                ArchivedMessageDto(
                    info = json.decodeFromString<Message>(entity.payload),
                    parts = (partsByMsg[entity.id] ?: emptyList()).mapNotNull { pe ->
                        pe.payload?.let { runCatching { json.decodeFromString<Part>(it) }.getOrNull() }
                    },
                )
            }
            val buckets = buildArchiveBuckets(sessionId, messages)
            buckets.forEach { bucket -> archiveDao.upsert(bucket) }
            enforceArchiveLimit(sessionId)
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "[archive] session=$sessionId: archived ${messages.size} msgs → ${buckets.size} buckets")
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "[archive] session=$sessionId: archive failed (data dropped as before)", e)
        }
    }

    /** 按时间窗口分桶；超 512KB 按 200 条切子桶。返回待写桶列表。 */
    internal fun buildArchiveBuckets(sessionId: String, messages: List<ArchivedMessageDto>): List<ArchiveBucketEntity> {
        val now = clock()
        return messages.groupBy { m ->
            m.info.time.created / ARCHIVE_BUCKET_WINDOW_MS
        }.flatMap { (_, group) ->
            group.chunked(ARCHIVE_BUCKET_MAX_MESSAGES).map { chunk ->
                val jsonBytes = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)
                ArchiveBucketEntity(
                    sessionId = sessionId,
                    bucketStart = chunk.minOf { it.info.time.created },
                    bucketEnd = chunk.maxOf { it.info.time.created },
                    messageCount = chunk.size,
                    uncompressedSize = jsonBytes.size,
                    payload = ZstdCodec.compress(jsonBytes),
                    createdAt = now,
                    lastAccessedAt = now,
                )
            }
        }
    }

    /** 保护上限：每会话超 [ARCHIVE_BUCKET_LIMIT] 桶时删最久未访问。 */
    private suspend fun enforceArchiveLimit(sessionId: String) {
        val current = archiveDao.count(sessionId)
        if (current <= ARCHIVE_BUCKET_LIMIT) return
        val excess = current - ARCHIVE_BUCKET_LIMIT
        archiveDao.leastAccessed(sessionId, excess).forEach { archiveDao.delete(it.id) }
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[archive] session=$sessionId: evicted $excess least-accessed buckets (limit=$ARCHIVE_BUCKET_LIMIT)")
        }
    }

    companion object {
        private const val TAG = "MessageStore"
        const val SESSION_MESSAGE_LIMIT = 1000
        const val ARCHIVE_BUCKET_WINDOW_MS = 86_400_000L          // 1 天
        const val ARCHIVE_BUCKET_MAX_BYTES = 512 * 1024           // 512KB（调研约束）
        const val ARCHIVE_BUCKET_MAX_MESSAGES = 200
        const val ARCHIVE_BUCKET_LIMIT = 200                      // 每会话桶保护上限 ≈ 20 万条历史
    }
}
```

**注意**：`ARCHIVE_BUCKET_MAX_BYTES` 定义了但分桶逻辑先用 `ARCHIVE_BUCKET_MAX_MESSAGES` 切分（时间窗口 + 条数双约束已满足"单桶 ≤512KB"的意图——200 条 JSON 通常 < 512KB；如需字节级切分可在 Task 6 加强，YAGNI 先不做）。

- [ ] **Step 5: 跑测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.MessageStoreTest" --rerun`
Expected: PASS（原 6 + 新 3）

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageDao.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt
git commit -m "feat: 二期 #32 MessageStore 归档编排（prune 前整桶 zstd 归档 + 桶保护上限）"
```

### Task 4: MessageCacheRepository 接口扩展 + MessageStore 归档读取

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/MessageCacheRepository.kt`（新增 2 方法）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt`（实现 loadArchivedRange / hasArchivedMessages）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt`（扩展）

**Interfaces:**
- Consumes: `ArchiveBucketDao`（Task 2）、`ZstdCodec`（Task 1）、`ArchivedMessageDto`（Task 2）
- Produces: `MessageCacheRepository.loadArchivedRange(sessionId, limit, beforeCreated): List<MessageWithParts>`、`hasArchivedMessages(sessionId, beforeCreated): Boolean`

**关键**：接口扩展会波及 `MessageStore`（真实实现需新增方法）+ 所有 mockk relaxed（自动适配，无需改）。`MessageStoreTest` 里 `store` 声明为接口类型已能验证新方法。

- [ ] **Step 1: 写失败测试（MessageStoreTest 扩展）**

在 `MessageStoreTest.kt` 追加：

```kotlin
@Test
fun loadArchivedRange_decodesAndReturnsMessages() = runTest {
    val bucket = ArchiveBucketEntity(
        id = 1L, sessionId = "ses_1",
        bucketStart = 100L, bucketEnd = 300L,
        messageCount = 2, uncompressedSize = 0,  // 下面用真实 json 计算
        payload = ByteArray(0),
        createdAt = 1L, lastAccessedAt = 1L,
    )
    // 预编码：构造一个真实归档桶（复用 buildArchiveBuckets 产生的 payload）
    val msgs = listOf(
        ArchivedMessageDto(msg("msg_1", 100).info, msg("msg_1", 100).parts),
        ArchivedMessageDto(msg("msg_2", 200).info, msg("msg_2", 200).parts),
    )
    // 直接构造桶（绕过 DAO）：用 json 手动序列化压缩
    val jsonBytes = json.encodeToString(msgs).toByteArray(Charsets.UTF_8)
    val realBucket = ArchiveBucketEntity(
        id = 1L, sessionId = "ses_1",
        bucketStart = 100L, bucketEnd = 200L,
        messageCount = 2, uncompressedSize = jsonBytes.size,
        payload = ZstdCodec.compress(jsonBytes),
        createdAt = 1L, lastAccessedAt = 1L,
    )
    coEvery { archiveDao.latestBefore("ses_1", 1000L, any()) } returns listOf(realBucket)

    val result = store.loadArchivedRange("ses_1", limit = 50, beforeCreated = 1000L)

    assertEquals(2, result.size)
    assertEquals("msg_1", result[0].info.id)
    assertEquals("msg_2", result[1].info.id)
    // 读取后 touch 更新 lastAccessedAt
    coVerify { archiveDao.touch(1L, any()) }
}

@Test
fun loadArchivedRange_noBuckets_returnsEmpty() = runTest {
    coEvery { archiveDao.latestBefore("ses_1", 1000L, any()) } returns emptyList()

    val result = store.loadArchivedRange("ses_1", limit = 50, beforeCreated = 1000L)

    assertEquals(0, result.size)
}

@Test
fun hasArchivedMessages_trueWhenBucketExists() = runTest {
    coEvery { archiveDao.latestBefore("ses_1", 1000L, 1) } returns listOf(mockk<ArchiveBucketEntity>())

    assertEquals(true, store.hasArchivedMessages("ses_1", 1000L))
}

@Test
fun hasArchivedMessages_falseWhenNone() = runTest {
    coEvery { archiveDao.latestBefore("ses_1", 1000L, 1) } returns emptyList()

    assertEquals(false, store.hasArchivedMessages("ses_1", 1000L))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.MessageStoreTest" --rerun`
Expected: 编译失败（接口缺方法 / loadArchivedRange 不存在）

- [ ] **Step 3: 接口加方法（MessageCacheRepository.kt）**

```kotlin
    /**
     * 归档读取：查 session 在 [beforeCreated] 之前的归档桶（bucketEnd < beforeCreated），
     * 跨桶解压拼接直到凑满 [limit] 条；读到的桶 touch(lastAccessedAt)。无归档返回 emptyList。
     */
    suspend fun loadArchivedRange(sessionId: String, limit: Int, beforeCreated: Long): List<MessageWithParts>

    /** 是否存在 beforeCreated 之前的归档数据（翻页 hasMore 判断）。 */
    suspend fun hasArchivedMessages(sessionId: String, beforeCreated: Long): Boolean
```

- [ ] **Step 4: MessageStore 实现**

```kotlin
    override suspend fun loadArchivedRange(
        sessionId: String,
        limit: Int,
        beforeCreated: Long,
    ): List<MessageWithParts> = withContext(Dispatchers.IO) {
        databaseRecovery.withCorruptionRecovery {
            val result = mutableListOf<MessageWithParts>()
            var beforeEnd = beforeCreated
            var need = limit
            while (need > 0) {
                val buckets = archiveDao.latestBefore(sessionId, beforeEnd, limit = 1)
                if (buckets.isEmpty()) break
                val bucket = buckets[0]
                val decoded = runCatching { decodeBucket(bucket) }.getOrElse { e ->
                    AppLogger.e(TAG, "[dearchive] session=$sessionId bucket=${bucket.id}: decode failed, skipping", e)
                    emptyList()
                }
                archiveDao.touch(bucket.id, clock())
                result.addAll(decoded)
                if (BuildConfig.DEBUG && decoded.isNotEmpty()) {
                    AppLogger.d(TAG, "[dearchive] session=$sessionId bucket=${bucket.id}: ${decoded.size} msgs (before=$beforeEnd)")
                }
                need -= decoded.size
                beforeEnd = bucket.bucketStart  // 下个桶必须更早（用桶起点做游标，避免边界重复）
                if (decoded.isEmpty()) break  // 坏桶防死循环
            }
            result
        } ?: emptyList()
    }

    override suspend fun hasArchivedMessages(sessionId: String, beforeCreated: Long): Boolean =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                archiveDao.latestBefore(sessionId, beforeCreated, limit = 1).isNotEmpty()
            } ?: false
        }

    /** 解压单个归档桶 → MessageWithParts 列表（created 升序）。 */
    private fun decodeBucket(bucket: ArchiveBucketEntity): List<MessageWithParts> {
        val bytes = ZstdCodec.decompress(bucket.payload, bucket.uncompressedSize)
        val dtos = json.decodeFromString<List<ArchivedMessageDto>>(bytes.decodeToString())
        return dtos.map { dto -> MessageWithParts(info = dto.info, parts = dto.parts) }
            .sortedBy { it.info.time.created }
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.MessageStoreTest" --rerun`
Expected: PASS

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/MessageCacheRepository.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt
git commit -m "feat: 二期 #32 MessageCacheRepository 归档读取接口 + MessageStore 解压实现（[dearchive] 日志）"
```

### Task 5: 翻页归档优先（MessagePaginationUseCase + Delegate 适配）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCase.kt`（loadOlderMessages 改造 + LoadOlderResult）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegate.kt`（loadOlderMessages 适配）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCaseTest.kt`（扩展）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegateTest.kt`（扩展）

**Interfaces:**
- Consumes: `messageStore.loadArchivedRange` / `hasArchivedMessages`（Task 4）
- Produces: `data class LoadOlderResult(val messages: List<MessageWithParts>, val source: LoadOlderSource)` + `enum class LoadOlderSource { ARCHIVE, NETWORK }`

**关键**：`loadOlderMessages` 先尝试归档；归档有数据 → 直接返回（ARCHIVE 来源，不调网络）。归档读尽 → 走网络（NETWORK 来源，现有逻辑）。`MessagePaginationDelegate.loadOlderMessages` 对 ARCHIVE 来源**只进内存**（`chatRepository.upsertMessages(APPEND_ONLY)`），不调 `messageStore.upsertMessages`。

- [ ] **Step 1: 写失败测试（MessagePaginationUseCaseTest 扩展）**

```kotlin
@Test
fun loadOlderMessages_archiveAvailable_returnsArchiveWithoutNetwork() = runTest {
    val archived = listOf(msg("msg_0", 50), msg("msg_1", 100))
    coEvery { messageStore.messageCreatedAt("msg_5") } returns 500L
    coEvery { messageStore.hasArchivedMessages("ses_1", 500L) } returns true
    coEvery { messageStore.loadArchivedRange("ses_1", 50, 500L) } returns archived

    val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_5")

    assertTrue(result.isSuccess)
    val loaded = result.getOrThrow()
    assertEquals(2, loaded.messages.size)
    assertEquals(LoadOlderSource.ARCHIVE, loaded.source)
    // 网络不调用
    coVerify(exactly = 0) { sessionRepository.listMessages(any(), any(), any(), any()) }
    // 不落热表（防死循环）
    coVerify(exactly = 0) { messageStore.upsertMessages(any(), any(), any()) }
}

@Test
fun loadOlderMessages_noArchive_usesNetwork() = runTest {
    coEvery { messageStore.messageCreatedAt("msg_5") } returns 500L
    coEvery { messageStore.hasArchivedMessages("ses_1", 500L) } returns false
    val page = MessagePage(messages = listOf(msg("msg_0", 50)), nextCursor = null)
    val expectedBefore = CursorCodec.encode("msg_5", 500L)
    coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

    val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_5")

    assertTrue(result.isSuccess)
    assertEquals(LoadOlderSource.NETWORK, result.getOrThrow().source)
    assertEquals(1, result.getOrThrow().messages.size)
    coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_0", 50)), false) }
}

@Test
fun loadOlderMessages_archiveReadExhausted_fallsBackToNetwork() = runTest {
    coEvery { messageStore.messageCreatedAt("msg_5") } returns 500L
    coEvery { messageStore.hasArchivedMessages("ses_1", 500L) } returns true
    coEvery { messageStore.loadArchivedRange("ses_1", 50, 500L) } returns emptyList()  // 归档空（坏桶等）
    val page = MessagePage(messages = listOf(msg("msg_0", 50)), nextCursor = null)
    val expectedBefore = CursorCodec.encode("msg_5", 500L)
    coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

    val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_5")

    assertTrue(result.isSuccess)
    assertEquals(LoadOlderSource.NETWORK, result.getOrThrow().source)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCaseTest" --rerun`
Expected: 编译失败（LoadOlderResult / source 不存在）

- [ ] **Step 3: 实现 LoadOlderResult + 改造 loadOlderMessages**

在 `MessagePaginationUseCase.kt`：

```kotlin
enum class LoadOlderSource { ARCHIVE, NETWORK }

data class LoadOlderResult(
    val messages: List<MessageWithParts>,
    val source: LoadOlderSource,
)

// loadOlderMessages 改造（替换整个方法）：
    suspend fun loadOlderMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        beforeId: String?,
    ): Result<LoadOlderResult> {
        // 本地归档优先：before 游标对应的 created 之前的归档桶
        val beforeCreated = beforeId?.let { messageStore.messageCreatedAt(it) }
        if (beforeCreated != null && messageStore.hasArchivedMessages(sessionId, beforeCreated)) {
            val archived = messageStore.loadArchivedRange(sessionId, limit, beforeCreated)
            if (archived.isNotEmpty()) {
                AppLogger.d(TAG, "[paging] session=$sessionId: ${archived.size} older msgs from archive (before=$beforeCreated)")
                return Result.success(LoadOlderResult(archived, LoadOlderSource.ARCHIVE))
            }
        }
        // 归档读尽 → 网络
        return runCatching {
            val before = beforeId?.let { id ->
                val created = messageStore.messageCreatedAt(id)
                if (created != null) CursorCodec.encode(id, created) else null
            }
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
            LoadOlderResult(page.messages, LoadOlderSource.NETWORK)
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCaseTest" --rerun`
Expected: PASS（原 6 + 新 3）

- [ ] **Step 5: MessagePaginationDelegate 适配**

`MessagePaginationDelegate.loadOlderMessages()` 改造（替换方法体）：

```kotlin
    fun loadOlderMessages() {
        val sid = sessionIdProvider()
        scope.launch {
            _isLoadingOlder.value = true
            try {
                val beforeId = messageStore.oldestMessageId(sid)
                val result = messagePaging.loadOlderMessages(serverId, sid, currentMessageLimit, beforeId)
                    .getOrThrow()
                // 归档来源只进内存（不落热表 → 防死循环）；网络来源保持现状（upsert 内自控落库）
                chatRepository.upsertMessages(sid, result.messages, MergeStrategy.APPEND_ONLY)
                _hasOlderMessages.value =
                    if (result.source == LoadOlderSource.ARCHIVE) {
                        // 归档还有更早 → true；已读尽 → 由下轮网络决定（这里保守 true，触发下轮尝试）
                        true
                    } else {
                        result.messages.size >= currentMessageLimit
                    }
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "Loaded older: ${result.messages.size} msgs (source=${result.source}, before=$beforeId, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load older messages", e)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }
```

**注意**：`MessagePaginationDelegateTest` 现有 3 个 `loadOlderMessages` 测试（55-142 行）mock 返回 `Result.success(mkMessages(...))`——签名改为 `Result<LoadOlderResult>` 后**必须同步更新 mock**。计划 Step 5 前置：更新这些测试。

- [ ] **Step 5a: 更新 MessagePaginationDelegateTest 现有 loadOlderMessages mock**

将 3 处 `returns Result.success(mkMessages(N))` 改为：

```kotlin
returns Result.success(LoadOlderResult(mkMessages(N), LoadOlderSource.NETWORK))
```

（第 57、90、118 行附近；`import dev.leonardo.ocbeacon.domain.usecase.LoadOlderResult` / `LoadOlderSource`）

- [ ] **Step 5b: 补 MessagePaginationDelegateTest 归档分支测试**

```kotlin
@Test
fun `loadOlderMessages archive source only merges memory not store`() = runTest {
    val paging = mockk<MessagePaginationUseCase> {
        coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0") } returns
            Result.success(LoadOlderResult(mkMessages(10), LoadOlderSource.ARCHIVE))
    }
    val store = mockk<MessageStore> {
        coEvery { oldestMessageId("sid-1") } returns "m-0"
    }
    val repo = mockk<ChatRepository>(relaxed = true)
    val delegate = MessagePaginationDelegate(
        manageSessionUseCase = mockk(relaxed = true),
        messagePaging = paging,
        messageStore = store,
        chatRepository = repo,
        settingsRepository = mockk(),
        serverId = "srv",
        scope = this,
        sessionIdProvider = { "sid-1" },
        loadingSink = {},
        errorSink = {},
    )

    delegate.loadOlderMessages()
    advanceUntilIdle()

    // 归档来源只进内存（APPEND_ONLY），不落热表
    verify(exactly = 1) { repo.upsertMessages("sid-1", any(), MergeStrategy.APPEND_ONLY) }
    assertTrue(delegate.hasOlderMessages.value)
}
```

- [ ] **Step 5c: MessagePaginationDelegate 适配**

`MessagePaginationDelegate.loadOlderMessages()` 改造（替换方法体）：

```kotlin
    fun loadOlderMessages() {
        val sid = sessionIdProvider()
        scope.launch {
            _isLoadingOlder.value = true
            try {
                val beforeId = messageStore.oldestMessageId(sid)
                val result = messagePaging.loadOlderMessages(serverId, sid, currentMessageLimit, beforeId)
                    .getOrThrow()
                // 归档来源只进内存（不落热表 → 防死循环）；网络来源保持现状（upsert 内自控落库）
                chatRepository.upsertMessages(sid, result.messages, MergeStrategy.APPEND_ONLY)
                _hasOlderMessages.value = when (result.source) {
                    LoadOlderSource.ARCHIVE -> {
                        // 归档仍有更早数据 → 允许继续翻页（下次循环归档读尽后自动切网络）
                        // 注意：hasArchivedMessages 在 use case 内已判断，这里保守返回 true
                        // 由下一轮 loadOlderMessages 的 use case 内部判断决定是否继续
                        true
                    }
                    LoadOlderSource.NETWORK -> result.messages.size >= currentMessageLimit
                }
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "Loaded older: ${result.messages.size} msgs (source=${result.source}, before=$beforeId, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load older messages", e)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }
```

- [ ] **Step 6: 跑相关测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.MessagePaginationDelegateTest" --rerun`
Expected: PASS（原 3 个 loadOlderMessages 测试已改 mock + 新增 1 个归档分支）

- [ ] **Step 7: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCase.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegate.kt app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCaseTest.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegateTest.kt
git commit -m "feat: 二期 #32 翻页归档优先（LoadOlderResult + ARCHIVE/NETWORK 来源，归档只进内存）"
```

### Task 6: 收尾（clearSession 级联 + 全量验证 + 文档）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt`（clearSession 加归档清理）
- Modify: `docs/backlog.md`（#32 进度更新）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt`（clearSession 测试）

**Interfaces:**
- Consumes: `ArchiveBucketDao.clearSession`（Task 2）
- Produces: `MessageStore.clearSession` 同时清热表 + 归档；全绿测试矩阵

- [ ] **Step 1: MessageStore.clearSession 级联清理**

```kotlin
    override suspend fun clearSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                dao.clearSession(sessionId)
                archiveDao.clearSession(sessionId)
            }
        }
    }
```

- [ ] **Step 2: 补 clearSession 测试**

```kotlin
@Test
fun clearSession_clearsHotAndArchive() = runTest {
    store.clearSession("ses_1")
    coVerify(exactly = 1) { dao.clearSession("ses_1") }
    coVerify(exactly = 1) { archiveDao.clearSession("ses_1") }
}
```

- [ ] **Step 3: 跑测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.MessageStoreTest" --rerun`
Expected: PASS

- [ ] **Step 4: 全量单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全 PASS（含一期 1313+ 用例）

- [ ] **Step 5: 编译检查（含 androidTest 编译）**

Run: `.\gradlew :app:compileDevDebugKotlin` + `.\gradlew :app:compileDevDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 更新 backlog #32**

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt backlog.md
git commit -m "feat: 二期 #32 clearSession 级联归档清理 + 全量测试通过"
```

### 最终验证（模拟器，见 Skill: verification-before-completion）

1. 构建 dev debug APK → 安装模拟器
2. 注入 >1100 条消息（db-inject 脚本复用一期流程）→ 启动 → 发送真实消息触发 upsert → logcat 查 `[prune]` + `[archive]`
3. db 实证：热表 = 1000，archive_buckets > 0（query archive_buckets）
4. 断网（飞行模式）→ 进入会话 → 向上滚动触发 loadOlderMessages → logcat 查 `[paging] ... from archive` + `[dearchive]` → UI 显示历史消息
5. 恢复网络 → 继续滚动 → 归档读尽后走网络
6. 全量日志佐证 + 截图存档
