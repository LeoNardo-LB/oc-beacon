# 2026-09-25 · #435 高度引擎统一化——48ms 流式节奏并入配对体系（设计）

> 状态：**设计定稿，实施中** · 关联：backlog #435（用户裁决：「统一一个地方处理，最好将所有高度有关的内容整合到我们的高度设置引擎中来」）
> 前置调研：docs/research/433-height-engine-map.md · docs/research/433-streaming-pipeline-map.md · docs/research/433-compose-viewport-research.md · docs/research/sse-scroll-stability-iron-laws.md
> 引擎 spec：docs/specs/2026-09-21-pre-render-coordinator-design.md

## 1. 背景与问题

流式输出期间视口震荡（#432 报告，stream-instant 缓解层装机后用户仍感知「来回震荡」）。#432 修的是**引擎 episode 派发点**的贴底豁免（expand skip-dispatch），但视口执行体系当时有**四套并行**：

| 体系 | 机制 | 贴底豁免 |
|---|---|---|
| 引擎 episode（CardExpandReveal） | settle→原子落地→applyPairedPreRenderShift（dispatchRawDelta 配对到全额） | ✅ #432 bottomPinnedExpandSkip（严格 0,0） |
| 引擎 steady（#430 迟到增长） | measure 记 Δreport → pre-draw flush 派发 | ❌ **无**（对称漏洞） |
| COMP 家族（DeferredRevealCompensator ×5 挂载点） | measure 入队 PreRenderShiftChannel → 帧界 pump → drain dispatchRawDelta | ❌ **无**（仅收缩向有 drop-at-bottom） |
| GUARD（含 #432 stream-instant） | 事后拉回 requestScrollToItem(0) | —（事后补救，非配对） |

### 震荡根因链（本轮定罪）

流式带工具场景（DSH 常态）：tool_progress 横幅（列表 index 0-6 锚定区，位于最新消息**之下**）以 48ms 节奏注入输出而增长 → COMP-TOOL 在 shouldCompensate 成立的窗口（离底/滚动后）逐批 enqueue +Δ → `PreRenderShiftChannel.drain` 无贴底判定直接 `dispatchRawDelta(+Δ)` → fiso 被推离 0 → atBottom 翻假 → GUARD 拉回 (0) → 下一批再推 = **锯齿震荡**（48ms×N 周期，与 100-444px 漂移实测吻合）。纯文本流式时 COMP-MSG 在贴底不注入（shouldCompensate=false），故症状以带工具流式为重。

诚实声明：「哪个推手在哪种状态点火」的完整经验证仍有缺口（贴底态下 shouldCompensate 理应为 false；历史 190-444px 证据来自 2026-08-30 会话开启场景，架构已数代更迭）。**本设计的统一规则对推手身份不确定性完全鲁棒**：任何家族的任何派发在贴底跟随态与读历史态一律免派发，唯「锚上移进入增长源」配对——震荡类根因（无豁免的派发）从构造上消失，真机验证矩阵（§7）负责经验证伪。

## 2. 统一配对规则（核心，纯函数）

锚点即意图。对任一增长源 item（lazy index = S，本帧增长 Δ>0）：

```
pair(Δ) ⟺ anchorIndex == S ∧ anchorOffset > 0
``

| 锚构型 | 语义 | 行为 |
|---|---|---|
| anchor < S（锚在增长源之下——含贴底跟随族 fii=0 与横幅锚） | 追加语义：增长向上扩展，新内容出现于底缘可见位，旧内容上移 | **免派发**（dispatch 反而把新行推到屏幕外并离底） |
| anchor == S ∧ fiso == 0（恰在增长源底缘） | 追加语义边界 | **免派发** |
| anchor == S ∧ fiso > 0（上移进入增长源） | 阅读尾段：增长点位于视口底缘之下 | **+Δ 同帧配对**（fiso+=Δ：增长点上方内容纹丝不动，新行留在底缘之下）——原 COMP offset+delta 语义的精确等价（dispatchRawDelta 正向=fiso+=Δ，#430 标定） |
| anchor > S（锚在增长源之上=读历史，增长源整体在视口之下） | 阅读位置神圣 | **免派发**（现 COMP 在此注入=「读历史被拖拽」缺陷，顺带修复） |

- **收缩（Δ<0）一律不派发**（只 rebase 基线）——保持现 COMP 行为（收缩全揭示）；贴底收缩 drop 与通道分支 1 语义一致。
- 派发经 `applyPairedPreRenderShift`（配对到全额，残差 0.5f 量化阈）+ 残量回账 2s 重试窗（#430 同款）；用户滚动（isScrollInProgress，自有派发豁免）即弃配 rebase。
- **引擎 steady flush 增补同一不变量**：`bottomPinnedExpandSkip(fii,fiso)`（#432 既有纯谓词，严格 0,0）成立 → 跳过派发 + rebase。episode 派发点已豁免（#432），steady 点补齐对称漏洞。

## 3. 目标架构

```
增长源（48ms 批 → 重组 → measure）
  ├─ 卡片展开/收起（episode）────── CardExpandReveal 引擎（不变,#432 豁免已备）
  ├─ 卡片迟到增长（steady）──────── 引擎 steady flush（+§2 贴底豁免增补）
  └─ 流式家族增长（消息/工具横幅/压缩卡）
        ↓ measure 相 note(height)（item 级 layout 节点,真高直报——无裁剪）
        StreamingGrowLedger（每列表单例,per-key 基线,Δ 累计）
        ↓ pre-draw flush 单点（PreRenderCoordinator.registerFlushTask,#423 K1）
        §2 规则求值 → applyPairedPreRenderShift / 免派发丢弃
GUARD：回落纯安全网（250ms 去抖五条件），stream-instant 分支退役
```

- 48ms 节奏**结构性继承**：ledger 的输入就是 48ms 批驱动的 measure，flush 派发节奏=48ms 批节奏，无需任何定时器层（增长侧批处理铁律不动）。
- 同帧闭合（K1）：measure 记账 → 本帧 pre-draw flush 派发 → draw。未配对中间态不上屏 → **延迟揭示/裁剪/version 代计数/帧界排队全部不再需要**。
- 单一视口权威：流式家族加入 PreRenderCoordinator flush 体系（registerStreaming 构想落地为常驻 flush task，空账本零成本早退）。

## 4. 实施清单

1. **ScrollCompensation.kt 重写**：`StreamingGrowLedger`（per-key 基线 map + pending 累计 + §2 规则求值 `pairedPending(anchorIdx, anchorOff, keyIndexLookup)`）+ `Modifier.streamingGrowPairing(listState, ledger, key)`（layout 节点：无界测真高 → note → 直报真高；onForgotten 清基线）。删除 `DeferredRevealCompensator`/`deferredRevealCompensation`/`CompensateState`。保留 `LazyListReflection`（引擎收起锚点恢复仍用）。
2. **ChatMessageList.kt**：5 挂载点迁移（COMP-MSG:1603 / COMP-CMP(v1):1668 / COMP-CMP(msg):2008 / COMP-CMP(tail):2239 / COMP-TOOL:2319 → streamingGrowPairing，ledger 单例 remember）；删除 shouldCompensate 双 key effect（499-508）与三 Compensator remember（471-482）；挂常驻 flush task（attachFlushHost 旁）；删除 PreRenderShiftChannel pump（1246-1252）；ScrollDiag 探针改读 ledger。
3. **CardExpandReveal.kt**：steady flush task（1070-1114）入口增补 `bottomPinnedExpandSkip` 豁免（跳过+rebase，#432 不变量推广到 steady 相）。
4. **ChatScrollController.kt**：删除 stream-instant 分支（221-238）与 `streamingTurnActive` 参数；**ChatScreen.kt:338** 同步删参。
5. **删除 PreRenderShiftChannel.kt**（全部用户迁移完毕）。
6. 测试：`StreamingPairingRuleTest`（§2 谓词四构型+收缩+残量）、`StreamingGrowLedgerTest`（基线/rebase/滚动弃配/键回收）；迁移 `DeferredRevealCompensatorTest`/`RevealCompensatorsTest`；`ChatScrollControllerTest` 去 streamingTurnActive。
7. 铁律文档更新：铁律 3/4 语义迁移说明 + 变更日志；AGENTS.md SSE 铁律段落同步。

## 5. 边界与让位矩阵

| 场景 | 行为 |
|---|---|
| 用户滚动中（isScrollInProgress） | flush 弃配 rebase（位置神圣）；派发自有豁免标志防自杀 |
| episode 进行中（引擎租约） | 流式 flush 不让位——两者 Δ 来源独立、dispatchRawDelta 可加性；引擎 episode 自有豁免 |
| 跳转锁 | 不变（跳转定位期间视口归跳转） |
| 新消息到达（MSGEFFECT） | 不变（messageCount 变化 → requestScrollToItem(0)，语义正确） |
| stream 结束 | streamingMsgId→null → 挂载点卸载 → 基线随 onForgotten 清；延迟分片（结构变化）由 key 锚定吸收 |
| 多 item 同时增长（消息+工具横幅） | 同 flush 单帧合并求值（K1 单点） |

## 6. 退役清单

DeferredRevealCompensator（含 version 代计数/延迟揭示/holdReveal/shiftSettled 门）、PreRenderShiftChannel（帧界 pump/代计数/贴底收缩分支——语义由 §2 规则吸收）、CompensateState+shouldCompensate 双 key effect、GUARD stream-instant、rememberChatScrollController.streamingTurnActive 参数。

## 7. 验证矩阵（真机，DSH 触发流式）

1. **贴底+工具流式**（震荡复现向量）：DSH 会话发「运行 run_code 列目录」类消息 → 录屏+logcat：GUARD reanchor 次数=0、fiso 恒 0（[DEBUG-drift] 流）、像素带追踪无锯齿。
2. **贴底+纯文本流式**：长回复流式 → 同上判据。
3. **读历史稳定**：流式中上滚两屏静止 → 视口纹丝不动（修复拖拽缺陷的正向验证）。
4. **尾段阅读**：上滚进入最新消息 ~300px 静止 → 流式期间该内容屏位稳定（±1px）。
5. **卡片展开/收起回归**：流式外卡片 toggle → #432 行为不变（贴底免跳/中位守恒）。
6. 单测全绿 + lint + compileDevDebugKotlin。

## 8. 风险与回退

- 风险：贴底跟随态判据依赖「锚在增长源之下」构型成立（banner 锚/0 锚）。chunk 裂变/横幅高度动画使锚短暂处于 1..S-1——规则按 anchor<S 族处理=追加语义，行为安全。
- 回退：单 commit 实施，验证失败整体 revert；stream-instant 已在历史（66a226ce）可单独复活。
