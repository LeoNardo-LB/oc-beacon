# 堆积队列状态补偿 drain 设计（#176 + #177 统一修复）

- 日期：2026-08-21
- 状态：已定案（grilling Q5/Q6/Q15 + 卡片既定方向），实施中
- 范围：#176（TOCTOU 边沿错过）、#177（退出会话/切后台/断连恢复滞留）
- 关联：docs/journal/2026-08-21-p1-p2-dev-batch.md

## 1. 根因（三断点，静态链已验证）

堆积管线现状为**纯边沿触发**：唯一自动推进器是自然成功 turn 结束回调
（`PendingMessagePipeline.onNaturalTurnEnd` ← FSM Busy→Idle 且事件为
SseIdle/SseStatus(Idle)，SessionStateService.kt:409-415）。

1. **边沿错过即死（#176 TOCTOU）**：弹气泡时 busy → turn 在用户点击
   「堆积消息」前结束 → onNaturalTurnEnd 此刻队列空、no-op → 随后
   enqueuePendingMessage 无条件入队不重验 FSM → 消息落 Idle 态，无未来边沿。
2. **POST 失败不动点**：launchDrain 的 POST 失败后条目留队首
   （PendingMessagePipeline.kt:92-95），注释假设"等下一次自然结束"——
   若无人再发消息，该假设结构性不成立。
3. **RestValidation(Idle) 不在白名单**：切后台断连后 L3/L4 恢复的 Idle
   转移（SessionStateService.kt:552/558/572/718）不触发
   naturalTurnEndListener → 队列不推进。

已排除两项嫌疑：listener 生命周期（EventDispatcher/协作器应用级单例接线，
退出会话不丢）；SSE 存活（FGS+WakeLock 保护）。

## 2. 设计定案

**状态驱动补偿**：FSM 判 Idle + 队列非空 → drain。三触发器汇聚同一
`drainIfIdle(sessionId)`，per-session in-flight 去重沿用既有 draining map。

### 触发器

| # | 触发器 | 修哪个断点 |
|---|--------|-----------|
| T1 | **管线自有心跳**：appScope 循环每 5s 扫 `sessionIdsWithPending()`，逐会话 drainIfIdle | ②（无限重试，用户定案） |
| T2 | **入队即时检查**：enqueue 落库后调 `onEnqueued(sid)` → drainIfIdle | ①（精确路径） |
| T3 | **反应式 Idle 观察**：collect `statusFlow`，会话转 Idle（任意来源：自然结束/L3/L4/force-complete）→ drainIfIdle | ③ |

- T1 不复用 TaskDelegate 5s 轮询：那是 **VM 作用域 + active 空集早退 +
  30s 退避**（TaskDelegate.kt:100-110/190-191），不满足 app 级后台驱动。
- 自然结束 listener **保留**（原路径），与 T3 双触发被 draining map 去重。
- FSM 无状态的会话：心跳**不**直接 drain（未知≠Idle）；由 L4
  syncFromRest / 进会话 requestValidation 补态后 T3 接手。

### 安全护栏

- drain 条件：`statusFlow[sid] is SessionStatus.Idle`，否则跳过（Busy/
  Streaming/Ask/无状态一律不 drain）。
- `hasPendingUserInput(sid)` 为真跳过——问题待答时不清空队列（防把
  待答状态当作可推进）。
- serverId 解析：SSS 新增 `serverIdFor(sessionId)`（SSE 投递归属
  ownership map ?: currentServerId）；null 跳过（与现状一致）。
- 发送语义不变：peek → POST → 成功才 delete（at-least-once）；失败留队首，
  心跳 5s 静默无限重试（grilling Q5 定案）；无任何通知/提示音（Q15 静默）。

### 行为变更（显式记录）

原设计"手动停止/错误不触发"被状态补偿取代：**错误 turn 结束（FSM 落
Idle）后队列同样自动推进**。依据：用户定案的方向即"FSM Idle + 队列非空 →
drain"（含 RestValidation 亦触发），且与 Q5"POST 失败无限重试"语义一致
——服务器既然 Idle，下一条消息照常消费。

### 手动入口（grilling Q6 定案：做）

SessionRow 长按详情对话框：队列非空时新增「继续发送堆积消息 (N)」按钮
→ `pipeline.continueNow`。SessionListViewModel 注入 pipeline +
pendingMessageRepository，暴露 `pendingCounts: StateFlow<Map<String,Int>>`。

## 3. 改动面

| 层 | 文件 | 改动 |
|----|------|------|
| data | PendingMessagePipeline.kt | +drainIfIdle/onEnqueued/start()（T1 心跳+T3 collector，interval 构造注入便于测试） |
| data | SessionStateService.kt + 接口 | +serverIdFor() / hasPendingUserInput() 公开 |
| data | PendingMessageDao / Repository(+Impl) | +observeCounts(): Flow<Map<String,Int>> / sessionIdsWithPending() |
| data | EventDispatcher init | pipeline.get().start()（心跳随首个连接启动） |
| ui | ChatViewModel.enqueuePendingMessage | 落库后 pipeline.onEnqueued(sid) |
| ui | SessionListViewModel / SessionRow / 详情对话框 | pendingCounts + 「继续发送」按钮（仅 count>0 显示） |
| i18n | values ×15 | 新 key：session_details_continue_queue |

## 4. 测试计划

JVM（扩展 PendingMessagePipelineTest；TestScope 虚拟时间驱动心跳）：
1. T2：Idle 会话 enqueue → 立即发队首并 delete（#176 直击）
2. T1：POST 失败 → advanceTimeBy(5s) → 重发成功 → delete（断点②）
3. T3：statusFlow 值变化落 Idle → drain（断点③语义）
4. Busy 会话 enqueue 不发；Busy→Idle 才发（回归原设计）
5. hasPendingUserInput=true 跳过
6. 多会话独立 + in-flight 去重（并发触发只发一条）
7. serverIdFor=null 跳过

真机 E2E（小米 houji e69a99d8，logcat 证据 `PendingMsgPipeline`）：
- E1（#176 精确复现）：长任务 busy → 弹气泡 → 等 turn 自然结束 → 点
  「堆积消息」→ **零手动操作**消息自动发出
- E2（#177 退出会话）：堆积 → 返回列表 → turn 结束 → 自动发出
- E3（#177 断连恢复）：堆积 → 服务器重启 → 恢复后 ≤10s 自动发出
- E4（手动入口）：队列非空会话长按 → 按钮 → 队首发出
- E5（回归）：正常堆积链路（busy 入队 → 自然结束自动发）不回归
