# 展开面「渲染前计算」架构设计（布局层恒定 + 绘制层揭示）

> 日期：2026-08-30
> 状态：设计定案（grill Q1–Q14 全定案）；§5 实现层选型待评审；待实现
> 位置约定：active spec 置于 docs/specs/；实现并验收后移至 docs/archive/specs/ 并更新本行状态
> 涉及模块：ui/screens/chat/components（ExpandReveal 家族六槽位 · PreRenderShiftChannel · ChatMessageList）
> 关联 backlog：#262 · journal：`docs/journal/2026-08-30-expand-prerender.md`

## 1. 概述

**问题**：现补偿体系（ExpandRevealCompensator + PreRenderShiftChannel 帧界排空）的视窗移动比内容高度变化**晚一帧**——帧界排空的固有错位。叠加 AnimatedVisibility（AV）enter/exit 边界帧的 ~30px 单帧台阶（2026-08-30 journal 实测残余，明确递延本项），贴底/mid-list 展开仍有可感知跳动。

**用户裁决（2026-08-30）**：「渲染前进行计算，计算完毕之后再渲染」。

**本设计**：tap 时一次性测得展开终态高度 finalH → 视窗一次预移 Δ → 布局层恒为终态 → 视觉揭示改为绘制层纯 clip 动画（无 fade、无内容位移）；AV 高度动画从布局层退役。**验收标准 = 零位移**：tap 后首帧起锚点内容纹丝不动，任何一帧错位即失败。

## 2. 现状与证据（2026-08-30 代码实证）

### 2.1 ExpandReveal 家族（六槽位，全部 AV 高度动画）

| 槽位 | 组件 / 文件 | compensator | reveal tag | AV |
|---|---|---|---|---|
| RB | ReasoningBlock.kt:60 | :190 | :196 | :202（NoFade） |
| TC | tools/cards/ToolCardScaffold.kt（承载整个工具卡家族） | :118 | :129 | :240 |
| EV | components/EventCard.kt（expandRevealListState 参数 :96） | :114 | :178 | :200 |
| TODO | tools/cards/TodoListCard.kt | :158 | :164 | :170 |
| QPC | components/QuestionPartContent.kt | :140 | :146 | :152 |
| QC | components/MessageCardAssistant.kt（内嵌 pending QuestionCard） | :384 | :390 | :396 |

核心：ExpandReveal.kt——状态机 :84（onMeasure :120）、layout 包装 :193（无限高测量 :200-203、clip 必须在外层 :81）、共享 AV spec :26/:37（fade+tween200）、NoFade 变体 :48/:54、LocalChatListState :62。

### 2.2 PreRenderShiftChannel（帧界排空）

object（PreRenderShiftChannel.kt:46），全 App 唯一补偿注入入口。生产者仅两处（都在 measure 块内）：ExpandReveal.kt:231（USER_EXPAND）与 ScrollCompensation.kt:185（OTHER，流式家族）。排空泵：ChatMessageList.kt:933-938 `LaunchedEffect{ while(true){ withFrameNanos{}; drain() } }`——帧时钟回调相位于**下一帧 measure 遍首**应用（request-position 通道）。净代价：**视窗移动晚高度变化一帧**（#262 靶子）。分支：①贴底+USER_EXPAND+Δ>0 → requestScrollToItemNoCancel 预移 :101-123；②贴底收起 → 丢弃由上方吸收 :127-133；③mid-list → dispatchRawDelta :149。

### 2.3 关键 journal 证据链

- **mid-list 2.4× 过冲**（已修，79a8ce0b 无限底 guard）：mid-list toggle 注入 ±444px 视窗位移 vs 185px 真实高度变化（docs/journal/2026-08-30-first-cycle-jump-and-question-card.md :14-19）。
- **AV 边界 ~30px 单帧台阶**：彻底消除需以补偿器状态机直接驱动展开高度替换 AV——即本设计（同 journal :53-57 递延条款）。
- **「滑完才展开」前科**：expand 家族 holdReveal 滚动裁剪 = 过度防御，V6 首轮反馈定性后**拆除**（commit 7f1777be；docs/journal/2026-08-27-event-card-unification.md :872-873）。流式家族 holdReveal 保留（#239 另一语义，ScrollCompensation.kt:92-105），不属本项。
- **markdown 首帧 Loading**：Mikepenz MarkdownState 新组合实例首帧 = State.Loading → 测得空高（afac74f1 定因，shell 输出因此改 verbatim 直渲染）→ RB/TC 展开体测量的深水区（§4.6 分级承诺）。
- **预测量污染前科**：jump-target 预组合 2026-08-21 被移除——预测尺寸污染 item 布局（ChatViewModel.kt:564-566）。本设计的 tap 时槽内临时 subcompose 与之不同轴（§5A 论证）。
- **spring→tween(200, FastOutSlowIn) 裁决**（2026-08-30）：clip 动画沿用。
- **reverseLayout=true**（ChatMessageList.kt:970）：方向语义翻转，「预移」符号约定必须沿既有通道的已验证约定（docs/chat-ui-event-lifecycle.md E2）。

## 3. 裁决清单（grill 2026-08-30，Q1–Q14）

| # | 裁决 |
|---|---|
| 验收 | 零位移：tap 后首帧起锚点内容纹丝不动；一帧错位即失败 |
| 锚点 | tap 控件的视口位置固定；贴底退化为底部锚定特例 |
| 收起 | 对称处理：预计算终态、预移、clip 收拢（力学见 §4.3） |
| 揭示形态 | 纯 clip（内容静态），六槽位统一——现无一槽位已是纯静态（5 槽 fade+tween、RB NoFade 但均 AV 布局动画） |
| 测量 | tap 时同步隐式测量（subcompose），不接受估算+校正 |
| 切分 | 试点先行（EV）→ 真机验收 → 铺开其余五槽 |
| 旧体系 | 统一组件取代 ExpandReveal 全家；PreRenderShiftChannel **保留**为流式家族入口；USER_EXPAND 分支死代码删除；DeferredRevealCompensator 不动 |
| 流程 | spec 先行（本文件）→ 实现层选型评审 → 开工 |
| markdown | 分级承诺：非 markdown 槽位（EV/TODO/QPC/QC）v1 全量零位移；markdown 槽位（RB/TC）首展开「首帧零位移 + 异步增长残余走既有链路」，二次展开靠缓存全零 |
| 折叠态 | 正文不组合（fling 只背 header，保 #258）+ finalH 缓存（rememberSaveable，跨组合窗口存活） |
| 测试 | 删 ExpandReveal 侧死契约测试，为新状态机写等价覆盖；DeferredRevealCompensatorTest（12 用例）不动 |
| 动画 | tween 200ms FastOutSlowIn 沿用 |
| 边界 | (i) 快速连续 toggle 与 (iii) 流式中 tap 展开**必须正确**；(ii) fling 中 tap 维持立即执行、不引入 hold（7f1777be 不复活）；(iv) 默认展开卡硬性不回归：首次组合直接终态、无动画、无扣留 |

## 4. 目标架构

### 4.1 组件形态

新统一组件（建议名 `PreRenderExpand`，落 `components/PreRenderExpand.kt`）+ 纯状态机 `PreRenderExpandMachine`（可单测核心，取代 ExpandRevealCompensator）：

```kotlin
@Composable
fun PreRenderExpand(
    expanded: Boolean,
    header: @Composable () -> Unit,      // 恒组合（折叠态唯一在树内容）
    content: @Composable () -> Unit,     // 仅展开/动画期组合（§4.4）
    contentKey: Any,                      // finalH 缓存失效键（§5D）
    logTag: String,
    listState: LazyListState = LocalChatListState.current ?: return, // 六槽位就地消费
)
```

状态机状态：`IDLE / MEASURING / REVEALING / EXPANDED / CLOSING`；输入：tap、measureResult(finalH)、shiftApplied（复用 PreRenderShiftChannel.shiftSettled 竞态门语义）。

### 4.2 展开管线（两路径）

- **缓存命中 / 收起**（Δ 在 tap 时刻已知：展开=finalH 缓存 − 现高；收起=现高 − header 高，header 恒组合故已知）：tap 处理器内（measure 块外）`dispatchRawDelta(Δ)` 预移 + 提交终态 → 下一渲染帧**同帧原子**落地（视窗已移 + 布局终态 + clip 从 0 开幕）。真·零帧间错位。
- **首次展开**（缓存未命中）：Compose 无测量同步 API，finalH 只能在 measure 遍中获得。tap → 状态 MEASURING → 下一 measure 遍 subcompose 测得 finalH（本遍布局持旧高，多余被外层 clip 裁掉**不放置**）→ enqueue Δ（复用帧界排空，USER_EXPAND 源）→ 再下一遍遍首视窗应用 Δ + 全量揭示——**视窗位移与高度揭示严格同遍配对**（既有机制已验证的配对法，中间不渲染任何半态）。「渲染前计算完毕再渲染」在此路径的精确含义：计算遍零渲染，渲染遍一次性终态。净延迟 1 个配对周期（8-16ms，不可感知）。
- 展开方向布局恒为终态：reveal 首帧 item 高即 finalH，clip 矩形 0→finalH（tween 200ms）。下方 item 的 Δ 位移与视窗 Δ 预移同帧对消 = 锚点零位移（卡片方向裁决「item 高不随动画变化」严格成立于展开侧）。

### 4.3 收起管线（力学与展开不对称，需评审确认）

收起幕布需要绘制空间，布局无法瞬跳终态——采用**布局与 clip 同步缓动**：t=0 起 200ms 内 layout 高度 expandedH→collapsedH 与 clip 矩形同步收缩，下方内容随幕布收拢平滑上移（正是 2026-08-30 用户裁决「下方收上来」+ 比例收拢的观感，collapseScale 语义由本力学自然吸收）。mid-list 锚定 header 时上方全程静止、**零视窗位移**（收缩只拉下方）；贴底由 LazyList 原生端锚定吸收（现分支②语义）。**与卡片「布局层恒定」表述的偏差在此显式登记**：恒定严格指展开侧；收起侧为同步缓动，若评审否决则退化为「瞬跳终态 + 无收起动画」（零位移仍成立）。

### 4.4 折叠态与 finalH 缓存

折叠 = content 不进组合树（fling 只背 header 组合成本，保 #258 实测结论）。finalH 缓存 `rememberSaveable`（LazyList item 键下跨滚动回收存活）：
- 红利 1：二次展开走 §4.2 同帧原子路径（零延迟）。
- 红利 2：**展开态卡滚回视口首帧即终高**——即使 markdown 异步重解析，布局高度已是缓存终值，下方零漂移（现体系做不到：首遍只能上报异步短高度再长高）。
- 失效：contentKey 变更（§5D）；失效后回落首次展开路径。

### 4.5 hit-test 门控

draw-layer clip 不裁剪命中测试：揭示/收拢动画 200ms 内，clip 外不可见内容可能可点（QC 槽位有按钮，真实风险）。门控：动画进行中容器级 pointer 拦截（consume/cancel）——动画结束即解除。折叠态 content 不组合，天然无命中问题。

### 4.6 承诺分级（markdown 异步高度）

markdown 首帧 Loading（§2.3）使 RB/TC 首次展开的 finalH 测量天然不准。契约：
- 非 markdown 槽位（EV/TODO/QPC/QC）：v1 全量零位移。
- markdown 槽位（RB/TC）：首展开 = 首帧零位移（测量值即预移依据）+ 解析完成后增长残余走既有配对链路（降级承诺，与今日行为不回归）；二次展开 = 缓存 finalH（内容已解析）全零。
- 彻底解法（预热解析/测量）留铺开阶段单独评估，不阻塞本 spec。

## 5. 实现层选型（待评审）

| # | 选型 | 推荐 | 备选 / 风险 |
|---|---|---|---|
| A | 测量挂点 | 组件内 SubcomposeLayout 按需 subcompose（MEASURING 遍一次性） | 与污染前科（ChatViewModel.kt:564-566）不同轴：那次是**预测 jump target 常驻组合**污染他人布局；本次是槽内、tap 后、瞬态测量，测完即转正为真实组合（首展开）或仅存数字（缓存路径）。备选：外部预测量器——引入跨组件耦合，不取 |
| B | 预移通道 | 帧外（tap 处）dispatchRawDelta；帧内（MEASURING）沿用帧界 enqueue→drain 配对 | requestScrollToItemNoCancel 是绝对定位 API，不适合相对 Δ；drain 分支③已验证 dispatchRawDelta 跨 item 语义。reverseLayout 符号约定沿既有实现，契约测试锁死 |
| C | hit-test 门控 | 动画期容器级 pointer 拦截 | 逐区域命中过滤（按 clip 矩形裁命中）更精细但复杂度高，200ms 窗口不值得 |
| D | 缓存失效键 | contentKey = (稳定 id, 内容版本)：EV=eventKey、TC/RB=part id+update 代次、TODO/QPC/QC=结构 hash | 保守回退：任何该 part 数据变更即失效（宁可重测不可错高） |
| E | 试点窗口 | EV 试点期 channel 分支①②保留（无生产者即不触发），全量铺开后清理 commit 统一删除 | 混合态窗口内 EV(新)/五槽(旧)并存——channel 为共享单例，需契约测试证明无串扰 |

## 6. 边界契约（v1 验收范围）

1. **快速连续 toggle**：必须正确——布局恒终态使 clip 中途重定向平凡（动画值重定向，无布局回滚）。
2. **fling 中 tap 展开**：立即执行，不引入 hold（7f1777be 拆除的过度防御不复活）。
3. **流式进行中 tap 展开**：必须正确——预移与 COMP-* 补偿并发语义写进契约测试（两通道共存时序）。
4. **默认展开卡**：硬性不回归——首次组合直接终态、无动画、无扣留（「滑完才展开」结构性不复发；配合 §4.4 红利 2，已知高度卡滚入零漂移）。

## 7. 旧体系退役清单

- 代码：`ExpandRevealCompensator`（:84）、`Modifier.expandRevealCompensation`（:193）、AV spec 四件（:26/:37/:48/:54）——六槽位全部迁移后整文件删除；`LocalChatListState`（:62）保留（新组件消费）；PreRenderShiftChannel 保留，`ShiftSource.USER_EXPAND` 与 drain 分支①②随清理 commit 删除（分支③由 OTHER 源继续使用）。
- 测试：`ExpandRevealCompensatorTest`（5 用例）删除；`RevealCompensatorsTest` 拆分——DeferredReveal 侧保留、ExpandReveal 侧删除；新增 `PreRenderExpandMachineTest`（等价覆盖：测量→预移→提交顺序契约、clip 状态机、hit-test 门控、缓存命中/失效、边界 1-4）。`DeferredRevealCompensatorTest`（12 用例）不动。

## 8. 试点与铺开

1. **EV 试点**（证据最厚：±444px/185px=2.4× 基线 + EV-REVEAL 计数器现成）：实现三件套（组件/状态机/门控）+ EV 单槽接线 + 机器测试 → 编译/单测绿 → 真机 V6（§9 清单）→ 用户验收。
2. 铺开 TODO/QPC/QC（非 markdown，全零位移承诺）。
3. 铺开 RB/TC（markdown 降级承诺验收：首展开残余 vs 今日行为不回归、二次展开全零）。
4. 清理 commit：删 ExpandReveal.kt 全家 + channel 分支①② + 死测试；全量单测。

ChatScreen/ChatMessageList 编辑遵守 chatscreen-editing-protocol（read→edit→compileDevDebugKotlin→commit 循环，禁止跨 agent 并行）。

## 9. 验证计划（V1–V6）

- **V1 编译**：每编辑循环 compileDevDebugKotlin（120s 超时）。
- **V2 单测**：PreRenderExpandMachineTest + 既有全量（--rerun 防 UP-TO-DATE；已知 #261 环境性红除外）。
- **V3 i18n**：无文案变更，N/A。
- **V4 构建**：assembleDevDebug。
- **V5 观测**：logcat trace tag 体系沿替（PR-EXPAND/EV 试点期保留 EV-REVEAL 对照，计数器断言「inject=0 落地配对数=动画帧数」类指标按实现细化）。
- **V6 人工清单**（EV 试点，真机）：
  1. mid-list tap 展开：标题行首帧起纹丝不动（对照屏幕上方参照物）
  2. 贴底 tap 展开：上方内容不动，卡片向下揭示
  3. 收起：下方内容随幕布 200ms 同步收拢上移，无瞬移、无冻结、无残裁剪
  4. 快速连点 toggle：无冻结/空白/残裁剪（2026-08-28 divider-design 回归标准）
  5. 流式进行中展开历史卡：锚点稳定
  6. fling 后松手 tap：立即展开（不等滚动停止）
  7. 默认展开卡滚出再滚回：首帧即全高（无 stub→长高）
  8. 首次 vs 二次展开：肉眼不可辨差异（缓存路径验证）

## 10. 风险与护栏

- **SSE 铁律不动**：Law 1（有状态 Markdown）、Law 3（COMP-MSG 仅 isStreamingMsg）、Law 4（双 key LaunchedEffect）、Law 5（streamingMsgId 时间戳语义）——本设计零接触流式补偿家族。
- **#258 不回归**：折叠态不组合正文是硬约束，禁止「常驻组合+常年 clip」诱惑。
- **7f1777be 教训**：展开路径永不引入滚动闸门。
- **同帧原子路径的 drag 竞态**：tap 处 dispatchRawDelta 与活跃 drag/fling 并存时的语义（边界 2 场景）需契约测试覆盖；异常时宁可退帧界配对路径（保正确性弃延迟）。
- **混合试点窗口**：channel 共享单例，EV(新)/五槽(旧)并存期无串扰需测试证明（§5E）。
