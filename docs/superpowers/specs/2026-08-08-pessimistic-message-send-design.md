# 悲观消息重构设计（Pessimistic Message Sending）

> 日期：2026-08-08 · 状态：已确认 · 类型：重构/根治闪烁

## 1. 背景与动机

用户发送消息后界面会"闪一下"（整个列表跳动 + 气泡内容下沉/顶起）。经真机 logcat 取证，根因是**乐观消息 → 服务器确认消息的切换**：

- 发送瞬间插入乐观占位（`pending-*` id）→ REST 204 后移除 → SSE 回显服务器消息（不同 id）→ LazyColumn item 销毁重建
- 方案 D（预生成服务器兼容 id）后条目不重建，但 SSE `MessageUpdated` 确认时 parts 为空（`parts 1→0→1`）→ 气泡内容短暂消失/恢复

**用户决策**：放弃乐观消息，采用**悲观消息**（与 opencode 官方 web 端一致）——发送后不显示占位，等待服务器 SSE 回显才出现在列表；发送失败恢复草稿到输入框 + snackbar 提示。**全面清理乐观体系，不留技术债。**

## 2. 目标与非目标

### 目标
- 根治发送闪烁（无任何乐观→服务器中间态）
- 删除乐观体系 4 层全部代码（UI store / data 层空操作链 / PendingPrompt 持久化对账 / messageID 链）
- 失败恢复闭环：草稿（含图片附件）+ snackbar 提示
- 保留独立于乐观的正确机制（QUEUED 徽章、SSE part 批处理、summary 播种、回归检测）

### 非目标
- 不重写 MessageDataDelegate combine 管道 / ChatViewModel 门面 / SSE 驱动架构（已成熟，SSE 铁律保护）
- 不修改 SSE 流式渲染、滚动补偿（ScrollCompensation/LazyListReflection）
- 不改变 revert/删除消息等其他功能

## 3. 发送数据流（悲观时序）

```
用户点发送
  → SendStateStore.setSending(true)      （防双击 RS-007 + 发送按钮呼吸圈）
  → POST /prompt_async → 204              （输入框已同步清空）
  → setSending(false)                      （可连续发送新内容）
  → 服务器 SSE MessageUpdated 回显
  → EventDispatcher → 消息缓存 → combine → UI 显示（消息直接以服务器权威出现）
失败（REST 异常）
  → 恢复草稿到输入框（text parts + 图片附件）
  → errorSink → interactionState.error → snackbar（有消息时）/ ChatErrorState（空列表）
```

- 发送按钮禁用（isSending）仅覆盖 REST 受理前（204 即恢复）——允许连续发送（用户既有习惯，与 opencode web 一致）
- 消息排序：服务器回显后按 `time.created` 排序（combine 现有逻辑），连续发送多条按服务器入库顺序显示

## 4. 删除清单

### 4.1 整个文件删除（9 个）

| 文件 | 内容 |
|------|------|
| `domain/model/OptimisticMessage.kt` | 乐观消息模型（12 行） |
| `domain/model/UserMsgStatus.kt` | 状态枚举（Sending/Sent/Failed，仅乐观渲染用） |
| `domain/model/PendingPromptRecord.kt` | 持久化记录模型 |
| `domain/repository/PendingPromptRepository.kt` | 接口（5 方法） |
| `data/repository/PendingPromptRepository.kt` | Impl（文件 JSON 存储 `pending_prompts.json`） |
| `data/repository/PendingPromptReconciliation.kt` | missingPendingPromptIds 对账纯函数 |
| `test/.../ui/screens/chat/OptimisticMessageStoreTest.kt` | 测试已删的 store |
| `test/.../data/repository/MissingPendingPromptIdsTest.kt` | 对账纯函数测试 |
| `androidTest/.../fakes/FakePendingPromptRepository.kt` | 接口删除后无法编译 |

（注：`OptimisticMessageStore.kt` 已于本会话删除，由 `SendStateStore.kt` 替代——见 §5.1）

### 4.2 主代码局部删改

| 文件 | 删除/修改 | 位置 |
|------|----------|------|
| `ChatViewModel.kt` | import ×3（PendingPromptRecord/Repository/missingPendingPromptIds）；构造参数 `pendingPromptRepository`；init 恢复块；对账循环块（含 confirmMessage 分支）；sendDelegate 构造参数调整（§5.3）；`retrySendMessage` 门面；注释 "PENDING_RECONCILE_MIN_AGE_MS" 字样 | L11,13,15 / L47 / L77 / L337-341 / L374-402 / L476-493 / L501 |
| `MessageDataDelegate.kt` | `optimisticStore` → `sendStateStore`；combine 源删 2（pendingMessageIds/pendingMessages，11→9 源，args 索引重排：statuses 8→7、progressList 9→8）；删 args[7]/args[10] 解析；删乐观合并块（activePending/pendingByServerId，mergedChatMessages 直通 chatMessages）；MessageListState 构造删 pending 字段；MsgDiag [combine] 日志删 `pending=` 字段；interactionState 的 `optimisticStore.isSending` → `sendStateStore.isSending`；**新增 `reportError(msg)` 暴露 _error setter** | L111-114 / L138,141 / L156,168 / L228-256 / L269-270 / L273-281 / L303 |
| `ChatUiState.kt` | import OptimisticMessage；MessageListState.pendingMessageIds/pendingMessages 字段；ChatUiState.pendingMessageIds 字段；`PENDING_RECONCILE_MIN_AGE_MS` 常量 | L7 / L26-27 / L147-148 / L170-171 |
| `ChatStateAggregator.kt` | `pendingMessageIds` 组装行 | L189 |
| `MessageCard.kt` | `pendingStatus` 参数 + 透传 + import | L5 / L16 / L36 |
| `MessageCardUser.kt` | `pendingStatus` 参数；`when(pendingStatus)` 重构（删 Sending/Failed/Sent 分支，**保留 QUEUED**，提升为顶层 if）；`onRetry` 死参数 | L39 / L62 / L63 / L210-251 |
| `ChatMessageList.kt` | `pendingStatus` 匹配行；`onRetry = retrySendMessage(...)` 行 | L609 / L610 |
| `ChatRepository.kt` | `addOptimisticMessage` 接口（无调用方） | L226-230 |
| `ChatRepositoryImpl.kt` | `override addOptimisticMessage` 转发 | L374-376 |
| `EventDispatcher.kt` | `addOptimisticMessage` 转发 | L454-455 |
| `MessageEventHandler.kt` | `addOptimisticMessage` 空方法体（"故意为空"）；`pending-*` 过滤 5 处（setMessages 条件过滤/DIAG 计数/依赖乐观的 warning/mergeMessages 过滤/replaceMessages 无条件过滤） | L39-51 / L363 / L378 / L381 / L383-385 / L428-430 / L450-454 |
| `di/DomainModule.kt` | PendingPromptRepository import ×2 + `bindPendingPromptRepository` 绑定 | L12,20 / L56-57 |
| `androidTest/.../di/FakeDomainModule.kt` | import ×2 + 绑定 | L14,27 / L61 |
| `androidTest/.../fakes/FakeChatRepository.kt` | `override addOptimisticMessage`（其 promptAsync 无需改——接口还原为 7 参后自然匹配） | L233-236 |
| `docs/architecture.md` | 删除 "PendingPromptRepository（基于文件的 JSON, 乐观消息持久化）" 描述 | L22 |

### 4.3 messageID 链还原（悲观下无消费方）

| 文件 | 还原内容 |
|------|---------|
| `data/dto/request/ChatRequests.kt` | PromptRequest 删 `messageID` 字段 |
| `data/api/message/MessageApi.kt` | promptAsync 接口 + 实现删 `messageID` 参数 |
| `domain/repository/ChatRepository.kt` | promptAsync 删 `messageID` 参数 |
| `data/repository/ChatRepositoryImpl.kt` | promptAsync 删 `messageID` 参数 |
| `domain/usecase/SendMessageUseCase.kt` | sendPrompt 删 `messageID` 参数 |
| `test/.../SendMessageUseCaseTest.kt` / `SubmitAnnotationsUseCaseTest.kt` | mock 8 参 → 7 参，coVerify 还原 |

## 5. 组件改造

### 5.1 SendStateStore（已建，保留）
仅 `isSending: StateFlow<Boolean>` + `setSending`。由 MessageDataDelegate 持有并暴露（`internal val sendStateStore`），ChatViewModel 注入 sendDelegate——与旧 optimisticStore 模式一致。

### 5.2 ChatSendDelegate（已重写为悲观版，保留微调）
- 构造参数：`sendStateStore` + `errorSink` + `draftDelegate`（无 pendingPromptRepository/optimisticStore）
- sendParts：防双击 → setSending(true) → POST → 失败恢复草稿 + errorSink → finally setSending(false)
- **失败恢复补全附件**（技术债修复）：catch 中除 text 外，将 file parts 转为 `RevertedDraftPayload.attachmentUris` 回填（现状恒空，图片附件失败后丢失）。格式：`attachmentUris = parts.filter { it.type == "file" }.mapNotNull { it.url }`（与 DraftInputDelegate/ChatInputBar 现有附件恢复消费逻辑对齐，实现时核对 `RevertedDraftPayload` 定义与附件恢复路径）

### 5.3 ChatViewModel
- 构造参数：删 `pendingPromptRepository`（27 → 26 参）
- sendDelegate 构造：`sendStateStore = messageData.sendStateStore`、`errorSink = { messageData.reportError(it) }`
- init：删恢复块 + 对账循环（tokenStatsTracker.collect 内保留消息统计逻辑）
- 删 `retrySendMessage` 门面

### 5.4 MessageCardUser 渲染
```
状态区：when(pendingStatus) { Sending/Failed/Sent/null }  →  if (isQueued) { QUEUED 徽章 }
```
悲观化后无 Sending/Failed/Sent（消息直接以服务器权威出现），仅保留 QUEUED（来自 FSM 状态，与乐观无关）。

## 6. 保留项（勿动）

| 项 | 说明 |
|----|------|
| `ChatScreen.kt` AnimatedVisibility 移除 | 空会话首条消息 fadeIn 闪烁修复（已提交前改动，保留） |
| `ChatScrollController.kt` requestScrollToItem | 新消息同帧位置锚定，消除"偏移一帧再拉回"（保留） |
| `forceScrollToBottom()` 设 autoScrollEnabled=true | 中间发送强制跳底（保留） |
| `ScrollCompensation.kt` / `LazyListReflection` | SSE 流式高度补偿（保留） |
| `MessageEventHandler` pendingDeltas | SSE part delta 48ms 批处理（与乐观无关） |
| `MessageEventHandler` summary 播种 | 用户消息无 part delta 时的内容兜底（保留） |
| `MessageEventHandler` unexpected user count warning | 通用回归检测（阈值 >1，不依赖乐观） |
| `Part.Text.synthetic/ignored` | OpenCode API 原生字段 |
| QUEUED 徽章 / queuedMessageIds | FSM 状态派生（保留） |

## 7. 测试策略

| 文件 | 处置 |
|------|------|
| `OptimisticMessageStoreTest.kt` / `MissingPendingPromptIdsTest.kt` | 删除 |
| `ChatViewModelSendTest.kt` | 重写悲观语义：isSending 翻转（发送前 true → 完成后 false）、失败恢复草稿（restoredDraft + errorSink）、sendPrompt 7 参 mock、防双击（第二次调用被忽略） |
| `ChatViewModelDeleteTest/QueuedTest/PermissionTest/StreamingTest` | 机械适配：删 pendingPromptRepository mock 声明 + 构造参数 |
| `SendMessageUseCaseTest` / `SubmitAnnotationsUseCaseTest` | messageID 还原：mock 8→7 参 |
| `FakeChatRepository`（androidTest） | 删 addOptimisticMessage override |
| `ChatRepositoryImplTest` | 无需改（3 参位置调用不受影响） |

## 8. 验证计划

1. `.\gradlew :app:compileDevDebugKotlin` → 编译通过
2. `.\gradlew :app:compileDevDebugAndroidTestKotlin` → androidTest 编译通过
3. `.\gradlew :app:testDevDebugUnitTest --rerun` → 全量单测
4. 真机验证（dev 版）：
   - 发送消息：**无闪烁**（消息在 SSE 回显后出现，无中间态）
   - 连续发送 2-3 条：顺序正确、无错乱
   - 失败场景（断网发送）：输入框恢复草稿（text + 附件）+ snackbar 提示
   - 历史加载：退出重进后消息正确显示
   - revert / QUEUED 徽章 / 流式回复：正常

## 9. 风险与缓解

| 风险 | 缓解 |
|------|------|
| combine 参数索引重排（11→9 源）出错 | 按清单精确调整；编译 + 单测兜底；MsgDiag 日志验证 |
| `pending-*` 过滤删除后未来误用该前缀 | 新架构无注入路径；PR 记录放弃该约定 |
| ChatViewModel 26 参构造的测试适配遗漏 | 5 个测试文件逐一按行号适配；编译全量兜底 |
| 失败恢复附件的 `RevertedDraftPayload` 消费端兼容 | 检查 ChatInputBar 附件恢复消费逻辑后实现 |
