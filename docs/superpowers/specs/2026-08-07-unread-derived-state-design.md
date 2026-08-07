# 设计：未读红点派生状态模型（Derived Unread State）

- 日期：2026-08-07
- 关联：backlog #25（红点时间戳时钟一致性；由 #24 关闭后的衍生调研立项）
- 状态：已评审通过（brainstorming 确认：派生状态模型，全服务器域，零校准；方案 1 offset 校准判定为过度设计——为维持无价值的"客户端 now 已读"语义付税）
- 前版：`2026-08-07-clock-consistency-design.md`（方案 1，被派生状态模型取代，见 §9 差异）

## 1. 背景与问题

未读红点判定是一个**跨时钟域比较**（服务器时刻 vs 客户端时刻），但代码把它当**同时钟域比较**实现：

```
isUnread:  lastReplyTime(混合域)  >  max(readTimes, unreadBaseline, allReadAt) —— 全部客户端 now
```

本机部署（模拟器/真机连本机 serve）客户端=服务器，时钟一致，问题被掩盖；**连远端服务器时时钟偏差 → 红点误报/漏报**。

### 1.1 根因（2026-08-07 deep-explore 调研核实，全部有代码证据）

| # | 根因 | 影响 | 位置 |
|---|------|------|------|
| R1 | 红点体系（commit 9468666c）注释意图"服务器时刻"，却复用未改造的 `markSessionIdle`（客户端 now），forceComplete 先覆盖后读取——注释与实现矛盾 | 所有兜底路径 | EventDispatcher.kt:67-82, MessageEventHandler.kt:520 |
| R2 | CommandExecuted 无时间戳字段 → 含工具调用的 turn 中该消息 completed 被客户端 now 覆盖，随后被 forceComplete 读出——高频确定性污染 | 所有含工具调用 turn | SseEvent.kt:214-219, EventDispatcher.kt:275-277 |
| R3 | StepEnded.timestamp 正确路径设计后服务器不发该事件（2026-08-07 实证），服务器时刻"主路径"空转 | 全部场景退回兜底 | EventDispatcher.kt:195-198 |
| R4 | 已读链路（markSessionRead/一键已读/基线/内存信号）全部客户端 now | 即使左值纯净，远端仍误报 | SettingsDataStoreReadTimes.kt:34-95, ChatViewModel.kt:136 |
| R5 | 等式两边时钟域不对称且零校准机制 | 系统性 | SessionListStateBuilder.kt:24-33 |
| R6 | ReasoningBlock.kt:71 已防御时钟偏差（下限钳制），红点链路零防御 | 已知但不防 | — |

### 1.2 关键洞察（本方案立足点）

**"已读"不需要记录"时刻"，只需要记录"消费到哪条回复"**。"哪条回复"有天然的服务器域标识：**该会话最后一条完成消息的 completed 时间戳**（服务器时刻）。由此：

- **跨时钟域比较可以被消除**（而非被校准）——比较的两边都是服务器域
- "客户端 now 已读"语义无不可替代价值：时刻会误判"退出时还在流的消息"，内容位置不会
- 红点从"事件时序驱动"（idle → forceComplete → 写 lastReplyTime）变为"**状态快照派生**"（messages + status + 已读标记三输入纯函数）——天然免疫事件顺序/丢失/竞态

### 1.3 基线（unreadBaseline）删除论证

原基线防"升级后历史会话（从未打开过）全部显示红点"。派生模型下：

- **未进过的会话无消息数据（懒加载）→ maxCompleted 无值 → 天然不红点**——基线防的场景已不存在
- SSE 在线且未进过的会话有 maxCompleted → 红点是**期望行为**（其他设备的新回复），基线不应压制
- 基线机制反而引入新问题：SessionListViewModel init（loadSessions 同时）写入基线时消息未加载 → 全局 max 偏低 → 历史会话误红点
- **结论：删除 unreadBaseline 概念**（isUnread 签名简化、删除 ensureUnreadBaseline/_unreadBaseline 机制）

## 2. 目标与范围

- **根治**：红点判定全链路服务器时钟域，结构上免疫时钟偏差与事件延迟（无需任何校准组件）
- 红点**语义不变**：绑定 turn 完全结束（会话状态 Idle）才出现；不实施"进行中即红点"
- **代码净减少**：删除 `_turnEndTime`、forceComplete 红点写入、onTurnEnded 红点接线、lastReplyTime 持久化、unreadBaseline 机制
- 历史数据：**允许一次性清空重建**（用户放开兼容约束）；已读标记（readTimes/allReadAt）值域从客户端 now → 服务器 completed
- 范围：红点体系（EventDispatcher / MessageEventHandler / SessionListStateBuilder / SessionListViewModel / SettingsDataStoreReadTimes / SettingsRepository / SessionRepository / ChatViewModel markRead 链 / 相关测试）。**不触碰**：聊天页渲染、SSE 流式管线、SessionStateService FSM 状态机（仅读 statusFlow）、列表状态切片（#23 产物）

## 3. 方案：派生状态模型

### 3.0 核心公式

```
isUnread(session) =
    会话状态 == Idle                                        // turn 完全结束（用户需求门控）
    && maxCompleted[session] > max(readTimes[sid], 一键已读)   // 全部服务器域
```

其中 `maxCompleted[session]` = 该会话最后一条完成 assistant 消息的 completed（服务器时刻，实时派生）。

### 3.1 maxCompleted flow（替代 _turnEndTime / lastReplyTime / 基线）

EventDispatcher 新增 `_lastCompletedReplyTime: MutableStateFlow<Map<String, Long>>`（每会话最后完成时刻，**全程服务器域**）：

| 更新点 | 来源 | 时钟域 |
|--------|------|--------|
| `MessageUpdated` 且 assistant 且 `completed != null` | 事件内 `time.completed`，**增量**：> 当前 max 才更新 | 服务器 ✅ |
| REST replaceMessages/mergeMessages 后 | 重算该会话 max(completed)（低频） | 服务器 ✅ |

暴露公共 `val lastCompletedReplyTime: StateFlow<Map<String, Long>>`。

**持久化（重启恢复）**：`_lastCompletedReplyTime` 全量持久化到 DataStore（key `session_last_reply_time`，值域服务器 completed）。
- init：v2 迁移完成后读 seed → `update` 合并取 max（不覆盖 SSE 并发写入的更新值）；`runCatching` 容错。
- 后台收集：maxCompleted 变化 → 全量写回（`runCatching` 容错，best-effort）。
- 断线期限制：重启期间服务器新回复缺失为已知限制（非本任务引入，与旧 lastReplyTime 机制相同）——SSE 重连后增量补全。

- **删除**：`_turnEndTime`、`messageForceCompleter` 红点写入职责、`onTurnEnded` 红点接线、`replyTimePersistScope`（lastReplyTime 持久化 collector）
- `messageForceCompleter` **保留**其 `markSessionIdle` 调用（UI 流式终止语义：CommandExecuted 精确标记、part time.end 补全）——但不再写任何红点时间戳
- 清理：`clearForSession`/`clearAll` 级联移除；`clearForServer(sessionIds)` 同样移除对应会话条目

### 3.2 isUnread 改造（SessionListStateBuilder.kt:24-33）

```kotlin
internal fun isUnread(
    sessionId: String,
    maxCompleted: Map<String, Long>,
    readTimes: Map<String, Long>,
    allReadAt: Long = 0L,
    status: SessionStatus,             // 新增：turn 结束门控
): Boolean {
    if (status != SessionStatus.Idle) return false          // turn 未结束/未知/错误 → 不红点
    val last = maxCompleted[sessionId] ?: return false
    return last > maxOf(readTimes[sessionId] ?: 0L, allReadAt)
}
```

- 删除 `unreadBaseline` 参数（§1.3）
- 调用点（buildContentState / buildTreeNodes）已有 `statuses[session.id]`，传入即可
- status == null（状态未知）→ 不 Idle → 不红点（保守，REST 同步后修正——与现状 idle 丢失依赖 L3 兜底等价）
- SessionStatus.Error → 不红点（等价现状：forceComplete 仅在 Idle 转换触发）

### 3.3 已读标记记录（值域变化）

**规则**：`markSessionRead` 时取**该会话最后一条完成 assistant 消息的 completed**（服务器时刻）；**会话无任何 completed 消息则不更新标记**（防"快速进出消息未加载 → 已读=0 → 误现红点"：用户未消费任何内容，之后红点合理）。

| 调用点 | 现状 | 改为 |
|--------|------|------|
| ChatViewModel.markSessionRead（退出） | 内部 `System.currentTimeMillis()` | 调用方传最后 completed（ChatViewModel 持有消息列表：`filterIsInstance<Assistant>().mapNotNull{completed}.maxOrNull()`）；无则跳过更新 |
| SessionReadSignal.markRead（内存即时信号） | ChatViewModel 传客户端 now | 传同一服务器 completed |
| 一键已读 markAllSessionsRead | 内部 now → allReadAt | 传入**已知会话全局 max** = max(所有 maxCompleted 值)；内存信号逐会话传该值 |
| ~~unreadBaseline~~ | ~~内部 now~~ | **删除**（§1.3） |

- 懒加载自洽性：未进过的会话无 maxCompleted → isUnread 直接 false（永不红点），与一键已读/基线无关——**天然免疫历史全红点与懒加载缺口**
- DataStore **结构不变**（readTimes/allReadAt 的 key 与层级），仅**值域变化**（服务器 completed 替代客户端 now）

### 3.4 一次性迁移（允许，用户放开约束）

- 版本标记（DataStore key：`unread_state_v2_migrated`）
- 首次运行新版：**清空 readTimes / allReadAt**（值域变化，旧客户端 now 值无法与新服务器域比较）
- 幂等（标记存在则跳过）
- 不需要基线重建（基线已删除）；未进过会话天然无红点，无需额外防护

### 3.5 删除项汇总（代码净减少）

| 删除 | 位置 |
|------|------|
| `_turnEndTime` MutableStateFlow | EventDispatcher.kt:190 |
| forceComplete 红点写入（`_turnEndTime.update` 块） | EventDispatcher.kt:73-81 |
| onTurnEnded 红点接线（`sessionNextHandler.onTurnEnded = ...`） | EventDispatcher.kt:199-203 |
| replyTimePersistScope（lastReplyTime 持久化 collector） | EventDispatcher.kt:204-206 |
| `SettingsDataStore.lastReplyTimes()` / `saveLastReplyTimes()` | SettingsDataStoreReadTimes.kt:44-57 |
| `SettingsDataStore.ensureUnreadBaseline()` + SettingsRepository 接口 | SettingsDataStoreReadTimes.kt:72-82 |
| `SessionListViewModel._unreadBaseline` + init 调用 | SessionListViewModel.kt:137,340 |
| isUnread 的 unreadBaseline 参数与 lastReplyTime 语义 | SessionListStateBuilder.kt |

## 4. 数据流（改造后）

```
服务器 (SSE/REST，全部服务器时刻)
  │
  ├─ MessageUpdated(assistant, completed≠null) ──┐
  └─ REST replace/merge 后重算 ──────────────────┴→ _lastCompletedReplyTime（maxCompleted，内存，实时）
                                                            │
                            ↕ 持久化（init 读 seed / 后台收集写回，runCatching 容错）↕
                            DataStore（key session_last_reply_time，值域服务器 completed）——重启恢复源
                                                            │
SessionStatus=idle / L3 REST 校验 → statusFlow 状态 Idle ────┤
                                                            │
已读标记（服务器 completed）：退出会话 / 一键已读(全局 max) ──→ DataStore（值域服务器）
                                                            │
isUnread:  status==Idle && maxCompleted > max(readTimes, allReadAt)   ← 纯函数，快照派生
```

**快照派生免疫性**：红点判定不依赖"idle 事件在消息之后到达"的事件顺序——消息与状态都齐才红点（先消息后状态 / 先状态后消息均正确）；REST 合并晚到自然正确（maxCompleted 实时派生）；无写入时序竞态。

## 5. 验收标准

1. **全服务器域**：红点判定链路（maxCompleted / 已读标记 / 一键已读）无任何 `System.currentTimeMillis()` 参与比较——单测断言 + 代码审查
2. **turn 结束门控**：status Busy 时即使存在完成消息也不红点；Idle 后才红点——单测
3. **工具调用不污染**：含 CommandExecuted 的 turn，红点判定值 = 服务器 completed（非客户端 now）——单测
4. **已读记录规则**：无 completed 消息的会话退出时不更新已读标记；有则记录最后 completed——单测
5. **一键已读**：全局 max 写入；未进过会话天然不红点——单测
6. **迁移幂等**：首次运行清空 readTimes/allReadAt；再次运行不清——单测
7. **重启恢复**：模拟器实测——杀进程重启 → REST 同步后红点恢复（已读状态保持，未读回复红点）✅
8. **本机行为不变**：真机/模拟器回归——发消息→返回→turn 结束红点出现；消费红点；看完退出无红点；一键已读（复用 maestro/regression-unread-chain-a/b.yaml + 手动长 turn 验证）
9. **编译 + 全量单测**：`compileDevDebugKotlin` ✅ + `testDevDebugUnitTest --rerun` ✅（现有红点测试按新签名适配后全绿）

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| statusFlow 门控新增漏报（状态未知/Error） | 与现状等价（现状 idle 丢失同样依赖 L3 兜底）；REST 同步后 Idle → 红点恢复；Error 不红点 = 现状行为 |
| 快速进出会话（消息未加载）已读标记不更新 | 规则明确：用户未消费内容，之后红点合理（防误现） |
| maxCompleted 全量扫描性能 | 增量维护（MessageUpdated 只在该会话 max 增加时更新；REST 整批低频重算） |
| 迁移清空已读状态 | 用户放开约束；未进过会话天然无红点（无需基线防历史全亮） |
| 服务器时钟回拨（NTP） | completed 变小可能漏报，概率极低（秒级回拨）；无客户端时钟依赖 |
| 会话消息从未加载但 SSE 在线的历史回复 | SSE 为增量事件流不推历史（2026-08-07 已实证），无该场景 |

## 7. 验证链

编译 ✅ → 新单测（maxCompleted 增量 / isUnread+status / 已读记录规则 / 一键已读 / 迁移）✅ → 全量单测 ✅ → 构建安装 ✅ → 真机回归（maestro/regression-unread-chain-a/b.yaml + 手动长 turn + 重启恢复）✅ → 完成声明前加载 verification-before-completion

## 8. 非目标（明确不做）

- 红点语义变更（进行中即红点）——用户已否定（#24 关闭结论）
- busy 指示强化 / FOLDER 未读计数 / 未读置顶——候选，另立条目
- 时钟偏移校准（方案 1）——过度设计，被派生状态模型取代
- "已读时间记录客户端 now + 换算"——消灭比较而非校准比较

## 9. 与方案 1（前版 spec）的差异

| 维度 | 方案 1（offset 校准） | 派生状态模型（本 spec） |
|------|---------------------|------------------------|
| ClockOffsetEstimator + 拦截器 + EMA | 需要 | **删除**（零校准组件） |
| _turnEndTime / forceComplete 红点写入 / lastReplyTime 持久化 | 保留+修 | **删除**（红点 = 状态派生） |
| unreadBaseline 机制 | 保留+换算 | **删除**（未进过会话天然不红点，无需基线） |
| 竞态修正（REST 合并晚于 idle） | 需状态检查 | **天然不存在**（快照派生） |
| 已读语义 | 客户端 now + offset | 内容位置（服务器 completed）——更精确 |
| 迁移 | 清空 lastReplyTime | 清空 readTimes/allReadAt（值域变化） |
| 时钟依赖 | offset 估计值 | **纯服务器域，零估计** |
