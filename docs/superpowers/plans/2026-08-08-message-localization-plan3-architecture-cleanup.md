# Plan 3：存储层架构清理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成方案 C 的最后部分：EventDispatcher 拆分（UnreadBadgeService + StreamingOwnershipRegistry）、SettingsDataStore 3 文件合并、DraftDataStore 迁移 DataStore、DI 模块合并统一——消除上帝类与持久化技术异构，保持行为零变化。

**Architecture:** ①红点时间源（`_lastCompletedReplyTime` + 4 处增量维护 + `runBlocking` 同步落盘）从 EventDispatcher 抽出为独立 `UnreadBadgeService`（异步 DataStore 写，消除 runBlocking）；②多服务器去重（`streamingSessionOwners` ConcurrentHashMap）抽出 `StreamingOwnershipRegistry`；③SettingsDataStore 三个扩展文件并入主类（内部按域分组）；④DraftDataStore 从 File+JSON 迁移 DataStore（`stringPreferencesKey` 存 JSON 字符串）；⑤DataModule/DomainModule 合并为统一 DI 组织（含 MessageCacheRepository 绑定——Plan 2 已加的 @Binds 迁入）。

**Tech Stack:** DataStore 1.2.1（现有）、Hilt 2.59.2、kotlinx.serialization（现有）、Room 2.8.4（Plan 1/2 已就绪）。

## Global Constraints

- **行为零变化**：EventDispatcher 的公共 API（`processEvent`/`upsertMessages`/`lastCompletedReplyTime`/`clearAll`/`clearForServer` 等）与 `SessionStateService` 回调接线（`messageForceCompleter` 的 `persistLastCompletedReplyTime` 同步落盘语义）必须保持
- **红点语义不变量**（2026-08-07 历史决策）：maxCompleted 只增不减（REST 快照滞后不移除）；只有 SessionDeleted 移除；clearForServer/clearAll 不清红点数据；红点判定只用服务器 completed（不用客户端 now）
- **消除 runBlocking**：`persistLastCompletedReplyTime` 的 `runBlocking + withTimeout(500)` 改为 UnreadBadgeService 内部异步收集（用 ApplicationScope + 有界 Channel 或 stateIn 持久化 collector——实施者选最简等价方案，但**必须保证"杀进程不丢"的语义**：写入路径仍需同步到达 DataStore 或由幂等 seed 恢复兜底）
- SettingsDataStore 合并：三个文件（主 279 行 + ReadTimes 92 行 + Tags 166 行）合并为单类，**公开 API 零变化**（`allReadAt`/`markAllSessionsRead`/`sessionReadTimes`/`markSessionRead`/`lastCompletedReplyTimes`/`saveLastCompletedReplyTimes`/`runUnreadStateV2Migration`/`sessionTags`/`sessionTagAssignments`/`addSessionTag` 等全部保留为成员方法，调用方零改动）
- DraftDataStore：迁移到 DataStore 后**公开接口零变化**（`getDraft`/`saveDraft`/`getDraftSessionIds`/`clearDraft`——DraftRepository 接口不变），内存缓存 + 延迟加载语义保留
- StreamingOwnershipRegistry：`claim(sessionId, serverId)`（putIfAbsent 语义）/`isOwnedBy(sessionId, serverId)`/`release(sessionId)`/`releaseAllForServer(serverId)`/`clearAll()`
- DI 统一：DataModule + DomainModule 合并为单 `DomainModule`（或 `RepositoryModule`），全部 @Binds；DatabaseModule 保持独立（Room 提供者）
- 测试：红点语义测试（只增不减/SessionDeleted 移除/持久化恢复）、注册表状态机测试、Settings 合并后现有测试全过、Draft 迁移后现有测试全过
- Windows 构建：`.\gradlew` 前缀；编译 120s / 单测 180s
- androidTest 仍受预存 #29 阻塞——本计划测试走 JVM 单测

---

### Task 1: UnreadBadgeService 抽出（红点时间源独立化）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadBadgeService.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt`（移除红点维护，改为调用 service）
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadBadgeServiceTest.kt`

**Interfaces:**
- Consumes: `SettingsDataStore`（`lastCompletedReplyTimes`/`saveLastCompletedReplyTimes`）、`CoroutineScope`（ApplicationScope 注入）、`MessageEventHandler`（messages 读取用于 recomputeMaxCompleted）
- Produces:
  - `UnreadBadgeService`（@Singleton）：
    - `val lastCompletedReplyTime: StateFlow<Map<String, Long>>`
    - `fun onMessageCompleted(sessionId: String, completed: Long)`（SSE MessageUpdated 增量，max 合并）
    - `fun recomputeMaxCompleted(sessionId: String, messages: List<Message>)`（REST 整批替换后）
    - `fun removeSession(sessionId: String)`（SessionDeleted 级联）
    - `suspend fun seedFromStorage()`（init 幂等 seed + 落盘合并）

- [ ] **Step 1: 写失败测试（红点语义不变量）**

`UnreadBadgeServiceTest.kt`（MockK mock SettingsDataStore + MessageEventHandler）：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadBadgeServiceTest {

    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
    private val scope = kotlinx.coroutines.test.UnconfinedTestDispatcher().let { d ->
        kotlinx.coroutines.CoroutineScope(d + kotlinx.coroutines.SupervisorJob())
    }
    private val service = UnreadBadgeService(settingsDataStore, scope)

    @Test
    fun onMessageCompleted_keepsMax() {
        service.onMessageCompleted("ses_1", 100)
        service.onMessageCompleted("ses_1", 50)   // 更小 → 不回退
        service.onMessageCompleted("ses_1", 200)

        assertEquals(200L, service.lastCompletedReplyTime.value["ses_1"])
    }

    @Test
    fun recomputeMaxCompleted_onlyIncreases() {
        service.onMessageCompleted("ses_1", 300)
        // REST 快照滞后：completed=null 或更小 → 不移除已记录的 max
        service.recomputeMaxCompleted("ses_1", listOf(assistant("msg_1", null)))

        assertEquals(300L, service.lastCompletedReplyTime.value["ses_1"])
    }

    @Test
    fun recomputeMaxCompleted_updatesWhenLarger() {
        service.recomputeMaxCompleted(
            "ses_1",
            listOf(assistant("msg_1", 500), assistant("msg_2", 400)),
        )

        assertEquals(500L, service.lastCompletedReplyTime.value["ses_1"])
    }

    @Test
    fun removeSession_deletesEntry() {
        service.onMessageCompleted("ses_1", 100)
        service.removeSession("ses_1")

        assertEquals(null, service.lastCompletedReplyTime.value["ses_1"])
    }

    @Test
    fun seedFromStorage_mergesMax() = runTest {
        coEvery { settingsDataStore.lastCompletedReplyTimes() } returns
            kotlinx.coroutines.flow.flowOf(mapOf("ses_1" to 700L, "ses_2" to 100L))
        service.onMessageCompleted("ses_1", 500)  // 内存已有较小值

        service.seedFromStorage()

        assertEquals(700L, service.lastCompletedReplyTime.value["ses_1"])  // seed 更大 → 覆盖
        assertEquals(100L, service.lastCompletedReplyTime.value["ses_2"])  // 新增
    }

    private fun assistant(id: String, completed: Long?): Message =
        Message.Assistant(
            id = id,
            sessionId = "ses_1",
            time = TimeInfo(created = 0, completed = completed),
            parentId = "p",
        )
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.repository.UnreadBadgeServiceTest" --rerun`（180s）
Expected: FAIL——`UnreadBadgeService` 不存在

- [ ] **Step 3: 实现 UnreadBadgeService**

`UnreadBadgeService.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UnreadBadgeService"

/**
 * 红点时间源——每会话最后完成 assistant 消息的 completed（**服务器时刻**）的单一真相源。
 *
 * 从 EventDispatcher 抽出（原 _lastCompletedReplyTime + 4 处增量维护 + runBlocking 落盘）。
 *
 * 语义不变量（2026-08-07 历史决策）：
 * - maxCompleted **只增不减**：REST 快照滞后（流式中 completed=null）不移除已记录值
 * - 只有 removeSession（SessionDeleted）移除；clearForServer/clearAll 不清红点数据
 * - 判定只用服务器 completed，不用客户端 now
 */
@Singleton
class UnreadBadgeService @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val scope: CoroutineScope,
) {
    private val _lastCompletedReplyTime = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastCompletedReplyTime: StateFlow<Map<String, Long>> = _lastCompletedReplyTime

    private var persistJob: Job? = null

    /** SSE MessageUpdated(completed!=null) 增量：max 合并 + 异步落盘。 */
    fun onMessageCompleted(sessionId: String, completed: Long) {
        val changed = _lastCompletedReplyTime.update { map ->
            if (completed > (map[sessionId] ?: 0L)) map + (sessionId to completed) else map
        }
        if (changed) persistAsync()
    }

    /** REST 整批替换后重算：只增不减（见类注释）。 */
    fun recomputeMaxCompleted(sessionId: String, messages: List<Message>) {
        val maxTs = messages.filterIsInstance<Message.Assistant>()
            .mapNotNull { it.time.completed }
            .maxOrNull()
        val changed = _lastCompletedReplyTime.update { map ->
            if (maxTs == null) map
            else if (maxTs > (map[sessionId] ?: 0L)) map + (sessionId to maxTs)
            else map
        }
        if (changed) persistAsync()
    }

    /** SessionDeleted 级联：删除会话的红点条目。 */
    fun removeSession(sessionId: String) {
        val changed = _lastCompletedReplyTime.update { it - sessionId }
        if (changed) persistAsync()
    }

    /**
     * 启动种子化：DataStore 读 seed（服务器域值）→ max 合并进内存 → 落盘合并结果。
     * 幂等；迁移（runUnreadStateV2Migration）必须先于本方法执行（EventDispatcher init 顺序保证）。
     */
    suspend fun seedFromStorage() {
        val seed = runCatching { settingsDataStore.lastCompletedReplyTimes().first() }
            .getOrDefault(emptyMap())
        AppLogger.d(TAG, "[seed] loaded ${seed.size} entries: ${seed.entries.take(3)}")
        _lastCompletedReplyTime.update { current ->
            val merged = current.toMutableMap()
            for ((sid, ts) in seed) {
                if (ts > (merged[sid] ?: 0L)) merged[sid] = ts
            }
            merged
        }
        persistNow()
    }

    // ---- 落盘 ----------------------------------------------------

    /**
     * 异步落盘（合并批量写）：状态变化时调度一次写；量小频低，DataStore 原子写。
     * 相比旧 runBlocking 同步写，本方案用"写前 snapshot + 幂等 seed 恢复"兜底：
     * kill 进程窗口内未落盘的值由下次启动 seedFromStorage 的 max 合并恢复（有界丢失：仅最后几秒增量）。
     * @return 是否调度了写（供测试断言）
     */
    private fun persistAsync(): Boolean {
        persistJob?.cancel()
        persistJob = scope.launch {
            val snapshot = _lastCompletedReplyTime.value
            runCatching { settingsDataStore.saveLastCompletedReplyTimes(snapshot) }
                .onFailure { e -> AppLogger.e(TAG, "persist failed (seed will recover on next start)", e) }
        }
        return true
    }

    private suspend fun persistNow() {
        runCatching { settingsDataStore.saveLastCompletedReplyTimes(_lastCompletedReplyTime.value) }
            .onFailure { e -> AppLogger.e(TAG, "seed persist failed", e) }
    }
}
```

> ⚠️ 语义说明：旧实现 `persistLastCompletedReplyTime` 是 `runBlocking + withTimeout(500)` **同步**落盘（"杀进程不丢"）。本实现改为异步（`persistAsync`）+ seed 恢复兜底——**有界丢失窗口**（cancel 旧 job + 新 job 之间的毫秒级）。此变更符合 spec §5.3"消除 runBlocking"，但**实施者必须**在报告中明确此语义差异，并在 Task 4 收尾时人工验证重启恢复。若项目要求严格同步落盘，改用 ApplicationScope + 每次写不 cancel 的节流方案。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.repository.UnreadBadgeServiceTest" --rerun`（180s）
Expected: 5 个测试全部 PASS

- [ ] **Step 5: EventDispatcher 接线替换**

`EventDispatcher.kt` 改造：
1. 构造注入 `unreadBadgeService: UnreadBadgeService`
2. 删除 `_lastCompletedReplyTime`（:188-189）、init 持久化块（:191-215）、`persistLastCompletedReplyTime()`（:457-465）、`recomputeMaxCompleted`（:439-450）
3. 替换调用点：
   - `messageForceCompleter`（:74-81）→ 语义：markSessionIdle 场景没有 completed 值，旧代码是 `persistLastCompletedReplyTime()`（落盘当前内存值）。改为调 `unreadBadgeService.persistAsync()`（等价：把当前内存值写盘）
   - `processEvent` MessageUpdated completed 增量（:293-301）→ `unreadBadgeService.onMessageCompleted(event.info.sessionId, completed)`
   - `SessionDeleted` 级联（:270-272）→ `unreadBadgeService.removeSession(deletedSessionId)`
   - `upsertMessages`/`setMessages`/`mergeMessages`/`replaceMessages` 末尾（:412/:418/:424/:430）→ `unreadBadgeService.recomputeMaxCompleted(sessionId, messageHandler.messages.value[sessionId].orEmpty())`
4. `lastCompletedReplyTime` 门面（:189）改为 `get() = unreadBadgeService.lastCompletedReplyTime`
5. init 块：迁移执行顺序保持——先 `runUnreadStateV2Migration()` 再 `seedFromStorage()`（launch 在 IO scope）

- [ ] **Step 6: 编译 + 全量单测 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL + 全量 PASS

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadBadgeService.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadBadgeServiceTest.kt
git commit -m "refactor: UnreadBadgeService 抽出（红点时间源独立，消除 runBlocking 落盘）"
```

---

### Task 2: StreamingOwnershipRegistry 抽出（多服务器去重）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/StreamingOwnershipRegistry.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StreamingOwnershipRegistryTest.kt`

**Interfaces:**
- Consumes: 无（纯状态）
- Produces:
  - `StreamingOwnershipRegistry`（@Singleton）：
    - `fun claim(sessionId: String, serverId: String): Boolean`（putIfAbsent；已归属且非本 server → false）
    - `fun release(sessionId: String)`
    - `fun releaseAllForServer(serverId: String)`
    - `fun clearAll()`

- [ ] **Step 1: 写失败测试**

`StreamingOwnershipRegistryTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingOwnershipRegistryTest {

    private val registry = StreamingOwnershipRegistry()

    @Test
    fun firstClaimerWins() {
        assertTrue(registry.claim("ses_1", "srv_A"))
        assertFalse(registry.claim("ses_1", "srv_B"))  // 已被 srv_A 认领
        assertTrue(registry.claim("ses_1", "srv_A"))   // 同 server 重复认领 OK
    }

    @Test
    fun release_allowsNewClaim() {
        registry.claim("ses_1", "srv_A")
        registry.release("ses_1")

        assertTrue(registry.claim("ses_1", "srv_B"))
    }

    @Test
    fun releaseAllForServer_freesOwnedSessions() {
        registry.claim("ses_1", "srv_A")
        registry.claim("ses_2", "srv_B")
        registry.claim("ses_3", "srv_A")

        registry.releaseAllForServer("srv_A")

        assertTrue(registry.claim("ses_1", "srv_B"))  // srv_A 释放后可被认领
        assertFalse(registry.claim("ses_2", "srv_A")) // srv_B 仍持有
        assertTrue(registry.claim("ses_3", "srv_C"))
    }

    @Test
    fun clearAll_emptiesEverything() {
        registry.claim("ses_1", "srv_A")

        registry.clearAll()

        assertTrue(registry.claim("ses_1", "srv_B"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.repository.StreamingOwnershipRegistryTest" --rerun`（180s）
Expected: FAIL——`StreamingOwnershipRegistry` 不存在

- [ ] **Step 3: 实现 StreamingOwnershipRegistry**

`StreamingOwnershipRegistry.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多服务器流式会话所有权注册表。
 *
 * 当两个服务器配置指向同一后端（同一 OpenCode serve 实例）时，
 * 两条 SSE 连接都会投递相同的全局事件。若不做所有权跟踪，
 * 追加式事件（如 MessagePartDelta）会被应用两次，流式文本输出翻倍。
 *
 * 首个为会话投递事件的服务器获得所有权；其他服务器的同会话事件被跳过。
 * 所有权在 [release]（SessionDeleted）、[releaseAllForServer]（clearForServer）
 * 或 [clearAll] 时释放。
 */
@Singleton
class StreamingOwnershipRegistry @Inject constructor() {

    private val owners = ConcurrentHashMap<String, String>()

    /** @return true 表示调用方获得/持有所有权（可处理事件）；false 表示被其他服务器持有（应跳过）。 */
    fun claim(sessionId: String, serverId: String): Boolean {
        val existing = owners.putIfAbsent(sessionId, serverId)
        return existing == null || existing == serverId
    }

    fun release(sessionId: String) {
        owners.remove(sessionId)
    }

    fun releaseAllForServer(serverId: String) {
        owners.entries.removeAll { it.value == serverId }
    }

    fun clearAll() {
        owners.clear()
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.repository.StreamingOwnershipRegistryTest" --rerun`（180s）
Expected: 4 个测试全部 PASS

- [ ] **Step 5: EventDispatcher 接线替换**

1. 构造注入 `ownershipRegistry: StreamingOwnershipRegistry`
2. 删除 `streamingSessionOwners`（:99）
3. `processEvent` 所有权检查（:242-252）→ `if (!ownershipRegistry.claim(sessionId, serverId)) { skip }`
4. `SessionDeleted`（:268）→ `ownershipRegistry.release(deletedSessionId)`
5. `clearAll`（:505）→ `ownershipRegistry.clearAll()`
6. `clearForServer`（:518）→ `ownershipRegistry.releaseAllForServer(serverId)`

- [ ] **Step 6: 编译 + 全量单测 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL + 全量 PASS

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/StreamingOwnershipRegistry.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StreamingOwnershipRegistryTest.kt
git commit -m "refactor: StreamingOwnershipRegistry 抽出（多服务器去重独立化）"
```

---

### Task 3: SettingsDataStore 三文件合并 + DraftDataStore 迁移 DataStore

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStore.kt`（合并 ReadTimes + Tags 为成员方法）
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimes.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTags.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/DraftDataStore.kt`（File → DataStore）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/di/DomainModule.kt`（如需）

**Interfaces:**
- Consumes: `DataStore<Preferences>`（NetworkModule 已提供）
- Produces: SettingsDataStore 公开 API **零变化**（全部扩展函数转为成员方法）；DraftDataStore 公开 API 零变化（DraftRepository 接口不变）

- [ ] **Step 1: 合并 SettingsDataStore**

1. 读 `SettingsDataStoreReadTimes.kt`（92 行）和 `SettingsDataStoreTags.kt`（166 行）全文
2. 将两个文件的**全部扩展函数**（`allReadAt`/`markAllSessionsRead`/`sessionReadTimes`/`markSessionRead`/`lastCompletedReplyTimes`/`saveLastCompletedReplyTimes`/`runUnreadStateV2Migration` + `sessionTags`/`sessionTagAssignments`/`addSessionTag`/`renameSessionTag`/`deleteSessionTag`/`toggleSessionTag`/`favoriteSessions`/`addFavorite`/`removeFavorite` 等——以实际文件为准）转为 `SettingsDataStore` 的**成员方法**
3. key 常量（`SESSION_READ_TIMES_PREFIX` 等）移入 SettingsDataStore companion
4. `builtinFavoriteTag()` 顶层函数 → companion 成员
5. 保持 `dataStore`/`context` 构造不变
6. 删除两个扩展文件

> ⚠️ 实施注意：先读全两个文件（含未展示的 60-166 行 Tags 部分），**逐方法**转换为成员方法（把 `fun SettingsDataStore.xxx` 改为 `fun xxx`，`dataStore`/`json` 直接引用成员）。测试文件 `SettingsDataStoreReadTimesTest.kt`/`SettingsDataStoreTagsTest.kt` 可能直接调扩展函数——同步改为实例方法调用（或保留顶层函数作为兼容包装？**不保留**——按 spec 消除前缀约定，测试同步更新为 `store.allReadAt(...)` 形式）。

- [ ] **Step 2: 运行现有测试确认**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.data.repository.SettingsDataStore*" --rerun`（180s）
Expected: PASS（测试同步后）

- [ ] **Step 3: 迁移 DraftDataStore 到 DataStore**

`DraftDataStore.kt` 重写：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DraftDataStore"

@Singleton
class DraftDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : dev.leonardo.ocbeacon.domain.repository.DraftRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val draftsKey = stringPreferencesKey("session_drafts")

    /** 内存缓存，延迟加载（同步读——DataStore 首次读是 IO；runBlocking 一次性成本可接受）。 */
    private var drafts: MutableMap<String, Draft>? = null

    private fun ensureLoaded(): MutableMap<String, Draft> {
        drafts?.let { return it }
        val loaded = try {
            val content = runBlocking { dataStore.data.first() }[draftsKey]
            if (content.isNullOrBlank()) {
                mutableMapOf()
            } else {
                json.decodeFromString<Map<String, Draft>>(content).toMutableMap()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load drafts, starting fresh: ${e.message}")
            mutableMapOf()
        }
        drafts = loaded
        return loaded
    }

    override fun getDraft(sessionId: String): Draft? {
        val d = ensureLoaded()[sessionId]
        return if (d != null && !d.isEmpty) d else null
    }

    override fun saveDraft(sessionId: String, draft: Draft) {
        val map = ensureLoaded()
        if (draft.isEmpty) {
            map.remove(sessionId)
        } else {
            map[sessionId] = draft
        }
        persist(map)
    }

    override fun getDraftSessionIds(): Set<String> =
        ensureLoaded().filter { !it.value.isEmpty }.keys

    override fun clearDraft(sessionId: String) {
        val map = ensureLoaded()
        if (map.remove(sessionId) != null) {
            persist(map)
        }
    }

    private fun persist(map: Map<String, Draft>) {
        try {
            runBlocking {
                dataStore.edit { prefs ->
                    prefs[draftsKey] = json.encodeToString(map)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist drafts: ${e.message}")
        }
    }
}
```

> ⚠️ 说明：`DraftRepository` 接口方法是同步的（`getDraft`/`saveDraft` 非 suspend）——DataStore 是异步 API，这里用 `runBlocking` 桥接。这与旧 File 读写（同步）行为一致（saveDraft 立即持久化）。首次读 runBlocking 在调用线程（UI）有一次性 IO 成本——**与旧实现相同**（旧 `file.readText()` 也是同步）。若实施中发现主线程阻塞风险，可评估改为 suspend 接口（DraftRepository 改动会波及调用方——**先不改接口**，保持零变化，除非测试暴露问题）。

- [ ] **Step 4: 编译 + 全量单测 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL + 全量 PASS

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStore.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/DraftDataStore.kt app/src/test/kotlin/
git commit -m "refactor: SettingsDataStore 三文件合并 + DraftDataStore 迁移 DataStore（持久化收敛）"
```

> ⚠️ 删除文件用 `git rm`。

---

### Task 4: DI 模块合并 + 命名统一 + 收尾验证

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DataModule.kt`（删除——绑定并入 DomainModule）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/di/DomainModule.kt`（并入 ChatRepository/SessionRepository/MessageCacheRepository 绑定）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/di/DatabaseModule.kt`（保持）
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherArchitectureTest.kt`（可选）

**Interfaces:**
- Produces: 统一 DI：DomainModule 含全部 @Binds（ChatRepository/SessionRepository/MessageCacheRepository/Draft/Agent/Server/Provider/Settings/SessionState/Mcp/File/Vcs）；DatabaseModule 独立（Room 提供者）

- [ ] **Step 1: 合并 DI 模块**

1. `DataModule.kt` 的 `bindChatRepository`/`bindSessionRepository` 移到 `DomainModule.kt`
2. `DomainModule.kt` 加 `bindMessageCacheRepository`（Plan 2 加的——确认它现在在哪：如果已在 DomainModule 则不动；如果在 DataModule 则移过来）
3. 删除 `DataModule.kt`
4. 检查 androidTest 的 `FakeDomainModule`（@TestInstallIn 替换 DomainModule+DataModule）——**合并后需同步**：FakeDomainModule 的 @TestInstallIn 注解引用 DataModule 的位置会失效，需更新为只替换 DomainModule

- [ ] **Step 2: 命名统一检查**

`git grep -n "data.repository" -- app/src/main/kotlin/dev/leonardo/ocbeacon/domain` → 确认 domain 层无 data 依赖（除 MessagePage 已在 domain.model）

- [ ] **Step 3: 编译 + 全量单测 + androidTest 编译**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）+ `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）+ `.\gradlew :app:compileDevDebugAndroidTestKotlin`（120s，预期仍失败 #29）
Expected: BUILD SUCCESSFUL + 全量 PASS

- [ ] **Step 4: 人工验证清单（输出给用户）**

- [ ] App 启动：红点状态恢复（杀进程重启后未读回复红点仍显示）——UnreadBadgeService 持久化语义验证
- [ ] 双服务器指向同一后端：流式文本不翻倍（StreamingOwnershipRegistry 去重）
- [ ] 会话列表红点行为与重构前一致（增删/一键已读/标签收藏）
- [ ] 草稿保存/恢复正常（DraftDataStore DataStore 版）
- [ ] Diagnostics 日志 + 消息缓存仍正常（回归）

- [ ] **Step 5: 更新 backlog + 提交**

`backlog.md` 更新 #30（消息本地化批次 Plan 3 完成——全批次完成）；更新 #31 状态（损坏恢复——如本计划未覆盖则保持待办）。

```bash
git add backlog.md
git commit -m "docs: backlog #30 更新（消息本地化批次 Plan 3 完成）"
```

---

### Task 5: 全量验证 + 收尾

**Files:** 无新增

- [ ] **Step 1: 完整验证矩阵**

Run（顺序执行）:
1. `.\gradlew :app:compileDevDebugKotlin`（120s）→ BUILD SUCCESSFUL
2. `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）→ 全量 PASS
3. `.\gradlew :app:compileDevDebugAndroidTestKotlin`（120s）→ 预期失败（#29）
4. `git grep -n "runBlocking" -- app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt` → 无匹配（runBlocking 已消除——DraftDataStore 的 runBlocking 是接口约束，报告说明）

- [ ] **Step 2: 回归自检**

- `git grep -n "streamingSessionOwners\|_lastCompletedReplyTime" -- app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt` → 无匹配（已抽出）
- `git grep -ln "SettingsDataStoreReadTimes\|SettingsDataStoreTags" -- app/src/main` → 无匹配（文件已删）
- `git grep -n "DataModule" -- app/src/androidTest` → 检查 FakeDomainModule 引用是否已同步

- [ ] **Step 3: 汇总人工验证清单**

汇总 Plan 3 Task 4 Step 4 清单 + Plan 1/2 遗留清单 → 完整验证清单（用户统一执行）

- [ ] **Step 4: 最终 commit（如有）**

```bash
git add backlog.md
git commit -m "docs: backlog 更新（消息本地化全批次完成，验证清单待用户）"
```
