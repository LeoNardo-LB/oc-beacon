# Session Status FSM 设计

> **日期**: 2026-06-14
> **状态**: 设计确认，待实施
> **关联问题**: 会话已结束仍显示输出中 / 消息一直排队中 / 各类间歇性状态 bug
> **方案**: FSM（有限状态机）+ 多层容错

---

## 1. 背景与动机

### 1.1 用户报告的症状

1. **"会话已结束但仍显示输出中"** — 服务器已完成处理，客户端仍显示 Busy
2. **"消息一直显示排队中"** — Agent 已回复，但用户消息仍标记为 queued
3. **"间歇性出现/不出现"** — 大量状态相关的时序竞态，难以稳定复现

### 1.2 当前架构的根本缺陷

客户端有 **6 个独立的状态来源**，各自维护，互相不一致：

| 状态来源 | 维护者 | 问题 |
|---------|--------|------|
| `sessionStatuses` (SSE 推送) | SessionEventHandler | SSE 事件可能丢失，30秒才纠正 |
| `time.completed` (消息字段) | MessageEventHandler | 被 queuedMessageIds 用来推断"排队"，completion 丢失则永久排队 |
| `_pendingMessageIds` (发送追踪) | ChatViewModel.sendParts | 成功路径不清除 |
| `_isSending` (发送状态) | ChatViewModel.sendParts | 并发竞态 |
| `_rawMessagesList` (消息快照) | ChatViewModel.sseJob | 被 sessionMetaState 当触发器但不用，abortSession 后冻结 |
| `sseTimestamps` (SSE 时间戳) | SessionEventHandler | 仅用于 markSessionIdleProtected，没用于"新鲜度检测" |

UI 的"输出中"指示器看 `sessionStatus`，"排队中"看 `time.completed`，两者**不联动**——这就是 bug 的温床。

### 1.3 关键发现：isPrematureIdle 基于误判

客户端 `EventDispatcher.kt:72-87` 的 `isPrematureIdle` 注释说：
> "The server sends idle events between tool calls / agent dispatches, but the conversation is not truly done."

**服务器源码证明这是错的**。`prompt.ts:1215` 的 `runLoop` 是 `while(true)` 循环：
- 每次迭代顶部 `status.set(busy)`（prompt.ts:1223）
- 只有 `finish != "tool-calls"` 才退出循环（prompt.ts:1245-1263）
- 退出后才调用 `onIdle` → 发 idle

**工具调用间隙绝不会发 idle**。`isPrematureIdle` 在防一个不存在的问题，反而可能**错误地阻止合法的 idle 事件**（当 assistant message 的 `time.completed` 恰好为 null 时）。

---

## 2. 设计目标

1. **确立单一真相源**：会话状态（idle/busy/retry）由一个 FSM 管理，所有 UI 派生量从它派生
2. **主动容错**：不依赖 30 秒全局轮询，改为按需检测 + 精准校验
3. **移除误判防御**：`isPrematureIdle` 改为 L5 交叉验证（"阻止"变"校验"）
4. **可独立单元测试**：FSM 是纯函数，可穷举所有转换路径
5. **与服务器行为对齐**：基于 OpenCode 服务器源码（非推测）

---

## 3. 服务器行为事实（来自源码追踪）

| # | 事实 | 源码依据 |
|---|------|---------|
| 1 | 公开状态只有 3 种：`idle` / `busy` / `retry` | `session/status.ts:16-37` |
| 2 | 工具调用间隙不发 idle，整个 prompt 处理期间保持 busy | `prompt.ts:1215` runLoop while(true) |
| 3 | `prompt_async` 是 fire-and-forget，立即返回 204 | `handlers/session.ts:314` |
| 4 | 并发 prompt_async 排队不拒绝 | `runner.ts:196-216` |
| 5 | busy 是纯内存状态，服务器重启后丢失 | `run-state.ts` SynchronizedRef |
| 6 | 致命错误：halt() 发 session.error + session.status(idle) | `processor.ts:930,956` |
| 7 | retry 期间发 session.status(retry)，不改变 Runner 内部状态 | `processor.ts:1013` |

### 完整 SSE 事件时序（含工具调用）

```
POST /session/{id}/prompt_async → 204

session.status(busy)               ← runLoop iteration 1
message.updated                    ← user message
[session.next.agent.switched]      ← 如果 agent 切换
[session.next.model.switched]      ← 如果 model 切换
[session.next.prompted]
message.updated                    ← assistant message #1
session.next.step.started
session.next.text.started
session.next.text.delta (多次)
session.next.text.ended
session.next.tool.input.started
session.next.tool.input.delta (多次)
session.next.tool.input.ended
session.next.tool.called
session.next.tool.success / tool.failed
session.next.step.ended (finish=tool-calls)
    *** 不发 idle！session 保持 busy ***
session.status(busy)               ← runLoop iteration 2（幂等）
message.updated                    ← assistant message #2
session.next.step.started
session.next.text.*
session.next.step.ended (finish=stop)
session.status(idle)
session.idle
```

---

## 4. 架构概述：两层 FSM

### 4.1 为什么需要两层

服务器只有 3 种公开状态。客户端如果也只追踪这 3 种，UI 反馈会太粗糙（"正在处理"但不能区分是"等待响应"还是"流式输出"还是"工具调用中"）。

但核心状态机必须**简单稳定**，不能因为新增一个 `session.next.*` 事件类型就改变核心 FSM。

**解决方案**：两层架构。

```
┌──────────────────────────────────────────────────────────┐
│  Layer 1: 核心状态机（Core FSM）                          │
│  对齐服务器公开状态。简单、稳定。是"会话是否在处理"的     │
│  唯一真相源。                                            │
│                                                          │
│  状态: Idle / Busy / Retry                               │
│  转换: 由 session.status / session.idle / session.error  │
│        / sendParts / abortSession / REST校验 驱动         │
└──────────────────────────────────────────────────────────┘
          ↓ 当 Core = Busy 时，附加 Layer 2
┌──────────────────────────────────────────────────────────┐
│  Layer 2: 活动详情（Activity）                            │
│  派生状态，仅 Busy 时有意义。用于 UI 反馈和细粒度容错。  │
│  可以演进（新增 session.next.* 事件类型不影响 Core）。    │
│                                                          │
│  活动: Waiting / Streaming / ToolCalling / Compacting    │
│  转换: 由 session.next.* 事件驱动                        │
└──────────────────────────────────────────────────────────┘
```

### 4.2 完整状态定义

```kotlin
// Layer 1: 核心状态（对齐服务器 SessionStatus.Info）
sealed class CoreStatus {
    data object Idle : CoreStatus()
    data object Busy : CoreStatus()
    data class Retry(
        val attempt: Int,
        val message: String,
        val nextRetryAt: Long
    ) : CoreStatus()
}

// Layer 2: 活动详情（仅 Busy 时有意义）
sealed class Activity {
    data object Waiting : Activity()        // 等 assistant message 创建
    data object Streaming : Activity()      // 接收文本流
    data class ToolCalling(
        val toolName: String?,
        val callId: String?
    ) : Activity()
    data object Compacting : Activity()     // 上下文压缩
}

// FSM 完整状态
data class SessionFSMState(
    val core: CoreStatus,
    val activity: Activity?,            // 仅 Busy 时非 null
    val lastEventAt: Long,              // 最后一次 SSE 事件时间戳（L2 用）
    val lastCoreTransitionAt: Long,     // 最后一次 Core 转换时间戳
    val savedActivity: Activity? = null // Compacting 时保存的前一个 Activity
)
```

**初始状态**：`SessionFSMState(core = Idle, activity = null, lastEventAt = now, lastCoreTransitionAt = now)`

---

## 5. 状态转换规则

### 5.1 Layer 1 Core 转换矩阵

| 当前状态 | 触发源 | 事件 | 新状态 | 备注 |
|---------|--------|------|--------|------|
| Idle | 客户端 | sendParts 成功 | Busy + Activity=Waiting | 乐观更新 |
| Idle | SSE | session.status(busy) | Busy + Activity=Waiting | 服务器确认 |
| Busy | SSE | session.status(busy) | Busy（幂等） | runLoop 多次迭代 |
| Busy | SSE | session.status(idle) | Idle + Activity=null | 正常完成 |
| Busy | SSE | session.idle | Idle + Activity=null | 兼容旧事件 |
| Busy | SSE | session.error | Idle + Activity=null | 致命错误 |
| Busy | SSE | session.status(retry) | Retry | 服务器重试 |
| Retry | SSE | session.status(busy) | Busy + Activity=Waiting | 重试开始 |
| Retry | SSE | session.status(idle) | Idle + Activity=null | 重试放弃 |
| Any | 客户端 | abortSession | Idle + Activity=null | 终端操作 |
| Any | REST 校验 | REST 说 idle | Idle | L3/L5 覆盖 |
| Any | REST 校验 | REST 说 busy | Busy + Activity=Waiting | L3 覆盖 |
| Any | REST 校验 | REST 说 retry | Retry | L3 覆盖 |

**关键设计**：
- `session.status(busy)` 幂等（服务器 runLoop 每次迭代都发）
- `abortSession` 终端操作，立即转 Idle（不等 SSE 确认）
- REST 校验最高优先级，可覆盖任何 SSE 推导的状态

### 5.2 Layer 2 Activity 转换（仅 Core=Busy 时）

| 当前 Activity | 事件 | 新 Activity | 备注 |
|--------------|------|-------------|------|
| null/Waiting | step.started | Waiting | 新 step 开始 |
| Waiting | text.started | Streaming | 文本生成开始 |
| Streaming | text.delta | Streaming（保持） | 持续流 |
| Streaming | text.ended | Streaming（保持） | 可能紧接 tool.input |
| Waiting/Streaming | tool.input.started | ToolCalling | 工具参数输入开始 |
| ToolCalling | tool.called | ToolCalling（保持） | 工具开始执行 |
| ToolCalling | tool.success/failed | ToolCalling（保持） | 等 step.ended |
| Any | step.ended (finish=tool-calls) | Waiting | 回等下一轮 |
| Any | step.ended (finish≠tool-calls) | 保持 | 等 Core 转 Idle |
| Any | compaction.started | Compacting | 保存前一个 Activity 到 savedActivity |
| Compacting | compaction.ended | 恢复 savedActivity | 压缩完成 |
| Core→Idle | (任何) | null | Core 回 Idle 时清空 |

**finish reason 获取**：`SessionNextEvent.StepEnded` 事件携带 finish 字段。如果缺失，通过"step.ended 后是否有新的 step.started"推断。

### 5.3 非法转换检测

| 转换 | 含义 | 响应 |
|------|------|------|
| Idle → Streaming（未经 Busy） | busy 事件丢失 | 标记 suspicious → 触发 L3 |
| Idle → ToolCalling（未经 Busy） | busy 事件丢失 | 标记 suspicious → 触发 L3 |
| Retry → Streaming（未经 Busy） | busy 事件丢失 | 标记 suspicious → 触发 L3 |

**注意**：Busy → Idle → Busy → Idle 是合法的（多次 prompt 处理）。

---

## 6. 容错机制（5 层）

```
L1: SSE 事件驱动（主源）
    每个事件 → FSM.transition(state, event) → newState
    非法转换 → 标记 suspicious → 交给 L3

L2: 事件新鲜度检测（守护协程，每 5 秒扫描）
    条件: Core=Busy && (now - lastEventAt) > 15秒
    动作: 触发 L3 针对该 session 的 REST 校验

L3: REST 按需校验（精准，非全局轮询）
    调用: GET /session/status（获取所有 session 状态）
    动作: 用 REST 结果强制覆盖 FSM 状态
    权威性: REST 直接查询服务器内存，是 ground truth

L4: 心跳超时（已有机制，保留）
    条件: SSE heartbeat 超时 (HEARTBEAT_TIMEOUT_MS)
    动作: 全量 REST 同步 + recoverMessages

L5: 状态一致性交叉验证（取代 isPrematureIdle）
    条件: Core=Idle 但存在 incomplete assistant message（time.completed == null）
    旧机制: isPrematureIdle → 阻止 idle 事件（导致卡 Busy）
    新机制: 触发 L3 REST 校验
      - REST 说 idle → 接受 Idle，UI 层面忽略 incomplete（见下方说明）
      - REST 说 busy → 恢复 Busy（服务器仍在处理）
    本质: 从"阻止"变为"校验"
    
    "UI 层面忽略 incomplete" 的含义：
    - 不修改 MessageEventHandler 的数据（time.completed 是服务器的事实记录）
    - FSM=Idle 时，queuedMessageIds 强制为空（第 8.2 节）
    - "输出中"指示器看 FSM Core，不看 message completion（第 8.1 节）
    - 即：completion 事件丢失不影响 UI，因为 FSM 已通过 REST 确认 idle
```

### 6.1 L2 阈值选择

推荐 **15 秒**。服务器在 LLM 处理期间会持续发 `session.next.*` 事件（text.delta/tool.input.delta），如果 15 秒完全没有事件，很可能是 SSE 断连或服务器卡住。

### 6.2 与当前 30 秒全局轮询的对比

| 维度 | 当前（30 秒全局轮询） | FSM（L2 按需 + L3 精准） |
|------|---------------------|------------------------|
| 触发方式 | 定时全局 | 按需（仅 Busy 且 stale） |
| 延迟 | 最长 30 秒 | 最长 15 秒 |
| 服务器负载 | 每 30 秒全量查询 | 仅在需要时查询 |

---

## 7. 代码整合方案

### 7.1 新增模块

#### `SessionStatusFSM.kt`（domain/model 层）

纯函数状态机，无副作用，可独立单元测试。

```kotlin
object SessionStatusFSM {
    data class TransitionResult(
        val newState: SessionFSMState,
        val isSuspicious: Boolean,
        val clearIncompleteMarkers: Boolean
    )
    
    fun transition(state: SessionFSMState, event: FsmEvent): TransitionResult
    fun initialState(): SessionFSMState
}

sealed class FsmEvent {
    // Core 事件
    data class SseStatus(val status: CoreStatus) : FsmEvent()
    data object SseIdle : FsmEvent()
    data class SseError(val message: String) : FsmEvent()
    data object ClientSendParts : FsmEvent()
    data object ClientAbort : FsmEvent()
    data class RestValidation(val status: CoreStatus) : FsmEvent()
    
    // Activity 事件（session.next.*）
    data class StepStarted(val sessionId: String) : FsmEvent()
    data class TextStarted(val sessionId: String) : FsmEvent()
    data class ToolInputStarted(val sessionId: String, val toolName: String?, val callId: String?) : FsmEvent()
    data class StepEnded(val sessionId: String, val finish: String?) : FsmEvent()
    data object CompactionStarted : FsmEvent()
    data object CompactionEnded : FsmEvent()
}
```

#### `SessionStatusManager.kt`（data/repository 层，@Singleton）

有状态协调器，持有 FSM Map + 容错协程。

```kotlin
@Singleton
class SessionStatusManager @Inject constructor(
    private val sessionRepository: SessionRepository,  // L3 REST 校验
    private val messageEventHandler: MessageEventHandler,  // L5 交叉验证（读 messages StateFlow）
    @ApplicationScope private val appScope: CoroutineScope  // 容错协程的生命周期
) {
    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())
    
    // UI 读取接口
    val statusFlow: StateFlow<Map<String, CoreStatus>>   // 派生自 _fsmStates
    val activityFlow: StateFlow<Map<String, Activity?>>   // 派生自 _fsmStates
    
    // 事件入口
    fun onSseEvent(event: SseEvent, sessionId: String)
    fun onSendParts(sessionId: String)
    fun onAbort(sessionId: String)
    fun onRestValidation(sessionId: String, status: CoreStatus)
    
    // Session 生命周期
    fun clearSession(sessionId: String)  // session.deleted 时清理
    
    // 容错协程（init 时启动）
    private fun startStalenessGuard()   // L2: 每 5 秒扫描
    private suspend fun validateSession(sessionId: String)   // L3
    private suspend fun crossValidate(sessionId: String)     // L5
    
    // 重连恢复
    suspend fun restoreFromRest()  // SSE 重连后全量 REST 同步
}
```

### 7.2 EventDispatcher 整合

```
SSE 事件流
    ↓
EventDispatcher.processEvent()
    ├─ messageHandler.handle(event)
    ├─ permissionHandler.handle(event)
    ├─ questionHandler.handle(event)
    ├─ miscHandler.handle(event)
    ├─ sessionNextHandler.handle(event)   // tool progress 保留
    └─ sessionStatusManager.onSseEvent(event, sessionId)  // ★ 状态决策
```

**关键**：EventDispatcher 保持"分发"职责，不承担状态决策。

### 7.3 替换/移除/保留清单

| 现有代码 | 操作 | 理由 |
|---------|------|------|
| `SessionEventHandler._sessionStatuses` | **替换** → `SessionStatusManager.statusFlow` | 状态来源统一 |
| `SessionEventHandler.handleSessionStatus/Idle` | **转发** → `SessionStatusManager.onSseEvent` | 不自己写状态 |
| `EventDispatcher.isPrematureIdle` | **移除** | 基于误判，L5 取代 |
| `EventDispatcher.hasIncompleteAssistant` | **移入** `SessionStatusManager` L5 | 职责归位 |
| `SseConnectionManager.launchPeriodicStatusSync` | **移除** | L2+L3 取代 |
| `ChatViewModel.sessionMetaState` 的 `_rawMessagesList` 触发器 | **移除** | 无效触发器 |
| `SessionEventHandler._sessions` | **保留** | session 列表管理独立 |
| `SessionEventHandler._sseTimestamps` | **整合** → `SessionFSMState.lastEventAt` | 复用 |
| `markSessionIdleProtected` | **保留**（可能移入 Manager） | abort 场景 |
| `MessageEventHandler` | **保留** | 消息管理独立 |
| `SseConnectionManager.recoverMessages` | **保留** | 重连恢复 |
| `SessionNextEventHandler` 的 toolProgress | **保留** | 工具进度详情 |

### 7.4 同时修复的确定性 bug

| Bug | 修复方式 |
|-----|---------|
| `queuedMessageIds` 仅看 time.completed | 从 FSM CoreStatus 派生（Core=Idle 时强制清空） |
| `abortSession` 取消 sseJob 不重启 | 重启 `startObservingMessages()` |
| `messageListState` 不过滤空 parts assistant | 添加过滤（与 startObservingMessages 一致） |
| `_pendingMessageIds` 成功路径不清除 | 成功路径也清除 |
| `sendParts` 混用 model 来源 | 统一从 `modelConfigState.value` 读取 |

---

## 8. 派生量从 FSM 派生

### 8.1 "输出中"指示器

```kotlin
val isProcessing: StateFlow<Boolean> = sessionStatusManager.statusFlow
    .map { it[sessionId] is CoreStatus.Busy || it[sessionId] is CoreStatus.Retry }
```

**不再卡住**：SSE idle 丢失 → L2 在 15 秒内检测 → L3 REST 校验纠正。

### 8.2 "排队中"消息

```kotlin
val queuedMessageIds: StateFlow<Set<String>> = combine(
    sessionStatusManager.statusFlow,
    chatRepository.getMessagesFlow(sessionId)
) { statuses, messages ->
    val status = statuses[sessionId]
    if (status !is CoreStatus.Busy && status !is CoreStatus.Retry) {
        emptySet()  // ★ Core=Idle 时强制清空
    } else {
        val pendingIdx = messages.indexOfLast { 
            it is Message.Assistant && it.time.completed == null 
        }
        if (pendingIdx >= 0) {
            messages.drop(pendingIdx + 1).filter { it.isUser }.map { it.id }.toSet()
        } else emptySet()
    }
}
```

**不再永久排队**：FSM 确认 Idle 时排队标记立即消失。最坏情况 15 秒延迟。

### 8.3 当前活动详情（新增 UI 能力）

```kotlin
val currentActivity: StateFlow<Activity?> = sessionStatusManager.activityFlow
    .map { it[sessionId] }
```

UI 可显示精确反馈：
- `Waiting` → "思考中..."
- `Streaming` → PulsingDotsIndicator
- `ToolCalling("read")` → "正在调用 read..."
- `Compacting` → "压缩上下文中..."

---

## 9. 迁移路径（渐进式）

| Phase | 内容 | 验证标准 |
|-------|------|---------|
| **P1** | 新增 `SessionStatusFSM` + `SessionStatusManager`，并行运行 | FSM 单元测试通过 + 日志对比现有状态一致 |
| **P2** | `ChatViewModel.sessionMetaState` 从 `statusFlow` 读取 | compileDevDebugKotlin 通过 + UI 手动测试状态显示正确 |
| **P3** | 移除 `isPrematureIdle`，改为 L5 交叉验证 | 单元测试 + 手动测试（多轮工具调用场景） |
| **P4** | 移除 `launchPeriodicStatusSync`，改为 L2+L3 | 手动断网测试 + 15 秒内状态恢复 |
| **P5** | 修复 4 个确定性 bug | 逐个验证 |

每个 Phase 独立可验证，可回滚。

---

## 10. 测试策略

### 10.1 FSM 单元测试（纯函数，高覆盖率）

```kotlin
class SessionStatusFSMTest {
    // 穷举所有合法转换
    @Test fun idle_to_busy_on_sendParts()
    @Test fun idle_to_busy_on_sse_status_busy()
    @Test fun busy_to_idle_on_sse_status_idle()
    @Test fun busy_to_retry_on_sse_status_retry()
    @Test fun retry_to_busy_on_sse_status_busy()
    @Test fun busy_remains_busy_on_duplicate_status_busy()  // 幂等
    @Test fun any_to_idle_on_abort()
    
    // Activity 转换
    @Test fun waiting_to_streaming_on_text_started()
    @Test fun streaming_to_toolcalling_on_tool_input_started()
    @Test fun toolcalling_to_waiting_on_step_ended_tool_calls()
    @Test fun activity_resets_on_core_idle()
    
    // 非法转换
    @Test fun idle_to_streaming_is_suspicious()
    @Test fun suspicious_triggers_rest_validation()
}
```

### 10.2 SessionStatusManager 集成测试

```kotlin
class SessionStatusManagerTest {
    @Test fun staleness_guard_triggers_rest_validation_after_15s()
    @Test fun rest_validation_overrides_sse_state()
    @Test fun cross_validation_detects_incomplete_message_on_idle()
    @Test fun abort_immediately_sets_idle()
}
```

### 10.3 手动测试场景

1. **正常对话**：发消息 → 看到输出中 → 流式输出 → 完成
2. **多轮工具调用**：agent 调用多个工具 → 状态保持输出中 → 最终完成
3. **断网恢复**：对话中断网 → 15 秒内状态恢复正确
4. **abort 后重发**：abort → 立即显示空闲 → 重新发消息正常
5. **服务器重启**：对话中服务器重启 → REST 校验恢复 idle

---

## 11. 风险与缓解

| 风险 | 概率 | 缓解 |
|------|------|------|
| FSM 状态定义与服务器行为不完全对齐 | 低 | 基于源码追踪，非推测；L3 REST 是兜底 |
| L2 守护协程耗电 | 低 | 5 秒扫描 + 仅 Busy session 才触发 REST |
| 并行运行期（P1）状态不一致 | 中 | P1 仅日志对比，不影响 UI |
| 移除 isPrematureIdle 后出现状态闪烁 | 低 | L5 交叉验证 + L3 REST 校验兜底 |
| StepEnded 的 finish 字段缺失 | 中 | 通过后续 step.started 推断 |

---

## 12. 未来扩展

- **Prompt Queue 可视化**：服务器有 prompt 排队机制（ensureRunning awaitDone），未来可在 Activity 中增加 `Queued` 状态
- **子 Agent（Subtask）追踪**：服务器 subtask 通过同一 sessionID 发事件，未来可在 Activity 中区分父/子 agent
- **Retry 倒计时 UI**：`CoreStatus.Retry` 已携带 `nextRetryAt`，可显示倒计时
