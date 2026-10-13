# SSE Scroll Stability — Iron Laws & Regression History

> **权威文档**。AGENTS.md 的 "SSE Scroll Stability" 段落和代码内注释均引用本文档。
> 任何修改聊天列表滚动行为的人，**必须先读本文档**。

## 1. 背景：SSE → UI 管道

流式输出（SSE token）到达 UI 的管道：

```
SSE token 到达
    ↓
100ms delta 批处理（MessageEventHandler.scheduleFlush，周期常量 STREAM_FLUSH_INTERVAL_MS 定义于引擎域 ScrollCompensation.kt）
    ↓ 单次 flush = 1 次 StateFlow 更新 = 1 次重组
高度补偿（layout{} modifier，仅 streaming message）
    ↓ requestScrollToItemNoCancel 抵消高度增长
渲染
```

违反管道任何一环，都会重新引入闪烁 / 块状输出 / 视窗跳动。

## 2. 五条铁律（2026-07 修正版）

### 铁律 1：`Markdown()` 必须用 `rememberMarkdownState(content, retainState=true)`

无状态的 `Markdown(content=...)` 每次重组都重新解析 markdown AST → 高度振荡 → 闪烁。

**位置**：`markdown/MarkdownContent.kt` 的所有渲染入口。

### 铁律 2：`scheduleFlush()` 绝不能取消正在运行的 timer

每个 token 都取消 in-flight timer 会在到达速率高于批周期（100ms，见 §1）时饿死 flush → 块状突发输出。

**位置**：`MessageEventHandler.kt:58`。实现：`if (batchJob?.isActive == true) return`。

### 铁律 3：流式增长配对只作用于 streaming message（#435 起为 streamingGrowPairing）

对所有 assistant 消息作用会让已完成消息暴露在不稳定测量下 → 已完成消息也跳动。

**位置**：`ChatMessageList.kt` 的 `itemModifier` 条件分支 `if (isStreamingMsg)`。（#435：延迟揭示 deferredRevealCompensation/COMP 家族已退役，挂载纪律不变——改为 `streamingGrowPairing` 记账入 `StreamingGrowLedger`，由 PreRenderCoordinator pre-draw flush 按「锚即意图」统一规则配对派发，见 spec 2026-09-25-435。）

**注意 multi-message turn**：`isStreamingMsg` 用 `(turnGroups[rawIndex] ?: listOf(msg)).any { it.message.id == streamingMsgId }`（提交 `92a30e48`）。因为 displayItems 的 turn 代表是 **oldest**，而 streaming 是 **newest**，单消息匹配会让 multi-message turn 补偿失效。`.any{}` 是正确的。displayItems 每 turn 只 1 个代表 item，所以 `.any{}` 不会导致补偿泄漏到多个 item。

### 铁律 4（★ 本次修正）：LaunchedEffect 必须双 key `(isScrollInProgress, isAtBottom)`

> **#435 更新（2026-09-25）**：shouldCompensate 机制已随 COMP 家族退役——流式增长配对改由 StreamingGrowLedger 的「锚即意图」纯几何规则承担（贴底跟随族/读历史免派发，锚上移进入增长源 +Δ 同帧配对），不再依赖任何 LaunchedEffect 旗标。**autoScroll 的双 key 语义不变**（ChatScrollController 的 snapshotFlow 双值流等价式），本铁律对 autoScroll 继续有效。

> **这条铁律曾经写反了**，是 2026-07 本次回归的直接原因。详见第 3 节。

`autoScrollEnabled` 和 `shouldCompensate` 的 LaunchedEffect **必须同时 key `isScrollInProgress` 和 `isAtBottom`**。

`isAtBottom` 作为 key 是**自愈机制**：当用户通过**非拖动方式**回到底部（fling 惯性、SSE 内容推送、补偿滚动），`isScrollInProgress` 不会变化（这些不是手势拖动），但 `isAtBottom` 会从 false→true。只有把 `isAtBottom` 放进 key，LaunchedEffect 才会在这些时刻重新触发，及时把 `shouldCompensate` 重置为 false、`autoScrollEnabled` 重置为 true。

**单 key（仅 `isScrollInProgress`）的后果**：
- 用户回到底部后，`shouldCompensate` 卡在 `true` → 每个 SSE token 的 `delta > 0` 都触发 `requestScrollToItemNoCancel` → 视窗周期性跳动
- `autoScrollEnabled` 卡在陈旧值 → `LaunchedEffect(messageCount)` 在不该 scroll 时 scroll

**位置**：
- `ChatScreen.kt` autoScroll LaunchedEffect
- `ChatMessageList.kt` shouldCompensate LaunchedEffect

### 铁律 5（★ 2026-07 第二轮修复发现）：streamingMsgId 只依赖消息 completed 时间戳，绝不能加 `.takeIf { sessionMeta.isStreaming }`

> **`668384e3` 加了这个 takeIf，导致补偿完全不工作——是"被拖着往下走"的真正元凶。**

`streamingMsgId` 决定了哪个 item 会被套上高度补偿 modifier。它**必须**只看消息自身的 `time.completed == null`：

```kotlin
// ✅ 正确（v360 验证）
val streamingMsgId = remember(rawMessages) {
    rawMessages.lastOrNull { it.isAssistant && it.message.time.completed == null }?.message?.id
}

// ❌ 错误（668384e3 引入的回归）
// sessionMeta.isStreaming 在生产中会卡在 false（activityFlow 检测失效），
// 强制 streamingMsgId=null，关闭所有补偿。
?.takeIf { sessionMeta.isStreaming }
```

**根因证据**（诊断日志 v443）：整个 SSE 输出会话期间 `streamingMsgId=null`、`isStreaming=false`、**零条** `MSG_LAYOUT` 事件。补偿从未触发过。

**为什么 sessionMeta.isStreaming 不可靠**：它来自 `SessionStateService` 的 activityFlow（经过 FSM 转换），中间环节多（SSE 事件 → handler → FSM → activityFlow → sessionMeta）。任一环节失效都会导致状态卡住。而消息的 `completed` 时间戳直接反映数据状态，零间接层。

**位置**：`ChatMessageList.kt` 的 `streamingMsgId` 定义。

## 2.4bis 高度参照系与动态性铁律（2026-10-13 用户裁决，七报定罪催生）

> **高度原点**：`reverseLayout=true` 下，会话流**最底部**是高度计算的 0 点（fii=0/fiso=0=绝对底；一切锚定/几何在该参照系内表达）。
>
> **高度是动态的**：流式期间一切高度（item 高/内容高/揭示盒高）随内容持续变化。任何「稳定高度」的正确计算公式是：
>
> **稳定高度 = 高度改变事件发出时刻的当前高度 + Δ高度变化**
>
> 即锚定**事件时刻**的采样，绝不冻结、绝不跨事件累积（max 棘轮/历史高水位/暂态膨胀高都是违规形态）。

**为什么（2026-10-13 七报实证，/home/leonardo/.cache/540r5/p7_900_full.log）**：

1. **max 棘轮种子违规**（#549 Fix A 引入面的教训）：终高种子 `if (sz > seed) seed = sz` 把首次展开期的暂态测量（分期表格/重组前的高值）永久钉住——第二次展开驱动直奔虚高 tgt=13807（itemH 当时仅 4672），全高配对让位把视口推飞 +11.6k px（fiso 2155→13813）＝用户「第二次点击跳到上面」。
2. **巨卡全高让位未按动态视口截断**：揭示高远超视口时，「离底让位」逐帧配对的总位移=揭示全高（本例 ~5 屏）——正确语义应截断到「揭示顶部到达视口顶」的动态量（事件时刻计算），而非全高。
3. **一帧滞后细节**：帽预记 `reserved = trueHeight + dStep` 中 trueHeight 是上一次 measure 的采样——事件时刻与采样时刻间流式增长会使锚点偏一帧的增量（后续帧 hatDelta 吸收、无净差，但设计新高度机制时必须显式声明该滞后，禁止假设采样=当下）。

**验证公式（随需求逐案累积，追加于本节末尾）**：

-（待后续每次高度修改需求时分析总结后补充——格式：场景 / 事件时刻量 / Δ / 稳定高度验证式 / 实测对照）

## 2.5 滚动性能铁律（2026-08 追加）

> 本节处理**滚动卡顿/跳过**（非流式稳定性）。2026-08 连续 6 轮迭代（v1→v6）的经验。
> 核心结论：**卡顿是分层的**——数据层、派生层、渲染层、框架层各贡献一部分，
> 任何一层单独修复都不够，必须逐层排查。

### 铁律 6：派生计算缓存必须"内容感知"，禁止整体禁用或纯结构缓存

`renderableTurns`（每消息渲染数据）的缓存策略有三个失败案例，一个正确方案：

| 策略 | 失败原因 |
|------|---------|
| ❌ **整体禁用**（`useCache = activeTools.isEmpty()`） | 工具运行期间每 48ms 全量重算 ~1000 条 → 工具活跃时滑动必卡（用户实测"一开始不卡、用一会儿后卡"） |
| ❌ **纯 id 缓存**（`msg.message.id` 命中即用） | 流式期间消息 id 稳定但 parts 每 48ms 变 → 气泡冻结在首个 token（历史回归 `37d9a6ac`） |
| ❌ **列表引用复用**（结构未变时返回上一轮 List） | 乐观消息插入后新旧列表长度/位置错位 → `renderableTurn is required for ASSISTANT role` 崩溃 |
| ✅ **内容指纹缓存** | 指纹覆盖变异字段（Text/Reasoning 尾部 hash + Tool output 尾部 hash + completed 时间戳 + error），指纹相同 → 复用实例；变化 → 只重算该消息 |

**指纹设计要点**（`messageFingerprint` / `partsFingerprint` / `toolFingerprint` / `tailHash`）：
- **只 hash 会变异的字段**：`tailHash`（末 64 字符）+ 长度——SSE 追加/工具注入只动末尾，避免全文本逐字符 hash（大文本每 48ms 全量 hash 同样卡）
- **Tool 状态流转覆盖**：Running→Completed（服务器替换）时 output 变化 → 指纹变 → 重算；output 相同时指纹相同 → 缓存安全
- **消息级字段**：`time.completed`（durationMs 显示）+ `error`（errorText 显示）——不在 parts 里，必须单独纳入指纹
- **流式消息强制排除**（`msg.message.id != streamingId`）——流式消息永远走重算分支（内容每 48ms 变，缓存无意义）

**位置**：`ChatMessageList.kt` 的 `renderableCache` + 指纹函数（文件末尾）。

### 铁律 7：LazyLayoutCacheWindow 参数必须与数据层稳定性联动

`LazyListState(cacheWindow = LazyLayoutCacheWindow(ahead, behind))` 是 **Compose LazyColumn 的虚拟布局缓存窗口**（以**视口**为单位，不是 item 数）：
- `ahead`（滚动方向前方）：预组合量——fling 高速滚过来直接显示
- `behind`（滚动方向后方）：保持量——滚回去不销毁重建

**历史迭代**（每个值都是特定数据层状态下的局部最优）：

| 版本 | 窗口 | 数据层状态 | 结果 |
|------|------|-----------|------|
| v1 | 1.5 / 1.0 | 每 48ms 全量重建 + renderableTurns 全量重算 | 不跳了，但反复滑动卡（窗口内 item 全量重组） |
| v2 | 1.0 / 0.5 | 同上 | 仍卡 |
| v3 | 0.75 / 0.25 | renderableTurns 指纹缓存 | 基本不卡，但工具期间卡（铁律 6 整体禁用问题） |
| v5 | **1.5 / 1.5（对称）** | + ChatMessage 实例缓存 + turnGroups/jumpTargets 签名缓存 | ✅ 摩擦保持 + fling 预组合，无卡顿 |

**教训**：
- **大窗口 + 数据层不稳定 = 灾难**（窗口内 item 全量重组）；**数据层稳定后大窗口是必须的**
- **behind 太小**（0.25）→ 拖拽摩擦（小范围来回）时 item 反复销毁重建（Markdown 树反复构建）→ 拖拽开始卡——日志特征：同一 idx 几十毫秒内多次 `composed`
- **ahead 太小**（0.75）→ fling 高速滚动时视口瞬间滚出已组合区域 → 新 item 被迫即时组合 → fling 启动卡
- 对称窗口（1.5/1.5）覆盖上下两个方向

**位置**：`ChatViewModel.kt` 的 `listState`。

### 铁律 8：消息列表层的派生计算必须签名缓存（分配风暴控制）

**"用一会儿后卡"的根因是 GC 分配风暴，不是缓存满**：数据层 combine 每 ~48ms 全量重建全部消息（即使只有最后一条在变）→ 下游所有 `remember(rawMessages)` 派生计算（turnGroups/jumpTargets/renderableTurns）全部重算 → 每轮分配 ~2000+ 对象 → 堆增长 → GC 每 10 秒释放 ~100MB → 卡顿。

**三层缓存缺一不可**：

| 层 | 缓存 | 机制 | 收益 |
|----|------|------|------|
| 数据层 | `MessageDataDelegate.chatMessageCache` | `parts` 与 `message` **引用相等**（`===`）→ 复用上一轮 `ChatMessage` 实例 | 每轮分配 1974 → 1~2 条 |
| 派生层 | `turnGroups` / `jumpTargets` 签名缓存 | 消息 **id 序列签名**未变 → 复用上次 Map/List | 消除每轮 ~2000 entry 分配 |
| 渲染层 | `renderableTurns` 指纹缓存（铁律 6） | 内容指纹命中 → 复用实例 | 消除重算 + 重组 |

**安全前提（引用稳定性）**：`EventDispatcher` 更新 parts/messages 时**只替换变化消息的 List/元素**（`mergeMessages`/`replaceMessages` 均复用 existing 实例），`ToolProgressOutputInjector.inject` 无匹配时返回原引用——**引用相等比较才可靠**。若数据层复制所有消息，引用缓存全部失效（退化为全量重建，但不崩溃）。

**turnGroups 签名缓存的安全性**（历史教训 `37d9a6ac`）：缓存 Map 中的旧 `ChatMessage` 引用由 renderableTurns **miss 分支引用修正**兜底——miss（流式/新消息）时 `turnMsgs.map { if (it.message.id == msg.message.id) msg else it }` 强制当前消息用最新引用。另一读取点（isStreamingMsg 判断）只比较 id，不受旧引用影响。

**位置**：`MessageDataDelegate.kt`（chatMessageCache + `lastCombineSessionId` 切换清理）、`ChatMessageList.kt`（turnGroupsSigRef/jumpTargetsSigRef + miss 分支修正）、`ToolProgressOutputInjector.kt`（changed 标志 + 原引用返回）。

### 铁律 9（#471③，2026-09-30 追加）：流式与完结必须走同一归一化——禁止任何单侧文本变换

**完结瞬间重排跳变族的最后结构性根因**：归一化只在完结发生 = 流式显示文本与完结渲染文本不一致 → 完结换装即一次性重排（真机实证：表格轮 -8544/+7236、纯文本轮 ±24px 残余、#471④ retry 场景 -1330px ③族）。

**规则**：流式 ingest（pilot 包装器入口）与完结渲染（normalizeForRender 全部消费点）必须共用同一归一化核心——normalizeMarkdownCore（CRLF/表格空行/数学降级）→ normalizeForStreaming（pilot）与 normalizeForRender（完结）同序组合，**render == streaming 逐字节不变量入测**（NormalizeSentinelEquivalenceTest）。任何新的文本变换必须同时接入两侧并证明放行单调：回改点落在 SafePrefixGate 扣留区，NormalizationStreamingMonotonicityTest 逐字符增长×gate 放行前缀稳定性性质测试钉死。

**随批四项配套修订**（归一化前移的破口，spec docs/specs/2026-09-30-471-3-streaming-normalization-unification-design.md §3.3 矩阵修订）：gate 尾 `$` run 收口（逐字符凑对）/ 表格三处排除未定案 `$$`、`\[` 行 / 表头行待定三行结构空行毕业回退 / MarkdownFenceLine 统一围栏判定（三变换与 gate 的栏状态分歧——兼修栏内表格插空行、栏内 ```xxx 误闭合两个存量渲染缺陷）。

**位置**：`MarkdownContent.kt`（normalizeMarkdownCore / normalizeForStreaming / splitOversizedParagraphsByPosition）、`StreamingMarkdownPilot.kt`（ingest 前移）、`MarkdownFenceLine.kt`（统一围栏判定）。

## 2.6 流式揭示域铁律（2026-09-30 追加，#437 收口五域收编）

> 2026-09-29 发掘审计定罪的文档同步缺口：本节止于 #435 期间，#437 稳定揭示 / #438 限速与保 key / #472 行内放行 / #474 守卫分通道 / #476 GUARD 死区五域铁律全部散落 journal 未收编——按 R-7 收口要求补齐。

### 铁律 10（#437）：流式揭示只放安全前缀——不稳定尾禁止先排版后回溯重释义

**不稳定尾先字面排版、后回溯重释义 = 已显示内容高度回溯**（真机录屏 A-B 翻转帧定罪；#435 引擎只能配对单调增长，回溯即坍缩）。

**规则**：pilot 差分与 append 之间必须经 `SafePrefixGate`——稳定块 + 开放段纯文字安全后缀两级放行；尾部扣留；完结 EOF 全量 flush。「未闭合构造零输出」（2026-09-25 用户裁决）。**差分基准必须取放行前缀 `normalized.take(released)`，不得用归一化全文快照**（#471③ 验收第三根因：`$$` 闭合重写天然非前缀但落扣留区，SMP 误判重生成 → 静默 resetKey 重建，真机 h 塌缩 -1128 从零重铺×2）。

**位置**：`SafePrefixGate.kt`、`StreamingMarkdownPilot.kt`（spec：docs/specs/2026-09-25-437-streaming-md-stable-reveal-design.md）。

### 铁律 11（#438）：大放行必须壁钟限速，配对 set 必须保 key

**机制**：空行毕业一次性倾泻千字符级 = 单帧数千 px 增长（真机 run4 append max 2087ch）；配对 set 丢 key 走 null-key 裸 index 通道 = 换流式项时补偿错位（#444 fling 跳变回归的嫌疑通道）。

**规则**：①gate 大放行壁钟限速 `BIG_RELEASE_CH=200` / 间隔 ≥200ms（与到达解耦，防止「旧的 EOF 一次性大跳」换装复发为可见铺开闪烁）；②`resolvePairedTarget` 越窗时必须走 `dataKeyAt` key 投影——与 LazyColumn item key 同源（含 chunk 后缀），`remember` key 加 `chatEntries`（重排后记忆清零语义正确）。

**位置**：`SafePrefixGate.kt`（限速）、`ScrollCompensation.kt`（resolvePairedTarget / 让位防御）。

### 铁律 12（#472）：已闭合行内构造即时放行，判定与解析器逐字镜像；非前缀跳变先宽限再重建

**机制**：行扫描对含 ACTIVE_MARKERS 的行整行扣留 → 已闭合 `*斜体*`/`**粗体**`/`` `代码` `` 也憋到段落末一口气倾泻（R9 实证 444ms 聚 7 批 d=6236px）。

**规则**：已闭合 emphasis/code/strikethrough + 纯文字尾巴按纯文字同节奏增量放行（`InlineSpanSafety.safeCut` 逐字镜像 markdown-jvm 0.7.9 的 canOpen/canClose 判定——**解析器升版必须重验镜像**）；保守扣留四情形维持：未闭合开标记 / 硬停字符（`\ [ ] ! | # > $$ ☐☑✅`）/ 快照尾 run（EOF 侧翼未知 + 防跨批 run 撕裂）/ 配对 span 跨扣留点开标记。

**非前缀跳变处置**（#472 验收轮回归教训）：非前缀 ≠ 立即重建——300ms 宽限冻结（`nonPrefixRebuildDue`）等数据层摆动旧串回来无缝续播，超窗才真重生成重建；hold 桥接期 pilot 输入整冻结（终帧即终点）。

**位置**：`InlineSpanSafety.kt`、`SafePrefixGate.kt`、`StreamingMarkdownPilot.kt`。

### 铁律 13（#474）：手势通道与程序注入通道必须分治——用户链解除程序意图

**机制**：程序配对注入（LEAP/RESIZE 补偿、GUARD 重锚）与用户手势共用语义时，卡内链传导/fling 中 tap 会触发程序化滚动（贴底吸附、制动拉底）——「卡内滚动吞噬拖动」是分通道 by-design 语义，不是死锁。

**规则**：用户手势链（UserInput）到达必须解除 autoScroll 程序意图（`CardFlingLeakGuard.onUserChain` → onExpandDeparture 解除）；展开/收起补偿方向遵循「上方不动 + 向下扩展」（2026-09-29 用户裁决终向）；fling 泄漏拦截上提 item 级通用守卫，协议内反转注入。

**位置**：`CardFlingLeakGuard.kt`、`ChatScrollController.kt`。

### 铁律 14（#476）：重锚必须有死区——微离底不得拉回绝对底

**机制**：零高横幅带（恒驻 banner 槽位不可见时 0 高）→ firstVisibleItemIndex 双稳（0↔首实项，同物理位 off 不变，17ms 翻转）→ isAtBottom 振荡；GUARD 无死区时微离底也重锚 → 落点 off 永不清偿 = ~270ms/轮永续拉锯战（用户字面场景「上移一点即被吸回底」）。

**规则**：GUARD 重锚死区 `REANCHOR_MIN_OFF_PX=120`，且 `materiallyOff` 入 snapshotFlow 键——死区内不重锚；卡内链传导经 onUserChain 解除武装（与铁律 13 同源）。

**位置**：`ChatScrollController.kt`。

## 3. 回归历史：为什么这个能力"反复出现又消失"

### 3.1 时间线

| 提交 | 行为 | 说明 |
|------|------|------|
| `6bad1cc` (v360) | **双 key** `(isScrollInProgress, isAtBottom)` | ✅ 用户验证正常 |
| `1b7e1ea5` | 补偿扩大到所有 assistant | 误修，后被纠正 |
| `dd55c3bf` | 修 viewport 漂移 | |
| `ac303cf1` | **移除 isAtBottom 作为条件** | 注释误判 isAtBottom 导致 premature reset |
| `46e65854` | **恢复 isAtBottom 作为条件** | commit 明说 "beta.360-verified behavior" |
| **`67e46011`** | **key 从双改单**（核心回归提交） | 基于未验证的理论假设，同时改了多项 |
| `76e1a35f` | **把单 key 写进 AGENTS.md 铁律** | ★ 固化了错误，成为反复回归的根源 |
| `92a30e48` | isStreamingMsg 改 `.any{}` | 修 multi-message turn，正确，但非本次回归因 |
| (本次修复) | **恢复双 key + 修正铁律** | 回到 v360 验证行为 |

### 3.2 根因机制（本次 2026-07 回归）

`67e46011` 的 commit 理由：*"isAtBottom 在 SSE 期间瞬态翻转会 lock autoScrollEnabled=true → viewport snaps to bottom"*。

这个理论**在实际运行中不成立**，实际发生的恰恰相反：

1. 用户滚到中间阅读历史（`shouldCompensate=true`）
2. SSE 在底部输出，补偿工作，视窗稳定
3. 用户通过 fling 惯性 / SSE 推送回到底部
4. **单 key 下**：`isScrollInProgress` 没变（非拖动），LaunchedEffect 不触发，`shouldCompensate` 保持 `true`
5. 底部仍在每个 token 执行 `requestScrollToItemNoCancel(firstVisibleItemIndex, scrollOffset + delta)` → 视窗周期性跳动

双 key（v360）下：步骤 3 中 `isAtBottom` false→true 触发 LaunchedEffect，`shouldCompensate=false`，补偿停止，视窗稳定。

### 3.3 反复回归的模式

```
  有人发现跳动
       ↓
  按"直觉"改 key 策略 / 补偿范围
       ↓
  短期看似修复（因为碰巧绕过当前场景）
       ↓
  写进 AGENTS.md 当铁律
       ↓
  下一个人按铁律"纠正"代码 → 又跳
       ↓
  （循环）
```

**打破循环的唯一方法**：铁律必须有 git 历史 + 用户验证证据支撑，不能基于理论假设。v360 是**唯一**有用户明确验证"正常"的版本，任何偏离 v360 行为的改动必须附带验证证据。

### 3.4 2026-08 滚动性能迭代链（v1 → v6）

| 版本 | 改动 | 结果 | 根因 |
|------|------|------|------|
| v1 | `cacheWindow = 1.5/1.0` + 移除 `safeFlingBehavior` | 不跳过气泡了，但反复滑动卡 | 大窗口 + 数据层每 48ms 全量重建 → 窗口内 item 全量重组 |
| v2 | 窗口调小 1.0/0.5 | 仍卡 | 同上（未触及数据层） |
| v3 | `renderableTurns` 按 id 缓存（`activeTools.isEmpty()` 时）+ 窗口 0.75/0.25 | 基本不卡；**工具运行期间卡** | 整体禁用缓存（铁律 6 ❌）→ 每 48ms 全量重算 |
| v4 | 内容指纹缓存替代整体禁用 + `ChatMessage` 实例缓存；**崩溃**（`renderableListRef` 列表引用复用） | 工具期间不卡；发消息崩溃 | 列表结构变化时返回旧列表 → 索引错位 → `renderableTurn is required for ASSISTANT role` |
| v4 修复 | 回退列表引用复用（每次返回新 List，元素仍缓存） | 崩溃修复 | — |
| v5 | 对称窗口 1.5/1.5 | 摩擦重建消失（composed 不再重复） | 窗口太大/太小均失败，对称覆盖双向 |
| v6 | `turnGroups`/`jumpTargets` 签名缓存 + miss 分支引用修正 | ✅ 用户反馈"基本感受不到卡顿" | GC 分配风暴（每 10 秒释放 ~100MB）缓解 |

**关键诊断日志**（真机排查滚动卡顿）：

| 日志 | 含义 |
|------|------|
| `ChatScroll: JUMP idx A -> B (gap=N)` | fling 跳变检测（gap>1 = 一帧跳过多个 item，需配合 composed 判断是否视觉跳过） |
| `ChatScroll: composed idx=X id=...` | item 进入组合——**同一 idx 几十毫秒内多次出现 = 摩擦销毁重建**（窗口 behind 太小） |
| `Background concurrent mark compact GC freed 100MB` | **分配风暴**——每 10s 一次 = 派生计算全量重算（铁律 8） |
| `MsgDiag: [combine] msgs=N` | 数据层全量重建次数与消息数——注意触发频率与卡顿时刻的相关性 |

## 4. 关键代码位置速查

| 关注点 | 文件 | 行号（本次修复后） |
|--------|------|------|
| autoScroll LaunchedEffect | `ChatScreen.kt` | ~333 |
| shouldCompensate LaunchedEffect | `components/ChatMessageList.kt` | ~160 |
| streaming message 识别 | `components/ChatMessageList.kt` | ~448 |
| layout 高度补偿 modifier | `components/ChatMessageList.kt` | ~449-471 |
| 100ms flush（`STREAM_FLUSH_INTERVAL_MS`，#442 二期收编引擎域） | `MessageEventHandler.kt` | ~58 |
| Markdown stateful 渲染 | `markdown/MarkdownContent.kt` | ~364 |
| isAtBottom 定义 | `ChatScreen.kt` | ~326 |
| snapToBottom 扩展 | `util/ChatScrollUtils.kt` | ~26 |
| requestScrollToItemNoCancel 反射 | `components/ChatMessageList.kt` | ~698（LazyListReflection） |
| cache window（滚动性能） | `ChatViewModel.kt` | `listState`（`LazyLayoutCacheWindow(1.5f, 1.5f)`） |
| renderableTurns 指纹缓存 | `components/ChatMessageList.kt` | `renderableCache` + miss 分支引用修正 |
| turnGroups / jumpTargets 签名缓存 | `components/ChatMessageList.kt` | `turnGroupsSigRef` / `jumpTargetsSigRef` |
| 指纹/签名函数 | `components/ChatMessageList.kt` | 文件末尾（`messageFingerprint`/`partsFingerprint`/`toolFingerprint`/`tailHash`/`messagesSignature`） |
| ChatMessage 实例缓存 | `MessageDataDelegate.kt` | `chatMessageCache` + `lastCombineSessionId` |
| inject 引用稳定 | `tools/ToolProgressOutputInjector.kt` | `changed` 标志 + 原引用返回 |

## 5. 验证方法

### 5.1 静态检查（改完代码必做）

1. `grep -rn "LaunchedEffect(listState.isScrollInProgress)" app/src/main/kotlin/` —— **应为 0 结果**（必须都是双 key）
2. `grep -rn "LaunchedEffect(listState.isScrollInProgress, isAtBottom)" app/src/main/kotlin/` —— **应 ≥ 2 结果**（ChatScreen + ChatMessageList）
3. `grep -rn "rememberMarkdownState" app/src/main/kotlin/` —— Markdown 渲染入口必须有
4. `grep -rn "batchJob?.cancel" app/src/main/kotlin/` —— scheduleFlush 路径不应有

### 5.2 动态检查（真机）

1. **底部跟随**：SSE 输出时在底部 → 视窗平滑跟随新内容，不跳动
2. **阅读历史稳定**：滚到中间 → SSE 输出时视窗纹丝不动
3. **回归自愈**：滚到中间 → SSE 输出 → fling 回到底部 → 视窗稳定跟随，无残余跳动
4. **multi-message turn**：一个 turn 内多条消息 → 补偿正确作用

### 5.3 滚动性能检查（2026-08 追加，真机）

1. **拖拽开始**：手指按住小幅度来回摩擦 → 不得卡顿（composed 日志不得出现同一 idx 反复）
2. **fling 启动**：手指松开瞬间 → 不得卡顿（窗口 ahead 预组合充分）
3. **新消息出现**：流式新气泡进入视口 → 不得明显卡顿
4. **长时间使用**：连续对话 + 跑工具 10 分钟后再滑动 → 不得出现"用一会儿后卡"（GC 分配风暴）——同时观察 logcat GC 频率（正常应远低于每 10s 一次）
5. **内容正确性**：流式期间气泡内容持续更新（不得冻结在首个 token）、工具输出实时注入、时长/错误显示正确——验证指纹缓存无漏检
6. **发送消息**：发送后列表正常插入（回归检查 v4 崩溃场景）

## 6. 决策记录

### 为什么不把 isStreamingMsg 改回单消息匹配（v360 原始版）？

因为 v360 之后 `92a30e48` 修了一个真实问题：displayItems 的 turn 代表是 **oldest**，streaming 是 **newest**，单消息匹配让 multi-message turn 补偿失效。`.any{}` 是正确修复。在当前 displayItems 结构下（每 turn 1 个代表 item），`.any{}` 不会泄漏到多个 item，行为安全。

### 为什么不保留 67e46011 的 `delta <= 500` 上限？

当前代码（HEAD）实际已经没有这个上限了（后续提交移除了），补偿条件是 `shouldCompensate && lastHeight > 0 && delta > 0`。本次修复不动这个条件，只动 key 策略——最小变更原则。

## 7. 变更日志

| 日期 | 提交 | 变更 |
|------|------|------|
| 2026-06-22 | v360 (`6bad1cc`) | 双 key + completed-only streamingMsgId，用户验证正常 |
| 2026-07-01 | `67e46011` | key 改单（回归 #1） |
| 2026-07-01 | `76e1a35f` | 错误铁律写入 AGENTS.md（固化回归 #1） |
| 2026-07-01 | `668384e3` | streamingMsgId 加 takeIf(sessionMeta.isStreaming)（回归 #2） |
| 2026-07-09 | 本次 | 恢复双 key（修复 #1）+ 移除 takeIf（修复 #2）+ 修正铁律 + 本文档 |
| 2026-08-06 | v1-v6 | 滚动性能全链路修复：cache window（跳过）→ 指纹缓存（重算）→ 实例/签名缓存（分配风暴）→ 对称窗口（摩擦/fling）；新增铁律 6-8 与 3.4/5.3 节 |
| 2026-09-25 | #435 | COMP 家族（DeferredRevealCompensator×5 挂载点）/PreRenderShiftChannel/GUARD stream-instant 退役；流式增长并入高度引擎统一配对（StreamingGrowLedger→pre-draw flush，「锚即意图」规则）；引擎 steady flush 增补 #432 贴底豁免；顺带修复读历史流式拖拽缺陷 |
| 2026-09-30 | #437 收口 | 新增 §2.6 铁律 10-14：五域收编（#437 稳定揭示安全前缀 / #438 限速与保 key / #472 行内放行+非前缀宽限 / #474 手势-程序通道分治 / #476 GUARD 死区）——补 2026-09-29 发掘审计定罪的文档同步缺口；同日铁律 9（#471③ 归一化同源）随坍缩重建批次先期落档 |
| 2026-10-01 | #442 批次 C | cadence 文档漂移修正：§1 管线/铁律 2/§4 速查表的 48ms 表述更新为现行 100ms（常量实际已于 #437 cadence 快赢改为 100ms、#442 终审 S4 收编引擎域，本文档滞后）；§3.4 历史表格保留 48ms 原文（记录当时事实） |

---

**最后提醒**：如果你发现视窗又跳动了，按以下顺序排查：
1. **`grep -n "takeIf.*isStreaming" ChatMessageList.kt`** — 如果有结果，说明铁律 5 又被违反了（回归 #2）
2. **`grep -n "LaunchedEffect(listState.isScrollInProgress)" ChatScreen.kt ChatMessageList.kt`** — 如果有单 key 结果，说明铁律 4 又被违反了（回归 #1）
3. 这两个是历史上反复出现的回归点。不要在未读本文档的情况下修改 key 策略或 streamingMsgId 判定。
