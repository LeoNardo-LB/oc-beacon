# 设计：未读红点时间戳时钟一致性（Clock Consistency）

- 日期：2026-08-07
- 关联：backlog #25（红点时间戳时钟一致性；由 #24 关闭后的衍生调研立项）
- 状态：已评审通过（brainstorming 对话确认：方案 1 校准+消除污染，历史 lastReplyTime 一次性清空）

## 1. 背景与问题

未读红点判定是一个**跨时钟域比较**（服务器时刻 vs 客户端时刻），但代码把它当**同时钟域比较**实现：

```
isUnread:  lastReplyTime(混合域)  >  max(readTimes, unreadBaseline, allReadAt) —— 全部客户端 now
```

本机部署（模拟器/真机连本机 serve）客户端=服务器，时钟一致，问题被掩盖；**连远端服务器时时钟偏差 → 红点误报/漏报**。

### 1.1 根因（2026-08-07 deep-explore 调研核实，全部有代码证据）

| # | 根因 | 影响 | 位置 |
|---|------|------|------|
| R1 | 红点体系（commit 9468666c）注释意图"服务器时刻"，却复用未改造的 `markSessionIdle`（客户端 now），forceComplete 先覆盖后读取——**注释与实现矛盾** | 所有兜底路径 | EventDispatcher.kt:67-82, MessageEventHandler.kt:520 |
| R2 | **CommandExecuted 无时间戳字段** → 含工具调用的 turn 中该消息 completed 被客户端 now 覆盖，随后被 forceComplete 的 `maxByOrNull` 读出——**高频确定性污染**（每次工具调用） | 所有含工具调用 turn | SseEvent.kt:214-219, EventDispatcher.kt:275-277 |
| R3 | StepEnded.timestamp 正确路径设计后**服务器不发该事件**（2026-08-07 实证），服务器时刻"主路径"空转 | 全部场景退回兜底 | EventDispatcher.kt:195-198 |
| R4 | 已读链路 4 个时间戳（markSessionRead/一键已读/基线/内存信号）**全部客户端 now** | 即使左值纯净，远端仍误报 | SettingsDataStoreReadTimes.kt:34-95, ChatViewModel.kt:136 |
| R5 | 等式两边时钟域不对称且**零校准机制** | 系统性 | SessionListStateBuilder.kt:24-33 |
| R6 | ReasoningBlock.kt:71 已防御时钟偏差（下限钳制），红点链路零防御 | 已知但不防 | — |

### 1.2 历史事实

- 作者 9468666c 时**已意识到**客户端 now 会误报（EventDispatcher.kt:69-72 注释为证），但实现未对齐
- "全客户端时刻"方案已被历史否决：idle 延迟到达会让客户端接收时刻永远晚于已读时刻 → 结构性误报（SessionNextEvent.kt:208-209 注释）
- 校准源确认（curl 实证）：服务器 HTTP 响应带 `Date` 头（RFC 7231，秒级精度），Ktor OkHttp engine 可插 OkHttp Interceptor 读取（NetworkModule.kt:66）
- SSE 走原始 OkHttp（MessageApi.kt:163）不走 Ktor HttpClient——**校准源只用 REST 请求的 Date 头**（列表进入/刷新/搜索/聊天打开均有 REST，频率足够）

## 2. 目标与范围

- **根治**：红点判定全链路统一到服务器时钟域（结构上免疫时钟偏差与事件延迟）
- 红点**语义不变**：绑定 turn 完全结束（idle）才出现；不实施"进行中即红点"
- 本机部署**行为零变化**（offset≈0，与现状逐字节等价）
- **历史数据兼容**：readTimes/allReadAt/unreadBaseline 旧值直接兼容（比较时换算）；lastReplyTime 旧值（域混杂无法区分）升级一次性清空重建
- 范围：红点体系（EventDispatcher / MessageEventHandler / SessionListStateBuilder / SettingsDataStoreReadTimes / 相关测试）。**不触碰**：聊天页渲染、SSE 流式管线、SessionStateService FSM 状态机（仅读 statusFlow）、列表状态切片（#23 产物）

## 3. 方案：时钟偏移校准 + 污染消除（根治）

### 3.1 A. 时钟偏移估计器 `ClockOffsetEstimator`（per-serverId）

```
offset = serverTime - clientTime
样本   = Date 头（服务器响应生成时刻, epoch ms）- 客户端接收时刻
```

- **采样**：OkHttp Interceptor（NetworkModule engine.config `addInterceptor`）读 `Date` 头 → 解析（RFC 7231：`Fri, 07 Aug 2026 10:58:15 GMT`）→ 提交样本 (serverTime, clientReceiveTime)
- **线程安全**：Interceptor 在 OkHttp 线程调用，样本提交与读取必须同步安全（如 synchronized 或原子变量；提交仅更新内存估计，无 IO）
- **平滑**：EMA（α≈0.3）；异常剔除（|样本−当前估计|>10s 视为时钟跳变，重估不污染 EMA）
- **未校准 fallback = 0**：首帧/未收到任何 Date 头时视为同时钟（本机部署行为不变）
- **作用域**：per-serverId（多服务器各自校准；同一后端双配置共享同偏移，无冲突）
- 注入：`@Singleton`，依赖注入到使用方（EventDispatcher / SessionListViewModel 等）；Interceptor 经 NetworkModule 注入
- 持久化上次估计作为启动初值：**不做**（YAGNI，首次 REST 即校准，首帧窗口 <1s）

### 3.2 B. 红点时间戳独立通道（消除 R1/R2/R3）

**新增 `_lastServerCompleted: MutableStateFlow<Map<String, Long>>`（EventDispatcher 内）**——每会话"服务器已知最后完成时刻"，**全程服务器域**：

| 更新点 | 来源 | 时钟域 |
|--------|------|--------|
| `MessageUpdated` 且 assistant 且 `completed != null` | 事件内 `time.completed` | 服务器 ✅ |
| REST 消息合并（replaceMessages/mergeMessages 后派生） | REST `time.completed`（mergeMessageMeta 已合并） | 服务器 ✅ |

- **forceComplete（idle）改读通道**：`_turnEndTime.update { it + (sessionId to max(通道值, created兜底)) }`——created 也是服务器域；不再从 messages map 读（避开 markSessionIdle 污染）
- **`markSessionIdle` 保持现状**（UI 流式终止语义：CommandExecuted 精确标记、REST 兜底标记），但**不再流入红点时间戳**
- StepEnded.timestamp（R3）：若未来服务器开始发送，`onTurnEnded` 回调同样写入通道（兼容设计，当前不触发）

### 3.3 C. 已读对齐：比较点换算（消除 R4/R5，无迁移）

**只改 `isUnread` 一处**（SessionListStateBuilder.kt:24-33）：

```kotlin
lastReplyTime[sessionId] > maxOf(readTimes[sid], unreadBaseline, allReadAt) + offset
```

- 右值（客户端域）+ offset = 服务器时刻 → 等式两侧统一服务器域
- **历史数据兼容推演**（已逐场景验证）：
  - readTimes/allReadAt/unreadBaseline：旧值 ≈ 当时客户端 now，+当前 offset ≈ 历史服务器时刻（时钟漂移小量，可忽略）✅
  - 旧 lastReplyTime：**域混杂无法区分** → 见 3.5 一次性清空
- 内存信号（SessionReadSignal.justRead）同为客户端域 → 比较时 +offset 一致
- 判定结果的域：仅比较用，不写回存储（无迁移）

### 3.4 D. REST 合并竞态修正

SSE idle 丢失场景：L3 REST 校验 → 会话置 Idle → replaceMessages 合并 REST completed → 通道更新但 `_turnEndTime` 不重写 → 红点漏报。

**修正**：通道更新点检查会话状态（`sessionStateService.statusFlow`）——若该会话当前 Idle → 同步重写 `_turnEndTime`。事件顺序保证正常路径（消息完成先于 idle）不受影响。

### 3.5 E. 历史 lastReplyTime 一次性清空

- 新增版本标记（DataStore key，如 `redotime_v1_migrated`）
- 首次运行新版：清空 `lastReplyTime` 持久化数据（readTimes/allReadAt/unreadBaseline **保留**）
- 语义自洽：升级时点所有历史回复均为旧 turn，清空后红点全灭，按纯净机制重新积累；杜绝"远端 + 旧版工具 turn 会话 → 永久红点误现"
- 实现：SettingsDataStore 增加一次性迁移方法（~10 行），EventDispatcher 启动/首次访问时执行

## 4. 数据流（改造后）

```
服务器 (SSE/REST 服务器时刻 completed)
  │
  ├─ MessageUpdated(assistant, completed≠null) ──┐
  ├─ REST 合并 (mergeMessageMeta) ───────────────┤→ _lastServerCompleted（通道，内存）
  └─ (未来) StepEnded.timestamp ─────────────────┘
                                                   │  + 会话 Idle（状态检查）
SessionStatus=idle → FSM forceComplete ───────────┴→ _turnEndTime（持久化 lastReplyTime）
                                                        │
Date 头 (REST 响应) → OkHttp Interceptor → ClockOffsetEstimator（per-serverId, EMA）
                                                        │ offset
isUnread:  lastReplyTime(服务器) > max(readTimes, baseline, allReadAt)(客户端) + offset
```

## 5. 验收标准

1. **通道纯净**：含工具调用的 turn，`_turnEndTime` 的值等于服务器 completed（非客户端 now）——单测断言
2. **CommandExecuted 不污染**：CommandExecuted 后 forceComplete 读到的仍是服务器 completed
3. **offset 校准**：ClockOffsetEstimator 单测（Date 解析、EMA、异常剔除、fallback=0）
4. **isUnread+offset**：offset 非零时判定正确；offset=0 时与现状逐字节等价（现有红点测试全绿）
5. **REST 竞态**：会话已 Idle 时通道更新 → _turnEndTime 同步更新（单测）
6. **历史清理**：首次运行清空 lastReplyTime；再次运行不清（标记幂等）
7. **本机行为不变**：真机/模拟器回归——发消息→返回→turn 结束红点出现；消费红点；重启持久化（复用 maestro/regression-unread-chain-a/b.yaml）
8. **编译 + 全量单测**：`compileDevDebugKotlin` ✅ + `testDevDebugUnitTest --rerun` ✅

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| Date 头解析失败（格式异常/缺失） | 解析失败丢弃样本，不抛异常；fallback=0 |
| offset 噪声（网络半 RTT） | EMA 平滑；红点判定秒级粒度，噪声毫秒级无感 |
| 服务器时钟跳变（NTP） | 异常剔除（|样本−估计|>10s 重估） |
| 未校准首帧窗口 | fallback=0（本机行为），首次 REST 后 <1s 修正 |
| forceComplete 读通道为空（极端：无任何服务器 completed） | 回退 created（服务器域），不引入客户端 now |

## 7. 验证链

编译 ✅ → 新单测（Estimator/通道/isUnread+offset/竞态/迁移）✅ → 全量单测 ✅ → 构建安装 ✅ → 真机回归（复用 maestro/regression-unread-chain-a/b.yaml + 手动长 turn 验证）✅ → 完成声明前加载 verification-before-completion

## 8. 非目标（明确不做）

- 红点语义变更（进行中即红点）——用户已否定（#24 关闭结论）
- busy 指示强化 / FOLDER 未读计数 / 未读置顶——候选，另立条目
- offset 持久化（启动初值）——YAGNI
- "已读时间记录时换算"——与历史数据兼容冲突（旧值无法区分域），已否决
