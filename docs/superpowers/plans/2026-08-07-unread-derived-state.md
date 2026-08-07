# 未读红点派生状态模型实现计划（#25）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把未读红点从"跨时钟域时间戳比较"重构为"全服务器域状态派生"（maxCompleted + status==Idle 门控 + 服务器域已读标记），根治时钟偏差导致的误报/漏报。

**Architecture:** 红点 = 纯函数 `status==Idle && maxCompleted > max(readTimes, allReadAt)`。maxCompleted 由 EventDispatcher 从 MessageUpdated/REST 合并增量派生（替代 _turnEndTime/lastReplyTime/基线）；已读标记值域从客户端 now 改为服务器 completed；删除 unreadBaseline 机制与 lastReplyTime 持久化；一次性迁移清空已读标记。

**Tech Stack:** Kotlin + Coroutines Flow + StateFlow + DataStore Preferences + JUnit4/MockK/Turbine

**Spec:** `docs/superpowers/specs/2026-08-07-unread-derived-state-design.md`

## Global Constraints

- 红点语义不变：绑定 turn 完全结束（status==Idle）才出现；**禁止**任何"进行中即红点"逻辑
- 红点判定链路（maxCompleted/已读标记/一键已读）**禁止**出现 `System.currentTimeMillis()` 参与比较
- 不触碰：SessionStateService FSM 状态机（仅读 `statusFlow`）、聊天页渲染、SSE 流式管线、列表状态切片（#23 产物）
- `markSessionIdle` 的客户端 now **保留**（UI 流式终止语义），但不得流入红点判定
- 构建：`.\gradlew :app:compileDevDebugKotlin`（120s 超时）；单测：`.\gradlew :app:testDevDebugUnitTest --rerun --tests "*ClassName*"`（180s 超时）
- Windows PowerShell：写文件用 Write/Edit 工具，勿用命令行内联中文
- 每次编译成功后 commit，commit 前缀 `refactor:`/`feat:`/`test:`/`chore:` + `#25`

---

### Task 1: EventDispatcher maxCompleted flow

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherUnreadTest.kt`（全重写）

**Interfaces:**
- Consumes: `SseEvent.MessageUpdated`（`event.info.time.completed` 服务器时刻）、`Message.Assistant`、`Message.time.completed`
- Produces: `EventDispatcher.lastCompletedReplyTime: StateFlow<Map<String, Long>>`——Task 2/4 依赖（替代 `lastReplyTime`）

- [ ] **Step 1: 读 EventDispatcher.kt 全文**（449 行），定位：`_turnEndTime`（190 行）、`messageForceCompleter`（67-82）、`onTurnEnded` 接线（199-203）、`replyTimePersistScope`（204-206）、委托方法 `setMessages`/`mergeMessages`/`replaceMessages`（383-390）、`clearForSession`/`clearForServer`/`clearAll`（425-447）

- [ ] **Step 2: 写失败测试（重写 EventDispatcherUnreadTest.kt）**

替换整个文件（删除 step.ended/lastReplyTime 相关 7 个测试，改为 maxCompleted 语义 5 个测试）：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessagePartHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageRemovedHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageUpdatedHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * 未读提示数据源（lastCompletedReplyTime / maxCompleted）测试：
 * 仅 assistant 消息 completed（服务器时刻）更新；用户消息/未完成消息不算；
 * 增量取 max；无完成消息的会话移除条目。
 */
class EventDispatcherUnreadTest {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var stateServiceScope: TestScope
    private lateinit var sessionStateService: SessionStateService

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        val messageStore = MessageEventHandler()
        sessionStateService = SessionStateService(
            appScope = stateServiceScope,
            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
        )
        dispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            messagePartHandler = MessagePartHandler(messageStore),
            messageUpdatedHandler = MessageUpdatedHandler(messageStore),
            messageRemovedHandler = MessageRemovedHandler(messageStore),
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(),
            sessionStateService = sessionStateService,
            settingsDataStore = mockk<SettingsDataStore>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    private fun pushAssistantMessage(id: String, sessionId: String, created: Long, completed: Long? = null) {
        dispatcher.processEvent(
            SseEvent.MessageUpdated(
                Message.Assistant(id = id, sessionId = sessionId, time = TimeInfo(created = created, completed = completed), parentId = "p0")
            ),
            "svr1"
        )
    }

    @Test
    fun `assistant message with completed updates maxCompleted with server timestamp`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `assistant message without completed does NOT update`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = null)
        assertNull(dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `user message does NOT update`() = runTest {
        dispatcher.processEvent(
            SseEvent.MessageUpdated(Message.User(id = "m1", sessionId = "s1", time = TimeInfo(5000L))),
            "svr1"
        )
        assertNull(dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `later completed overwrites with max`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        pushAssistantMessage("m2", "s1", created = 200L, completed = 400L) // 更早完成 → 不覆盖
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
        pushAssistantMessage("m3", "s1", created = 300L, completed = 900L) // 更晚完成 → 覆盖
        assertEquals(900L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `replaceMessages recomputes max for session`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        // REST 替换整批（如回退后），旧消息被移除
        dispatcher.replaceMessages("s1", emptyList())
        assertNull(dispatcher.lastCompletedReplyTime.first()["s1"])
        val newer = Message.Assistant(id = "m9", sessionId = "s1", time = TimeInfo(created = 1000L, completed = 2000L), parentId = "p0")
        dispatcher.replaceMessages("s1", listOf(newer))
        assertEquals(2000L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*EventDispatcherUnreadTest*"`
Expected: FAIL（`lastCompletedReplyTime` 未定义）

- [ ] **Step 4: 实现 maxCompleted flow**

EventDispatcher.kt 修改：
1. `_turnEndTime`（190 行）替换为：
```kotlin
/** 每会话最后完成 assistant 消息的 completed（**服务器时刻**，实时派生）。
 *  红点判定的唯一时间源——替代旧 _turnEndTime（曾混入 markSessionIdle 的客户端 now）。
 *  增量维护：MessageUpdated(completed≠null) 或 REST 整批替换时更新。 */
private val _lastCompletedReplyTime = MutableStateFlow<Map<String, Long>>(emptyMap())
val lastCompletedReplyTime: StateFlow<Map<String, Long>> = _lastCompletedReplyTime
```
2. 删除 `messageForceCompleter` 内的 `_turnEndTime.update` 块（73-81 行），保留 `markSessionIdle(sessionId)` 与 `messageRefresher` 接线（函数体只剩 markSessionIdle 调用）
3. 删除 `sessionNextHandler.onTurnEnded = { ... }` 接线（199-203 行）
4. 删除 `replyTimePersistScope`（55 行声明 + 204-206 行 collector）
5. 在 `processEvent` 的跨 handler 区（`CommandExecuted` 块旁）新增：
```kotlin
// 红点时间源：assistant 消息完成（服务器 completed）→ 增量更新 maxCompleted。
// 与 markSessionIdle（客户端 now，UI 流式终止）解耦——红点判定只用服务器时刻。
if (event is SseEvent.MessageUpdated && event.info is Message.Assistant) {
    val completed = event.info.time.completed ?: return@let
    _lastCompletedReplyTime.update { map ->
        val cur = map[event.info.sessionId] ?: 0L
        if (completed > cur) map + (event.info.sessionId to completed) else map
    }
}
```
（注意放在 `processEvent` 函数体内正确位置，`return@let` 需用 `if` 嵌套避免——直接用：
```kotlin
if (event is SseEvent.MessageUpdated && event.info is Message.Assistant && event.info.time.completed != null) {
    val sessionId = event.info.sessionId
    val completed = event.info.time.completed!!
    _lastCompletedReplyTime.update { map ->
        if (completed > (map[sessionId] ?: 0L)) map + (sessionId to completed) else map
    }
}
```
）
6. 委托方法 `setMessages`/`mergeMessages`/`replaceMessages`（383-390）各加一行重算（在委托调用后）：
```kotlin
private fun recomputeMaxCompleted(sessionId: String) {
    val maxTs = messageHandler.messages.value[sessionId]
        ?.filterIsInstance<Message.Assistant>()
        ?.mapNotNull { it.time.completed }
        ?.maxOrNull()
    _lastCompletedReplyTime.update { map ->
        if (maxTs == null) map - sessionId else map + (sessionId to maxTs)
    }
}
```
7. 清理：`clearForSession` 中加 `_lastCompletedReplyTime.update { it - sessionId }`；`clearForServer` 的 sessionIds 循环复用 `clearForSession`（核对现有实现结构，若 clearForServer 逐个调用 clearForSession 则自动覆盖）；`clearAll` 中加 `_lastCompletedReplyTime.value = emptyMap()`

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*EventDispatcherUnreadTest*"`
Expected: PASS（5 测试）

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（EventDispatcher 无残留 lastReplyTime/_turnEndTime 引用）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherUnreadTest.kt
git commit -m "refactor: #25 EventDispatcher maxCompleted flow（红点时间源改服务器时刻，删 _turnEndTime/onTurnEnded/持久化 collector）"
```

---

### Task 2: 数据层改造（已读标记服务器域 + 迁移）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimes.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SessionRepository.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImpl.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimesTest.kt`（适配+新增）

**Interfaces:**
- Consumes: `EventDispatcher.lastCompletedReplyTime`（Task 1）
- Produces:
  - `SettingsRepository.markSessionRead(serverId: String, sessionId: String, completedTs: Long)`——显式传服务器时刻
  - `SettingsRepository.markAllSessionsRead(serverId: String, globalMax: Long)`——显式传全局 max
  - `SettingsRepository.runUnreadStateV2Migration()`——一次性清空
  - `SessionRepository.getLastCompletedReplyTimeFlow(): Flow<Map<String, Long>>`（替代 getLastReplyTimeFlow）
- 删除：`ensureUnreadBaseline`、`lastReplyTimes()`、`saveLastReplyTimes()`

- [ ] **Step 1: 读 SettingsDataStoreReadTimes.kt 全文**（95 行）+ SettingsRepository.kt 相关段（60-90 行）+ SettingsRepositoryImpl.kt（55-80 行）+ SessionRepositoryImpl.kt（70-80 行）+ SettingsDataStoreReadTimesTest.kt 全文

- [ ] **Step 2: 写失败测试（适配 + 新增）**

SettingsDataStoreReadTimesTest.kt 修改：
- `markSessionRead then read back` → 调用改 `store.markSessionRead("svr1", "ses1", 5000L)`，断言 `sessionReadTimes` 返回 `mapOf("ses1" to 5000L)`
- `markSessionRead is server-scoped` → 同样加第三参（不同服务器不同值）
- `markSessionRead overwrites previous timestamp` → 第二次传更大值断言覆盖；**新增断言：传更小值不覆盖**（`markSessionRead("svr1","ses1", 9000L)` 后读回仍为 9000L——若再传 7000L 则仍 9000L？——**注意**：markSessionRead 语义=记录退出时消费位置，可能回退？会话消息重算后 max 变小——已读标记取"退出时最后 completed"，正常情况下只增。为简单：**markSessionRead 直接覆盖**（不做 max），因为调用方传的就是当时最后 completed。测试：第二次 9000L 覆盖第一次 5000L ✅）
- `markAllSessionsRead then read back` → 调用改 `store.markAllSessionsRead("svr1", 8000L)`，断言 `allReadAt` 返回 8000L
- 删除 ensureUnreadBaseline 相关测试（若有）
- **新增迁移测试**：
```kotlin
@Test
fun `v2 migration clears read times and all read once`() = runTest {
    store.markSessionRead("svr1", "ses1", 5000L)
    store.markAllSessionsRead("svr1", 8000L)
    store.runUnreadStateV2Migration()
    assertEquals(emptyMap(), store.sessionReadTimes("svr1").first())
    assertEquals(0L, store.allReadAt("svr1").first())
    // 幂等：再次运行不清（此时已清，写入新值后再次迁移不动）
    store.markSessionRead("svr1", "ses2", 1000L)
    store.runUnreadStateV2Migration()
    assertEquals(mapOf("ses2" to 1000L), store.sessionReadTimes("svr1").first())
}
```
（迁移需幂等：标记存在则跳过。测试需 import `kotlinx.coroutines.flow.first`——核对现有 import）

- [ ] **Step 3: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SettingsDataStoreReadTimesTest*"`
Expected: FAIL（签名不匹配/方法不存在）

- [ ] **Step 4: 实现数据层改造**

SettingsDataStoreReadTimes.kt：
1. 删除 `LAST_REPLY_TIME_KEY`/`lastReplyTimeKey`（19/27 行）、`lastReplyTimes()`（44-49）、`saveLastReplyTimes()`（52-57）、`ensureUnreadBaseline()`（72-82）、`UNREAD_BASELINE_PREFIX`（16 行）、`baselineKey`（25 行）
2. `markAllSessionsRead`（34-38）改为：
```kotlin
/** 一键已读：记录全局已读位置（已知会话最后完成消息的 completed，服务器时刻），消除所有小红点。 */
suspend fun SettingsDataStore.markAllSessionsRead(serverId: String, globalMax: Long) {
    dataStore.edit { prefs ->
        prefs[allReadKey(serverId)] = globalMax
    }
}
```
3. `markSessionRead`（85-95）改为：
```kotlin
/** 将会话标记为已读（记录最后消费的完成消息 completed，服务器时刻）。 */
suspend fun SettingsDataStore.markSessionRead(serverId: String, sessionId: String, completedTs: Long) {
    dataStore.edit { prefs ->
        val current = prefs[readTimesKey(serverId)]?.let {
            runCatching { readTimesJson.decodeFromString(readTimesSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        prefs[readTimesKey(serverId)] = readTimesJson.encodeToString(
            readTimesSerializer,
            current + (sessionId to completedTs)
        )
    }
}
```
4. 新增迁移：
```kotlin
private const val UNREAD_STATE_V2_MIGRATED_KEY = "unread_state_v2_migrated"

/** 一次性迁移：清空已读标记（readTimes/allReadAt）——值域从客户端 now 变为服务器 completed，旧值不可比。幂等。 */
suspend fun SettingsDataStore.runUnreadStateV2Migration() {
    dataStore.edit { prefs ->
        if (prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] == true) return@edit
        val keys = prefs.asMap().keys.filter {
            it.name.startsWith(SESSION_READ_TIMES_PREFIX) || it.name.startsWith(ALL_READ_PREFIX)
        }
        keys.forEach { prefs.remove(it) }
        prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] = true
    }
}
```
（需 import `androidx.datastore.preferences.core.booleanPreferencesKey`）

SettingsRepository.kt：`ensureUnreadBaseline`（76 行）删除；`markAllSessionsRead(serverId)`（82）→ `markAllSessionsRead(serverId: String, globalMax: Long)`；`markSessionRead(serverId, sessionId)`（85）→ `markSessionRead(serverId: String, sessionId: String, completedTs: Long)`；新增 `runUnreadStateV2Migration()`
SettingsRepositoryImpl.kt：同步实现（62-72 行区域，删 ensureUnreadBaseline）
SessionRepository.kt：`getLastReplyTimeFlow()`（47）→ `getLastCompletedReplyTimeFlow(): Flow<Map<String, Long>>`
SessionRepositoryImpl.kt（77-79）：
```kotlin
override fun getLastCompletedReplyTimeFlow(): Flow<Map<String, Long>> =
    eventDispatcher.lastCompletedReplyTime
```
（删除 onEach 日志或保留——保留 `onEach { AppLogger.d("UnreadDiag", "[read] ${it.size} entries...") }` 亦可，核对 eventDispatcher 在 SessionRepositoryImpl 中可用）

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SettingsDataStoreReadTimesTest*"`
Expected: PASS

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: **可预期失败**（SessionListViewModel/ChatViewModel 仍引用旧签名）——记录错误清单，Task 4 修复。若失败，检查是否仅剩 ViewModel 层引用。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimes.kt app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SettingsRepository.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsRepositoryImpl.kt app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SessionRepository.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImpl.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimesTest.kt
git commit -m "refactor: #25 数据层已读标记服务器域（markRead 传 completedTs，删基线/lastReplyTime 持久化，v2 迁移）"
```

---

### Task 3: isUnread 改造（status 门控 + 删基线）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TreeNode.kt`（buildTreeNodes 调用点）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt`

**Interfaces:**
- Consumes: `SessionStatus`（domain.model）、`SessionListDataInputs`（含 `lastReplyTime` 字段——Task 4 改源，本 Task 字段名暂保留）
- Produces: 新 `isUnread` 签名（下）

- [ ] **Step 1: 读 SessionListStateBuilder.kt 全文**（131 行）+ TreeNode.kt 中 buildTreeNodes 签名与 isUnread 调用点（150/160 行）+ SessionListUnreadTest.kt 全文

- [ ] **Step 2: 写失败测试（SessionListUnreadTest.kt 适配 + 新增）**

适配：现有调用 `isUnread(sid, lastReplyTime, readTimes, baseline, allReadAt)` → 新签名 `isUnread(sid, maxCompleted, readTimes, allReadAt, status)`——去掉基线参数、加 status 参数。现有基线相关测试（"基线=5000：更早的回复不算未读"等）**删除**。

新增（沿用该文件现有 fixture 构造模式，`SessionStatus` 从 `dev.leonardo.ocbeacon.domain.model` import）：

```kotlin
@Test
fun `busy session never unread even with newer completed`() {
    val state = buildContentState(
        data = data.copy(lastReplyTime = mapOf("s1" to 10_000L)),
        ui = ui,
        serverId = serverId,
        draftRepository = draftRepository,
    )
    // 构造的 treeNodes 中 statuses 默认 Idle——直接测纯函数：
    assertTrue(isUnread("s1", mapOf("s1" to 10_000L), mapOf("s1" to 5_000L), allReadAt = 0L, status = SessionStatus.Busy) == false)
    assertTrue(isUnread("s1", mapOf("s1" to 10_000L), mapOf("s1" to 5_000L), allReadAt = 0L, status = SessionStatus.Idle) == true)
}

@Test
fun `idle status required for unread`() {
    assertFalse(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 0L, status = SessionStatus.Busy))
    assertFalse(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 0L, status = SessionStatus.Error))
}

@Test
fun `allReadAt gating works with status`() {
    assertFalse(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 20_000L, status = SessionStatus.Idle))
    assertTrue(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 5_000L, status = SessionStatus.Idle))
}
```
（`SessionStatus.Error` 构造——核对枚举定义；若无 Error 用现有变体。测试里 `data.copy(lastReplyTime = ...)` 的 `data` 是现有 fixture 变量名——以实际文件为准调整）

- [ ] **Step 3: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionListUnreadTest*"`
Expected: FAIL（isUnread 签名不匹配）

- [ ] **Step 4: 实现 isUnread 改造**

SessionListStateBuilder.kt（24-33 行替换）：
```kotlin
/**
 * 未读判定：会话状态为 Idle（turn 完全结束）且有完成回复时间，
 * 且晚于 max(最后已读位置, 一键已读位置)。全部服务器时刻，纯函数。
 * 会话状态未知/进行中（非 Idle）→ 不红点。
 */
internal fun isUnread(
    sessionId: String,
    maxCompleted: Map<String, Long>,
    readTimes: Map<String, Long>,
    allReadAt: Long = 0L,
    status: SessionStatus,
): Boolean {
    if (status != SessionStatus.Idle) return false
    val last = maxCompleted[sessionId] ?: return false
    return last > maxOf(readTimes[sessionId] ?: 0L, allReadAt)
}
```
buildContentState 调用点（109 行）：
```kotlin
hasUnread = isUnread(session.id, data.lastReplyTime, readTimes, data.allReadAt, data.statuses[session.id] ?: SessionStatus.Idle)
```
TreeNode.kt buildTreeNodes 两个调用点（150/160 行）同样适配（去掉 unreadBaseline 参数、加 status 参数——调用处已有 `statuses[session.id]` 局部变量；签名 `buildTreeNodes(...)` 的参数列表中删 `unreadBaseline`，若 TreeNode 文件另有 isUnread 默认参数调用一并适配）。注意 buildTreeNodes 的 statuses 参数已存在，直接传入。

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionListUnreadTest*"`
Expected: PASS

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: 通过（若 SessionListViewModel 仍有旧引用报错，记录，Task 4 修复）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TreeNode.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt
git commit -m "refactor: #25 isUnread status 门控 + 删基线参数（turn 结束才红点）"
```

---

### Task 4: ViewModel 适配（列表 + 聊天）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListShellStateTest.kt`（mock 适配）
- Test: 其他引用旧接口的测试（`grep -rn "getLastReplyTimeFlow\|ensureUnreadBaseline\|markSessionRead(" app/src/test` 逐个适配）

**Interfaces:**
- Consumes: `EventDispatcher.lastCompletedReplyTime`（Task 1）、新数据层签名（Task 2）
- Produces: 无新接口

- [ ] **Step 1: 读 SessionListViewModel.kt 相关段**（80-160 构造/状态、205-275 数据分组、330-375 基线/一键已读、520-560 refresh）+ ChatViewModel.kt（120-145 markSessionRead）

- [ ] **Step 2: 写失败测试（mock 适配）**

SessionListShellStateTest.kt:69：`every { sessionRepository.getLastReplyTimeFlow() }` → `getLastCompletedReplyTimeFlow()`。grep 其他测试中旧引用并同步。

- [ ] **Step 3: 实现 SessionListViewModel 适配**

1. 数据分组（218-220）：`sessionRepository.getLastReplyTimeFlow()` → `sessionRepository.getLastCompletedReplyTimeFlow()`（参数名 `lastReplyTime` 保留，字段语义=实时 maxCompleted）
2. init（339-341）：删除基线初始化块（`_unreadBaseline` 与 `settingsRepository.ensureUnreadBaseline(serverId)` 调用）；删除 `_unreadBaseline` 声明（137 行）与设置数据分组中对它的引用（grep `_unreadBaseline` 全部清理）
3. markAllSessionsRead（368-375）：
```kotlin
/**
 * 一键已读：记录全局已读位置（已知会话最后完成消息的最大 completed，服务器时刻），
 * 消除所有红点；此后新完成的回复才重新红点。
 */
fun markAllSessionsRead() {
    viewModelScope.launch {
        val completedMap = sessionRepository.getLastCompletedReplyTimeFlow().first()
        val globalMax = completedMap.values.maxOrNull() ?: return@launch
        completedMap.keys.forEach { sessionReadSignal.markRead(it, globalMax) }
        settingsRepository.markAllSessionsRead(serverId, globalMax)
    }
}
```
4. `_pendingReadSessionId` 消费处（约 156 行 `settingsRepository.markSessionRead(serverId, sid)`）：改为取 maxCompleted 传参，无值跳过：
```kotlin
viewModelScope.launch {
    val ts = sessionRepository.getLastCompletedReplyTimeFlow().first()[sid]
    if (ts != null) settingsRepository.markSessionRead(serverId, sid, ts)
}
```

- [ ] **Step 4: 实现 ChatViewModel.markSessionRead 适配**（132-143）

```kotlin
fun markSessionRead() {
    val srv = serverId
    val sid = sessionId
    if (srv.isNotBlank() && sid.isNotBlank()) {
        // 已读位置 = 该会话最后一条完成 assistant 消息的 completed（服务器时刻）。
        // 会话无任何完成消息（如秒退、消息未加载）→ 不更新已读标记（用户未消费内容，之后红点合理）。
        val lastCompleted = messages.value[sid]
            ?.filterIsInstance<Message.Assistant>()
            ?.mapNotNull { it.time.completed }
            ?.maxOrNull()
        if (lastCompleted == null) {
            AppLogger.d("UnreadDiag", "[markRead] sid=${sid.take(12)} no completed msg, skip")
            return
        }
        AppLogger.d("UnreadDiag", "[markRead] sid=${sid.take(12)} completed=$lastCompleted")
        sessionReadSignal.markRead(sid, lastCompleted)
        viewModelScope.launch {
            withContext(NonCancellable) { settingsRepository.markSessionRead(srv, sid, lastCompleted) }
        }
    }
}
```
（`messages` 需是 ChatViewModel 中可访问的该会话消息状态——grep ChatViewModel 中消息 StateFlow 变量名，如 `messages`/`messageList`，以实际为准；`Message` 已 import 则用全限定或加 import）

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（清除 Task 2/3 遗留的旧引用错误）

- [ ] **Step 6: 运行相关测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionList*"` + `--tests "*ChatViewModel*"`（分两次或一次通配）
Expected: PASS（mock 适配后）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListShellStateTest.kt
git commit -m "refactor: #25 ViewModel 适配（maxCompleted 源、一键已读全局 max、markRead 传服务器时刻、删基线）"
```

---

### Task 5: 迁移接线

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherUnreadTest.kt`（新增 1 测试）

**Interfaces:**
- Consumes: `SettingsDataStore.runUnreadStateV2Migration()`（Task 2）

- [ ] **Step 1: 写失败测试（追加到 EventDispatcherUnreadTest）**

```kotlin
@Test
fun `init triggers v2 migration once`() = runTest {
    // settingsDataStore 是 relaxed mock——验证迁移被调用
    // 用 spy 或 verify：EventDispatcher init 中应调用 runUnreadStateV2Migration
}
```
（实现：在 setup 中用 `mockk<SettingsDataStore>(relaxed = true)` 换 `spyk`/`verify`——实际做法：EventDispatcher init 中 `appScope.launch { settingsDataStore.runUnreadStateV2Migration() }`？EventDispatcher 没有 appScope——用已有 replyTimePersistScope 替代？它被删了。新增：
```kotlin
private val unreadMigrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
init { unreadMigrationScope.launch { settingsDataStore.runUnreadStateV2Migration() } }
```
测试用 `coVerify { settingsDataStore.runUnreadStateV2Migration() }`（mockk relaxed + coVerify，注意 runTest 时序）。若 coVerify 时序不稳，改为在测试里直接构造并 `verify`——以实际通过为准，测试目的 = 迁移被触发）

- [ ] **Step 2: 实现迁移接线**（EventDispatcher init 新增，见上）

- [ ] **Step 3: 运行测试 + 编译**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*EventDispatcherUnreadTest*"` + `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherUnreadTest.kt
git commit -m "feat: #25 一次性 v2 迁移接线（App 启动清空旧域已读标记）"
```

---

### Task 6: 构建 + 回归 + backlog 收尾

**Files:**
- Modify: `backlog.md`（#25 勾选）

- [ ] **Step 1: 全量单测**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL，全绿（含旧红点相关测试适配后）

- [ ] **Step 2: 构建安装**

Run: `.\gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL → 安装到模拟器（replicant adb-app install）

- [ ] **Step 3: 真机回归（模拟器 + 本机 opencode serve）**

手动/复刻场景（按 spec §5 验收）：
1. 发消息 → 返回列表 → 长 turn 期间无红点 → turn 结束红点出现（UnreadDiag 日志验证 maxCompleted 为服务器时刻）
2. 进会话消费红点 → 返回 → 红点消失
3. 看完回复退出 → 无红点
4. 一键已读 → 全消 → 新回复再红点
5. **杀进程重启 → REST 同步后红点恢复、已读状态保持**
6. 工具调用 turn（触发 CommandExecuted）→ 红点判定值 = 服务器 completed（UnreadDiag 日志 `[markRead] completed=` 与 `lastCompletedReplyTime` 对比）
可复用：`maestro/regression-unread-chain-a/b.yaml`

- [ ] **Step 4: backlog #25 勾选**

按 backlog.md 格式在 #25 条目补 `- **2026-08-07 完成**：...` 一行并 `[x]`；#24 已关闭无需动

- [ ] **Step 5: Commit**

```bash
git add backlog.md
git commit -m "chore: backlog #25 完成（红点派生状态模型落地）"
```

---

## Self-Review 记录

- Spec 覆盖：§3.1→Task 1、§3.3 数据层→Task 2、§3.2→Task 3、§3.3 ViewModel→Task 4、§3.4→Task 5、§5 验收→Task 6 ✅
- 类型一致性：`lastCompletedReplyTime`（Task 1 产物）在 Task 2/4/5 全部同名引用 ✅；`markSessionRead(serverId, sessionId, completedTs)` 签名跨 Task 2/4 一致 ✅；`markAllSessionsRead(serverId, globalMax)` 一致 ✅
- 占位符：无 TBD/TODO；测试代码均已给出核心断言，机械适配类步骤给了明确规则
- 已知需 implementer 现场核对：ChatViewModel 消息状态变量名、SessionStatus.Error 是否存在、SettingsDataStoreReadTimesTest 现有结构、SessionListShellStateTest mock 细节
