# Plan 2：消息本地化核心 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消息本地化核心：已拉取消息落 Room 本地缓存，进入会话先渲染本地 + REST 增量拉取缺失区间（真游标分页），SSE 实时双写（内存 + Room）——消除大会话重复下载与首屏等待。

**Architecture:** 三层改造：①API 层接入 `before` 游标 + `X-Next-Cursor` header（`MessageApi.listMessages` 返回分页结果对象）；②新增 `MessageStore`（Room 读写 + 限量 1000 条/会话裁剪）；③数据流接线——`MessagePaginationDelegate` 缓存优先 + 真游标翻页，`MessageEventHandler` 合并策略统一为 `upsertMessages(sid, incoming, strategy)`，SSE 事件实时写 Room。内存 StateFlow 保持为 UI 热视图，Room 为持久层镜像。

**Tech Stack:** Room 2.8.4（已在 Plan 1 引入）、现有 kotlinx.serialization（Message/Part 已有自定义序列化器）、Ktor 3.5.0（读 header）、Hilt。

## Global Constraints

- 游标格式：`base64url(JSON({"id": <msgId>, "time": <created>}))`；分页响应 header：`Link: <url>; rel="next"` + `X-Next-Cursor: <cursor>`（服务端文档 + curl 实测）
- 限量缓存：**每会话最近 1000 条**，超出删除最旧（`DELETE ... NOT IN (SELECT ... ORDER BY created DESC, id DESC LIMIT 1000)`）；parts 靠 FK CASCADE 级联清理
- **严格最近 N 条**：翻页拉到本地窗口外的更早消息 → **仅内存渲染，不写 Room**（避免"写了又被裁"循环）
- SSE 实时写库：48ms 批处理（沿用现有机制）+ `Dispatchers.IO`；`MessagePartDelta` 聚合成最终文本后写一次
- 进入会话时序：内存快照 → 本地种子化（Room Flow）→ REST 增量（`before=<本地最旧游标>`）→ upsert → 限量裁剪
- 现有 `setMessages`（SSE 优先）/`replaceMessages`（REST 优先）/`mergeMessages`（仅补充）三方法语义保留，合并为单一 `upsertMessages(sessionId, incoming, strategy)`，调用点语义逐一对应（迁移时不得改变既有行为）
- 合并策略常量：`SSE_PRIORITY` / `REST_AUTHORITY` / `APPEND_ONLY`
- Room 写失败：捕获 + `AppLogger.e`，**内存视图不受影响**（写库失败不阻断 UI）
- 数据库版本仍为 1（表已建，Plan 2 只加 DAO 方法，不升级 schema）
- `CachedMessageEntity.payload` = 完整 Message JSON（用注入的 `Json`，`MessageWithParts.info` 序列化）；`CachedPartEntity.payload` = 完整 Part JSON（`PartSerializer` 已支持）
- 测试：MessageStore 单测（MockK mock DAO）+ upsert 合并单测（替换/扩充现有 `MessageEventHandlerMergeTest`）+ API 层单测（Ktor MockEngine 验证 header 解析）
- Windows 构建：`.\gradlew` 前缀；编译 120s / 单测 180s
- androidTest 仍受预存 #29 阻塞（Fake 类缺方法）——本计划所有 DAO/Store 测试走 JVM 单测（MockK），不依赖 androidTest 编译

---

### Task 1: MessageApi 游标分页接入

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/message/MessageApi.kt`（接口 + Impl）
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/message/MessagePage.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImpl.kt:212-219`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SessionRepository.kt:164`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/message/MessageApiCursorTest.kt`

**Interfaces:**
- Produces:
  - `data class MessagePage(val messages: List<MessageWithParts>, val nextCursor: String?)`
  - `MessageApi.listMessages(conn, sessionId, limit, before): MessagePage`
  - `SessionRepository.listMessages(serverId, sessionId, limit, before): Result<MessagePage>`
- Consumes: `ServerConnection`（现有）、`MessageWithParts`（现有）

- [ ] **Step 1: 写失败测试（API 层 header 解析）**

`MessageApiCursorTest.kt`（Ktor MockEngine）：

```kotlin
package dev.leonardo.ocbeacon.data.api.message

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageApiCursorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun apiWith(engine: MockEngine): MessageApiImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val apiClient = ApiClient(httpClient = client, json = json)
        return MessageApiImpl(apiClient)
    }

    private val conn = ServerConnection(
        baseUrl = "http://test.local",
        username = "u",
        password = "p",
    )

    @Test
    fun listMessages_passesLimitAndBeforeAsQueryParams() = runTest {
        var requestedUrl: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = "[]",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = apiWith(engine)
        val cursor = "eyJpZCI6Im1zZ18xIiwidGltZSI6MTIzfQ"

        api.listMessages(conn, "ses_1", limit = 50, before = cursor)

        assertTrue(requestedUrl!!.contains("limit=50"))
        assertTrue(requestedUrl!!.contains("before=$cursor"))
    }

    @Test
    fun listMessages_parsesNextCursorHeader() = runTest {
        val nextCursor = "eyJpZCI6Im1zZ18yIiwidGltZSI6NDU2fQ"
        val engine = MockEngine { request ->
            respond(
                content = "[]",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType, "application/json",
                    "X-Next-Cursor", nextCursor,
                    "Link", "<http://test.local/session/ses_1/message?limit=50&before=$nextCursor>; rel=\"next\"",
                ),
            )
        }
        val api = apiWith(engine)

        val page = api.listMessages(conn, "ses_1", limit = 50, before = null)

        assertEquals(nextCursor, page.nextCursor)
        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun listMessages_noNextCursorHeader_returnsNull() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = "[]",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = apiWith(engine)

        val page = api.listMessages(conn, "ses_1", limit = 50, before = null)

        assertNull(page.nextCursor)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.api.message.MessageApiCursorTest" --rerun`（180s）
Expected: FAIL——`MessagePage` 不存在 / `listMessages` 签名不匹配

- [ ] **Step 3: 创建 MessagePage**

`MessagePage.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.api.message

import dev.leonardo.ocbeacon.domain.model.MessageWithParts

/**
 * 分页消息结果。nextCursor 为空表示已到最早消息（无更早数据）。
 */
data class MessagePage(
    val messages: List<MessageWithParts>,
    val nextCursor: String?,
)
```

- [ ] **Step 4: 修改 MessageApi 接口与实现**

接口（`MessageApi.kt:27-28` 替换）：

```kotlin
interface MessageApi {
    suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
    ): MessagePage
```

实现（`MessageApiImpl.kt:124-129` 替换）：

```kotlin
    override suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int?,
        before: String?,
    ): MessagePage {
        val response = httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
            limit?.let { parameter("limit", it) }
            before?.let { parameter("before", it) }
        }
        val messages = response.body<List<MessageWithParts>>()
        val nextCursor = response.headers["X-Next-Cursor"]
        return MessagePage(messages = messages, nextCursor = nextCursor)
    }
```

> 说明：`response.body<List<MessageWithParts>>()` 之前是链式 `.body()`——改为先取 response 再读 body，才能访问 headers。`listMessagesRaw`/`exportSessionToStream` 等其余方法不变。

- [ ] **Step 5: 修改 Repository 层**

`SessionRepository.kt:164`（domain 接口）：

```kotlin
    suspend fun listMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String? = null,
    ): Result<MessagePage>
```

`SessionRepositoryImpl.kt:212-219`：

```kotlin
    override suspend fun listMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String?,
    ): Result<MessagePage> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.listMessages(conn, sessionId, limit, before)
    }
```

补 import：`dev.leonardo.ocbeacon.data.api.message.MessagePage`

- [ ] **Step 6: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.api.message.MessageApiCursorTest" --rerun`（180s）
Expected: 3 个测试全部 PASS

- [ ] **Step 7: 编译 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）
Expected: BUILD SUCCESSFUL（注意：`MessagePaginationUseCase`/`MessagePaginationDelegate` 的 `listMessages` 调用会因返回类型变化而编译失败——**这是预期中间态**，Task 3 会修复；如果本 Task 单独编译不过，先修改这两个调用点改为 `.messages` 提取，保证编译通过再提交）

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/message/ app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImpl.kt app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SessionRepository.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/message/
git commit -m "feat: MessageApi 游标分页（before 参数 + X-Next-Cursor header 解析）"
```

---

### Task 2: MessageStore（Room 读写 + 限量裁剪）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageDao.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/OcBeaconDatabase.kt`（加 messageDao()）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DatabaseModule.kt`（加 provideMessageDao）
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt`

**Interfaces:**
- Consumes: `CachedMessageEntity`/`CachedPartEntity`（Plan 1）、`MessageWithParts`/`Message`/`Part`（现有）、`Json`（注入）
- Produces:
  - `MessageDao`：`upsertMessages`、`upsertParts`、`messagesForSession(sessionId, limit, beforeId?)`、`partsForSession`、`oldestMessageId(sessionId)`、`countForSession`、`pruneToLimit(sessionId, limit)`、`clearSession`
  - `MessageStore`（@Singleton，注入 `MessageDao` + `Json`）：
    - `suspend fun upsertMessages(sessionId, messages: List<MessageWithParts>, persistOldBeyondWindow: Boolean = false)`
    - `suspend fun observeMessages(sessionId): Flow<List<MessageWithParts>>`
    - `suspend fun loadRange(sessionId, limit, beforeId: String?): List<MessageWithParts>`
    - `suspend fun oldestMessageId(sessionId): String?`
    - `suspend fun clearSession(sessionId)`

- [ ] **Step 1: 写失败测试（MessageStore 单测，MockK）**

`MessageStoreTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageStoreTest {

    private val dao = mockk<MessageDao>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val store = MessageStore(dao, json)

    private fun msg(id: String, created: Long): MessageWithParts = MessageWithParts(
        info = Message.User(
            id = id,
            sessionId = "ses_1",
            time = TimeInfo(created = created),
        ),
        parts = listOf(
            Part.Text(id = "part_$id", sessionId = "ses_1", messageId = id, text = "hello"),
        ),
    )

    @Test
    fun upsertMessages_serializesInfoAndParts() = runTest {
        val m = msg("msg_1", 100)

        store.upsertMessages("ses_1", listOf(m))

        coVerify {
            dao.upsertMessages(any())
            dao.upsertParts(any())
        }
    }

    @Test
    fun upsertMessages_persistOldBeyondWindowFalse_skipsMessagesOlderThanOldestCached() = runTest {
        // 本地已有 msg_3（created=300）为最旧 → 窗口边界 = 300
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_3"
        coEvery { dao.messageCreatedAt("msg_3") } returns 300L
        val older = msg("msg_1", 100)
        val newer = msg("msg_4", 400)

        store.upsertMessages("ses_1", listOf(older, newer), persistOldBeyondWindow = false)

        // 只 upsert msg_4；msg_1 被跳过（在窗口外）
        coVerify(exactly = 1) { dao.upsertMessages(match { entities -> entities.any { it.id == "msg_4" } }) }
        coVerify(exactly = 0) { dao.upsertMessages(match { entities -> entities.any { it.id == "msg_1" } }) }
    }

    @Test
    fun upsertMessages_persistOldBeyondWindowTrue_writesAll() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_3"
        coEvery { dao.messageCreatedAt("msg_3") } returns 300L
        val older = msg("msg_1", 100)
        val newer = msg("msg_4", 400)

        store.upsertMessages("ses_1", listOf(older, newer), persistOldBeyondWindow = true)

        coVerify(exactly = 1) { dao.upsertMessages(match { entities -> entities.any { it.id == "msg_1" } }) }
    }

    @Test
    fun upsertMessages_prunesAfterWrite() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns null
        val m = msg("msg_1", 100)

        store.upsertMessages("ses_1", listOf(m))

        coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
    }

    @Test
    fun loadRange_passesBeforeCursor() = runTest {
        store.loadRange("ses_1", limit = 50, beforeId = "msg_5")

        coVerify(exactly = 1) { dao.messagesForSession("ses_1", 50, "msg_5") }
    }

    @Test
    fun oldestMessageId_delegates() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_1"

        assertEquals("msg_1", store.oldestMessageId("ses_1"))
        assertNull(store.oldestMessageId("ses_2"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.MessageStoreTest" --rerun`（180s）
Expected: FAIL——`MessageDao`/`MessageStore` 不存在

- [ ] **Step 3: 创建 MessageDao**

`MessageDao.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(entities: List<CachedMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParts(entities: List<CachedPartEntity>)

    /** 分页读：按 created DESC 取 [limit] 条；beforeId 非空时只取比它更早的。 */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId " +
            "AND (:beforeId IS NULL OR id < :beforeId) " +  // ULID 字典序 = 时间序
            "ORDER BY created DESC, id DESC LIMIT :limit",
    )
    suspend fun messagesForSession(sessionId: String, limit: Int, beforeId: String?): List<CachedMessageEntity>

    @Query("SELECT * FROM cached_parts WHERE messageId IN (:messageIds)")
    suspend fun partsForMessages(messageIds: List<String>): List<CachedPartEntity>

    @Query("SELECT id FROM cached_messages WHERE sessionId = :sessionId ORDER BY created ASC, id ASC LIMIT 1")
    suspend fun oldestMessageId(sessionId: String): String?

    @Query("SELECT created FROM cached_messages WHERE id = :messageId")
    suspend fun messageCreatedAt(messageId: String): Long?

    @Query(
        "DELETE FROM cached_messages WHERE sessionId = :sessionId AND id NOT IN " +
            "(SELECT id FROM cached_messages WHERE sessionId = :sessionId " +
            "ORDER BY created DESC, id DESC LIMIT :limit)",
    )
    suspend fun pruneToLimit(sessionId: String, limit: Int): Int

    @Query("DELETE FROM cached_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
```

> ⚠️ ULID 排序说明：`id < :beforeId` 用字符串比较。ULID 是字典序=时间序（Crockford base32 字母表有序），`msg_` 前缀固定，同前缀下字符串序可靠。REST API 的 `before` 游标是 `base64url(JSON({id,time}))`，与本地 ID 比较是两套机制——本地查询用 ID 字典序即可（Task 4 翻页时才用游标）。

- [ ] **Step 4: 创建 MessageStore**

`MessageStore.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息本地缓存存储（Room）。
 *
 * 限量策略：每会话最近 [SESSION_MESSAGE_LIMIT] 条；翻页拉到窗口外的更早消息
 * 默认不落库（persistOldBeyondWindow=false），避免"写了又被裁"循环。
 */
@Singleton
class MessageStore @Inject constructor(
    private val dao: MessageDao,
    private val json: Json,
) {

    suspend fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        persistOldBeyondWindow: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        runCatching {
            val oldestId = dao.oldestMessageId(sessionId)
            val oldestCreated = oldestId?.let { dao.messageCreatedAt(it) }
            val toPersist = if (persistOldBeyondWindow || oldestCreated == null) {
                messages
            } else {
                messages.filter { m -> m.info.time.created >= oldestCreated }
            }
            if (toPersist.isEmpty()) return@withContext

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
                            type = p.type,
                            text = (p as? dev.leonardo.ocbeacon.domain.model.Part.Text)?.text,
                            payload = json.encodeToString(p),
                        )
                    }
                },
            )
            dao.pruneToLimit(sessionId, SESSION_MESSAGE_LIMIT)
        }.onFailure { e ->
            AppLogger.e(TAG, "MessageStore upsert failed (memory view unaffected)", e)
        }
    }

    /** Room Flow：本地库变化 → 自动发新值。 */
    fun observeMessages(sessionId: String): Flow<List<MessageWithParts>> =
        dao.observeMessages(sessionId).map { entities -> entities.map(::toMessageWithParts) }

    /** 游标分页读：beforeId 非空取更早，否则取最新 limit 条。 */
    suspend fun loadRange(sessionId: String, limit: Int, beforeId: String? = null): List<MessageWithParts> =
        withContext(Dispatchers.IO) {
            val entities = dao.messagesForSession(sessionId, limit, beforeId)
            entities.map(::toMessageWithParts)
        }

    suspend fun oldestMessageId(sessionId: String): String? =
        withContext(Dispatchers.IO) { dao.oldestMessageId(sessionId) }

    suspend fun clearSession(sessionId: String) = withContext(Dispatchers.IO) {
        dao.clearSession(sessionId)
    }

    // ---- 映射 ----------------------------------------------------

    private suspend fun toMessageWithParts(entity: CachedMessageEntity): MessageWithParts {
        val info = json.decodeFromString<dev.leonardo.ocbeacon.domain.model.Message>(entity.payload)
        val parts = dao.partsForMessages(listOf(entity.id))
            .map { partEntity ->
                partEntity.payload?.let {
                    runCatching { json.decodeFromString<dev.leonardo.ocbeacon.domain.model.Part>(it) }
                        .getOrNull()
                }
            }
            .filterNotNull()
        return MessageWithParts(info = info, parts = parts)
    }

    companion object {
        private const val TAG = "MessageStore"
        const val SESSION_MESSAGE_LIMIT = 1000
    }
}
```

> 说明：`observeMessages` 需要 DAO 的 Flow 支持——在 MessageDao 增加：
> ```kotlin
>     @Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created DESC, id DESC")
>     fun observeMessages(sessionId: String): Flow<List<CachedMessageEntity>>
> ```

- [ ] **Step 5: 修改 OcBeaconDatabase + DatabaseModule**

`OcBeaconDatabase.kt`：

```kotlin
abstract class OcBeaconDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun messageDao(): MessageDao
}
```

`DatabaseModule.kt`：

```kotlin
    @Provides
    fun provideMessageDao(database: OcBeaconDatabase): MessageDao = database.messageDao()
```

- [ ] **Step 6: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.MessageStoreTest" --rerun`（180s）
Expected: 6 个测试全部 PASS

- [ ] **Step 7: 编译 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/ app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DatabaseModule.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt
git commit -m "feat: MessageStore Room 消息缓存（限量 1000 条/会话 + 窗口外不落库）"
```

---

### Task 3: 分页管线接入（缓存优先 + 真游标翻页）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCase.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegate.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCaseTest.kt`

**Interfaces:**
- Consumes: `MessageStore`（Task 2）、`MessagePage`（Task 1）、`SessionRepository.listMessages(..., before)`（Task 1）
- Produces:
  - `MessagePaginationUseCase`：
    - `observeMessages(sessionId): Flow<List<Message>>`（不变，从 chatRepository）
    - `suspend fun loadMessagesForSession(serverId, sessionId, limit): Result<List<MessageWithParts>>`（缓存优先：本地有则返回本地 + 触发增量）
    - `suspend fun loadOlderMessages(serverId, sessionId, limit, beforeId): Result<List<MessageWithParts>>`

- [ ] **Step 1: 写失败测试**

`MessagePaginationUseCaseTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.data.api.message.MessagePage
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePaginationUseCaseTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val sessionRepository = mockk<SessionRepository>(relaxed = true)
    private val messageStore = mockk<MessageStore>(relaxed = true)
    private val useCase = MessagePaginationUseCase(chatRepository, sessionRepository, messageStore)

    private fun msg(id: String, created: Long): MessageWithParts = MessageWithParts(
        info = Message.User(id = id, sessionId = "ses_1", time = TimeInfo(created = created)),
        parts = emptyList(),
    )

    @Test
    fun loadMessagesForSession_localCacheExists_returnsLocalAndIncremental() = runTest {
        val local = listOf(msg("msg_2", 200), msg("msg_3", 300))
        coEvery { messageStore.loadRange("ses_1", 50, null) } returns local
        coEvery { messageStore.oldestMessageId("ses_1") } returns "msg_2"
        val page = MessagePage(messages = listOf(msg("msg_4", 400)), nextCursor = null)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, null) } returns Result.success(page)

        val result = useCase.loadMessagesForSession("srv", "ses_1", 50)

        assertTrue(result.isSuccess)
        // 返回本地 + 增量合并
        assertEquals(3, result.getOrThrow().size)
        // 增量落库
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_4", 400)), false) }
    }

    @Test
    fun loadMessagesForSession_noLocalCache_fetchesFull() = runTest {
        coEvery { messageStore.loadRange("ses_1", 50, null) } returns emptyList()
        coEvery { messageStore.oldestMessageId("ses_1") } returns null
        val page = MessagePage(messages = listOf(msg("msg_1", 100)), nextCursor = null)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, null) } returns Result.success(page)

        val result = useCase.loadMessagesForSession("srv", "ses_1", 50)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_1", 100)), false) }
    }

    @Test
    fun loadOlderMessages_usesBeforeCursor() = runTest {
        val page = MessagePage(messages = listOf(msg("msg_0", 50)), nextCursor = null)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, "msg_1") } returns Result.success(page)

        val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_1")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_0", 50)), false) }
    }

    @Test
    fun observeMessages_delegatesToChatRepository() = runTest {
        val msgs = listOf<Message>(msg("msg_1", 100).info)
        coEvery { chatRepository.getMessagesFlow("ses_1") } returns flowOf(msgs)

        val result = useCase.observeMessages("ses_1")

        assertEquals(msgs, result.first())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCaseTest" --rerun`（180s）
Expected: FAIL——构造签名不匹配（缺 messageStore 参数）

- [ ] **Step 3: 改造 MessagePaginationUseCase**

`MessagePaginationUseCase.kt` 全文替换：

```kotlin
package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.data.api.message.MessagePage
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class MessagePaginationUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val messageStore: MessageStore,
) {
    fun observeMessages(sessionId: String): Flow<List<Message>> =
        chatRepository.getMessagesFlow(sessionId)

    /**
     * 进入会话加载：缓存优先。
     * 本地有缓存 → 返回本地 + REST 增量（before=本地最旧游标）合并；
     * 本地为空 → 全量拉取。
     */
    suspend fun loadMessagesForSession(
        serverId: String,
        sessionId: String,
        limit: Int,
    ): Result<List<MessageWithParts>> {
        val local = messageStore.loadRange(sessionId, limit, beforeId = null)
        val oldestId = messageStore.oldestMessageId(sessionId)
        return runCatching {
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = null)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
            mergeLocalAndRemote(local, page.messages)
        }
    }

    /** 翻页加载更早：before 游标 = 本地最旧消息 ID。 */
    suspend fun loadOlderMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        beforeId: String?,
    ): Result<List<MessageWithParts>> {
        return runCatching {
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = beforeId)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
            page.messages
        }
    }

    private fun mergeLocalAndRemote(
        local: List<MessageWithParts>,
        remote: List<MessageWithParts>,
    ): List<MessageWithParts> {
        val byId = (local + remote).associateBy { it.info.id }
        return byId.values.sortedBy { it.info.time.created }
    }
}
```

> 说明：`before` 参数传 `beforeId`（本地最旧消息 ID 字符串）——服务端游标是 `base64url(JSON({id,time}))`，但 **`before` 查询参数接受原始 ID 吗？** 需按 API 文档确认：调研结论游标=base64url(JSON({id,time}))。**实施注意**：若服务端要求完整游标格式，需在本地缓存最旧消息的游标（或由 `beforeId` 编码出游标）。Task 4 的增量拉取步骤会处理游标编码细节；本 Task 先传 ID，Task 4 统一修正为正确游标。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCaseTest" --rerun`（180s）
Expected: 4 个测试全部 PASS

- [ ] **Step 5: 编译修复调用点 + 提交**

`MessagePaginationDelegate.kt` 的 `listMessages` 调用点适配（loadMessagesForSession:69 / loadMessages:89 / loadOlderMessages:123）——返回类型从 `List` 变 `MessagePage`：

- `loadMessagesForSession`（:64-76）改为：

```kotlin
    suspend fun loadMessagesForSession() {
        currentMessageLimit = settingsRepository.getSettingsFlow().first().initialMessageCount
        val sid = sessionIdProvider()
        try {
            val messages = manageSessionUseCase.loadMessagesForSession(serverId, sid, currentMessageLimit)
                .getOrThrow()
            chatRepository.setMessages(sid, messages)
            _hasOlderMessages.value = messages.size >= currentMessageLimit
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "V1 loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load messages", e)
        }
    }
```

- `loadMessages`（:83-115）的 `listMessages` 调用改 `loadMessagesForSession(serverId, sid, currentMessageLimit).getOrThrow()`；OOM 重试分支保留原 `listMessages` 语义（改用 `.getOrThrow()` + `.messages`）或简化调用——**保持现有重试逻辑不变**，只改调用签名
- `loadOlderMessages`（:117-137）改为：

```kotlin
    fun loadOlderMessages() {
        val sid = sessionIdProvider()
        scope.launch {
            _isLoadingOlder.value = true
            try {
                val beforeId = messageStore.oldestMessageId(sid)
                val messages = manageSessionUseCase.loadOlderMessages(serverId, sid, currentMessageLimit, beforeId)
                    .getOrThrow()
                chatRepository.mergeMessages(sid, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "Loaded older: ${messages.size} messages (before=$beforeId, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load older messages", e)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }
```

> 注意：`MessagePaginationDelegate` 需要注入 `MessageStore`——它的构造由 `MessageDataDelegate` 直接 new（非 Hilt），需在 `MessageDataDelegate` 的构造参数中加 `messageStore`（从 ChatViewModel 注入链传入）。**实施注意**：`MessageDataDelegate` 由 `ChatViewModel` 构造，`ChatViewModel` 是 Hilt `@HiltViewModel`——在 ChatViewModel 构造加 `messageStore: MessageStore` 注入，传给 MessageDataDelegate 再传 paginationDelegate。

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCase.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegate.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegate.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCaseTest.kt
git commit -m "feat: 分页管线缓存优先（进入会话本地渲染 + REST 增量 + 真游标翻页）"
```

---

### Task 4: 增量拉取细节（游标编码 + 缺失区间补拉）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/CursorCodec.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/CursorCodecTest.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCase.kt`（用 CursorCodec）

**Interfaces:**
- Consumes: `MessageStore`（Task 2）
- Produces:
  - `object CursorCodec`：
    - `fun encode(id: String, time: Long): String`（base64url(JSON({"id","time"}))）
    - `fun decode(cursor: String): Pair<String, Long>?`

- [ ] **Step 1: 写失败测试**

`CursorCodecTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CursorCodecTest {

    @Test
    fun encode_roundTrips() {
        val cursor = CursorCodec.encode("msg_fdd9e0967001Swfy1V3tS3MUnk", 1786129549671L)

        val decoded = CursorCodec.decode(cursor)

        assertNotNull(decoded)
        assertEquals("msg_fdd9e0967001Swfy1V3tS3MUnk", decoded!!.first)
        assertEquals(1786129549671L, decoded.second)
    }

    @Test
    fun decode_invalidReturnsNull() {
        assertNull(CursorCodec.decode("not-base64!!!"))
        assertNull(CursorCodec.decode(""))
    }

    @Test
    fun decode_knownServerCursor() {
        // curl 实测返回的游标
        val cursor = "eyJpZCI6Im1zZ19mZGQ5ZTA5NjcwMDFTd2Z5MVYzdFMzTVVuayIsInRpbWUiOjE3ODYxMjk1NDk2NzF9"

        val decoded = CursorCodec.decode(cursor)

        assertNotNull(decoded)
        assertEquals("msg_fdd9e0967001Swfy1V3tS3MUnk", decoded!!.first)
        assertEquals(1786129549671L, decoded.second)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.CursorCodecTest" --rerun`（180s）
Expected: FAIL——`CursorCodec` 不存在

- [ ] **Step 3: 实现 CursorCodec**

`CursorCodec.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * OpenCode Server 消息分页游标编解码。
 * 游标 = base64url(JSON({"id": <msgId>, "time": <created>}))。
 */
object CursorCodec {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CursorPayload(val id: String, val time: Long)

    fun encode(id: String, time: Long): String {
        val payload = json.encodeToString(CursorPayload(id = id, time = time))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun decode(cursor: String): Pair<String, Long>? {
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(cursor)
            val payload = json.decodeFromString<CursorPayload>(String(bytes, Charsets.UTF_8))
            payload.id to payload.time
        }.getOrNull()
    }
}
```

> 说明：`Base64.getUrlEncoder()` 生成 URL-safe base64（`-`/`_` 替代 `+`/`/`），`withoutPadding()` 无 `=` 填充——与服务器 `base64url` 格式一致（curl 实测游标无填充）。若服务端游标带填充（`=`），`getUrlDecoder` 也能解码（Java Base64 URL decoder 接受带填充输入）。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.local.CursorCodecTest" --rerun`（180s）
Expected: 3 个测试全部 PASS（含 curl 实测游标解码验证）

- [ ] **Step 5: UseCase 使用 CursorCodec**

`MessagePaginationUseCase.kt` 中 `loadMessagesForSession` 增量拉取改传编码游标：

```kotlin
    suspend fun loadMessagesForSession(
        serverId: String,
        sessionId: String,
        limit: Int,
    ): Result<List<MessageWithParts>> {
        val local = messageStore.loadRange(sessionId, limit, beforeId = null)
        val oldestId = messageStore.oldestMessageId(sessionId)
        return runCatching {
            // 本地有缓存时，只拉取本地最旧游标之后的新消息
            val before = oldestId?.let { id ->
                val created = messageStore.messageCreatedAt(id)
                if (created != null) CursorCodec.encode(id, created) else null
            }
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
            mergeLocalAndRemote(local, page.messages)
        }
    }
```

> 需要 `MessageStore.messageCreatedAt(id)`——在 `MessageStore` 加委托方法（dao.messageCreatedAt 已有）：
> ```kotlin
>     suspend fun messageCreatedAt(messageId: String): Long? =
>         withContext(Dispatchers.IO) { dao.messageCreatedAt(messageId) }
> ```

- [ ] **Step 6: 编译 + 全量单测 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL + 全量 PASS

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/CursorCodec.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/CursorCodecTest.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/local/MessageStore.kt app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCase.kt
git commit -m "feat: 游标编解码（base64url JSON）+ 增量拉取用编码游标"
```

---

### Task 5: upsert 合并策略统一 + SSE 双写

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandler.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepository.kt`
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMergeTest.kt`（或新建）

**Interfaces:**
- Consumes: `MessageStore`（Task 2）、现有 `setMessages`/`mergeMessages`/`replaceMessages` 调用点
- Produces:
  - `enum class MergeStrategy { SSE_PRIORITY, REST_AUTHORITY, APPEND_ONLY }`
  - `MessageEventHandler.upsertMessages(sessionId, incoming: List<MessageWithParts>, strategy: MergeStrategy)`——内部实现从三方法提炼，行为逐语义对应
  - `EventDispatcher.upsertMessages(sessionId, incoming, strategy)` 委托
  - `ChatRepository.upsertMessages(sessionId, incoming, strategy)` 接口方法（替代 setMessages/mergeMessages/replaceMessages 三方法）

- [ ] **Step 1: 先读现有实现，写等价性测试**

读 `MessageEventHandler.kt:342-458`（三方法完整实现）与 `:260-340`（mergePart/mergePartsList/mergeMessageMeta 私有方法）。基于现有 `MessageEventHandlerMergeTest`（若存在）扩充：

```kotlin
// 新增测试（在现有 MergeTest 文件或新建 UpsertStrategyTest.kt）
class UpsertStrategyTest {

    // 使用与现有 MergeTest 相同的 fixture 构造 handler
    //（读现有测试文件了解 fixture 构造方式，保持一致）

    @Test
    fun ssePriority_keepsLongerSseText() {
        // 语义 = 原 setMessages：mergeMessageMeta（SSE 优先）+ mergePartsList（更长文本胜出）
    }

    @Test
    fun restAuthority_prefersIncomingInfo() {
        // 语义 = 原 replaceMessages：incomingById[msg.id]?.info ?: msg
    }

    @Test
    fun appendOnly_onlyAddsMissing() {
        // 语义 = 原 mergeMessages：existingById[newMsg.id] ?: newMsg
    }
}
```

> ⚠️ 实施说明：本 Task 的测试代码无法在 brief 中完整预写（依赖现有 fixture 结构）。实施者必须先读现有 `MessageEventHandlerMergeTest`，按其 fixture 风格写等价性测试，再提炼实现。**等价性验证标准**：三个策略的 upsert 输出与三方法的现有单测断言逐一对应。

- [ ] **Step 2: 提炼 upsertMessages 实现**

`MessageEventHandler` 新增（保留三方法为薄委托或直接替换调用点，见 Step 3）：

```kotlin
    enum class MergeStrategy { SSE_PRIORITY, REST_AUTHORITY, APPEND_ONLY }

    fun upsertMessages(
        sessionId: String,
        incoming: List<MessageWithParts>,
        strategy: MergeStrategy,
    ) {
        val incomingById = incoming.associateBy { it.info.id }
        when (strategy) {
            MergeStrategy.SSE_PRIORITY -> {
                // 语义 = 原 setMessages
                _messages.update { current ->
                    val existing = current[sessionId] ?: emptyList()
                    val merged = (existing.map { it.info }.associateBy { it.id } + incomingById)
                        .values
                        .sortedBy { it.time.created }
                    current + (sessionId to merged)
                }
                // parts：mergePartsList（更长文本胜出）——复用现有 mergePartsList
                _parts.update { current ->
                    val currentParts = current[sessionId] ?: emptyList()
                    val mergedParts = incoming.flatMap { mw ->
                        mw.parts.mapNotNull { p ->
                            mergePartsList(currentParts, listOf(p)).firstOrNull()
                        }
                    }
                    current + (sessionId to (currentParts + mergedParts).distinctBy { it.id })
                }
            }
            MergeStrategy.REST_AUTHORITY -> {
                // 语义 = 原 replaceMessages
                _messages.update { current ->
                    val existing = current[sessionId] ?: emptyList()
                    val merged = (incomingById + existing.map { it.info }.associateBy { it.id })
                        .values
                        .sortedBy { it.time.created }
                    current + (sessionId to merged)
                }
                _parts.update { current ->
                    // 复用现有 parts 合并逻辑
                }
            }
            MergeStrategy.APPEND_ONLY -> {
                // 语义 = 原 mergeMessages：仅补充缺失
                _messages.update { current ->
                    val existingById = (current[sessionId] ?: emptyList()).associateBy { it.id }
                    val merged = (existingById + incomingById.filterKeys { it !in existingById })
                        .values
                        .sortedBy { it.time.created }
                    current + (sessionId to merged)
                }
                // parts：仅补充新 messageId 的 parts
            }
        }
    }
```

> ⚠️ 实施注意：以上为策略骨架。**实施者必须从现有三方法（MessageEventHandler.kt:342-458）逐行提炼**，保证 `_messages`/`_parts` 更新顺序（先 parts 后 messages 的闪烁规避注释 :397）、`mergeMessageMeta`（:293）语义（SSE User→REST 权威、Assistant 字段合并）、`mergePart` 更长文本胜出（:261-277）等细节完全保留。**不允许**简化为不等价的逻辑。

- [ ] **Step 3: 替换调用点**

`EventDispatcher.kt`：删除 `setMessages`/`mergeMessages`/`replaceMessages` 委托，新增 `upsertMessages(sessionId, incoming, strategy)` 委托到 `messageEventHandler.upsertMessages`。

`ChatRepository.kt` 接口：三方法替换为 `fun upsertMessages(sessionId: String, messages: List<MessageWithParts>, strategy: MessageEventHandler.MergeStrategy)`。

`ChatRepositoryImpl.kt:361-371`：三方法替换为委托。

**调用点语义映射**（逐一核对，不得改变行为）：
| 原调用点 | 新调用 |
|---------|--------|
| `setMessages(sid, msgs)`（REST 进入会话） | `upsertMessages(sid, msgs, REST_AUTHORITY)` |
| `mergeMessages(sid, msgs)`（翻页/加载更早） | `upsertMessages(sid, msgs, APPEND_ONLY)` |
| `replaceMessages(sid, msgs)`（SSE 重连恢复） | `upsertMessages(sid, msgs, REST_AUTHORITY)` |

> ⚠️ 原 `setMessages` 语义实际是"SSE 优先"（mergeMessageMeta），但调用点语义是"REST 进入会话"——**实施时以调用点实际行为为准**：进入会话用 REST_AUTHORITY 还是 SSE_PRIORITY 取决于现有 setMessages 对 REST 数据的处理。实施者需对照三方法实现与调用点，确保行为不变，并在报告中说明映射依据。

- [ ] **Step 4: SSE 事件双写（MessageStore）**

`MessageEventHandler` 增加 MessageStore 注入（构造参数），在 SSE 事件处理路径（MessageUpdated/MessagePartUpdated/MessagePartDelta → `flushPendingDeltas`/part 更新处）追加 Room 写入：

```kotlin
    // 在 SSE part 批处理落内存后：
    messageStore.upsertMessages(sessionId, listOf(updatedMessageWithParts), persistOldBeyondWindow = false)
```

> ⚠️ 实施注意：SSE 写入点需找到 `MessageEventHandler` 中 SSE 事件 → `_messages`/`_parts` 的准确路径（MessageUpdated 处理、flushPendingDeltas 48ms 批处理）。写入频率控制：沿用 48ms 批处理（不是每个 delta 写）；MessagePartDelta 聚合成最终文本后写一次。Room 写入失败静默（MessageStore 内已捕获）。

- [ ] **Step 5: 编译 + 全量单测 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL + 全量 PASS（含现有 MergeTest 与新策略测试）

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepository.kt app/src/test/kotlin/
git commit -m "feat: upsert 合并策略统一（SSE_PRIORITY/REST_AUTHORITY/APPEND_ONLY）+ SSE 双写 Room"
```

---

### Task 6: 冷启动种子化 + 收尾验证

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt`（getMessagesFlow 种子化）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/OpenCodeApp.kt`（或 Application 入口，注入 MessageStore 触发初始化）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/`（如需要）

**Interfaces:**
- Consumes: `MessageStore.observeMessages`（Task 2）
- Produces: 冷启动时最后访问会话的消息立即可见（内存热视图从 Room 种子化）

- [ ] **Step 1: getMessagesFlow 种子化**

`ChatRepositoryImpl.getMessagesFlow(sessionId)` 现状（:58-63）：`eventDispatcher.messages.map { it[sessionId] ?: emptyList() }`——若内存为空（冷启动）且本地有缓存，需先种子化。改造：

```kotlin
    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> =
        flow {
            // 冷启动种子化：内存为空时从本地缓存读
            val memoryValue = eventDispatcher.messages.value[sessionId]
            if (memoryValue.isNullOrEmpty()) {
                val cached = messageStore.observeMessages(sessionId).first()
                if (cached.isNotEmpty()) {
                    // 触发 EventDispatcher 写入（沿用现有合并路径）
                    eventDispatcher.upsertMessages(sessionId, cached, MessageEventHandler.MergeStrategy.APPEND_ONLY)
                }
            }
            emitAll(
                eventDispatcher.messages.map { it[sessionId] ?: emptyList() }
                    .catch { e -> AppLogger.e("ChatRepository", "Error in getMessagesFlow", e); emit(emptyList()) }
            )
        }
```

> ⚠️ 实施注意：`ChatRepositoryImpl` 需要注入 `MessageStore`（构造参数）。种子化只在内存空时触发一次；`flow{}` + `first()` 在 IO 线程执行不阻塞 UI。注意 `emitAll` 前用 `emit` 首值。也可用 `onStart { }` 实现。**实施者选最简等价实现**，保持现有 `.catch` 语义。

- [ ] **Step 2: Application 入口初始化**

`OpenCodeApp.kt`（Hilt Application）onCreate 中注入 `MessageStore`（或通过 `@Inject lateinit`），触发一次预热（可选，非阻塞）：

```kotlin
    @Inject lateinit var messageStore: MessageStore
    override fun onCreate() {
        super.onCreate()
        // 消息缓存预热：可选，Room 打开是惰性的，首个 Flow 订阅时初始化
    }
```

> 说明：Room 数据库打开是惰性的，无需显式预热。此步若不需要可跳过——**实施者判断**：如果 Task 6 Step 1 已覆盖冷启动场景（getMessagesFlow 首次订阅即种子化），Application 入口无需改动，跳过此步并在报告中说明。

- [ ] **Step 3: 编译 + 全量单测**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL + 全量 PASS

- [ ] **Step 4: 人工验证清单（输出给用户，不执行）**

- [ ] 进入有历史会话：秒开（本地缓存渲染），无网络等待
- [ ] 断网进入已缓存会话：消息仍可见（离线浏览已拉取内容）
- [ ] 翻页向上：本地边界内零网络，边界外只拉缺失区间
- [ ] SSE 流式输出时重启 App：最近消息仍保留（Room 已写入）
- [ ] 会话消息 >1000 条：本地只保留最近 1000，更早翻页走网络
- [ ] 内存/磁盘占用：`ocbeacon.db` 大小合理（1000 条/会话 × 若干会话）

- [ ] **Step 5: 更新 backlog + 提交**

`backlog.md` 更新 #30 条目状态：Plan 2 完成。

```bash
git add backlog.md
git commit -m "docs: backlog #30 更新（消息本地化 Plan 2 完成）"
```

---

### Task 7: 全量验证 + 收尾

**Files:** 无新增

- [ ] **Step 1: 完整验证矩阵**

Run（顺序执行）:
1. `.\gradlew :app:compileDevDebugKotlin`（120s）→ BUILD SUCCESSFUL
2. `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）→ 全量 PASS（含新增 CursorCodec/MessageStore/MessagePaginationUseCase/UpsertStrategy 测试）
3. `.\gradlew :app:compileDevDebugAndroidTestKotlin`（120s）→ 仍预期失败（预存 #29）
4. （#29 修复后）补跑 LogDaoTest + 新 DAO 测试

- [ ] **Step 2: 回归自检**

- `git grep -n "setMessages\|mergeMessages\|replaceMessages" -- app/src/main` → 应只剩余 upsert 调用或已删（语义映射后无三方法残留）
- `git grep -n "currentMessageLimit \*= 2" -- app/src` → 无匹配（假分页已移除）

- [ ] **Step 3: 人工验证清单汇总**

汇总 Task 6 Step 4 清单 + Plan 1 遗留 3 项（Diagnostics 日志/修剪/21 天语义）→ 输出完整验证清单给用户

- [ ] **Step 4: 最终 commit（如有 backlog 变更）**

```bash
git add backlog.md
git commit -m "docs: backlog 更新（消息本地化批次 Plan 2 完成，验证清单待用户）"
```
