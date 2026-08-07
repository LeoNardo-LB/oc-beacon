# 悲观消息重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根治"发送消息闪烁"——删除整个乐观消息体系，改为悲观消息（opencode 官方一致：发送后无占位，SSE 回显才显示；失败恢复草稿+snackbar），不留任何乐观技术债。

**Architecture:** 乐观体系共 4 层（UI store / data 层 addOptimisticMessage 空操作链 / PendingPrompt 持久化对账 / messageID 链）全部删除。发送路径回归"POST → SSE 回显 → 缓存 → combine → UI"直通。保留与乐观正交的闪烁修复（AnimatedVisibility 移除、requestScrollToItem 同帧锚定）与 QUEUED/SSE 机制。

**Tech Stack:** Kotlin / Jetpack Compose / Hilt / Ktor / JUnit4 + MockK

**Spec:** `docs/superpowers/specs/2026-08-08-pessimistic-message-send-design.md`

## Global Constraints

- 悲观消息：发送后不创建任何乐观占位；消息只经服务器 SSE 回显出现
- 保留：SendStateStore.isSending（防双击+按钮呼吸圈）、QUEUED 徽章（FSM 派生）、MessageEventHandler pendingDeltas（part 批处理）、summary 播种、`unexpected user count` 回归检测、闪烁修复（ChatScreen AnimatedVisibility 移除 / ChatScrollController requestScrollToItem / ScrollCompensation）
- 每个任务结束必须编译通过 + 相关单测通过 + 单独 commit（ChatScreen 编辑协议同样适用于 Chat 相关文件：改前 Read、改后 compileDevDebugKotlin）
- 构建：`.\gradlew :app:compileDevDebugKotlin`（120s 超时）；单测：`.\gradlew :app:testDevDebugUnitTest --rerun --tests "..."`（180s）
- 不修改 version.properties；工作区已有未提交的半成品（SendStateStore.kt、悲观版 ChatSendDelegate.kt、ChatScreen/ChatScrollController 闪烁修复）作为任务 1 的起点，**不要回滚**

---

### Task 1: 恢复编译——乐观引用替换（MessageDataDelegate + ChatViewModel）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegate.kt:111-114, 303`（optimisticStore → sendStateStore、isSending 引用、新增 reportError）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt:11,13,15,47,77,337-341,374-402,476-493,501`（PendingPrompt 引用全删、sendDelegate 构造调整、retrySendMessage 门面删）

**Interfaces:**
- Produces: `MessageDataDelegate.sendStateStore: SendStateStore`（internal val）、`MessageDataDelegate.reportError(msg: String?)`（internal fun）
- Consumes: `SendStateStore`（已存在，`isSending: StateFlow<Boolean>` + `setSending(Boolean)` + `isSendingValue`）

- [ ] **Step 1: 改 MessageDataDelegate——optimisticStore 替换为 sendStateStore**

`MessageDataDelegate.kt:111-114` 替换：
```kotlin
    internal val sendStateStore = SendStateStore()
```
（删除整个 `optimisticStore = OptimisticMessageStore(...)` 块）

- [ ] **Step 2: 改 MessageDataDelegate——interactionState 的 isSending 引用**

`MessageDataDelegate.kt:303`：`optimisticStore.isSending,` → `sendStateStore.isSending,`

- [ ] **Step 3: 改 MessageDataDelegate——新增 reportError 暴露 _error**

在 `_error` 字段（L73）附近或类末尾新增：
```kotlin
    /** 发送失败等外部错误入口 —— 经 interactionState.error 供 snackbar/空态展示。 */
    internal fun reportError(msg: String?) {
        _error.value = msg
    }
```

- [ ] **Step 4: 改 ChatViewModel——删除 PendingPrompt import 与构造参数**

`ChatViewModel.kt`：
- L11 删 `import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord`
- L13 删 `import dev.leonardo.ocbeacon.domain.repository.PendingPromptRepository`
- L15 删 `import dev.leonardo.ocbeacon.data.repository.missingPendingPromptIds`
- L47 注释中删除 "PENDING_RECONCILE_MIN_AGE_MS" 字样
- L77 删构造参数行 `private val pendingPromptRepository: PendingPromptRepository,`

- [ ] **Step 5: 改 ChatViewModel——删 init 恢复块与对账循环**

- 删 L337-341（restoredPending 恢复块：`val restoredPending = ...` 到 `}`）
- 删 L374-402（对账循环：`// 将 pending prompt 与权威消息列表对账。` 到 `}` 结束，含 missing 分支与 confirmed 分支）——保留 tokenStatsTracker.update 块（L362-372）与后续 `}`（collect 块闭合）

- [ ] **Step 6: 改 ChatViewModel——sendDelegate 构造调整**

`ChatViewModel.kt:476-493` 替换为：
```kotlin
    private val sendDelegate = ChatSendDelegate(
        scrollSignal = scrollSignal,
        sendMessageUseCase = sendMessageUseCase,
        manageSessionUseCase = manageSessionUseCase,
        chatRepository = chatRepository,
        sessionRepository = sessionRepository,
        sessionStateService = sessionStateService,
        sendStateStore = messageData.sendStateStore,
        scope = viewModelScope,
        serverId = serverId,
        sessionIdProvider = { sessionLifecycle.sessionId },
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        ensureSession = { sessionLifecycle.ensureSession() },
        modelConfigProvider = { modelConfigState.value },
        selectedVariantProvider = { modelConfig.selectedVariantValue },
        errorSink = { messageData.reportError(it) },
        draftDelegate = draftDelegate,
    )
```
（删 `pendingPromptRepository = pendingPromptRepository,` 与 `optimisticStore = messageData.optimisticStore,`）

- [ ] **Step 7: 改 ChatViewModel——删 retrySendMessage 门面**

L501 删 `fun retrySendMessage(pendingId: String) = sendDelegate.retrySendMessage(pendingId)`

- [ ] **Step 8: 删死测试文件并编译验证**

`OptimisticMessageStoreTest.kt` 引用的 OptimisticMessageStore 已删（工作区现状）——删除死文件恢复单测编译：
```bash
Remove-Item app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/OptimisticMessageStoreTest.kt
```
Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（此时 PendingPromptRepository 等文件仍在但无引用，编译通过）

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: 乐观引用替换为 SendStateStore，ChatViewModel 移除 PendingPrompt 依赖（悲观消息第 1 步）"
```

---

### Task 2: messageID 链还原（data 层 + 相关测试）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/dto/request/ChatRequests.kt:8-16`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/message/MessageApi.kt:42-50, 205-228`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepository.kt:100-109`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt:146-157`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/SendMessageUseCase.kt:15-33`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SendMessageUseCaseTest.kt`、`SubmitAnnotationsUseCaseTest.kt`

**Interfaces:**
- Consumes: 无（纯还原）
- Produces: `promptAsync` / `sendPrompt` 恢复 7 参签名（无 messageID）

- [ ] **Step 1: 还原 5 个主代码文件**

逐个删除 messageID 相关：
- `ChatRequests.kt`：PromptRequest 删 `val messageID: String? = null,`
- `MessageApi.kt`：接口（L42-50）与实现（L205-228）的 `messageID: String? = null` / `messageID: String?` 参数；实现里 `messageID = messageID,` 一行
- `ChatRepository.kt`：promptAsync 删 `messageID: String? = null` 参数与 KDoc 中 "@param messageID" 行
- `ChatRepositoryImpl.kt`：promptAsync 删参数与 `messageID` 传参
- `SendMessageUseCase.kt`：sendPrompt 删 `messageID: String? = null` 参数、`messageID = messageID,` 行、KDoc

- [ ] **Step 2: 还原 SendMessageUseCaseTest**

`SendMessageUseCaseTest.kt`：
- L20/38：`coEvery { chatRepository.promptAsync(any(), any(), any(), any(), any(), any(), any(), any()) }` → 删 1 个 any（7 参）
- L22-30：sendPrompt 调用删 `messageID = "msg_test123"`
- L32：`coVerify { chatRepository.promptAsync("server1", "s1", parts, null, "build", null, null, "msg_test123") }` → 7 参（删 "msg_test123"）

- [ ] **Step 3: 还原 SubmitAnnotationsUseCaseTest**

`SubmitAnnotationsUseCaseTest.kt`：
- L26/40：8 参 → 7 参
- L32-34：coVerify 7 参（删末尾 `, null`）

- [ ] **Step 4: 编译 + 相关单测**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL
Run: `.\gradlew :app:compileDevDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL（FakeChatRepository 的 promptAsync 7 参恢复匹配）
Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCaseTest" --tests "dev.leonardo.ocbeacon.domain.usecase.SubmitAnnotationsUseCaseTest"`
Expected: 全过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/data/dto/request/ChatRequests.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/message/MessageApi.kt app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepository.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt app/src/main/kotlin/dev/leonardo/ocbeacon/domain/usecase/SendMessageUseCase.kt app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SendMessageUseCaseTest.kt app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SubmitAnnotationsUseCaseTest.kt
git commit -m "revert: 移除悲观消息下无消费方的 messageID 参数链"
```

---

### Task 3: 合并层与状态清理（MessageDataDelegate combine + ChatUiState + 渲染链）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegate.kt:129-281`（combine 源 11→9、乐观合并块删、MessageListState 构造）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatUiState.kt:7,26-27,147-148,170-171`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatStateAggregator.kt:189`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ChatMessageList.kt:609-610`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCard.kt:5,16,36`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCardUser.kt:39,62,63,210-251`

**Interfaces:**
- Produces: `MessageListState` 无 pendingMessageIds/pendingMessages 字段；`ChatUiState` 无 pendingMessageIds
- Consumes: Task 1 的 sendStateStore

- [ ] **Step 1: 改 MessageDataDelegate——combine 源 11→9 与参数解析**

`MessageDataDelegate.kt`：
- L138 删 `optimisticStore.pendingMessageIds,`
- L141 删 `optimisticStore.pendingMessages,`
- L156 删 `val pendingMessageIds = args[7] as Set<String>`
- L168 删 `val pendingMessages = args[10] as List<...OptimisticMessage>`
- L158 `val statuses = args[8] as Map<String, SessionStatus>` → `args[7]`
- L164 `val progressList = args[9] as? List<ToolProgressInfo>` → `args[8]`
- 删 L7 附近 `import dev.leonardo.ocbeacon.domain.model.OptimisticMessage`（若存在）

- [ ] **Step 2: 改 MessageDataDelegate——删乐观合并块**

L228-256 整块（`// 追加尚未被服务器确认的乐观消息。` 到 `val mergedChatMessages = ...` 结束）替换为：
```kotlin
            // 悲观消息：无乐观合并 —— 消息仅经服务器 SSE 回显进入 chatMessages。
            val mergedChatMessages = chatMessages
```

- [ ] **Step 3: 改 MessageDataDelegate——MessageListState 构造与诊断日志**

- L269-270 删 `pendingMessageIds = pendingMessageIds,` 与 `pendingMessages = pendingMessages,`
- L273-281 诊断日志：`merged=${mergedChatMessages.size}` 保留（变量名不变）；`pending=${pendingMessages.size}` 字段删除（同时删 `pendingMessages` 引用）

- [ ] **Step 4: 改 ChatUiState——删字段与常量**

- L7 删 `import dev.leonardo.ocbeacon.domain.model.OptimisticMessage`
- L26-27 删 `MessageListState.pendingMessageIds` / `pendingMessages` 字段定义
- L147-148 删 `ChatUiState.pendingMessageIds` 字段 + 注释
- L170-171 删 `PENDING_RECONCILE_MIN_AGE_MS` 常量 + 注释

- [ ] **Step 5: 改 ChatStateAggregator——删组装行**

`ChatStateAggregator.kt:189` 删 `pendingMessageIds = msgList.pendingMessageIds,`

- [ ] **Step 6: 改 ChatMessageList——删 pendingStatus/onRetry**

`ChatMessageList.kt`：
- L609 删 `pendingStatus = messageState.pendingMessages.find { it.pendingId == chatMessage.message.id }?.status,`
- L610 删 `onRetry = { viewModel.retrySendMessage(chatMessage.message.id) },`

- [ ] **Step 7: 改 MessageCard——删 pendingStatus 透传**

- L5 删 `import dev.leonardo.ocbeacon.domain.model.UserMsgStatus`
- L16 删参数 `pendingStatus: UserMsgStatus? = null,`
- L36 删 `pendingStatus = pendingStatus,`

- [ ] **Step 8: 改 MessageCardUser——删 pendingStatus 分支，保留 QUEUED**

- L39 删 `import dev.leonardo.ocbeacon.domain.model.UserMsgStatus`
- L62 删参数 `pendingStatus: UserMsgStatus?,`
- L63 删参数 `onRetry: (() -> Unit)?,`（死参数）
- L210-251 `when (pendingStatus) { ... }` 整块替换为：
```kotlin
                        // 悲观模式：无 Sending/Failed/Sent 状态（消息以服务器权威直接出现）。
                        // 仅保留 QUEUED 徽章（FSM 队列状态派生）。
                        if (isQueued) {
                            Surface(
                                shape = ShapeTokens.extraSmall,
                                color = QueuedBadgeColor
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_queued),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 8.sp,
                                        color = QueuedBadgeTextColor
                                    ),
                                    modifier = Modifier.padding(horizontal = SpacingTokens.XS.dp, vertical = 1.dp)
                                )
                            }
                        }
```

- [ ] **Step 9: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegate.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatUiState.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatStateAggregator.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ChatMessageList.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCard.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCardUser.kt
git commit -m "refactor: 删除乐观合并与 pending 状态渲染链（悲观消息第 2 步）"
```

---

### Task 4: data 层乐观死代码删除（addOptimisticMessage 链 + pending-* 过滤）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepository.kt:226-230`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt:374-376`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt:454-455`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandler.kt:39-51, 363, 378, 381, 383-385, 428-430, 450-454`

**Interfaces:**
- Consumes: 无
- Produces: 无（纯删除）

- [ ] **Step 1: 删 addOptimisticMessage 链（4 文件）**

- `ChatRepository.kt`：删 L226-230 接口方法与注释
- `ChatRepositoryImpl.kt`：删 L374-376 override
- `EventDispatcher.kt`：删 L454-455 转发方法
- `MessageEventHandler.kt`：删 L39-51 空方法体 `addOptimisticMessage` + 注释

- [ ] **Step 2: 删 MessageEventHandler 的 pending-* 过滤（5 处）**

逐处按上下文处理（先 Read 确认精确内容）：
- L363（setMessages）：`val filtered = if (hasRestUserMsgs) existing.filterNot { it.id.startsWith("pending-") } else existing` → `val filtered = existing`（若无条件则删整行条件逻辑）
- L378（DIAG）：删 `val beforePending = existing.count { it.id.startsWith("pending-") }`
- L381：日志字符串中删 `beforePending=$beforePending` 片段
- L383-385：删 `if (afterUser > beforeUser && beforePending == 0) { AppLogger.w(...) }` 告警块
- L428-430（mergeMessages）：删 pending 过滤条件，直接用 `existing`
- L450-454（replaceMessages）：`val realExisting = existing.filterNot { it.id.startsWith("pending-") }` → 直接用 `existing`；删相关注释

**注意**：保留 L161-164 `unexpected user count increase` 告警（通用回归检测）与 L172-192 summary 播种、L65-136 pendingDeltas 批处理。

- [ ] **Step 3: 删 FakeChatRepository override**

`androidTest/.../fakes/FakeChatRepository.kt:233-236`：删 `override fun addOptimisticMessage(...)`

- [ ] **Step 4: 编译验证（主代码 + androidTest）**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL
Run: `.\gradlew :app:compileDevDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepository.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcher.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandler.kt app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeChatRepository.kt
git commit -m "refactor: 删除 data 层 addOptimisticMessage 空操作链与 pending-* 过滤死代码"
```

---

### Task 5: PendingPrompt 体系删除（文件 + DI + 文档）

**Files:**
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/PendingPromptRecord.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/PendingPromptRepository.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/PendingPromptRepository.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/PendingPromptReconciliation.kt`
- Delete: `app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakePendingPromptRepository.kt`
- Delete: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/MissingPendingPromptIdsTest.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/di/DomainModule.kt:12,20,56-57`
- Modify: `app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeDomainModule.kt:14,27,61`
- Modify: `docs/architecture.md:22`

**Interfaces:**
- Consumes: Task 1（ChatViewModel 已无 PendingPrompt 引用）
- Produces: 无

- [ ] **Step 1: 删 6 个文件**

`Remove-Item` 上述 6 个文件（用 git rm 或删除后 git add -A）

- [ ] **Step 2: 删 DI 绑定**

- `DomainModule.kt`：删 L12/L20 import（PendingPromptRepository 相关）、L56-57 `bindPendingPromptRepository` 绑定
- `FakeDomainModule.kt`：删 L14/L27 import、L61 绑定

- [ ] **Step 3: 更新文档**

`docs/architecture.md:22`：删除 "PendingPromptRepository（基于文件的 JSON, 乐观消息持久化）" 相关描述

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin; .\gradlew :app:compileDevDebugAndroidTestKotlin`
Expected: 均 BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: 删除 PendingPrompt 持久化/对账体系（悲观消息不再需要）"
```

---

### Task 6: 模型文件删除（OptimisticMessage / UserMsgStatus）

**Files:**
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/OptimisticMessage.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/UserMsgStatus.kt`

**Interfaces:**
- Consumes: Task 3（渲染链已无 UserMsgStatus 引用）、Task 1（OptimisticMessageStoreTest 已删）
- Produces: 无

- [ ] **Step 1: 删 2 个文件并编译**

`Remove-Item` 2 个文件 → `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（若仍有引用，grep `OptimisticMessage|UserMsgStatus` 清理遗漏点）

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "refactor: 删除乐观消息模型与状态枚举（含对应测试）"
```

---

### Task 7: ChatViewModel 测试适配 + SendTest 重写

**Files:**
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelDeleteTest.kt:72,253`
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelQueuedTest.kt:86,302`
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelPermissionTest.kt:83,257`
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelStreamingTest.kt:66,227`
- Rewrite: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelSendTest.kt`

**Interfaces:**
- Consumes: Task 1（ChatViewModel 26 参构造）、Task 2（sendPrompt 7 参）
- Produces: 悲观语义测试基线

- [ ] **Step 1: 机械适配 4 个测试**

每个文件（DeleteTest/QueuedTest/PermissionTest/StreamingTest）：
- 删 mock 字段声明行（如 `private val pendingPromptRepository = mockk<...PendingPromptRepository>(relaxed = true)`）
- 删 `createViewModel()` 里 `pendingPromptRepository = pendingPromptRepository,` 行

- [ ] **Step 2: 重写 ChatViewModelSendTest**

- 删 mock 字段 pendingPromptRepository（L66）与构造传入（L175）
- 删 save 捕获（L196-197/235-236）、getMessagesFlow 回显 mock（L201-209）、savedIds/verify remove（L220-227/248-249）、`state.pendingMessageIds` 断言（L268-269）
- sendPrompt mock 改为 7 参：`coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } returns Unit`
- 保留测试改为悲观语义：
```kotlin
@Test
fun `isSending flips during send and clears after REST accepted`() = runTest {
    coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } returns Unit
    val viewModel = createViewModel()
    val collectJob = subscribeToState(viewModel)
    advanceUntilIdle()
    viewModel.sendMessage("Hello world")
    runCurrent()
    assertTrue(viewModel.uiState.value.isSending)  // 发送中（POST 受理前）
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.isSending) // 204 后恢复（可连续发送）
    collectJob.cancel()
}
```
```kotlin
@Test
fun `send failure restores draft and reports error`() = runTest {
    coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
        java.io.IOException("Network error")
    val viewModel = createViewModel()
    val collectJob = subscribeToState(viewModel)
    advanceUntilIdle()
    viewModel.sendMessage("Hello world")
    advanceUntilIdle()
    assertEquals("Hello world", viewModel.uiState.value.restoredDraft?.text) // 草稿恢复
    assertNotNull(viewModel.uiState.value.error) // 错误提示（snackbar 源）
    assertFalse(viewModel.uiState.value.isSending) // finally 复位
    collectJob.cancel()
}
```
```kotlin
@Test
fun `double send is ignored while sending`() = runTest {
    coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } returns Unit
    val viewModel = createViewModel()
    val collectJob = subscribeToState(viewModel)
    advanceUntilIdle()
    viewModel.sendMessage("first")
    viewModel.sendMessage("second") // isSending 期间应被忽略
    advanceUntilIdle()
    coVerify(exactly = 1) { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) }
    collectJob.cancel()
}
```
（保留原有 `restoredDraft is set on send failure in V1` 测试，改为断言 restoredDraft + error；`consumeRestoredDraft is safe when already null` 保留）

- [ ] **Step 3: 运行相关单测**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.chat.ChatViewModel*"`
Expected: 全过

- [ ] **Step 4: Commit**

```bash
git add app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel*
git commit -m "test: ChatViewModel 测试适配悲观消息语义（isSending/失败恢复/防双击）"
```

---

### Task 8: 失败恢复补全附件（技术债修复）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatSendDelegate.kt`（catch 块）
- 核对: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/DraftInputDelegate.kt`（RevertedDraftPayload 定义）、`ChatInputBar.kt`（附件恢复消费）

**Interfaces:**
- Consumes: `RevertedDraftPayload(text, attachmentUris)` 现有结构
- Produces: 发送失败时图片附件随草稿恢复

- [ ] **Step 1: 核对 RevertedDraftPayload 附件字段**

Read `DraftInputDelegate.kt` 中 `RevertedDraftPayload` 定义与 `ChatInputBar.kt` 的 `restoredDraft.attachmentUris` 消费逻辑，确认附件恢复的数据格式（URI 字符串列表？如何回填 attachmentHandler）。

- [ ] **Step 2: 改 ChatSendDelegate catch 块**

`ChatSendDelegate.kt` catch 块替换为：
```kotlin
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to send message", e)
                // 悲观消息失败：恢复草稿到输入框（text + 图片附件）+ 错误提示（snackbar）
                val failedText = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                val failedAttachments = parts.filter { it.type == "file" }.mapNotNull { it.url }
                if (failedText.isNotBlank() || failedAttachments.isNotEmpty()) {
                    draftDelegate.setRestoredDraft(
                        RevertedDraftPayload(
                            text = failedText,
                            attachmentUris = failedAttachments
                        )
                    )
                }
                errorSink(e.message ?: "Failed to send message")
            } finally {
                sendStateStore.setSending(false)
            }
```
（按 Step 1 核对结果调整字段名/类型；若消费端不支持则只恢复 text 并记录说明）

- [ ] **Step 3: 编译 + 相关单测**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL
Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.chat.ChatViewModelSendTest"`
Expected: 全过

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatSendDelegate.kt
git commit -m "fix: 发送失败时恢复草稿同时回填图片附件（修复附件丢失技术债）"
```

---

### Task 9: 全量验证

**Files:**
- 无代码改动（验证）

- [ ] **Step 1: 全量编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: androidTest 编译**

Run: `.\gradlew :app:compileDevDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 全量单测**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL（0 失败）

- [ ] **Step 4: grep 残留检查（技术债审计）**

Run: `rg -n "Optimistic|pendingPrompt|pendingMessage|UserMsgStatus|addOptimisticMessage|pending-" app/src -g "*.kt" | rg -v "pendingDeltas|queuedMessageIds|PendingDelta"`
Expected: 仅剩允许项（pendingDeltas/PendingDelta/queuedMessageIds）

- [ ] **Step 5: 真机验证清单（用户执行）**

构建安装 `.\gradlew :app:assembleDevDebug` → 安装到真机：
1. 发送消息：**无闪烁**（SSE 回显后消息直接出现，无中间态）
2. 连续发送 2-3 条：顺序正确
3. 断网发送：输入框恢复草稿（text + 附件）+ snackbar 提示
4. 退出重进：历史消息正确显示
5. revert / QUEUED 徽章 / 流式回复正常
6. 发送按钮呼吸圈（isSending）正常

- [ ] **Step 6: 收尾提交**

若有验证发现的修复 → 单独 commit；最终 `git status` 确认工作区干净（除预期文件）
