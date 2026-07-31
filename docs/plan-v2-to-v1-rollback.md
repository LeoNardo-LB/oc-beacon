# V2 → V1 API 完全回退实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完全移除 ChatViewModel 中对 V2 REST API 的消息加载依赖，回归 V1 REST API + V1 SSE 事件处理，消除所有 V2→V1 适配/桥接代码。

**Architecture:** V1 REST API (`OpenCodeApi`) 负责消息加载（`listMessages`）和发送（`promptAsync`），V1 SSE 事件处理（`EventDispatcher` → `MessageEventHandler`）负责实时更新 `chatRepository`。ChatViewModel 直接从 V1 `chatRepository` 读取消息渲染 UI，不再经过 V2 `SessionState` 中间层。

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Ktor, StateFlow, kotlinx.serialization

---

## 背景调研总结

### V1 API 能力（Android 客户端 `OpenCodeApi.kt`）
- `listMessages(conn, sessionId, limit=200)` → `List<MessageWithParts>` — 完整对话历史（200条）
- `promptAsync(conn, sessionId, parts, model, agent, directory)` — 发送消息（fire-and-forget, 204）
- `abortSession(conn, sessionId, directory)` → `Boolean`
- `getSession(conn, sessionId)` → `Session`
- 以及 30+ 个其他端点（会话管理、权限、配置、PTY 等）全部可用

### V1 SSE 事件处理（`EventDispatcher` + 6 个 Handler）
- `MessageEventHandler` — 处理消息增删改事件，维护 `messages` 和 `parts` StateFlow
- `SessionEventHandler` — 处理会话状态、diff、状态同步
- `SessionNextEventHandler` — 处理 tool progress、step progress、compaction、shell 等增量事件
- `PermissionEventHandler` / `QuestionEventHandler` / `MiscEventHandler` — 权限、问题、TODO

### V2 层的问题
- V2 REST API `/api/session/{id}/message` 返回的数据缺少 User/Assistant 消息
- V2 `SessionState` → V1 `Message`+`Part` 双重转换链冗余
- V2 SDK 没有通过 Hilt DI 管理，在 ChatViewModel 中 `by lazy` 创建

### 回退范围
**保留**：V2 SSE 事件流的数据通道（`SseClient.rawSseEventFlow` → 原始 JSON）不变，只是解析/处理方式从 V2 `EventReducer` 切回 V1 `EventDispatcher`
**移除**：V2 REST API 调用（`v2Sdk.messages()`）、V2 `SessionState` 容器、V2→V1 转换逻辑
**替换**：消息发送从 `v2Sdk.prompt()` 切回 V1 `promptAsync()`

---

## 文件变更清单

| 文件 | 操作 | 职责 |
|------|------|------|
| `ui/screens/chat/ChatViewModel.kt` | **重大修改** | 移除 V2 层，重写消息加载/SSE/发送逻辑 |
| `data/v2/OpenCodeV2Sdk.kt` | 删除或标记废弃 | SDK 接口 + 实现 |
| `data/v2/SessionMessage.kt` | 删除或标记废弃 | V2 消息类型 |
| `data/v2/SessionState.kt` | 删除或标记废弃 | V2 状态容器 |
| `data/v2/EventReducer.kt` | 删除或标记废弃 | V2 事件归约 |
| `data/v2/SseEventV2.kt` | 删除或标记废弃 | V2 SSE 事件类型 |
| `data/v2/EventParser.kt` | 删除或标记废弃 | V2 SSE 事件解析 |
| `data/v2/EventDeduplicator.kt` | 删除或标记废弃 | V2 事件去重 |
| `data/v2/SseConnectionManager.kt` | 删除或标记废弃 | V2 SSE 连接管理 |
| `data/v2/Types.kt` | 删除或标记废弃 | V2 共享类型 |

> **注意**：Task 1-4 只涉及 `ChatViewModel.kt` 的修改。Task 5 是清理 V2 文件。
> 先改 ChatViewModel 使其完全不依赖 `data.v2`，再删除文件。

---

## Task 1: 重写消息状态容器 — 用 V1 ChatRepository 替代 V2 SessionState

**Files:**
- Modify: `ui/screens/chat/ChatViewModel.kt:280-282` (移除 `_sessionState` / `sessionState`)
- Modify: `ui/screens/chat/ChatViewModel.kt:594-780` (重写 `messageListState`)

**Goal:** 将消息列表数据源从 V2 `_sessionState: MutableStateFlow<SessionState>` 切换为 V1 `chatRepository` 的消息 StateFlow。

- [ ] **Step 1: 理解 V1 ChatRepository 的消息接口**

确认 `chatRepository` 提供的消息读取方式。可能需要添加一个新的 `StateFlow` 暴露当前 session 的消息：
```kotlin
// chatRepository 中应该已有类似接口，确认即可
fun getMessagesFlow(sessionId: String): StateFlow<List<MessageWithParts>>
// 或
val messages: StateFlow<Map<String, List<MessageWithParts>>>
```

- [ ] **Step 2: 在 ChatViewModel 中添加 V1 消息数据源**

移除 `_sessionState` / `sessionState`，替换为从 `chatRepository` 获取消息的 Flow：
```kotlin
// 删除:
// private val _sessionState = MutableStateFlow(SessionState.EMPTY)
// val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

// 新增: 从 chatRepository 观察当前 session 的消息
private val _messagesFlow = MutableStateFlow<List<MessageWithParts>>(emptyList())
```

- [ ] **Step 3: 重写 `messageListState` 直接从 V1 数据映射**

移除 `_sessionState` + `_toolExpandedStates` 的 combine，改为从 V1 `MessageWithParts` 直接映射 `ChatMessage`：
```kotlin
val messageListState: StateFlow<MessageListState> = combine(
    _messagesFlow,
    _toolExpandedStates,
) { messages, toolExpandedStates ->
    val chatMessages = messages.map { mwp ->
        ChatMessage(message = mwp.info, parts = mwp.parts)
    }
    MessageListState(messages = chatMessages.reversed())
}
```

- [ ] **Step 4: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: 编译错误（因为其他地方还引用 `_sessionState`），记录所有错误位置

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: replace V2 SessionState with V1 message source in messageListState"
```

---

## Task 2: 重写消息加载逻辑 — loadSession() 和 refreshMessages()

**Files:**
- Modify: `ui/screens/chat/ChatViewModel.kt:1083-1155` (loadSession)
- Modify: `ui/screens/chat/ChatViewModel.kt:1299-1343` (refreshMessages)

**Goal:** 移除所有 V2 REST API 调用（`v2Sdk.messages()`），只用 V1 `manageSessionUseCase.listMessages()`。

- [ ] **Step 1: 重写 loadSession()**

```kotlin
private suspend fun loadSession() {
    // 1. Load V1 session info for directory / session metadata
    try {
        val session = manageSessionUseCase.getSession(serverId, sessionId)
        if (session.directory.isNotBlank()) {
            sessionDirectory = session.directory
        }
        sessionRepository.setSessions(serverId, listOf(session))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load session info", e)
    } finally {
        if (!sessionLoaded.isCompleted) {
            sessionLoaded.complete(Unit)
        }
    }

    // 2. Load V1 messages (complete conversation history, up to 200)
    try {
        val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = 200)
        chatRepository.setMessages(sessionId, messages)
        _messagesFlow.value = messages
        if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${messages.size} messages for session $sessionId")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load messages", e)
    }

    // 3. Start SSE subscription for real-time updates
    runCatching { startSseSubscription() }
        .onFailure { Log.e(TAG, "Failed to start SSE subscription", it) }
}
```

- [ ] **Step 2: 重写 refreshMessages()**

```kotlin
private suspend fun refreshMessages() {
    try {
        val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = 200)
        chatRepository.setMessages(sessionId, messages)
        _messagesFlow.value = messages
        if (BuildConfig.DEBUG) Log.d(TAG, "Refreshed ${messages.size} messages for session $sessionId")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to refresh messages", e)
    }
}
```

- [ ] **Step 3: 删除 toV2SessionMessages() 转换函数**

移除 `ChatViewModel.kt:1227-1276` 的 `List<MessageWithParts>.toV2SessionMessages()` 扩展函数。

- [ ] **Step 4: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: 编译错误（因为 SSE 订阅、发送消息等还引用 V2），记录错误位置

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: rewrite loadSession/refreshMessages to use V1 API only"
```

---

## Task 3: 重写 SSE 事件订阅 — 用 V1 EventDispatcher 替代 V2 EventReducer

**Files:**
- Modify: `ui/screens/chat/ChatViewModel.kt:1163-1204` (startSseSubscription)
- Modify: `ui/screens/chat/ChatViewModel.kt:284-298` (移除 v2Sdk)

**Goal:** 移除 V2 SSE 事件链（`v2Sdk.events()` → `EventReducer` → `_sessionState`），改用 V1 `SseClient` + `EventDispatcher` 处理 SSE 事件并更新 `chatRepository`/`_messagesFlow`。

- [ ] **Step 1: 确认 V1 SSE 事件到 chatRepository 的数据通路**

检查 `SseClient` 如何发送事件到 `EventDispatcher`，以及 `EventDispatcher` 如何更新 `chatRepository`（通过 `MessageEventHandler`）。
确认：SSE 事件处理后，`chatRepository` 的消息 StateFlow 是否自动更新？如果是，`_messagesFlow` 可以直接映射 `chatRepository` 的 Flow。

- [ ] **Step 2: 重写 startSseSubscription()**

核心变化：不再使用 `v2Sdk.events()`（V2 链路），而是使用 V1 的 SSE 事件处理。

方案 A（推荐）：如果 V1 `SseClient` + `EventDispatcher` 已在后台运行（由 Service 或 DI 管理），ChatViewModel 只需观察 `chatRepository` 的消息 Flow 即可，不需要自己订阅 SSE。

方案 B：如果 V1 SSE 需要手动启动，则：
```kotlin
private fun startSseSubscription() {
    // V1 SSE 已由 SseConnectionManager (service层) 管理
    // EventDispatcher 处理事件 → 更新 chatRepository
    // ChatViewModel 只需观察 chatRepository 的消息变化
    viewModelScope.launch {
        chatRepository.getMessagesFlow(sessionId).collect { messages ->
            _messagesFlow.value = messages
        }
    }
}
```

- [ ] **Step 3: 移除 v2Sdk 声明和初始化**

移除 ChatViewModel 中：
```kotlin
// 删除:
// private val v2Sdk: OpenCodeV2Sdk by lazy { ... }
```

- [ ] **Step 4: 处理 isBusy 状态**

`SessionState.isBusy` 之前基于 V2 `AssistantMessage.time.completed == null`。回退后需要从 V1 状态推导：
```kotlin
// 从 chatRepository 或 sessionRepository 的 sessionStatus 推导
val isSessionBusy: Boolean
    get() = sessionRepository.getSessionStatus(sessionId) is SessionStatus.Busy
         || sessionRepository.getSessionStatus(sessionId) is SessionStatus.Retry
```

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: replace V2 SSE subscription with V1 EventDispatcher"
```

---

## Task 4: 重写消息发送和会话操作

**Files:**
- Modify: `ui/screens/chat/ChatViewModel.kt:1824-1852` (sendParts)
- Modify: `ui/screens/chat/ChatViewModel.kt:1907-1919` (abortSession)
- Modify: `ui/screens/chat/ChatViewModel.kt:1415-1434` (loadOlderMessages)

**Goal:** 将 `v2Sdk.prompt()` → V1 `promptAsync()`，`v2Sdk.abort()` → V1 `abortSession()`，移除 V2 分页加载。

- [ ] **Step 1: 重写 sendParts() — 使用 V1 API**

```kotlin
private fun sendParts(parts: List<PromptPart>) {
    viewModelScope.launch {
        try {
            val currentSessionId = ensureSession()
            val text = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
            val files = parts.filter { it.type == "file" }.mapNotNull { it.url }.map {
                PromptPart(type = "file", text = null, url = it)
            }

            // V1 promptAsync — fire-and-forget, SSE events will update chatRepository
            sendMessageUseCase.sendPrompt(
                serverId = serverId,
                sessionId = currentSessionId,
                parts = parts,
                directory = sessionDirectory,
                model = null, // 使用当前选中的 model
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $currentSessionId via V1 API")
            refreshSessionTitleDelayed(currentSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }
}
```

- [ ] **Step 2: 重写 abortSession() — 使用 V1 API**

```kotlin
fun abortSession() {
    viewModelScope.launch {
        try {
            sseJob?.cancel()
            sseJob = null
            manageSessionUseCase.abortSession(serverId, sessionId, sessionDirectory)
            if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
            sessionRepository.updateSessionStatus(sessionId, SessionStatus.Idle)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to abort session", e)
        }
    }
}
```

- [ ] **Step 3: 重写 loadOlderMessages() — 使用 V1 API**

V1 没有 cursor 分页，只有 limit。如果已加载 200 条，可以尝试加载更多：
```kotlin
fun loadOlderMessages() {
    viewModelScope.launch {
        try {
            // V1 API 只支持 limit 参数，没有 cursor 分页
            // 已加载 limit=200 条，暂不支持加载更早消息
            // 后续如果需要，可以配合服务端 V2 cursor 分页 API
            if (BuildConfig.DEBUG) Log.d(TAG, "V1 API does not support cursor-based pagination")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load older messages", e)
        }
    }
}
```

> **注意**：V1 `listMessages` 有 `limit` 参数但没有 cursor。如果用户需要加载超过 200 条的消息，后续可以调研是否需要增加 V1 limit 或使用 V2 cursor API 仅用于分页场景。当前 200 条限制对于大多数会话足够。

- [ ] **Step 4: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: replace V2 prompt/abort with V1 API calls"
```

---

## Task 5: 清理 V2 依赖 — 移除所有 data.v2 import 和未使用代码

**Files:**
- Modify: `ui/screens/chat/ChatViewModel.kt:31-76` (移除 V2 import)
- Modify: `ui/screens/chat/ChatViewModel.kt:2418-2436` (移除 convertToolState)
- Delete: `data/v2/OpenCodeV2Sdk.kt`
- Delete: `data/v2/SessionMessage.kt`
- Delete: `data/v2/SessionState.kt`
- Delete: `data/v2/EventReducer.kt`
- Delete: `data/v2/SseEventV2.kt`
- Delete: `data/v2/EventParser.kt`
- Delete: `data/v2/EventDeduplicator.kt`
- Delete: `data/v2/SseConnectionManager.kt`
- Delete: `data/v2/Types.kt`

**Goal:** ChatViewModel 中零 V2 import，`data/v2/` 目录完全移除。

- [ ] **Step 1: 移除 ChatViewModel 中所有 `data.v2` import（约 46 行）**

移除第 31-76 行中所有 `import dev.leonardo.octether.data.v2.*` 语句。

- [ ] **Step 2: 移除 `convertToolState()` 函数**

移除 `ChatViewModel.kt:2418-2436`（已无调用方）。

- [ ] **Step 3: 全局搜索确认无其他 V2 引用**

```bash
rg "import dev.leonardo.octether.data.v2" app/src/main/kotlin/
```
Expected: 零结果

- [ ] **Step 4: 删除 `data/v2/` 目录下所有文件**

```bash
rm -rf app/src/main/kotlin/dev/minios/ocremote/data/v2/
```

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: remove V2 data layer entirely, zero V2 dependencies"
```

---

## Task 6: 端到端验证 — 确认所有功能正常

**Files:** 无代码修改，纯验证

**Goal:** 在模拟器上完整验证消息加载、发送、实时更新、权限处理等全链路。

- [ ] **Step 1: 编译 Release APK**

```bash
.\gradlew :app:assembleDevRelease
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 安装并连接到 OpenCode 服务器**

1. 在模拟器安装 APK
2. 连接到 `10.0.2.2:4096`（opencode / 环境变量密码）
3. 确认首页会话列表正常显示

- [ ] **Step 3: 验证消息加载**

1. 进入一个有历史消息的会话（如"对话界面状态同步问题"）
2. 确认消息完整加载（应有 100+ 条对话）
3. 确认 User/Assistant 消息正确渲染
4. 确认 Tool 调用卡片正确展示
5. 确认 Token 统计显示

- [ ] **Step 4: 验证消息发送**

1. 在会话中发送一条文本消息
2. 确认消息发送成功（无 crash）
3. 确认 AI 回复通过 SSE 实时流式显示
4. 确认消息发送后历史列表正确更新

- [ ] **Step 5: 验证其他功能**

1. 新建会话 → 发送消息 → 正常工作
2. 切换模型 → 发送消息 → 使用新模型
3. 中止会话 → 状态正确更新
4. 权限弹窗 → 回复后消失
5. 切换到其他会话再切回 → 消息仍然正确

- [ ] **Step 6: 验证日志无异常**

```bash
adb logcat -s ChatViewModel:* MessageEventHandler:* EventDispatcher:*
```
Expected: 无 ERROR 级别日志

- [ ] **Step 7: 最终 Commit**

如果所有验证通过：
```bash
git add -A && git commit -m "chore: V2→V1 rollback complete, all features verified"
```

---

## 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| V1 SSE 事件处理未覆盖所有实时场景 | 文本流式/工具进度显示不完整 | Task 3 Step 1 中详细检查 V1 EventHandler 覆盖范围 |
| V1 无 cursor 分页 | 无法加载超过 200 条历史消息 | 当前 200 条对大多数会话足够；后续可按需增强 |
| ChatViewModel 2447 行，修改面大 | 编译错误多、回归风险高 | 每个 Task 独立编译检查，逐步推进 |
| `chatRepository` 的消息 Flow 接口不明确 | Task 1/3 可能需要先修改 repository | Task 1 Step 1 先确认接口 |

---

## 实施顺序

```
Task 1 (消息状态容器) → Task 2 (消息加载) → Task 3 (SSE 事件) → Task 4 (发送/操作) → Task 5 (清理 V2) → Task 6 (验证)
```

每个 Task 产出一个可编译的 commit。如果某个 Task 的编译错误跨越到下一个 Task 的范围，可以在下一个 Task 中修复。
