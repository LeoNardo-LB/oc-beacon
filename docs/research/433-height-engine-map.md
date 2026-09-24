# 433 — 高度预渲染引擎（PreRender 协调器体系）代码架构地图

> 侦察（#433）。只读侦察，无代码改动。所有结论附 文件:行号。
> 核心文件：
> - `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/scroll/PreRenderCoordinator.kt`（151 行）
> - `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandReveal.kt`（1701 行）
> 设计 spec：`docs/specs/2026-09-21-pre-render-coordinator-design.md`

---

## 1. 引擎公开 API

### 1.1 PreRenderCoordinator（单例 object，主线程专用）
`PreRenderCoordinator.kt:49`。单一职责：列表高度变化 → 视口滚动配对的仲裁权威（KDoc :21-24）。

| API | 位置 | 语义 |
|---|---|---|
| `activeCount: Int` | :62 | 活动渲染前事务数（快照 `mutableIntStateOf` backing :64，让位方 snapshotFlow 读它即订阅生效） |
| `hasActiveTransactions: Boolean` | :67 | `backing > 0`；守卫/锚底/拉底让位判定 |
| `suspend fun <T> withEpisode(block): T` | :74-83 | 事务作用域：进入 +1、try/finally -1（取消/异常必释放，:71-72「租约泄漏=守卫永久哑火」）。线程语义：仅主线程读写，无原子原语（:46-47, :75-76） |
| `isFlushHostAttached: Boolean` | :97 | FLUSH 宿主 View 存活与否（WeakReference :90）；组件据此选 flush 路径 or 降级路径 |
| `fun attachFlushHost(view)` | :100-106 | 挂接宿主（ChatMessageList 组合期，LocalView）；FLUSH_PHASE_ENABLED=false 直接 return（:55, :101） |
| `fun detachFlushHost(view)` | :109-113 | 组合销毁严格摘除，与 attach 配对 |
| `internal registerFlushTask(task)` / `unregisterFlushTask(task)` | :116-124 | FLUSH 任务表（注册序即执行序 :88）；首任务到达挂监听、表空摘监听（空闲零开销） |
| `internal fun runFlush(): Boolean` | :130-136 | 单点排空：迭代副本防重入，任一任务返回 false → 本帧拒绘 |
| `internal const FLUSH_PHASE_ENABLED = true` | :55 | A/B 取证总开关；false → 引擎整体降级回 placed+stab 路径 |

### 1.2 PreDrawFlushTask（fun interface）
`PreRenderCoordinator.kt:16-18`：`fun onPreDraw(): Boolean`。返回 **true=允许本帧绘制；false=残差未闭，拒绘并请求本帧重排**——「增长已落地但配对未完成」的中间态从构造上不可能上屏（Chromium dual-phase 同款语义，:12-14）；拒绘次数由任务方自限 ≤2/帧（spec §8）。

### 1.3 CardExpandReveal.kt 导出的配对执行器（引擎的"手"）
- `applyPreRenderShift(ls, deltaPx)`（:148-159）：旧单发版，异常降级为渲染后推挤一帧。
- `applyPairedPreRenderShift(ls, deltaPx): Float`（:190-213）：**配对到全额**执行器——while 循环 `dispatchRawDelta`，残差经 `PairedDispatch.nextCommand`（:173-184，MAX_TRIES=4、亚像素 0.5f 量化阈、0 消费=物理边缘终止）重试至全额；返回总消费量。背景见 :161-171（#427 跨锚点测量竞态，单发漏 −268px/周期）。
- `bottomPinnedExpandSkip(fii, fiso)`（:1539）：严格贴底（fii==0 ∧ fiso==0）免派发判定——贴底布局以最新 item 为锚，dispatch +H 反而把视口推离贴底（:786-795）。半贴底域（fii==0, fiso>0）未取证，保守不启用（:1536-1538）。
- `settleFrameAdvances(measures, lastMeasures, measuredH)`（:1550-1551）：测量计数静止 ∧ H>0 才计稳（H>0 门槛防「组合间隙早熟判稳」，:1543-1549）。

---

## 2. 内部状态机与账本字段（CardExpandClock，:247-513）

### measure 阶段记录什么
几何节点 `CardExpandGeometryNode.measure`（:1631-1678）每遍：
1. `clock.recordMeasure()`（:1635）→ `measureCount++`（:505-507，plain，settle 判稳轮询用）。
2. 真测或复用缓存 placeable（tweening 窗口 :1647-1658；epoch 失效 :1638-1642；fraction≤0 强制真测防分离 LayoutNode 崩溃 :1645-1646）。
3. `clock.onMeasure(placeable.height)`（:1659）→ 写 `lastMeasuredH`，返回上报高度 `report = (fraction×H).toInt()`（ε 预热窗口 f≤0.001 上报恒 0，:332-335）。
4. `clock.noteSteadyReport(report)`（:1663）→ **稳态账本记账**（见 §3）。
5. `layout(width, report)` 上报（:1675）——未揭示部分由外层 clipRect 裁掉（:1417-1438）。

### 账本字段一览
| 字段 | 位置 | 语义 |
|---|---|---|
| `fraction` | :249 | 揭示分数，measure 读它建订阅 |
| `lastMeasuredH` | :253 | 最近实侧全高（0=从未测得） |
| `lastReportedH` | :264 | 上一帧已落地上报高——#424 双态：episode 中由 absorb 独占写（吸收驱动），稳定态跟随内容实测（:256-263） |
| `absorbedF / absorbedPx` | :268, :349 | 吸收账本（浮点原子，逐帧取整误差 telescoping ≤1px）；`primeLedger` :339、`absorb` :344 |
| `animating / tweening / measureCount / remeasureEpoch` | :271, :278, :285, :292 | 动画态 / placeable 缓存窗口 / settle 计数 / episode 末强制复测信号（:510-512） |
| `episodeDisplacement / departureFired / programmaticShift` | :296, :299, :398 | 本集累计位移（离底 >100px 触发 departure 回调 :703-706）/ 程序化位移豁免（防取消守卫误杀，:394-398） |
| `episodeShiftConsumedPx / episodeAnchorItem / episodeAnchorOffset` | :383, :391-392 | 展开集实消费位移（收起镜像回退基准 :377-382）/ 展开前锚点状态（收起按构造精确恢复 :386-392） |
| `pinTargetFraction / steadyHold / steadyRetryDeadlineNs` | :405, :479, :482 | FLUSH 修正器灭钉门控 / steady 挂起门 / 0 消费欠账 2s 重试窗（:139） |

### 何时/如何结算（settle）
- `settleUntilContentStable`（:1563-1585）：SETTLE_STABLE_FRAMES=2（:228）连续 2 帧 `settleFrameAdvances` 为真即稳；上限 MAX_SETTLE_MS=600ms（:234）防无限等。
- episode 主状态机（`LaunchedEffect(visible, listState)` :725-1024，整体包在 `PreRenderCoordinator.withEpisode` 内 :729/:1023）：
  - **展开**：warmup（:763）→ settle（:764）→ `Snapshot.withMutableSnapshot { driveTo(1f) }` 布局终态同步落地（:774-777）→ programmaticShift 包裹下 `applyPairedPreRenderShift(+H)`（:781-808，贴底时 skip-dispatch）→ 欠账 rebase（:816-823，贴底 skip 时 plain rebase :820，否则 `steadyRebaseAfterEpisodeDispatch` :822）→ steadyHold=false（:824）→ 离底关 autoScroll（:828-830）→ 200ms 纯绘制幕布（:834-847）。
  - **收起**（批次十四镜像化 :849-944）：draw-only 幕布收拢（:860-872）→ 锚点已知时 `LazyListReflection.requestScrollToItemNoCancel` 反射待定区恢复展开前锚点、与快照塌缩同遍 measure 原子生效（:886-909）；锚点未知且非用户滚动 → 镜像位移 `applyPairedPreRenderShift(-consumed)`（:914-928）→ plain `steadyRebase`（:934，防集末 flush 双配对 :931-933）。
  - **finally**（:958-1021）：loading 复位、drawFraction/curtain 兜底、`requestRemeasure()` 强制真测（:971）、仅取消/异常路径防御性 rebase（:988，正常完成集的欠账保留给 flush 补派 :983-986）、end-restore 已退役仅 probesResolved 门残留（:992-1008）、锚点携带（:1019）。

---

## 3. pre-draw flush 完整流程

### 谁注册 / 何时触发
- **宿主**：ChatMessageList 组合期 `DisposableEffect { PreRenderCoordinator.attachFlushHost(LocalView.current) }`（ChatMessageList.kt:674-678）——列表根即整窗绘制前单点（PreRenderCoordinator.kt:99-106）。
- **监听**：`syncListener`（:138-144）在「有宿主 ∧ 有任务」时挂 `ViewTreeObserver.OnPreDrawListener`（:94），每帧 draw 前回调 `runFlush()`。
- **任务方**（CardExpandReveal.kt）：
  1. **稳态配对任务**（#430，:1067-1121）：fraction>0 期间注册，snapshotFlow 等 fraction≤0 后 finally 注销（:1115-1120）。
  2. **FLUSH 实测钉位修正器**（#423 批次三，:1166-1341）：pinPending 期间注册（:1335-1340）。

### steady pairing 确切机制（measure 差值 ↔ dispatchRawDelta 配对）
1. **记账（measure 相）**：`noteSteadyReport(report)`（:424-441）——fraction≤0 恒清零（:425-429）；`steadyLedger == Int.MIN_VALUE` 时静默起基线不配对（:430-433）；否则 `d = report − steadyLedger`，非零则 `steadyPending += d`、基线前移、重试窗起算 2s（:434-440）。基线/rebase 协议：`steadyRebase`（:444-448，episode 派发点/收起点/取消点调用防 episode 自身跳变被重复配对）；`steadyRebaseAfterEpisodeDispatch(targetRep, consumed)`（:458-462，欠账模型：基线锚定派发目标、`steadyPending = 目标−实消费 coerceAtLeast(0)`——transient 0 消费的配对义务不丢，真机 19:34 定罪注释 :450-457）。
2. **派发（pre-draw flush 相）**：任务回调（:1070-1114）决策链：
   - `steadyHold`（集内 dispatch 决策点前）→ 挂账不派发（:1071）；
   - `listState.isScrollInProgress` → `steadyRebase()` 弃配（阅读位置优先权，:1072-1075）；
   - `lastReportedH <= 0`（增长未落地，布局无配对容量，派发必 0 消费且被清账=死亡）→ 等下一 pre-draw（:1076-1080，真机 19:46 定罪）；
   - `d = takeSteadyPending()`（取后即清 :486-490）；d==0 → 放行（:1082）；
   - programmaticShift 包裹 `applyPairedPreRenderShift(listState, d)`（:1083-1085）。

### 配对失败时怎么办
- **欠消费/0 消费**：`|d − consumed| ≥ 0.5f` 且仍在 2s 重试窗内 → `restoreSteady(d − consumed)` 残量回账下帧重试（:1089-1093，语义 :464-471）；窗外（真物理边缘）丢弃——「迟到的纠正比永久偏移更糟」（:1087-1088）。
- 消费量同步入 `episodeDisplacement`（:1094）与 `episodeShiftConsumedPx`（收起镜像账本须含稳态派发，:1095-1096）；离底越阈触发 departure（:1097-1100）。
- 另有第二重残差重试在 `applyPairedPreRenderShift` 内部同帧完成（§1.3，MAX_TRIES=4）。

### 附：FLUSH 实测钉位修正器（:1195-1334，三路 dispatch 共享 pinDispatchedTotal 防双发 :647-652）
账本阶段（rep 即时全额修正，:1232-1251）→ 实测阶段（新鲜坐标核销残差 :1260-1294，坐标同源戳 coordStamp==lastReportedH 才确认 :1263/:1284）→ hold 拒绘（分数到位未确认恒拒绘，:1318-1329，呼吸阀 HOLD_VENT_FRAMES=30 :217）→ 灭钉（稳定×3 ∧ 几何到位 ∧ 确认 ∧ 静默 1.8s，或超时 2s/6s，:1299-1314）。批次九轮 2 曾整体退役（:741-743「钉位武装/FLUSH 修正环退役」注释），但代码仍活跃注册——**注释与实际状态存在张力**（见 §7）。

---

## 4. 流式文本 item 增长：引擎当前**完全不管**（确认边界）

**确认：不接入。** 三重边界：
1. **组件级降级**：`LocalInStreamingTurn == true` 时 CardExpandReveal 直接退化为裸 AnimatedVisibility，引擎整个旁路（CardExpandReveal.kt:543-554；KDoc :63-65「杜绝双重注入」）。ChatMessageList 逐 item 注入 `LocalInStreamingTurn provides entryStreaming`（ChatMessageList.kt:2461；全列表注入点 :210）。
2. **租约 Phase 3 未接线**：KDoc 明示「后续 SSE 流式(registerStreaming)按 spec Phase 2/3 接入」——尚未实现（PreRenderCoordinator.kt:33-34）。
3. **流式增长由另一套机制承担**：`PreRenderShiftChannel`（PreRenderShiftChannel.kt:46-140）自述「**流式家族专用**」运输层（:9-13）——measure 块内 `enqueue`、帧边界 `drain` → mid-list `dispatchRawDelta`（:124）或贴底收缩放弃（:106-112），由 ChatMessageList 帧泵驱动（ChatMessageList.kt:1246-1252）；以及 GUARD 流式瞬时重锚 `requestScrollToItem(0)`（ChatScrollController.kt:221-238）。

即：**卡片引擎只管「已完结消息内卡片展开/收起 + 该卡片自身内容的 episode 外迟到增长」；流式 turn 的文本/part 增长由 PreRenderShiftChannel + MSGEFFECT/GUARD 通道独立处理，两体系互不感知。**

---

## 5. viewport lease（hasActiveTransactions）机制

- **租的谁**：CardExpandReveal 的 episode 全程——`LaunchedEffect(visible, listState)` 体内整体 `PreRenderCoordinator.withEpisode { … }`（CardExpandReveal.kt:729-1023），含 finally 收尾（end-restore/迟到增量/锚点携带）；快速反向 toggle 取消旧集 → finally 必释放，新集立即重持（计数嵌套语义，:726-728）。
- **租期**：episode 最坏链 ≈ settle 600ms + stab + 幕布 240ms + 收尾 ≈ 2s（ChatScrollController.kt:426-427 LEASE_WAIT_MS=3000 富余）。
- **与外部滚动仲裁的关系**（全部读 `hasActiveTransactions` 让位）：
  1. MSGEFFECT 新消息锚底：入口判定（ChatScrollController.kt:167-169）+ fling 等待后复查（:188）；被跳过的锚定由守卫 catch-up（:165-166 注释）。
  2. GUARD 守卫重锚：入口（:218-220）+ `AutoScrollArbiter.reanchorWhenSettledOffBottom(leaseActive=…)` 去抖复查点承重（:249, :464-477——动画静默窗不产生 Triple 再发射，仅入口判定不足以关闭战争窗）。
  3. ForceScrollExecutor 强滚锚底：`leaseActive` 有界等待 3s（:278, :397-403）；超时仍强制（发送后跟随的用户显式意图优先，:368-370）。
  4. PENDING 拉底：入口判定（:297）——animateScrollToItem 走 scroll{} 会取消在途 episode，故让位本周期（:295-296）。
  5. ChatMessageList 横幅锚底（A9）：入口（ChatMessageList.kt:709-711）+ fling 等待后双检（:719）。
  6. 预热让位：CardExpandReveal 空闲预热 effect 有界等待集结束（CardExpandReveal.kt:1041-1047，250ms×8 上限）。
- **设计动机**：十一轮收口实证守卫与 episode 位移指令共享视口互搏（±H 战争，双向受害），租约从构造上消除该竞态类（PreRenderCoordinator.kt:27-31）。

---

## 6. 全部调用方接入方式

### 宿主/控制器接线
| 接线点 | 位置 | 内容 |
|---|---|---|
| FLUSH 宿主挂接 | ChatMessageList.kt:674-678 | attachFlushHost/detachFlushHost（DisposableEffect） |
| Local 三件套（列表级） | ChatMessageList.kt:208-210 | `LocalCardExpandListState provides listState` / `LocalCardExpandDeparture provides onExpandDeparture` / `LocalInStreamingTurn provides streamingActive` |
| Local 三件套（entry 级） | ChatMessageList.kt:2459-2461 | 同上，`entryStreaming` 逐 item 供给 |
| 兜底包装 | ChatMessageList.kt:212 | `CardExpandReveal(visible=visible){content()}`（恒驻声明项内容层） |

### CardExpandReveal 调用方（11 处）
MessageBubble.kt:218 · MessageCardAssistant.kt:360, 428 · QuestionPartContent.kt:140 · ReasoningBlock.kt:302（`cacheKey = pinKey.ifEmpty { null }`）· EventCard.kt:186 · CompactionCard.kt:172 · InjectionCard.kt:111 · ToolCardScaffold.kt:263 · GlobToolCard.kt:93 · TodoListCard.kt:159。各卡片以 drop-in 替换原 AnimatedVisibility（CardExpandReveal.kt:516-519）；可选参数：`cacheKey`（finalH 缓存 :524-525）、`prewarmEligible`（预热资格谓词 :532）、`onExpandComputing`（#429 loading 过渡信号 :538）。

### ChatScreen 侧
ChatScrollController（ChatScrollController.kt:168/188/219/249/278/297）与 ChatMessageList（:710/719）经 `PreRenderCoordinator.hasActiveTransactions` 读取租约；无其他 ChatScreen 直连点（引擎完全封装在 components/scroll 两目录内）。

---

## 7. 可疑点 / 被注释的历史代码 / TODO

1. **死代码残留**：`drainPhaseA`（CardExpandReveal.kt:671-710）仅降级路径触发（:1398 `!isFlushHostAttached`），FLUSH 开启后常态不跑；`applyPreRenderShift`（:148-159）、`dispatchClosedLoop`（:1493-1531）、`closedLoopCommand`（:1485-1486）、`easedFraction`（:1469-1472）、`episodeEndCorrection`（:1457-1466，仅 probesResolved 门 :996 保留供单测）均为主路径退役的存量函数。
2. **注释与状态张力**：:741-743 注释称「钉位武装/FLUSH 修正环/两阶段揭示全部退役」，但 pinPending/FLUSH 修正器（:1166-1341）与两阶段 drawFraction 机制仍在活跃注册运行；pinAnchor/pinPending 的置位入口在本文件内未见（历史遗留，实际是否可达待真机日志确认）。
3. **显式撤除待重做**：#430 大组硬切换锚定「五轮真机迭代均错位，已撤，待 L3 AST 切片批次以 layoutInfo 键匹配方案重做」（ChatMessageList.kt:769-774）；#429 B 方案 movableContentOf 勘误撤除（CardExpandReveal.kt:1407-1410）。
4. **Phase 2/3 未接线**：registerSwap / registerStreaming 按 spec 未接入（PreRenderCoordinator.kt:33-34）。
5. **单例局限**：单例即每列表语义，多列表场景需组合局部化改造（KDoc :38-39 备案）。
6. **withEpisode 缩进债务**：:1023 注释「缩进未重排：热文件零 churn，引擎迁入时整体重构」。
7. **steadyHold 门控风险面**：稳态任务注册条件为 `clock.fraction > 0f` 快照初值（:1067-1068）——fraction 恒>0 的「组合保温」卡持有 flush 任务贯穿全会话生命周期，每帧 pre-draw 都跑决策链（多数走 d==0 早退，量级小但非零）。
8. **半贴底域未取证**：bottomPinnedExpandSkip 不覆盖 fii==0 ∧ fiso>0（:1536-1538），待观察放宽。
9. **FLUSH_PHASE_ENABLED** 定案后恒 true，但开关与降级路径（placed+stab）仍整链保留（:52-55），属「观测期未清理」状态。
