# 卡片/分割线高度预先获取 · 出现时高度补偿——可行性调研（2026-08-25）

> **目标**：评估「在卡片/分割线渲染之前预先获取其高度，使『出现』（而不仅是流式文本生长）也能做测量期高度补偿」的可行性。用户诉求的隐含前提是：统一容器（#215）后卡片形态收敛，高度似乎「可预算」。
> **方法**：一手源码取证——① 本仓库 ChatMessageList.kt / ScrollCompensation.kt / CompactionCard.kt / ChatScrollController.kt / 各卡片组件（含 #221 未提交工作区 diff）；② 本地 Gradle 缓存 `foundation-android-1.11.2-sources.jar`（compose-bom 2026.05.01 解析版）解包，逐行核对 LazyListMeasure.kt / LazyListScrollPosition.kt / LazyListState.kt / LazyList.kt 的锚定与测量回写源码（/tmp/compose-src）；③ docs/research/sse-scroll-stability-iron-laws.md、docs/journal/2026-08-24-card-unification.md（#215 验收反馈·一全档）、docs/journal/2026-08-24-compaction-divider-unification.md（#217/#219/#220 证据链）。未联网、未跑构建、未触碰设备——所有「预测行为」均标注待真机证实。
> **约束**：纯调研，不改任何代码。硬约束（用户最高指示）：任何被提议的补偿机制必须与主对话同模式——**测量期（渲染前）反射注入**，禁止「渲染后测量再补」的反应式补偿（onSizeChanged+scrollBy / LaunchedEffect 滚动类）。本文所有方案评估均以此为准绳。
> **关联**：docs/specs/2026-08-24-card-unification-design.md（#215 容器统一）、#220/#221（压缩分割线进行中态 + 展开区补偿，工作区未提交）、docs/research/sse-scroll-stability-iron-laws.md（铁律）。

---

## 执行摘要（TL;DR）

**核心结论：部分可行，但「预先获取高度」这个手段本身几乎无用武之地——三个「出现」场景类别里，一类已被现有机制天然覆盖（无需预知高度），一类在 reverseLayout 锚定几何下根本不产生可补偿的位移，一类被用户裁决明确排除。** 用户设想的「卡片出现 → 视口位移 → 需要预算高度来补偿」的因果链，经源码级推演，在本列表结构下大部分不成立：

1. **「流式 turn 内卡片弹入」（tool/思考/step 卡）已经是 COMP-MSG 的覆盖范围**——卡片弹入 = 流式 item 一次测量遍内高度增长 delta=H_card>0，`layout{}` 在测量期当场拿到真实高度并注入（ChatMessageList.kt:1156-1185）。**高度在注入决策的同一测量遍内已知，「预获取」没有时间差可弥合**。且这些卡片弹入无入场动画（AnimatedVisibility 只用于展开区，grep 全组件核实），是单帧阶跃，恰好落在现有补偿模型（单遍 delta）的最佳适用区。
2. **「新 item 插入」在 key 锚定下不产生需要补偿的位移**（LazyListScrollPosition.kt:95-105 + LazyLayoutItemProvider.kt:94-109 源码级证实）：插入点在锚点之下（视觉底侧）→ 锚点跟随旧 key，新项根本不被组合、无位移——这是 reverseLayout 聊天列表的立身之本；插入点在锚点之上（视口内）→ 锚点上方内容上推 H_new——但这几何几乎只在贴底时出现（横幅互相之间），而贴底时 shouldCompensate=false（跟随跳变是预期 UX，由 msgCount 锚定路径负责）。**唯一能想到的「插入时测量期注入」方案（新 item 首测反向守卫）在现列表 item 声明顺序下找不到 shouldCompensate=true 的实际触发场景。**
3. **「展开/收起 toggle」被 2026-08-25 用户终版裁决排除**（#215 journal：撤销全部补偿、动画回 M3 默认），本调研不挑战该裁决，仅在 §2.5 说明排除理由存档。
4. 调研的**最大实际收获是两个新发现**（不是预计算方案）：① **反射通道的回写竞争风险**——静态源码推演发现测量中注入的 request-position 会被同遍 `applyMeasureResult → updateFromMeasureResult` 回写覆盖，#215 journal 已实证**动画期间**逐帧注入被丢弃；流式文本场景虽经 v360 验证有效，但在当前 foundation 1.11.2 上「为什么没被回写丢弃」静态推演未闭合——**任何补偿扩展动工前必须先做通道存活验证（§6.1）**；② **贴底时尾部横幅类 item（tool_progress 聚合卡 / V1 进行中分割线 / retry banner）因 key 锚定落在视口底缘之下不可见**——这是「可见性缺口」而非「补偿缺口」，解法（若要做）是 reveal 滚动不是高度补偿，天然不属于本硬约束管辖。

**判定表**（详见 §4）：

| 子场景 | 判定 | 一句话依据 |
|---|---|---|
| 流式 turn 内卡片弹入 | **无需做**（已覆盖） | COMP-MSG 单遍 delta 模型精确覆盖；预知高度无增量价值 |
| 新 turn 消息气泡 / 转后台分割线 / V2 压缩分割线（骨架消息） | **无需做** | 消息区插入：贴底由 msgCount effect 同帧锚底（ChatScrollController.kt:118-139），阅读历史零位移 |
| 尾部横幅插入（V1 进行中分割线 / retry / tool_progress / step） | **不是补偿问题** | 插入点在锚下 → 零位移；真缺口是贴底不可见（reveal 问题，§2.4） |
| 展开/收起 toggle | **排除** | 用户终版裁决（#215 journal 2026-08-25） |
| SubcomposeLayout 预测量 / 静态高度预算表 / lookahead | **不值得做** | 消费者不存在（上述三类已闭环）；双重测量成本 + 高度失准反向注入风险 |
| 插入时测量期注入（冷启动反向守卫） | 技术可行、**无场景** | 几何上与 shouldCompensate=true 几乎不共存（§3.4） |

---

## 0. 版本基线

- **foundation 1.11.2**（compose-bom 2026.05.01，app/build.gradle.kts:157-165）。源码解包自本地 Gradle 缓存 `foundation-android-1.11.2-sources.jar`。
- 反射通道依赖的四个成员在 1.11.2 源码中逐一核对**仍在**：`LazyListState.scrollPosition`（LazyListState.kt:222-234 经 scrollPosition 属性暴露的内部对象）、`requestPositionAndForgetLastKnownKey`（LazyListScrollPosition.kt:81-86）、`measurementScopeInvalidator`（LazyListState.kt:395，ObservableScopeInvalidator = neverEqualPolicy MutableState 包装）、`scrollToBeConsumed`（LazyListState.kt:270）。降级路径（ScrollCompensation.kt:100-101）继续有效。

---

## 1. 机制基线：reverseLayout LazyColumn 的锚定真相（源码取证）

本节是全部场景判定的推理地基。四条结论均从 1.11.2 源码直接导出，其中 1.3 与 #215 journal 的真机取证互证。

### 1.1 锚点 = key 锚定，不是 index 锚定

每遍测量开始时（LazyList.kt:327-336）：

```kotlin
firstVisibleItemIndex = state.updateScrollPositionIfTheFirstItemWasMoved(itemProvider, state.firstVisibleItemIndex)
```

`updateScrollPositionIfTheFirstItemWasMoved`（LazyListScrollPosition.kt:95-105）→ `findIndexByKey(lastKnownKey, index)`（LazyLayoutItemProvider.kt:94-109）：若上一遍锚项的 key 现在位于别的 index（前方有插入/删除），锚点 index 跟随 key。**效果：结构变化后，锚项保持为首可见项、视觉位置不动。** 例外：`requestPositionAndForgetLastKnownKey` 会清空 lastKnownKey（下一遍测量退化为纯 index 锚定）——反射注入恰好走这条路（§1.5）。

### 1.2 锚点几何：firstVisibleItem = 视觉最底可见项

- `LazyListState.firstVisibleItemIndex` 读 `scrollPosition.index`（LazyListState.kt:221-222）——注意**不是** layoutInfo 的可见项列表。
- reverseLayout 下布局起点 = 视觉底边（`beforeContentPadding = bottomPadding`，LazyList.kt:232-235）；测量从锚项开始向上堆叠（LazyListMeasure.kt:197-238）。
- `firstVisibleItemScrollOffset` = 锚项被滚过视口底边的量。本仓库 `isAtBottom = firstVisibleItemIndex==0 && offset<100`（ChatScrollController.kt:91-96）与此几何自洽。
- 向后（锚点之下）组合只在两种情况发生：offset 为负（向后滚动中）或填充 beforeContentPadding 区（LazyListMeasure.kt:164-184）。横评：锚下 ~8dp padding 条内的组合即 §2.4「贴底横幅滑入 padding 条」的来源。

### 1.3 item 内部高度变化：零锚定修正（#215 已实证）

测量从锚点按各 item 尺寸堆叠，锚项自身高度变化不触发任何锚点修正——`(firstVisibleItemIndex, offset)` 不动，锚项底边钉住、生长向上延伸，上方内容整体上推 growth。这正是：
- **贴底跟随的机制本体**：贴底时锚 =（流式 item，0），文本生长向上推 = 视口内容自然跟随，无需任何滚动；
- **阅读历史位移的来源**：滚离底部后锚仍是流式 item（超大 item，off<item 高时锚不换人），生长仍向上推视口内容 → 这就是 COMP-MSG 要对消的量；
- #215 journal 真机逐帧日志互证：「(firstVisibleItemIndex, offset) 在整个 AnimatedVisibility 动画期间 freeze（锚 item 视觉底边被动钉住）」。

### 1.4 插入的两种几何（本调研最关键的判定工具）

设插入点 index 为 P，锚点 index 为 A（reverseLayout：index 小 = 视觉更靠底）：

| 几何 | 锚点行为 | 视觉效果 | 何时发生 |
|---|---|---|---|
| **P < A**（插在锚之下/视觉底侧） | key 锚定把 A 抬到 A+1，锚项视觉位置**完全不动** | **零位移**；新项落在视口底缘之下不被组合（至多滑入 ~8dp 底 padding 条，LazyListMeasure.kt:164-184） | 阅读历史时任何尾部插入；贴底时大多数横幅插入（横幅声明在消息之前 = index 更小） |
| **P > A**（插在锚之上、视口带内） | 锚点 (A, off) 不动，新项参与向上堆叠 | 插入点之上的内容**上推 H_new**；若用户正读上方内容即感知跳动 | 仅当锚点是比新项更靠底的**横幅**时（如 revert banner 可见时 retry/tool/step/question 弹出）；消息区插入永远在横幅之上，锚为消息时不构成此几何 |

推论（本列表 item 声明顺序下，ChatMessageList.kt:877-1055：revert → compaction → retry → tool → step → question → perm → 消息（新→旧）→ loading_older）：

- **消息区插入（新 turn 气泡、synthetic 转后台分割线、#219 压缩骨架消息）**：贴底时 P < A=0（新消息声明在最前）→ 零位移 + 新项不可见 → 随后 `LaunchedEffect(messageCount)` 同帧 `requestScrollToItem(0)` 把它锚到底缘（ChatScrollController.kt:115-139，注释明言「无『旧 key 锚定偏移一帧 → 再拉回』的闪烁循环」——即作者已知此几何）；阅读历史时 P < A → 零位移（这正是聊天列表用 reverseLayout 的目的）。
- **横幅区插入**：贴底且无更低横幅时 P=0 < A → 零位移 + 不可见（§2.4 的可见性缺口）；仅当更低横幅为锚时构成 P > A 的上推几何。

### 1.5 补偿通道与回写竞争（重要开放问题）

反射通道 `requestScrollToItemNoCancel`（ScrollCompensation.kt:83-102）= `requestPositionAndForgetLastKnownKey(idx, off+delta)` + poke `measurementScopeInvalidator`。源码层时序：

1. 注入发生在 item 的 `layout{}` 测量回调内 = **LazyList 本遍测量的中途**（LazyLayout 子组合在测量期进行）；
2. 本遍测量结束时 `applyMeasureResult`（LazyList.kt:389）→ `updateFromMeasureResult`（LazyListState.kt:626-630；LazyListScrollPosition.kt:51-64）**用本遍结果回写 (index, offset)**——静态推演下会覆盖中途注入的值；
3. poke 使测量作用域失效 → 同帧再测一遍；再测从回写后的锚点出发（且 lastKnownKey 已被注入清空、又被回写重设为锚项 key）。

**静态推演结论与实证事实存在未闭合的缝**：

- 实证 A（通道在流式场景有效）：铁律 v360 用户验证「滚到中间 → SSE 输出时视窗纹丝不动」（iron-laws §5.2），此后多轮回归验证。
- 实证 B（通道在动画场景失效）：#215 journal v1/v2 取证——「逐帧请求被测量回写（applyMeasureResult→updateFromMeasureResult）中途丢弃——请求 (1,140)/(2,79) 落空，锚点停在 (0,5086) 直到动画结束才跳 (2,912)」「request-position 通道与动画期间的测量回写存在结构性竞争；SSE 流式能用的前提是 shouldCompensate 门控（用户已滚离底部，回写与请求不撞车）」。
- 未闭合点：流式生长同为「测量中注入 → 同遍回写」，静态推演找不到「不撞车」的结构性原因（journal 的门控解释描述的是现象相关而非因果机制）。两种假说：① 某个静态分析未捕捉的时序细节（如再测遍中 item 重测使 delta 复算、或回写在 poke 失效传播前完成特定顺序）使注入存活；② **该通道在当前 foundation 版本的流式场景已静默退化**，而「阅读历史 + 流式」的最近一次显式人工验证停在 2026-07 铁律期（#217/#221 的验证都只覆盖贴底路径，journal 明言「COMP-MSG 零触发…补偿通道闲置=正常」）。

**对本次调研的含义**：无论未来做不做「出现时补偿」，§6.1 的通道存活验证都应先行——它决定的是**现有铁律机制本身的健康度**，优先级高于任何扩展。

另注：`scrollToBeConsumed` 通道（测量开始时无条件消费、随回写生效，无竞争，LazyListMeasure.kt:142-152）在 #215 终版裁决中随方案三整体撤销，实现存档 git history（a4eedab6）。本调研不提议复活它（用户裁决「不要有补偿逻辑」针对 toggle 场景；但该通道作为「无竞争的注入路径」的技术事实值得存档在此）。

---

## 2. 现状盘点：逐场景位移判定

### 2.1 场景总表

| # | 场景 | 类别 | 插入/生长位置 | 位移判定 | 现状覆盖 | 缺口性质 |
|---|---|---|---|---|---|---|
| 1 | 新 user/assistant turn 气泡 | item 插入 | 消息区头部（index 最小的消息位） | 贴底：零位移→msgCount 同帧锚底跳（预期跟随）；阅读历史：零位移 | `LaunchedEffect(messageCount)`（ChatScrollController.kt:118-139）+ 发送路径 ForceScrollExecutor（:141-152） | **无** |
| 2 | 转后台通知分割线（synthetic 消息） | item 插入 | 消息区 | 同 #1 | 同 #1（synthetic 也是消息，计数变化触发） | **无** |
| 3 | V2 压缩进行中分割线 | item 插入（骨架消息）+ item 内原位切换 | 消息区（#219 骨架：inbox.enqueued 瞬间入列） | 贴底：骨架入列触发 msgCount 锚底 → 分割线可见；进行中→完成同 item 原位切换（Q13） | 同 #1 + #221 COMP-CMP(msg)（ChatMessageList.kt:1322-1354） | **无**（补偿缺口已被 #221 收口；剩 §6.2 验证） |
| 4 | V1 压缩进行中分割线 / V2 尾部兜底（tailCompaction，messageId 不在列表，V1 本地置态 messageId=""，ChatViewModel.kt:492-502） | item 插入 | 横幅区（消息之后声明） | 贴底：**P<A 几何 → 零位移且不可见**（§1.4）；阅读历史：零位移 | **无 reveal 机制**（bannerCount 只用于跳转索引换算，ChatMessageList.kt:383-401，不驱动滚动） | **可见性缺口**（§2.4），非补偿缺口 |
| 5 | retry banner | item 插入 | 横幅区 | 贴底：零位移且不可见（P<A）；伴随 error 时有 snapToBottom（ChatScreen.kt:465-474） | error 路径覆盖；无 error 的 retry 出现无 reveal | 可见性缺口（低频） |
| 6 | revert banner | item 插入 | 横幅区 idx0 | 贴底：零位移且不可见，但 revert 动作本身改消息数 → msgCount effect 锚底时露出 | msgCount（计数减少也触发 LaunchedEffect） | 基本无 |
| 7 | tool_progress 聚合卡出现 | item 插入 | 横幅区 | 贴底：零位移，滑入底 padding 条（至多 ~8dp 可见，§1.2）；阅读历史：零位移 | 无 reveal；**后续增长**（多工具叠加/输出注入）由 COMP-TOOL 覆盖（ChatMessageList.kt:961-1004） | 可见性缺口；但气泡内嵌 Running 工具卡承担实际信息呈现（见 #8） |
| 8 | 流式 turn 内 tool/思考/step 卡弹入 | **item 内生长** | 流式 item 内部（PartContent.kt:138-270 ToolCardScaffold 家族；ReasoningBlock） | 单帧阶跃 delta=H_card（弹入无入场动画——AnimatedVisibility 仅用于展开区，grep 核实） | **COMP-MSG 已覆盖**（delta>0 即注入，ChatMessageList.kt:1156-1185；贴底时零修正锚定天然跟随，§1.3） | **无**（唯一残留：§1.5 通道健康 + 卡片若日后加入场动画会落入动画竞争区） |
| 9 | 压缩展开区流式增长 | item 内生长 | CompactionCard 展开区 | 单帧阶跃 | **COMP-CMP 已覆盖**（#221 尾部兜底 + 消息流对位两路径） | 无（#221 待真机验证） |
| 10 | question/permission 卡出现 | item 插入 | 横幅区 | 贴底：零位移不可见 → pendingCount effect `snapToBottom()`（ChatScrollController.kt:154-170） | 有 reveal | 无 |
| 11 | step indicator | item 插入 | 横幅区 | 同 #7 | 无 reveal | 同 #7 |
| 12 | 展开/收起 toggle（历史卡片） | item 内生长（AnimatedVisibility 动画，逐帧渐变） | 任意历史 item | 锚 freeze 期上方内容漂移（#215 取证：展开 -1344~-1380 / 收起 +1344 或冲出视口） | **用户裁决排除**（§2.5） | — |

### 2.2 已覆盖证明：「卡片弹入」无需预知高度

COMP-MSG 的 `layout{}` 对流式 item 做「无界约束测量 → realHeight − lastHeight = delta → delta>0 且 shouldCompensate 则注入」。卡片弹入时：

- 卡片在**同一测量遍**内成为流式 item 子树的一部分，`measurable.measure(constraints.copy(maxHeight=Infinity))` 测得的 realHeight **已含卡片全高**——不存在「渲染后才知道高度」的时间差；
- delta=H_card 为正 → 走与文本生长完全相同的注入路径；
- 弹入无动画 = 单遍阶跃 = 该模型的最佳适用区（对照：动画渐变会落入 §1.5 的逐帧竞争区，这正是 #215 方案一失败的通道层原因）。

**所以「预先获取高度」在此类场景的增益严格为零**：高度在决策同一遍已知，且现有注入已消费它。用户诉求在此维度上已被现状满足。

### 2.3 无位移证明：插入的几何

见 §1.4 推论。补充两点：

- **阅读历史时一切尾部插入零位移**——不是「有位移待补偿」，是「无事发生」。若在此场景注入补偿反而会**制造**位移（对不存在的位移做对消 = 视口反向跳）。
- **贴底时的上推几何（P>A）**只发生在「更低横幅为锚 + 更高横幅弹出」的组合（如 revert 可见时 retry 弹出）。此时 shouldCompensate=false（贴底）、autoScroll=true，上推量级 = 横幅高度（数十 dp），且与 msgCount/pendingCount 锚底跳变的既有语义混在一起——用户从未报告过此场景，先证实再谈补偿。

### 2.4 真缺口：贴底时尾部横幅「不可见」（可见性问题，不是补偿问题）

机制推演（§1.2/§1.4，**待真机证实**，验证协议 §6.2）：贴底锚=(底部横幅或最新消息, 0)时，横幅区新项插入 P<A → 不被组合 → 不可见；副作用链：锚 index 被抬 → `firstVisibleItemIndex ≥ 1` → `isAtBottom` 翻 false → 左下 ⬇ FAB 闪现（ChatScrollBottomFab 以 isAtBottomState 为显隐条件）。

- **tool_progress 聚合卡**：贴底时大概率看不到（气泡内嵌 Running 卡承担信息），直到下一条消息/问题/发送触发锚底。#215 journal 自述进度卡「瞬态性太强…难截，留用户日常使用中自然观察」——与该预测相容但从未实证。
- **V1 进行中分割线**：V1 无骨架消息（journal：「V1 SSE 只有单个 session.compacted」）、本地置态 messageId=""（ChatViewModel.kt:494）→ 尾部兜底是唯一渲染路径 → 贴底 + 长会话时预测**整个压缩期（秒级 HTTP 挂起）不可见**，完成后随 REST 刷新的 msgCount 变化锚底才见完成态。#217 真机 E2E 全部走 V2 骨架路径且用短会话（内容短于视口时测量回填逻辑会把全部 item 组合可见，LazyListMeasure.kt:241-263——故 R4「banner 8.4s 连续」与本预测不矛盾）；journal 明言「V1 路径…真机验证留待后续，行为由代码审查覆盖」——**本预测正是那次欠账的代码审查结论，建议补真机验证**。
- **解法归属**：若证实，修复是「reveal」（bannerCount 驱动的 `requestScrollToItem(0)`，与 msgCount 同款——显式滚动决策，非补偿），不属于本调研硬约束的管辖范围，也与「预知高度」无关（reveal 不需要知道高度）。

### 2.5 排除项存档：展开/收起 toggle

#215 验收反馈·一·终版裁决（2026-08-25，用户原话：「动画还是不对，不要有补偿逻辑！直接用M3属性的动画就行！」）：方案一（双向 delta）/方案三（修正窗 + scrollToBeConsumed 注入通道，矩阵验证 6 格全 dy=0）均被否决，接受倒序 LazyColumn 原生锚定行为（展开 dy=143 / 收起 dy=846 实测存档）。**本调研不提出任何 toggle 补偿方案**——预计算高度对 toggle 同样无增益（动画期间的逐帧 delta 本来就能测得，失败原因是通道竞争与用户裁决，不是高度未知）。

---

## 3. 技术路径评估（Compose 层面）

### 3.1 P1：SubcomposeLayout / 预组合测量（出现前用相同约束测高）

- **可行性**：技术上成立（SubcomposeLayout 可在组合期对任意子树施加任意约束测量），但是为「出现时补偿」服务的 P1 有三个结构性问题：
  1. **无消费者**：§2.2/§2.3 证明「出现」场景或已被覆盖（item 内）或无位移（插入）——测出的高度没有注入对象。
  2. **双重成本**：每张卡两遍组合测量；Markdown 类内容预测量时刻异步解析多半未完成（RenderReadiness/预解析供给体系存在的原因），预测高度 ≠ 真实高度的概率不可忽略。
  3. **失准即反向伤害**：注入 delta 偏差 = 视口反向跳。补偿机制的铁律是「宁可少补不可错补」——错补比不补更可见。
- **判定：不值得做。**

### 3.2 P2：静态高度预算（分割线/收起态卡片高度是否本质常量？）

逐卡核对（收起态/横幅态）：

| 元素 | 构成 | 高度可预算性 |
|---|---|---|
| CompactionCard 分割线行（两态同构，CompactionCard.kt:113-225） | Row(padding XS×2) + labelSmall 单行 + 14dp icon | **近似常量**（随字体缩放系数线性，可用 `LocalDensity`+`Typography` 行高预算到 ±1px） |
| 转后台分割线（ChatMessageList.kt:1394-1416 内联） | XS 垂直 padding + labelSmall 单行 + HorizontalDivider | 近似常量 |
| ToolProgressCard（单工具） | 16dp icon 行 + labelMedium + 4dp + 4dp 进度条 + 8dp×2 padding | 近似常量 |
| RetryBanner | bodyMedium 1-2 行（retry.message 有无两档） | 两档常量 |
| 文本类卡片（QuestionCard 表单、工具卡展开区、压缩摘要展开区） | Markdown/多行 | **不可预算**（宽度 × 字体 × 内容三方依赖；#221 刚为压缩展开区放弃固定 240dp 竖线改 matchParentSize 等高跟随——用户已亲手否决过「预设高度」这个方向） |

- **结论**：横幅/收起态高度确实高度可预算——**但预算值同样没有消费者**（同 §2.2/§2.3）。P2 的真正用途只有一个假设性场景：为 §3.4 的「插入注入」供高度——而该场景按几何分析几乎不存在。**判定：不值得单独做。**

### 3.3 P3：lookahead / 提前一帧组合

- Compose 的 lookahead pass（LazyListMeasure.kt 有 isLookingAhead 分支）是双 pass 布局机制（先测后放，服务于变形/滚动预测），**不是**「提前组合未来内容」的公开能力；把补偿挂 lookahead 通道意味着接管两遍测量的位置合成，复杂度远超收益且 API 皆 internal。
- LazyLayout 的 prefetch/cache window（本仓库已用 LazyLayoutCacheWindow(1.5,1.5)，铁律 7）做的是**视口外预组合**——它已经提前组合了「未来可能滚入」的 item，但其目的是滚动性能；补偿若在预组合测量里注入，时序错误（用户尚未滚到，注入即错位）。
- **判定：不适用。**

### 3.4 P4：插入时测量期注入（「冷启动反向守卫」——合规方案存档，不推荐实施）

若未来列表结构变化导致「P>A 上推几何」与 shouldCompensate=true 真实共存（例如把横幅改声明到消息之后），同模式方案如下（**测量期注入，完全符合硬约束**）：

- **接入点**：横幅 item 的包装 Box 挂 `layout{}`（tool_progress 的 COMP-TOOL 同位，ChatMessageList.kt:963-1004），守卫反转为冷启动敏感：

```kotlin
Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    val realHeight = placeable.height
    val isNewAppearance = insertCompensateState.lastHeight == 0 && realHeight > 0
    val growDelta = realHeight - insertCompensateState.lastHeight
    // 插入项自身 P>锚（组合到了 = 在锚上方或为锚本身）且用户在底意图时，
    // 首测（0→H）与生长（H→H+Δ）都注入：出现那一刻即对消
    if (compensateState.shouldCompensate && realHeight > 0 &&
        (isNewAppearance || growDelta > 0)
    ) {
        val inject = if (isNewAppearance) realHeight else growDelta
        LazyListReflection.requestScrollToItemNoCancel(
            listState,
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset + inject
        )
    }
    insertCompensateState.lastHeight = realHeight
    layout(placeable.width, realHeight) { placeable.placeRelative(0, 0) }
}
```

- **要点**：① 新项被组合这一事实本身就证明它在锚上方或即锚（§1.2 向后组合例外），无需显式判断 P 与 A 的关系；② `requestPositionAndForgetLastKnownKey` 清 lastKnownKey 的副作用在此无害（注入后本就要以 index 锚定）；③ 通道健康前置依赖 §6.1。
- **当前判定：无场景，不实施。**（横幅互相为锚的 P>A 几何只在贴底出现，而贴底 shouldCompensate=false。）

### 3.5 P5：scrollToBeConsumed 注入通道（已封存）

#215 方案三验证过它对「逐帧动画生长」的逐帧对消能力（6 格矩阵全 dy=0），终版裁决撤销。技术事实（无回写竞争、跨 item 折算原生处理，LazyListMeasure.kt:142-152）已存档于本文 §1.5 与 git history（a4eedab6）。**本调研不提议复活**（用户裁决在先；且其用武之地 toggle 场景同被裁决排除）。

---

## 4. 结论

### 4.1 总判定

**部分可行——但「可行」的部分（item 内卡片弹入）现状已经覆盖且无需预知高度；需要「预先获取高度」才能做的部分，要么无位移可补偿（插入几何），要么被用户裁决排除（toggle）。** 预先获取高度（SubcomposeLayout 预测量 / 静态预算表）在所有场景都找不到「既有机制做不到、而它能做到」的增量：

| 用户设想场景 | 真相 | 预计算价值 |
|---|---|---|
| 卡片弹入流式 turn | COMP-MSG 同遍测得高度并注入 | **零**（无时间差） |
| 分割线/横幅出现 | key 锚定 → 零位移（或贴底上推但那是跟随语义） | **零**（无位移可补；可见性问题是 reveal 不是补偿） |
| toggle 展开/收起 | 用户裁决排除补偿 | 不适用 |

### 4.2 建议行动（按优先级）

1. **（前置必做，P1 级）通道存活验证 §6.1**——它检验的是现有铁律机制在当前 foundation 版本的健康度，比任何扩展重要。若通道已死，优先修复/迁移（如评估 scrollToBeConsumed 通道替代）而非扩展。
2. **（P2 级，视验证结果）贴底尾部横幅可见性 §6.2**——若证实 tool_progress/V1 分割线贴底不可见，登记 backlog 做 reveal（bannerCount 驱动锚底，与 msgCount 同款显式滚动；不是补偿，不受硬约束管辖）。
3. **（不做）预计算高度的所有路径**（§3.1/§3.2/§3.3）与插入注入（§3.4——合规但无场景，方案存档备将来列表结构变化时启用）。

---

## 5. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 反射通道回写竞争（§1.5 开放问题） | 若流式场景通道已静默失效：阅读历史时流式输出视口持续上漂（每 48ms 一次）；一切扩展建立在沙上 | §6.1 验证先行；失效则优先评估 scrollToBeConsumed 通道（无竞争，已验证过的对消能力） |
| Compose 版本升级反射断链 | 四成员消失 → 一次性降级官方 requestScrollToItem（杀 fling 但不崩溃）——已有降级路径（ScrollCompensation.kt:100-101），本次 1.11.2 核对成员仍在 | 维持现有「升级前手测」纪律（ScrollCompensation.kt:24-30 注释） |
| 预测量高度失准 → 错误注入 | 补偿机制错补比不补更可见（反向跳） | 本文建议不引入预测量（§3.1） |
| 贴底横幅不可见若被误判为「补偿缺失」而加注入 | 对零位移场景注入 = 制造位移 | §2.3/§2.4 的几何分析先行；缺口登记前先跑 §6.2 |

---

## 6. 验证建议

### 6.1 通道存活验证（最高优先）

1. 真机 + REST `/api/session/{id}/prompt` 触发长流式回复（参照 #215 journal SSE 回归的环境搭法）；
2. 发送后**立即滚离底部约 1/3 屏**（保持锚仍在流式气泡上：短滚不离 item）；
3. 采流式期 4 个时点：截图（视口内容是否稳定）+ logcat `ScrollDiag`（`COMP-MSG fire delta` 频次 / `LEAP` / `RESIZE` / `gesture`）；
4. 判定：COMP-MSG fire 且视口内容四点纹丝不动 → 通道活（静态推演的未闭合点是假说①）；fire 但内容持续上移/LEAP 频发 → 通道已被回写竞争打死（假说②成立）→ 升级为 P0 修复项。
5. 对照复测（可选）：同法测 COMP-TOOL（工具期滚离底部）与 COMP-CMP（压缩展开区流式期滚离底部）。

### 6.2 贴底横幅可见性验证

1. **tool_progress**：贴底状态下触发 bash 工具（REST sleep 类），观察聚合卡是否出现、左下 ⬇ FAB 是否闪现（预测：卡不可见 + FAB 闪现）；随后发送任意消息验证 msgCount 锚底后卡可见。
2. **V1 进行中分割线**：V1 服务器（或本地置态 mock）长会话贴底触发压缩，观察整个 HTTP 挂起期是否可见（预测：不可见，完成态随 REST 刷新蹦出）。短会话（内容 < 视口）对照——预测可见（测量回填组合全部 item）。
3. **retry banner**：无 error 伴随的 retry 出现（可 mock sessionStatus）贴底观察。

### 6.3 插入上推几何验证（可选，低优先）

制造「revert banner 可见（贴底）+ 工具启动」组合，观察上方内容是否上推 H_tool（预测：会，量级 ≈ 卡高；与跟随语义混合，肉眼区分度低——可用 uiautomator 前后坐标取证，参照 #215 矩阵脚本思路）。

---

## 7. 参考

- 源码解包：`/tmp/compose-src/commonMain/androidx/compose/foundation/lazy/`（foundation-android 1.11.2-sources.jar；LazyListMeasure.kt / LazyListScrollPosition.kt / LazyListState.kt / LazyList.kt / LazyLayoutItemProvider.kt）
- 仓库代码：ChatMessageList.kt（L293-327 补偿状态与门控、L855-1004 列表结构与 COMP-CMP(tail)/COMP-TOOL、L1156-1185 COMP-MSG、L1322-1354 COMP-CMP(msg)）、ScrollCompensation.kt、CompactionCard.kt（#221 工作区版）、ChatScrollController.kt（msgCount/pendingCount/force 三 reveal 路径与 isAtBottom 定义）
- 文档：docs/research/sse-scroll-stability-iron-laws.md（铁律 3/4 与 §5.2 动态检查）、docs/journal/2026-08-24-card-unification.md §验收反馈·一（锚定 freeze 实证、通道定因 v1/v2、终版裁决）、docs/journal/2026-08-24-compaction-divider-unification.md（#217 E2E 与 V1 验证欠账）、docs/specs/2026-08-24-card-unification-design.md

---

## 变更日志

| 日期 | 作者 | 变更 |
|------|------|------|
| 2026-08-25 | 调研 agent（卡片高度预计算可行性委派） | 初版：机制基线（foundation 1.11.2 源码取证）、12 场景盘点、P1-P5 路径评估、通道回写竞争开放问题、验证协议三则 |
