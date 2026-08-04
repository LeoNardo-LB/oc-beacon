# 会话状态管理系统重构设计

> **状态**：已与用户对齐，待 review 后转入 writing-plans
> **日期**：2026-06-30
> **决策**：A（统一单一状态源）+ A1（会话级+消息级全统一）+ B2（转换矩阵+日志+in-memory 历史）+ 方案 2（一次性重写）

---

## 1. 背景与目标

### 1.1 背景

会话状态同步接连暴露多个 bug：directory 传播缺失（Bug1 目录树无活跃）、FSM 自愈失效（Bug2 进度条卡死）、缺席语义不一致（已结束会话卡 Busy）、`markSessionIdle` 漏 Part.Text、注释与代码不符、死代码。逐个打补丁已捉襟见肘，根因是**状态管理系统的结构性缺陷**。

### 1.2 核心目标

| 维度 | 目标 |
|------|------|
| 统一 | 单一 source of truth，消除两套状态机并行漂移 |
| 完整 | 转换矩阵穷举所有 (状态, 事件) 组合，无遗漏 case |
| 可控 | 纯函数 FSM + 明确不变量 + 闭环自愈 |
| 可追溯 | in-memory 转换历史 + 结构化日志，可诊断"为何卡 Busy" |

### 1.3 范围

- **在范围**：会话状态（core）+ 消息流式状态（activity）的统一管理；UI 读取迁移；恢复路径合一
- **不在范围**：持久化事件存储；服务端 sync/history 端点集成；多会话并发调度

---

## 2. 现状分析

### 2.1 三层状态源（并行，source of truth 不明确）

| 层 | 持有者 | 数据 | 驱动 |
|----|--------|------|------|
| ① `sessionStatuses` | SessionEventHandler | `Map<sid, SessionStatus>` + `_sseTimestamps` | SSE 直写 + 3 个 REST 方法（语义不同） |
| ② FSM `statusFlow` | SessionStatusManager | `Map<sid, SessionFSMState>` | SSE 经 FSM + REST validation + staleness |
| ③ `message.time`/`part.time` | MessageEventHandler | 消息/part 时间戳 | SSE message 事件 + REST replace + markSessionIdle |

**核心矛盾**：①②是两套会话状态机并行，谁是权威不明确；③隐式表达"流式中"。

### 2.2 6 条恢复路径（逻辑分散）

preLoadSessions / loadSessions / syncSessionStatus / refreshAndSync / recoverMessages / triggerRestValidation——各自处理 directory、缺席、FSM 同步，逻辑重复且曾不一致。

### 2.3 已修复的结构缺陷（本次重构的契机）

directory 传播缺失、缺席语义不统一、markSessionIdle 漏 Text、注释代码不符、StreamingStateTracker 死代码、两层割裂。

---

## 3. 架构设计

### 3.1 组件职责重新划分

```
┌─────────────────────────────────────────────────────────────────┐
│                    SessionStateService (新)                       │
│                  ═══ 唯一 source of truth ═══                     │
│  • 会话级 core (Idle/Busy/Retry)                                 │
│  • 消息级 activity (null/Waiting/Streaming/ToolCalling/Compacting)│
│  • 纯函数 FSM + 转换矩阵                                          │
│  • in-memory 转换历史 (per-session ring buffer)                  │
│  • staleness guard + REST 兜底闭环                                │
│  • directoryResolver (统一 directory 路由)                       │
│  输出: statusFlow / activityFlow / historyFlow                   │
└─────────────────────────────────────────────────────────────────┘
       ▲ SSE事件          ▲ REST校验         ▲ 客户端动作
┌──────┴──────┐    ┌──────┴───────┐   ┌──────┴────────┐
│EventDispatcher│   │syncFromRest()│   │onClientSend/  │
│ (转发,不存状态)│   │(聚合多dir,   │   │Abort()        │
│              │    │缺席=idle)    │   │               │
└──────┬──────┘    └──────────────┘   └───────────────┘
       │
┌──────┴───────────────┐   ┌────────────────────────────┐
│SessionEventHandler   │   │MessageEventHandler         │
│ (退化: 只存元数据)    │   │ (不变: 存内容)              │
│ • sessions 列表       │   │ • messages/parts           │
│ • serverSessions 映射 │   │ • time 元数据(排序/计时用)  │
│ • diffs/vcs/project  │   │ • UI 不再读 time 判流式     │
│ ❌ 移除 _sessionStatuses│  │   (改读 activityFlow)      │
│ ❌ 移除 _sseTimestamps │   │                            │
└──────────────────────┘   └────────────────────────────┘
```

### 3.2 数据流闭环

```
输入层:  SSE事件 ─┐
         REST校验 ─┼─▶ SessionStateService ─▶ FSM(纯函数) ─▶ 状态输出
         客户端动作┘            ▲                    │
                                │                    ▼
                           反馈闭环 ◀────────── UI 消费
                      (staleness guard          (目录树/进度条/
                       读 statusFlow            流式动画)
                       发现 Busy 卡住
                       → 触发 REST → 回流)
```

### 3.3 设计原则

1. **状态只在一处**：SessionStateService 是 status/activity 的唯一持有者
2. **写入统一入口**：所有变更经 SessionStateService 的少数方法，内部走 FSM
3. **闭环自愈**：staleness guard 读自己的输出发现异常 → 触发 REST → 回流修正
4. **可追溯**：每次 FSM 转换记 TransitionRecord

---

## 4. FSM 状态空间与转换矩阵

### 4.1 状态空间

**Core（会话级，互斥）**：Idle / Busy / Retry
**Activity（消息级，仅 Busy 时非 null）**：null / Waiting / Streaming / ToolCalling(tool, callId) / Compacting（保存旧 activity）

**有效状态组合（7 种）**：

| Core | 允许的 Activity |
|------|----------------|
| Idle | null（唯一） |
| Busy | null / Waiting / Streaming / ToolCalling / Compacting |
| Retry | null |

**不变量（FSM 强制）**：
- `Core == Idle ⟹ Activity == null`
- `Core == Retry ⟹ Activity == null`
- `Activity != null ⟹ Core == Busy`

### 4.2 事件清单（14 种）

| 类别 | 事件 | 来源 |
|------|------|------|
| 客户端 | ClientSendParts / ClientAbort | 用户操作 |
| SSE 核心 | SseStatus(Busy/Idle/Retry) / SseIdle / SseError | session.status/idle/error |
| SSE 活动 | StepStarted / TextStarted / TextDelta / TextEnded / ToolInputStarted(tool,cid) / StepEnded(finish) / CompactionStarted / CompactionEnded | session.next.* |
| REST | RestValidation(status) | REST 校验回流 |

### 4.3 转换矩阵——Core 维度

| 当前 Core | 事件 | 新 Core | Activity 处理 | side-effect |
|-----------|------|---------|--------------|-------------|
| Idle | ClientSendParts | Busy | →Waiting | — |
| Busy/Retry | ClientSendParts | Busy(保持) | 刷新 lastEventAt | — |
| Any | ClientAbort | Idle | →null | force-complete |
| Any | SseStatus(Busy) | Busy | 首次转 Busy→Waiting；否则保持 | — |
| Any | SseStatus(Idle) | Idle | →null | force-complete（若有 incomplete） |
| Any | SseStatus(Retry) | Retry | →null | — |
| Any | SseIdle | Idle | →null | force-complete（若有 incomplete） |
| Any | SseError | Idle | →null | 记录错误 |
| Any | RestValidation(s) | = s | s 为 Idle→null；Busy→Waiting | force-complete if Idle |

### 4.4 转换矩阵——Activity 维度（仅 Core==Busy 时生效）

| 当前 Activity | 事件 | 新 Activity | 备注 |
|---------------|------|-------------|------|
| Any | StepStarted | Waiting | 新步骤开始 |
| Any | TextStarted | Streaming | 文本输出开始 |
| Streaming | TextDelta | Streaming(保持) | 仅刷新时间戳 |
| Any | TextEnded | Waiting | 文本结束，等下一步/idle |
| Any | ToolInputStarted(t,cid) | ToolCalling(t,cid) | 工具调用 |
| ToolCalling | StepEnded("tool-calls") | Waiting | 工具完成，等下一步 |
| Any | StepEnded(其它 finish) | 保持 | 等 Core→Idle |
| Any | CompactionStarted | Compacting(savedOld) | 压缩，保存旧值 |
| Compacting | CompactionEnded | 恢复 savedOld | 压缩结束 |

### 4.5 异常 case 检测（suspicious → 触发 REST 校验）

| 场景 | 判定 | 处理 |
|------|------|------|
| Core==Idle 收到活动事件 | suspicious（丢了 Busy） | 不改状态，记 history，触发 REST |
| Core==Busy 且 activity 与 incoming 矛盾 | suspicious | 接受新 activity，记 history |
| staleness: Busy 且 lastEventAt > 15s | 超时 | 触发 REST（不影响状态） |
| RestValidation(Idle) 但本地有 incomplete | 矛盾 | 强制 idle + force-complete（信任 REST） |

### 4.6 force-complete 触发时机

Core 转为 Idle 时（任何路径：SseIdle/SseStatus(Idle)/SseError/ClientAbort/RestValidation(Idle)），无条件触发 `MessageEventHandler.markSessionIdle`（force-complete 所有 part.time.end + message.completed）。保证消息元数据与会话状态永远一致。

---

## 5. SessionStateService API 设计

### 5.1 接口

```kotlin
@Singleton
class SessionStateService @Inject constructor(
    @ApplicationScope appScope: CoroutineScope,
    sessionRepoProvider: Provider<SessionRepository>,
    directoryResolver: DirectoryResolver,
    incompleteChecker: IncompleteAssistantChecker,
    messageForceCompleter: MessageForceCompleter,
) {
    val statusFlow:   StateFlow<Map<String, SessionStatus>>
    val activityFlow: StateFlow<Map<String, SessionActivity?>>
    val historyFlow:  StateFlow<Map<String, List<TransitionRecord>>>

    fun onSseEvent(event: SseEvent, sessionId: String)
    fun onClientSendParts(sessionId: String)
    fun onClientAbort(sessionId: String)
    fun onRestValidation(sessionId: String, status: SessionStatus)
    suspend fun syncFromRest(projects: List<Project>): SyncResult

    fun setServerId(serverId: String)
    fun clearSession(sessionId: String)
    fun clearForServer(sessionIds: Set<String>)
    fun clearAll()
}
```

### 5.2 事件流水线（统一内部流程）

```
onXxx(sessionId, event)
    ▼
applyTransition(sessionId, event)
    ① 读当前 state（无则 initial=Idle×null）
    ② 纯函数 FSM.transition(state, event) → TransitionResult
    ③ 更新 _fsmStates[sessionId]
    ④ 追加 TransitionRecord 到 history（ring buffer, N=20）
    ⑤ 分支：
       ├─ forceComplete → messageForceCompleter.markIdle(sessionId)
       ├─ isSuspicious → triggerRestValidation(sessionId)
       └─ 结束
```

副作用集中在流水线，FSM 本身纯净可测。

### 5.3 directory 路由

```kotlin
fun interface DirectoryResolver { fun resolve(sessionId: String): String? }

// EventDispatcher init 注入：
sessionStateService.directoryResolver = DirectoryResolver { sid ->
    sessionHandler.sessions.value.find { it.id == sid }?.directory
}
```

所有 REST 查询经 directoryResolver 解析目标 directory。

### 5.4 恢复闭环（自驱）

```
SessionStateService 内部 appScope 协程，每 5s:
  checkStaleness() 读自己的 _fsmStates:
    ├─ Busy 且 lastEventAt > 15s → triggerRestValidation
    └─ Idle 但 incompleteChecker=true → triggerRestValidation

triggerRestValidation(sid):
  directory = directoryResolver(sid)
  REST fetchSessionStatuses(serverId, directory)
    ├─ 命中 → onRestValidation(sid, status)
    └─ 缺席且 directory != null → onRestValidation(sid, Idle)
    └─ directory == null → 跳过（保守，避免误判）
  → applyTransition(sid, RestValidation(...)) → FSM 修正 → 闭环
```

### 5.5 syncFromRest（6 路径合一）

```kotlin
suspend fun syncFromRest(projects: List<Project>): SyncResult {
    // ① 聚合查询所有 project worktree
    val aggregated = mutableMapOf<String, SessionStatus>()
    val dirs = if (projects.isEmpty()) listOf(null) else projects.map { it.worktree }
    for (dir in dirs) {
        sessionRepo.fetchSessionStatuses(serverId, dir)
            .onSuccess { aggregated += it.mapValues { toStatus(it.value) } }
    }
    // ② 缺席语义统一
    for ((sid, state) in _fsmStates.value) {
        if (state.core !is Idle && sid !in aggregated) {
            aggregated[sid] = if (incompleteChecker.hasIncomplete(sid)) state.core else Idle
        }
    }
    // ③ 应用到 FSM
    for ((sid, status) in aggregated) applyTransition(sid, RestValidation(status))
    return SyncResult(aggregated.size, aggregated.count { it.value is Busy })
}
```

**现有 6 路径映射**：preLoadSessions / loadSessions / syncSessionStatus / refreshAndSync / recoverMessages → 全部调 `syncFromRest(projects)`；triggerRestValidation → 内部单会话校验。

### 5.6 TransitionRecord

```kotlin
data class TransitionRecord(
    val sessionId: String,
    val timestamp: Long,
    val event: String,           // "SseStatus(Busy)" / "RestValidation(Idle)" 等
    val fromCore: String, val toCore: String,
    val fromActivity: String?, val toActivity: String?,
    val isSuspicious: Boolean,
    val reason: String?,         // "L2 stale 706638ms" / "absent from dir → idle"
)
```

per-session ring buffer（N=20），`historyFlow[sid]` 可查询；结构化 logcat 同步打印。

---

## 6. UI 迁移

### 6.1 读取点清单

| 文件 | 现有读取 | 迁移后 |
|------|---------|--------|
| SessionListViewModel（目录树 combine） | eventDispatcher.sessionStatuses | sessionStateService.statusFlow |
| ChatViewModel.sessionMetaState | sessionStatusManager.statusFlow | sessionStateService.statusFlow |
| ChatScreen 顶部进度条 | sessionMeta.sessionStatus | 不变（上游已切） |
| ChatMessageList.streamingMsgId | message.time.completed == null | activityFlow[sid] is Streaming |
| PartContent Reasoning isStreaming | part.time.end == null | activityFlow[sid] is Streaming（CompositionLocal 注入） |
| PartContent Reasoning durationMs/startTimeMs | part.time.start/end | 不变（元数据，计时用） |

### 6.2 EventDispatcher 重构

- 移除 sessionStatuses 相关（syncAllSessionStatuses / markSessionIdle / markSessionIdleProtected / updateSessionStatus）
- SSE 状态事件（SessionStatus/SessionIdle/SessionError/SessionNext.*）→ sessionStateService.onSseEvent
- 元数据事件（SessionCreated/Updated/Deleted）→ sessionEventHandler
- 消息事件 → messageEventHandler
- 设置 3 个回调（directoryResolver / incompleteChecker / messageForceCompleter）

---

## 7. 实施计划

### 7.1 步骤（每步可编译）

| 步骤 | 内容 | 验证 |
|------|------|------|
| S1 | 新建 SessionStateService + 演进 SessionStateFSM（activity + 矩阵）+ TransitionRecord | 纯函数 FSM 全 case 单测 |
| S2 | EventDispatcher 注入 SessionStateService；SSE 状态事件双写（旧+新并行）；设置 3 回调 | 新旧并行，对比一致性 |
| S3 | UI 读取点逐个切到 SessionStateService | 每点独立验证 |
| S4 | 6 条恢复路径切到 syncFromRest | 恢复合一 |
| S5 | 删除旧代码（SessionEventHandler._sessionStatuses / SessionStatusManager / EventDispatcher status 系列） | 架构干净 |
| S6 | 全量回归（单测 + 实测） | 验收 |

### 7.2 文件变更

**新建（3）**：
- `data/repository/SessionStateService.kt`
- `domain/model/SessionStateFSM.kt`（演进自 SessionStatusFSM）
- `domain/model/TransitionRecord.kt`

**修改（8）**：
- `data/repository/EventDispatcher.kt`
- `data/repository/handler/SessionEventHandler.kt`
- `ui/screens/sessions/SessionListViewModel.kt`
- `ui/screens/chat/ChatViewModel.kt`
- `ui/screens/chat/components/ChatMessageList.kt`
- `ui/screens/chat/components/PartContent.kt`
- `service/SseConnectionManager.kt`
- `ui/screens/chat/SessionActionsDelegate.kt`

**删除（1）**：
- `data/repository/SessionStatusManager.kt`

**测试**：
- 新建 `SessionStateFSMTest`（转换矩阵全 case）
- 新建 `SessionStateServiceTest`（事件链/staleness/syncFromRest）
- 迁移 `EventDispatcherIntegrationTest` / `SessionStatusFSMTest`

### 7.3 测试策略

| 层级 | 内容 |
|------|------|
| FSM 纯函数 | 7 状态 × 14 事件全组合 + 不变量校验 |
| Service 单元 | 事件链、staleness 自驱、syncFromRest（多 dir 聚合/缺席=idle/保护 incomplete）、history |
| 集成 | EventDispatcher→Service 全链、SSE 丢失→REST 兜底、directory 路由 |
| UI 回归 | 目录树活跃标记、进度条停止、流式动画结束、断线恢复 |
| 迁移现有 | EventDispatcherIntegrationTest 等适配 |

### 7.4 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| A1 流式动画迁移（part.time→activity） | 🔴 高 | FSM force-complete 保证 activity 清空；S2 双写期对比一致性；矩阵穷举结束 case |
| 一次性大改回归 | 🟡 中 | S1-S5 每步可编译可测；S2 双写过渡；全量回归 |
| directory 解析失败（孤儿会话） | 🟡 中 | resolver 返回 null 时保守跳过；clearSession 清理孤儿 |
| staleness 风暴 | 🟢 低 | REST 校验并发限流；可加退避 |
| Reasoning 计时器依赖 part.time | 🟢 低 | durationMs 仍读 part.time（不变），仅 isStreaming 切 activity |

---

## 8. 附录：与现有代码的对应

| 现有 | 重构后 |
|------|--------|
| SessionStatusManager（FSM） | 演进为 SessionStateService（+ activity + 历史） |
| SessionEventHandler._sessionStatuses | 删除，读 statusFlow |
| EventDispatcher.syncAllSessionStatuses | 迁入 syncFromRest |
| 6 条恢复路径 | 合为 syncFromRest 单入口 |
| part.time.end 驱动流式 UI | 改读 activityFlow；part.time 仅供计时 |
| markSessionIdle 分散调用 | 统一在 applyTransition 流水线 force-complete 分支 |
| StreamingStateTracker | 已清理（前置修复） |

---

## 9. 成功标准

1. 所有 UI（目录树/进度条/流式动画）读 SessionStateService 的 Flow，无其它 status 源
2. SessionEventHandler 不含 _sessionStatuses；SessionStatusManager 删除
3. 6 条恢复路径合一为 syncFromRest
4. SessionStateFSMTest 覆盖 7×14 全组合，不变量校验通过
5. 原有两个 bug 场景（多 worktree 目录树、断线进度条）实测不再复现
6. 全量单测（1046+）+ 新增矩阵测试通过
7. historyFlow 可查询任一会话的转换链路（可追溯验证）
