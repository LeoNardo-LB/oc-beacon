# 433 · SSE 流式输出 → UI 高度变化 → 视口控制管线全图（侦察 2026-XX）

范围：数据层 delta 批处理 → 渲染状态 → COMP-MSG 高度补偿 → GUARD/MEFFECT 视口控制。所有行号基于当前工作区。

## 1. SSE delta 从网络到 StateFlow 的路径 + 48ms 批处理

**入口分发**：`SseEventHandler.handle` 五分支（MessageEventHandler.kt:43-55），delta 事件走 `MessagePartDelta` → `handleMessagePartDelta`（838-860）：
- 播种 assistant 骨架（842 `ensureAssistantSkeleton`）
- 按 partId 派生契约判 kind（849 `MessageMergeEngine.inferDeltaKind`，`_reasoning_ord_` → reasoning）
- synchronized(pendingLock) 下 `pendingDeltas.add(PendingDelta(...))`（850-858；字段：messageId/partId/sessionId/delta/type，定义 106-112）
- `scheduleFlush()`（859）

**48ms 批处理确切实现**（scheduleFlush，276-284）：
- `if (batchJob?.isActive == true) return` —— **不取消进行中定时器**（铁律：防 token 速率 >1/48ms 时饿死 flush；277-278 注释）
- 否则 `batchScope(SupervisorJob+Dispatchers.Default)` 上 `launch { delay(48); flushPendingDeltas() }`（280-283）。tick 来源 = coroutines delay，非帧时钟。
- `flushPendingDeltas()`（286-395）：锁内取走整批 → `isStaleDelta` 过滤滞留 delta（终态 part 内容包含判定，300-318）→ `_parts.update` 一次 StateFlow 发射（331-348，批内经 `MessageMergeEngine.applyDelta` 聚合）→ 增量落盘 `deltaPersistQueue`（354-379）。**每次 flush = 1 次 StateFlow 更新 = 1 次重组**（102-105 注释）。
- 旁路 flush 点：`handleMessageUpdated`（text.ended 权威替换前）也调 `flushPendingDeltas`（394）。
- 全量 upsert 落盘另有 #340 批处理（threshold 128 / 250ms / tick 25ms，73-75, 200-214），仅影响 Room 持久化不影响 UI 视口。

**UI 消费**：ChatViewModel → ChatScreen `messageState`（ChatScreen.kt:292）→ ChatMessageList `rawMessages`。

## 2. 流式消息渲染路径（StreamingMarkdownState / retainState / scheduleFlush 不取消）

- **streamingMsgId 判定**：`remember(rawMessages) { rawMessages.lastOrNull { it.isAssistant && it.message.time.completed == null }?.message?.id }`（ChatMessageList.kt:404-408）——只看 completed 时间戳，**故意不用** sessionMeta.isStreaming（397-401 注释：生产观察到 stuck false）。turn 完成（completed 写入）⇒ streamingMsgId=null ⇒ compensateState/各 Compensator 以 streamingMsgId 为 key 整体重建归零（471-482）。
- **Markdown 渲染三分支**（MarkdownContent.kt）：
  - 试点分支（589-602）：`overrideState==null && !asyncParse && STREAMING_MD_PILOT && !isUser` → `rememberPilotStreamingMarkdownState(markdown)`，以 `streamingMarkdownState` 渲染。
  - 同步流式 fallback（630-636）：`rememberMarkdownState(content=normalizedForLib, retainState=true)`（铁律）。
  - 完结/大文本：preParsedState 分支（569-579）或 asyncParse（>200 字符）。
- **rememberPilotStreamingMarkdownState**（StreamingMarkdownPilot.kt:47-72）：`key(resetKey) { rememberStreamingMarkdownState() }`；`LaunchedEffect(markdown, state)` 内做前缀差分——首跑整串 append；非前缀（重生成/编辑）→ `prev=null; resetKey++` 整体重建；前缀增长只 append 后缀 delta（64-67）；等长无增量。48ms flush 的整串快照在此被差分为增量（类头 28-34）。append 在组合协程主线程（37-39）。
- **scheduleFlush 不取消规则**：见 §1（279 早退，无 cancel）——AGENTS.md 铁律。

## 3. COMP-MSG：layout{} 高度补偿

**状态机** `DeferredRevealCompensator`（ScrollCompensation.kt:43-134）+ layout 包装 `deferredRevealCompensation`（141-190）：
- measure 块：读 `compensator.version` 建立快照订阅（150）→ 无界测量取 realHeight（151-154）→ 同步读 `listState.isScrollInProgress` 作 holdReveal（165，快照零滞后，订阅沿下降沿自动复测 161-164）→ `onMeasure(realHeight, shouldCompensate, holdReveal, shiftApplied=PreRenderShiftChannel.shiftSettled(listState))`（166-171）。
- onMeasure 决策（81-133）：
  - 冷启动（reportedHeight<=0）：全量上报不注入（88-91）。
  - `holdReveal`（滚动中）：只配对揭示已消费注入，**本遍增长交 clipToBounds 裁剪**，视口零位移（100-105）。
  - `injectedPending!=0 && !shiftApplied`：保持基准裁剪（#258 竞态门，108-110）。
  - `!shouldCompensate`：全量揭示清欠（113-119）。
  - `extra = realHeight - (reportedHeight+injectedPending) > 0`：**注入 extra**——`injectedPending += extra; version++`，本遍只上报已消费基准（120-127，未补偿几何永不被放置）。
  - extra<=0：全量揭示重置基准（127-131）。
- 注入通道：`PreRenderShiftChannel.enqueue(listState, injectDelta)`（ScrollCompensation.kt:185）——measure 块内只入队。
- **方向与数量**：内容增长方向 extra>0，注入量=精确测量差值（非预测）；reverseLayout 下正增量=视口向最新贴底位移。

**PreRenderShiftChannel**（PreRenderShiftChannel.kt，全 App 唯一流式补偿注入入口）：
- `enqueue`（64-69）：WeakHashMap 累计 + 代计数 g[0]++ + CONFLATED 唤醒。
- 泵（ChatMessageList.kt:1246-1252）：`awaitPending → withFrameNanos → drain` 循环，挂起空闲不起帧（#412）。
- `drain`（95-138）：帧界回调相（早于当帧 measure 遍首）排空 → `state.dispatchRawDelta(total)`（124，跨 item 官方编程滚动入口）；贴底区收缩（atBottomZone ∧ total<0）直接放弃（106-112，上方内容吸收）；异常→放弃本帧不崩溃（132-137）；finally `g[1]=g[0]` 代计数落地（137，宁推挤不卡裁剪）。
- `shiftSettled`（85-88）：g[0]==g[1] = 无未落地注入，揭示方可安全揭示。

**挂载点**（ChatMessageList.kt:1599-1609）：`if (isStreamingMsg)` 才包 `clipToBounds + deferredRevealCompensation(msgReveal, shouldCompensate={compensateState.shouldCompensate}, logTag="COMP-MSG")`；非流式仅 clipToBounds（#231 防越界叠加）。V1 摘要卡仅在 `!isStreamingMsg` 时自包 COMP-CMP(v1)（1661-1674）——防双重注入。toolReveal 用于工具区（约 2011/2242/2322 处引用 shouldCompensate 同一在底意图）。压缩展开区 `compactionReveal`（480-482）。

**shouldCompensate（在底意图）**（ChatMessageList.kt:499-508）：`snapshotFlow { isScrollInProgress to isAtBottom.value }`——scrolling=true ⇒ shouldCompensate=true；非滚动 ∧ atBottom ⇒ false。即：**用户滚离（scrolling）期间补偿保持开（裁剪），回到贴底后补偿关闭（全量揭示）**。isAtBottom = `firstVisibleItemIndex==0 && offset<100`（ChatScrollController.kt:109-114）。

## 4. GUARD：ChatScrollController 分支表 + AutoScrollArbiter

文件 ChatScrollController.kt。核心是 MSGEFFECT 内嵌的守卫 snapshotFlow（208-262），key = `Triple(isScrollInProgress, autoScrollEnabled.value, idx==0&&offset<100)`：

| 分支 | 条件 | 行为 | 行号 |
|---|---|---|---|
| 不进入守卫 | scrolling ∨ !autoOn ∨ atBottom ∨ jumpLock ∨ lease | 无动作 | 218-219 |
| **stream-instant** | 上者通过 ∧ streamingTurnActive() | **跳过 250ms 去抖，立即 requestScrollToItem(0)**（#432：增长帧同帧锚底，净位移 0） | 228-237 |
| **GUARD 去抖** | 上者通过 ∧ 非流式 | `AutoScrollArbiter.reanchorWhenSettledOffBottom`：delay(250) 后复查 !scrolling∧autoOn∧!atBottom∧!jumpLock∧!lease 才 requestScrollToItem(0) | 239-260, 476-477 |

辅助机制：
- **autoScroll 再武装 effect**（122-149）：`snapshotFlow { isScrollInProgress to isAtBottom.value }` + collectLatest；scrolling ⇒ autoScroll=false（136-137）；atBottom ⇒ `rearmWhenSettledAtBottom`（delay 250ms 复查仍贴底才 rearm=true，142-146, 450-457）。#301：collectLatest 保证手势交接闪断帧不触发再武装/守卫。
- **ForceScrollExecutor**（forceScrollTick effect，267-281; 372-428）：等 totalItemsCount 增长（5s）→ 等 fling 停（2s）→ 等视口租约（3s）→ requestScrollToItem(0) → 一帧后校验，未贴底等 1s 或重滚一次。
- **PENDING effect**（283-312）：pendingCount>0 ∧ autoOn → 等消息/等 fling/等租约 → `animateScrollToItem(0)` 平滑下滑。
- **AutoScrollArbiter 参数**：ANCHOR_DEBOUNCE_MS=GUARD_DEBOUNCE_MS=**250ms**（440, 443），语义=「稳定非滚动 ≥250ms 且条件复查成立」（432-436）。
- **GUARD stream-instant 的 key 敏感性**：守卫流只在 Triple 变化时发射——流式增长若不改变 Triple 三元（仍离底、非滚动、autoOn）则**不会发射**，instant 分支实际依赖 offset 跨 100 阈值或滚动沿变化；见 §6 竞态。
- 让位条件：jumpLockActive（跳转视口锁，ChatScreen.kt:328-336）与 PreRenderCoordinator.hasActiveTransactions（#423 视口租约）。
- streamingTurnActive 源 = `sessionMeta.isStreaming`（ChatScreen.kt:338）= ChatStateAggregator combine `sessionStateRepository.activityFlow` 的 `activities[sid] is SessionActivity.Streaming`（ChatStateAggregator.kt:62, 80）。

## 5. MSGEFFECT：messageCount LaunchedEffect

`LaunchedEffect(messageCount)`（154-265），messageCount=`messageState.messages.size`（ChatScreen.kt:333）：
- 条件：messageCount>0 ∧ autoScrollEnabled ∧ !jumpLock ∧ !lease（167-168）。
- fling 等待（≤2s）+ autoScroll/租约重校验（175-188）→ `listState.requestScrollToItem(0)`（196，渲染前 effect 相同步注册，下一帧布局直接按位置定位，152-153）。
- 随后**常驻**启动守卫 snapshotFlow（208-262，见 §4）——守卫只在消息数曾变化后才挂接。
- dispatch 量：仅消息条数变化触发（新消息插入）；**流式 part 增长不触发**（messageCount 不变，221-223 注释）——流式期的贴底由 COMP-MSG 注入 + GUARD stream-instant 承担。
- 同族：revealBannerCount 锚底 effect（ChatMessageList.kt:700-723，压缩尾部兜底横幅，requestScrollToItem(0)）。

## 6. 同一帧内执行顺序 / 竞态

一帧的典型序列（流式增长帧）：
1. **48ms flush**（后台 Default 线程）→ `_parts` StateFlow 发射。
2. 重组：chatEntries 重建（ChatMessageList.kt:748）、streaming item 内容更新。
3. **measure 遍**：COMP-MSG layout 块测量 → realHeight 增长 → enqueue injectDelta（帧 k）+ version++ → 本遍上报旧基准（裁剪）。
4. **帧 k+1 回调相（Choreographer 动画相，早于 measure）**：PreRenderShiftChannel 泵 drain → dispatchRawDelta 位移落地。
5. 帧 k+1 measure 遍首消费 request-position → COMP-MSG 复测（version 订阅使本节点强制重测）→ 全量揭示。
6. 若 offset 跨过 100 阈值/滚动沿变化：守卫 snapshotFlow 发射 → GUARD stream-instant `requestScrollToItem(0)`（渲染前 effect 相注册，同帧或下一帧布局生效）。

**双重补偿窗口**：COMP-MSG 的 dispatchRawDelta 位移（帧 k+1）与 GUARD 的 requestScrollToItem(0) 绝对锚定可能落在相邻帧——注入已把视口推回贴底（offset<100）后，若 isAtBottom 尚未翻 true 前守卫快照发射，requestScrollToItem(0) 再锚一次（幂等同位置，无害但多一次失效）；反向窗口：GUARD 已锚底而注入残量未落地（shiftSettled=false 时揭示被裁剪，位置无跳变——#258 竞态门 108-110 堵「揭示先于位移」）。滚动中的 holdReveal（ScrollCompensation.kt:92-105）保证 fling 期间既不注入也不揭示，与用户 scrollBy 零冲突（旧 scrollToBeConsumed 直写通道已删除，PreRenderShiftChannel.kt:15-20）。

**isStreamingMsg（completed 时间戳）与 streamingTurnActive（activityFlow FSM）是两套判定**：前者管补偿挂载，后者管 GUARD 去抖跳过——存在短暂不一致窗口（turn 完成事件先到 FSM、completed 时间戳后落，或反之）。

## 7. 启用条件矩阵

| 机制 | isStreaming | isAtBottom | isScrollInProgress | 用户手势/其他 |
|---|---|---|---|---|
| 48ms flush | 恒开（有 delta 即批） | - | - | - |
| StreamingMarkdownState 试点 | 挂载于非 user 流式 item（STREAMING_MD_PILOT flavor 开关） | - | - | 非前缀→重建 |
| COMP-MSG（layout 补偿挂载） | isStreamingMsg=true 才挂（1599） | - | holdReveal=滚动中裁剪（165） | - |
| 注入实际生效（onMeasure extra>0 分支） | 挂载即算 | shouldCompensate=false（贴底）时全量揭示不注入（ScrollCompensation.kt:113） | 滚动中只裁剪（100） | - |
| shouldCompensate=true | - | 滚离后置 true（ChatMessageList.kt:502-503） | scrolling=true 置 true | 恢复贴底置 false（504-505） |
| GUARD stream-instant | streamingTurnActive=true（338） | !atBottom | !scrolling | !jumpLock ∧ !lease |
| GUARD 去抖 250ms | streamingTurnActive=false | !atBottom | !scrolling（含复查） | 同上 |
| autoScroll 再武装 | - | atBottom | !scrolling（稳定 250ms） | - |
| autoScroll=false（用户接管） | - | - | scrolling=true 置 false（137） | 拖动/fling |
| MSGEFFECT | - | - | 等 fling 结束（≤2s） | autoOn ∧ !jumpLock ∧ !lease |
| ForceScrollExecutor | - | - | 等 fling（2s）/租约（3s） | 用户发消息显式触发（forceScrollTick） |
| PENDING animate | - | - | 等 fling（2s） | pendingCount 增长 |

## 8. 流式结束（turn 完成）时各机制退出

- **streamingMsgId→null**：completed 时间戳写入（ChatMessageList.kt:404-408）⇒ `compensateState`/`msgReveal`/`toolReveal` 以 streamingMsgId 为 key 重建归零（471-475）——COMP-MSG 修饰符因 isStreamingMsg=false 整体卸下（1599 分支切换），欠账清理由重建即完成。
- **延迟分片登记**：`LaunchedEffect(streamingMsgId)` 检测 null 边沿记 turn key 交 renderSupply（789-799）——防视口内 key 裂变闪跳。
- **GUARD 退出 instant 分支**：sessionMeta.isStreaming 翻 false（activityFlow FSM）⇒ 守卫回落 250ms 去抖分支（228 条件不再成立）。
- **完结跳变吸收**：完结后归一化+分片接管（MarkdownContent.kt:586-588 注释），此时补偿已卸载——该一帧跳变依赖 GUARD 去抖分支（若 autoOn）或 LazyColumn 原生锚定兜底；autoOn=false 时视口留在用户位置（用户优先权铁律）。
- **buffer 定时器**：48ms batchJob flush 完自然结束；stale delta 由 isStaleDelta/终态守卫过滤（MessageEventHandler.kt:293-320）。

## 附：已知设计张力（供整合参考）
1. GUARD stream-instant 依赖 Triple 快照发射，流式匀速增长若不跨阈值不发射（§6）；实际贴底主要由 COMP-MSG 注入逐帧维持，GUARD 兜底大漂移（100-444px 量级）。
2. isStreamingMsg（渲染补偿）与 streamingTurnActive（GUARD）双真相源存在窗口错位。
3. 反射三件套（scrollPosition/requestPositionAndForgetLastKnownKey/measurementScopeInvalidator）绑定 Compose BOM 2026.05.01，有 JVM 单测钉签名（ScrollCompensation.kt:199-207）。
