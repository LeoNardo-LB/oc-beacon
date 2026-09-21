# 02 · 跨框架滚动锚定先例调研 — W3C / Chromium / Flutter / React / Apple

> 为 PreRenderCoordinator（#432，spec 见 `docs/specs/2026-09-21-pre-render-coordinator-design.md`）的集中式「渲染前计算模块」设计提供业界先例。
> 方法：直接精读一手来源——W3C 规范全文、Chromium `scroll_anchor.h/cc` 与 `LocalFrameView` 源码、GitHub issue/API、官方开发者文档（Apple docs JSON 后端）、各库 README 与 API 文档原文。**不含实现代码。**
> 每条结论附来源 URL。调研限制见 §8。

## 0. TL;DR

- **规范层（W3C/Chromium）已把问题分解为四个正交机制**：锚点选取（深度优先+可见性三态+排除子树）、补偿计算（前后两次相对偏移之差，仅块轴）、应用时机（布局后、绘制前的统一 flush 点）、抑制仲裁（抑制窗口内合并 + 显式抑制触发器）。这四个机制恰好对应我们「渲染前计算模块」需要收拢的四个关注点。
- **虚拟化列表库（JS 生态）的共识**：虚拟列表结构已知 → 不需要 DOM 深度遍历找锚，锚退化为「首个可见 item 的 index + item 内偏移」；调节权必须**唯一化**（virtua 明确要求业务容器 `overflow-anchor:none` 关掉原生锚定，防止双重补偿）。
- **聊天场景的特例解**：Flutter/JS 聊天列表普遍用**反转布局**（内容从视觉底边生长）把「追加导致跳动」消解在布局层，锚定退化为「贴底」布尔态；prepend 历史消息才需要真正的 index 寻址补偿。
- **平台层（Apple）的表态**：UIKit 在启用行高估算时**主动托管 contentOffset/contentSize 并禁止应用直接读写**——「系统托管偏移、应用不碰像素」是有官方文档背书的 API 形态。

---

## 1. W3C CSS Scroll Anchoring（权威规范）

来源：[CSS Scroll Anchoring Module Level 1, W3C WD 2020-11-11](https://www.w3.org/TR/css-scroll-anchoring/)（编辑草案：[drafts.csswg.org/css-scroll-anchoring](https://drafts.csswg.org/css-scroll-anchoring)；编辑为 Chromium 的 Steve Kobes）

### 1.1 模型

规范目的原话：「Changes in DOM elements above the visible region of a scrolling box can result in the page moving while the user is in the middle of consuming the content」——通过跟踪**锚点节点（anchor node）**的位置并对滚动偏移做 adjustment 来缓解。若滚动容器处于 scroll-snap 吸附态，锚定补偿被限制在 re-snap 允许的范围内（§2）。

### 1.2 锚点选取算法（§2.1）

**选锚目标**：选「DOM 中足够深、要么是应被优先对待的重要节点、要么靠近滚动盒 optimal viewing region 块起始边」的节点。

**可行候选（viable candidate）条件，全部满足才可行**：
1. 是元素且非 non-atomic inline；
2. 在滚动盒 S 中**部分可见或完全可见**（三态：fully visible / partially visible / fully clipped，判据是 scroll anchoring bounding rect = scrollable overflow rectangle 与 optimal viewing region 的关系）；
3. 是 S 的后代；
4. 不在排除子树（excluded subtree）中；
5. 从 C 到 S 的祖先链都不在排除子树中。

**排除子树**（任一即排除）：`display:none`；`position:fixed`；`position:absolute` 且包含块是滚动盒祖先；`overflow-anchor:none`。

**优先级候选（priority candidates）**：① 文档焦点区（focused area）的 DOM 锚；② find-in-page 当前命中元素（跨元素取第一个）。优先级候选逐个检查，可行即选之，否则进入 DOM 遍历。

**主算法**：滚动盒元素自身 `overflow-anchor:none` → 不选锚；否则先试优先级候选；再对每个 DOM 子节点执行**候选检查算法**——若节点属排除子树或**完全被裁剪**则连同后代跳过；**完全可见**则直接选中；**部分可见**则先递归子节点（含「包含块是 N 但 DOM 父不是 N」的绝对定位元素），都没有才回退选 N 本身。

规范原话注记：「Deeper nodes are preferred to minimize the possibility of content changing inside the anchor node but outside the viewport, which would cause visible content to shift without triggering any scroll anchoring adjustment.」——**选深不选浅，是为了避免锚点内部变化测不到**。

**重选时机**：概念上「任何滚动盒滚动位置变化时」都重算锚点；规范明确允许**惰性计算**（等到需要锚点时才算），这是性能取舍。

### 1.3 adjustment 计算时机与抑制窗口（§2.2 / §2.2.1）

锚点移动时，浏览器计算锚点 rect 块起始边相对滚动内容块起始边的**前偏移 y0 与现偏移 y1**，将 **y1−y0 的补偿排队**，**在抑制窗口结束时统一执行**。补偿是一次正式滚动（按 CSSOM-VIEW 产生 scroll 事件）。

**抑制窗口（suppression window）**：
- 开始：当前事件循环迭代的开始，或上一个抑制窗口的结束（取更晚者）；
- 结束：当前迭代的结束，或「下一个其结果/副作用会因滚动位置改变而不同的操作」之前（规范给的例子：`getBoundingClientRect()`）——**读布局的 API 会强制提前截窗**，保证读写一致性；
- 窗口内**多个锚点移动合并**；窗口结束时执行所有未被抑制的排队补偿。

即：**规范把 adjustment 定位在一帧的微任务之后、渲染之前的边界上，且单帧内多次高度变化只应用一次净补偿**。

### 1.4 抑制触发器（§2.2.2）——哪些情况禁用

发生在抑制窗口内则**取消该次补偿**：
1. 锚点→滚动器路径上（含两端）任一元素的以下属性计算值变化：`top/left/right/bottom`、`margin`、`padding`、`width/height/min-width/max-width/min-height/max-height`、`position`、`transform`；
2. 滚动器内任意元素 `position` 变化致其进入/脱离绝对定位（不限路径）；
3. **滚动器滚动偏移为零**。

规范注记：这些触发器「exist for compatibility with existing web content that has negative interactions with scroll anchoring due to shifting content in scroll event handlers」——**为兼容存量内容而设，本质是「锚点自身几何变了就不补、offset 为 0 时不补」的经验规则**。

### 1.5 公共 API：`overflow-anchor`（§3）

`auto | none`，初始 `auto`，适用所有元素，不继承。`none` = 该元素及其后代从锚点选取中排除（后代自嵌套滚动容器对其自身滚动盒自动恢复，除非也显式设 `none`）。**不可能对 `none` 子树重新打开**。规范的 API 设计立场：**锚定默认全开，API 只提供 opt-out（整树排除），不提供 opt-in 或手动补偿原语**。

---

## 2. Chromium 实现

来源：[scroll_anchor.h](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/layout/scroll_anchor.h) / [scroll_anchor.cc](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/layout/scroll_anchor.cc)；[LocalFrameView.cc](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/frame/local_frame_view.cc)。类注释即官方一句话定位：「Scrolls to compensate for layout movements (bit.ly/scroll-anchoring)」。

### 2.1 双相位 API：布局前存锚、布局后补偿

```cpp
// Records the anchor's location in relation to the scroller. Should be
// called when the scroller is about to be laid out.
void NotifyBeforeLayout();
// Scrolls to compensate for any change in the anchor's relative location.
// Should be called at the end of the animation frame.
void Adjust();
```

锚点对象在 `NotifyBeforeLayout()` 中**惰性**计算并缓存到下一次 `Clear()`；补偿量在布局完成后由 `ComputeAdjustment()` 计算：`delta = round(当前相对偏移) − round(保存的相对偏移)`，**只调块轴**（`delta.set_x(0)`）。源码注释解释了为什么要取整：锚点报小数位置但绘制时做 DIP 吸附，「anchor moving from 2.4px → 2.6px is really 2px → 3px, so we should scroll by 1px instead of 0.2px」（crbug.com/610805）——**补偿量必须与像素吸附口径一致，否则产生永久性亚像素漂移**。

### 2.2 锚点选取的工程实现

- `Examine()` 逐层判定：跳过滚动器自身、`overflow-anchor:none`、匿名块（「developers can't reason about them」）、非 text/box；可见性三态映射为搜索终止条件——**完全可见 → kReturn（选中并停止），部分可见 → kConstrain（先递归子代，无更优才选它）**；还需 `CandidateMayMoveWithScroller`（跟随滚动器移动才可作锚）。
- `FindAnchorRecursive()` 为深度优先遍历，另有独立一遍扫描「包含块与 DOM 父不一致的绝对定位后代」（crbug 692701）与分片上下文特判——与规范算法一一对应。
- **优先级候选**：可编辑焦点元素、find-in-page 命中（`FindAnchorInPriorityCandidates()`）；实验 flag `ScrollAnchorPriorityCandidateSubtreeEnabled` 下还会以候选为根再跑一遍递归选择，避免「选了候选但候选内部有更优锚」。
- 锚定角落（`Corner`：TopLeft/BottomLeft/TopRight）按滚动器几何选择。

### 2.3 抑制与仲裁的工程取舍

- **RAII 抑制计数**：`BeginSuppressAdjustment()/EndSuppressAdjustment()` + `SuppressScrollAnchorScope`，`Adjust()` 开头检查 `suppress_adjustment_count_ > 0` 直接放弃——给其他机制（如 scroll-start）让路的通用挂点。
- **样式变更抑制位**（头注释原文）：「We suppress scroll anchoring after a style change on the anchor node or one of its ancestors, if that change might have caused the node to move… See http://bit.ly/sanaclap」。`NotifyBeforeLayout()` 时沿锚→滚动器链重算 `scroll_anchor_disabling_style_changed_`；`Adjust()` 中若补偿非零且该位置位 → `ClearSelf()` 并放弃补偿。**语义：如果这次位移本身来自锚点路径上的显式样式/几何变更，就视为「开发者主动移动内容」，不补**——防止锚定与程序化滚动互相叠加成「clap」。
- **排队去重**：`queued_` 标志保证一帧内 `NotifyBeforeLayout` 不重复入队；补偿通过 `frame_view->EnqueueScrollAnchoringAdjustment()` 进入队列。
- **补偿的滚动类型标记**：`scroller_->SetScrollOffset(new_offset, ScrollType::kAnchoring, kStationaryScroll)`——补偿滚动与用户滚动在类型上可区分；并有 `UseCounter::kScrollAnchored` 做埋点统计。
- **持久化恢复**：`SerializedAnchor`（CSS selector + 相对偏移 + simhash 相似度）用于会话恢复后按选择器重找锚并按旧相对偏移复位（`RestoreAnchor`）。
- 工程现实注记（`FindAnchorInOOFs` 源码注释原文）：「the scroll anchor machinery often operates on a dirty layout tree」——**锚机制运行在未清理的布局树上，选取必须容忍对象/片段已被删除**。

### 2.4 渲染管线相位

`NotifyBeforeLayout()` 在布局前记录锚位；`Adjust()` 的执行点在 `LocalFrameView::RunPostLifecycleSteps()`——`DidBeginMainFrame()` 保证「若本帧还没跑过 PostLifecycle，则在提交前跑」（源码：`if (!did_run_post_lifecycle_steps_before_commit_) { RunPostLifecycleSteps(); }`）。即：**锚定补偿发生在 DocumentLifecycle 的 PostLifecycle 相（布局完成后、绘制/提交前），同一帧内闭环，用户永远看不到未补偿的中间帧**。

---

## 3. Flutter

### 3.1 框架没有滚动锚定——主跟踪 issue

[flutter/flutter #99158「Maintain scroll location to prevent content jumping when network images or dynamic network content loads into a listview that has already been laid out」](https://github.com/flutter/flutter/issues/99158)（open，P3，标签含 `framework`/`f: scrolling`/`workaround available`，50 👍）。报告场景：视口上方列表项内的网络图片异步加载→项高变化→内容上移跳变；请求为 ListView（或任意 Scrollable）增加 scroll anchoring 选项。（注：任务提示中的 #125701 经核验是 2023-04-28 的 engine roll 公告、与本主题无关，正确的主跟踪 issue 即 #99158。）

框架成员（Piinks）在 [2022-03-10 评论](https://github.com/flutter/flutter/issues/99158#issuecomment-1067352272)中给出的根因立场：**「Scroll position is not content aware… the ListView does not know anything about its children」**——像素偏移与内容语义脱钩，且 ListView 对子项无感知，这正是通用锚定难做的框架侧原因。截至本次调研该 issue 仍 open。报告者同时澄清了两个易混 issue：[#40340](https://github.com/flutter/flutter/issues/40340) 是 PageStorageKey 的跨状态位置保持、[#63946](https://github.com/flutter/flutter/issues/63946) 是插入/删除条目的位置——都不同于「已有项高度变化」。

### 3.2 `keepScrollOffset` 的真实语义（澄清误区）

[ScrollController.keepScrollOffset 源码文档](https://api.flutter.dev/flutter/widgets/ScrollController/keepScrollOffset.html)：**「Each time a scroll completes, save the current scroll offset with PageStorage and restore it if this controller's scrollable is recreated」**——它是**滚动位置在 widget 树重建间的持久化**（记像素值），与内容高度变化无关，**不是**滚动锚定。把它当成锚定机制是常见误区。

### 3.3 聊天模式的布局层解法：`reverse: true`

[ScrollView.reverse 官方语义](https://api.flutter.dev/flutter/widgets/ScrollView/reverse.html)：「the scroll view scrolls from bottom to top when reverse is true」。聊天列表的标准实践即基于此：列表视觉上仍贴底生长，但**新消息追加在逻辑 index 0（布局起点）**，已有消息的布局位置不变——追加类高度变化被布局方向消解，视口天然稳定；这也是 `reverse:true` 列表「贴底」语义的来源。本仓 ChatScrollController 的 `firstVisibleItemIndex==0` 贴底判据与之同构。

### 3.4 索引寻址：`scrollable_positioned_list`

[google/flutter.widgets · scrollable_positioned_list](https://github.com/google/flutter.widgets/tree/master/packages/scrollable_positioned_list)：`ItemScrollController.scrollTo(index:, duration:, curve:)` / `jumpTo(index:)`、`ItemPositionsListener`（当前可见项）、`ScrollOffsetController.animateScroll(offset:)`（相对偏移微调）。它把「定位」从像素偏移升级为 **index 寻址**，是 Flutter 生态里「锚点 = index + item 内偏移」这一公共 API 形态的代表实现。

### 3.5 聊天列表包

React 侧对照物更完整（见 §4.4 react-virtuoso 的 Message List）。Flutter 侧聊天包（Flyer chat 等）社区实践依赖 §3.3 的 reverse 布局；本次核验时 `Flyer-Chat/flutter_chat_ui` master 分支 README 返回 404（疑仓库迁移），未能逐包核验 pub.dev 上的具体 API（见 §8）。

---

## 4. React 生态

### 4.1 react-virtualized：命令式重算（第一代）

[Grid.recomputeGridSize 文档](https://github.com/bvaughn/react-virtualized/blob/master/docs/Grid.md)原文：「Recomputes row heights and column widths after the specified index… This function should be called if dynamic column or row sizes have changed but nothing else has. Since Grid only receives columnCount and rowCount it has no way of detecting when the underlying data changes. This method will also force a render cycle (via forceUpdate)」——**库不感知数据变化，测量（CellMeasurer）与重算全部由应用显式驱动**；配套 [CellMeasurer](https://github.com/bvaughn/react-virtualized/blob/master/docs/CellMeasurer.md) 提供一次性测量原语。

### 4.2 TanStack Virtual：测量回调 + 估算先验

[API 文档](https://github.com/TanStack/virtual/blob/main/docs/api/virtualizer.md)：
- `estimateSize`：官方建议动态测量场景「estimate the largest possible size… This will help the virtualizer calculate more accurate initial positions」——**故意高估，让修正方向一致向下，减少来回跳**。
- `measureElement`：`(element, entry: ResizeObserverEntry | undefined, instance) => number`——「called when the virtualizer needs to dynamically measure the size of an item」，**测量由 ResizeObserver 驱动**（浏览器保证该回调在 layout 后、paint 前执行，与 §2.4 的 PostLifecycle 相同相位）。
- `scrollMargin`：滚动坐标系原点偏移，文档明确「To dynamically measure value for scrollMargin you can use getBoundingClientRect() or ResizeObserver. This is helpful in scenarios when items above your virtual list might change their height」——**视口前方的头部高度变化被并入坐标系统一补偿**，而不是散落的个案修补。
- `onChange(instance, sync)`：`sync` 标记「whether scrolling is currently in progress」——**把「是否滚动中」作为调节决策的一等输入显式暴露给应用**。

### 4.3 virtua：零配置测量 + 调节权唯一化

[README](https://github.com/inokawa/virtua)：定位为「zero-config」——「It also handles common hard things in the real world (dynamic size measurement, **scroll position adjustment while reverse scrolling** and imperative scrolling, iOS support, etc)」；强制依赖 ResizeObserver。关键 API 证据：其 `Virtualizer` 自定义容器示例中，业务滚动容器必须写 `overflowAnchor: "none"`——**库自己承担全部滚动补偿，显式关掉浏览器原生锚定以避免双重补偿**。这是「调节权必须唯一」在真实库文档中的直接体现。README 另附与 react-virtuoso / react-window / react-virtualized / TanStack 的逐特性[对比表](https://github.com/inokawa/virtua#comparison)。

### 4.4 react-virtuoso：聊天场景的专职 API

[README](https://github.com/petyosi/react-virtuoso)：Message List 组件「built specifically for human/chatbot conversations… exposes an imperative data management API that gives you the necessary control over the scroll position when older messages are loaded, new messages arrive, and when the user submits a message. The scroll position can update instantly or with a smooth scroll animation」（文档站 [virtuoso.dev/message-list](https://virtuoso.dev/message-list/)）。即：**聊天场景的滚动控制被建模为「带意图的批量数据变更事务」（加载历史/新消息/发送），由应用声明意图、库决定视口怎么动**——等价于本仓 MSGEFFECT 锚底 + 历史分页补偿的收拢目标。

### 4.5 react-window v2：动态行高的官方态度

[README](https://github.com/bvaughn/react-window)：`itemSize` 接受「dynamic row height cache returned by the `useDynamicRowHeight` hook」，并附官方警告原文：「⚠️ Dynamic row heights are not as efficient as predetermined sizes. It's recommended to provide your own height values if they can be determined ahead of time.」——**生态一致结论：动态测高是兜底，能预测量就预测量**（与本仓「预测量供给层」思路一致）。

---

## 5. macOS / iOS

### 5.1 macOS NSTableView：显式行高变更通知 + 事务

- [`noteHeightOfRows(withIndexesChanged:)`](https://developer.apple.com/documentation/appkit/nstableview/noteheightofrows(withindexeschanged:))：「If the delegate implements `tableView(_:heightOfRow:)`, this method **immediately retiles the table view** using the row heights the delegate provides」；view-based 表默认动画（可用 `NSAnimationContext.duration = 0` 关闭），cell-based 表在 `beginUpdates/endUpdates` 包裹内才动画。**应用显式声明「哪些行高了」，表格立即重排**。
- [`beginUpdates()`](https://developer.apple.com/documentation/appkit/nstableview/beginupdates())：批量插入/删除/移动包裹在事务里同时动画，「**The selected rows are maintained during the series of insertions, deletions, moves, and scrolling**」——事务期间**选中态（另一种视口锚定）由表格托管维护**；「The main reason for doing a batch update… is to avoid having the table animate unnecessarily」。

### 5.2 iOS UITableView：估算行高 + 系统托管偏移

[`estimatedRowHeight`](https://developer.apple.com/documentation/uikit/uitableview/estimatedrowheight) 关键原文（本次从 Apple docs JSON 后端提取）：
- 估算把几何计算成本从加载时推迟到滚动时；self-sizing 表不得设 0；
- 「**When using height estimates, the table view actively manages the contentOffset and contentSize properties… Don't attempt to read or modify those properties directly.**」

这是四个平台/生态里**最直白的「集中式视口托管」官方表态**：开启估算后，偏移与总尺寸的唯一写者是 UITableView 自身，应用不得直接操作——与本仓 PreRenderCoordinator「谁有权动视口」的租约模型同构，只是粒度是整个表视图。

### 5.3 文档边界

UIKit 的公开文档**没有**提供「已可见 cell 行高变化 → 自动补偿偏移」的显式 API/语义说明（self-sizing 行高的补偿行为依赖 `beginUpdates/endUpdates` 与估算机制，社区通行做法是手动做 offset delta 配对）。macOS 则有显式 retile 调用。两平台的差异本身是先例：**iOS 选择把复杂性藏进「估算 + 托管」，macOS 选择暴露「声明 + 立即重排」**。

---

## 6. 综合提炼（对应设计四问）

### 6.1 锚点选取策略

| 体系 | 锚点是什么 | 选取策略 |
|---|---|---|
| W3C/Chromium | 任意 DOM 盒（LayoutObject） | 深度优先：最深**完全可见**者优先，部分可见先递归子代再回退自身；排除子树（display:none / fixed / 脱离滚动器的 absolute / opt-out）；优先级候选（焦点、find-in-page）覆盖普通遍历；惰性计算 |
| JS 虚拟化库 | 首个可见 item 的 index + item 内偏移 | 列表结构已知，无需遍历；测量缺失时用 estimateSize 先验（TanStack 建议高估） |
| 聊天模式 | 列表尾部（贴底布尔态） | 反转布局使追加不改变既有内容位置；锚定退化为「贴底/离开」状态机 |
| 历史消息 prepend | 某 index | scrollTo(index) 式绝对寻址 + 相对偏移微调 |

**可移植结论**：虚拟化场景不需要规范那套通用 DOM 遍历；锚点粒度直接取「首个可见 item index + offset」，把「深度优先找最稳锚」替换成「**锚点项自身高度变化时不补**」（见 6.3）。

### 6.2 adjustment 应用时机（渲染管线哪一相）

- Chromium：布局前存锚 → 布局后算差 → **PostLifecycle 相（commit/paint 前）统一 flush，单帧闭环**（§2.4）。
- W3C：事件循环迭代末尾（= 一帧的渲染前边界），读布局操作（getBoundingClientRect 类）强制提前截窗（§1.3）。
- JS 库：ResizeObserver 回调（浏览器规定 layout 后、paint 前触发）内同步改 offset——与 Chromium 同相。
- **可移植结论**：补偿必须发生在「布局完成之后、本帧绘制之前」的**单一 flush 点**，且**一帧内多次变化合并为一次净补偿**。Compose 中对应的正是「measure/layout 完成后、draw 前」的窗口——与 PreRenderCoordinator 的命名定位（pre-render）一致。

### 6.3 并发场景仲裁（流式追加 + 用户滚动 + 行高变化同时发生）

先例中出现的全部仲裁手段，按层归纳：
1. **合并**：抑制窗口内多次锚点移动只应用一次净补偿（W3C §1.3；Chromium `queued_` + Enqueue 队列）。
2. **用户意图优先**：补偿滚动带专用类型标记（Chromium `ScrollType::kAnchoring` / `kStationaryScroll`），可与用户滚动区分；TanStack 把「滚动中」作为 `onChange(sync)` 显式输入；本仓铁律 4（isScrollInProgress 双 key）是同一原则。
3. **自扰抑制**：位移若源于锚点路径上的显式几何/样式变更，则放弃补偿（W3C suppression triggers §1.4；Chromium `scroll_anchor_disabling_style_changed_` §2.3）——**流式行高变化发生在锚点项自身时，正确的动作是换锚/不补，而不是补偿**。
4. **边界特例**：offset==0（贴顶）不补（W3C）；聊天贴底态不补而是锚底（virtuoso/React 生态与本仓 MSGEFFECT 同构）。
5. **调节权唯一化**：库内调节时关闭原生锚定（virtua 的 `overflowAnchor:none`）——**同一视口同一帧只允许一个调节者**。
6. **反向经验（Flutter）**：没有集中协调器时，各方各自 jumpTo 补偿会产生可见延迟闪烁（#99158 用户反馈），且如本仓 04 文档所录，守卫与补偿「±H 战争」——**先例支持把仲裁权上收到单一协调器**。

### 6.4 公共 API 形态（可复用模块的接入面谱系）

按「应用声明多少」递增：
1. **声明式 opt-out（W3C `overflow-anchor:none`）**：默认全开，只声明禁区。模块化的最小 API——但要求框架层有默认锚定。
2. **零配置自动（virtua / react-window v2）**：库自动测量+自动补偿，业务无感；代价是官方自认动态尺寸低效、能预测量就预测量。
3. **测量回调 + 先验估算（TanStack）**：`measureElement(entry)` / `estimateSize`（建议高估）/ `scrollMargin`（把视口前方高度并入坐标系）/ `onChange(sync)`。**这是「库托管调节 + 应用提供测量事实」的均衡形态**。
4. **命令式重算（react-virtualized `recomputeGridSize`）**：应用知道数据变了才触发；最透明但把协调负担全推给应用（正是我们要消灭的形态）。
5. **索引寻址 + 变更事务 + 托管偏移（scrollable_positioned_list `scrollTo(index)`、react-virtuoso Message List、AppKit `beginUpdates/endUpdates` + `noteHeightOfRows`、UIKit 估算托管）**：定位用 index、偏移由系统托管、每次变更作为带意图的事务进入、系统在事务边界统一决定视口。
6. **持久化锚（Chromium `SerializedAnchor`）**：跨会话恢复时用「选择器 + 相对偏移」复位，提示锚定状态应是可序列化的纯数据。

**对 Compose 集中式 pre-render 模块的映射建议（不写实现）**：接入面取 3+5 的混合——① 模块内注册「视口租约」（调节权唯一，对应 6.3-5）；② 锚点数据结构 = 首个可见 item index + item 内偏移（可序列化，对应 6.4-6）；③ 各渲染前计算域向模块声明「变更事务 + 意图」（追加锚底 / 历史 prepend / 流式行高），模块在布局后、绘制前的单一 flush 点合并执行（对应 6.2）；④ 抑制规则内建：用户滚动中、锚点项自身高度变化、贴顶/贴底特例（对应 6.3）。

---

## 7. 来源清单

| # | 来源 | URL |
|---|------|-----|
| 1 | W3C CSS Scroll Anchoring WD | https://www.w3.org/TR/css-scroll-anchoring/ |
| 2 | Chromium scroll_anchor.h | https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/layout/scroll_anchor.h |
| 3 | Chromium scroll_anchor.cc | https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/layout/scroll_anchor.cc |
| 4 | Chromium LocalFrameView.cc（PostLifecycle） | https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/frame/local_frame_view.cc |
| 5 | Flutter #99158 | https://github.com/flutter/flutter/issues/99158 |
| 6 | Piinks 立场评论 | https://github.com/flutter/flutter/issues/99158#issuecomment-1067352272 |
| 7 | ScrollController.keepScrollOffset | https://api.flutter.dev/flutter/widgets/ScrollController/keepScrollOffset.html |
| 8 | ScrollView.reverse | https://api.flutter.dev/flutter/widgets/ScrollView/reverse.html |
| 9 | scrollable_positioned_list | https://github.com/google/flutter.widgets/tree/master/packages/scrollable_positioned_list |
| 10 | react-virtualized Grid.md | https://github.com/bvaughn/react-virtualized/blob/master/docs/Grid.md |
| 11 | TanStack Virtual virtualizer.md | https://github.com/TanStack/virtual/blob/main/docs/api/virtualizer.md |
| 12 | virtua README | https://github.com/inokawa/virtua |
| 13 | react-virtuoso README / Message List | https://github.com/petyosi/react-virtuoso · https://virtuoso.dev/message-list/ |
| 14 | react-window v2 README | https://github.com/bvaughn/react-window |
| 15 | NSTableView noteHeightOfRows | https://developer.apple.com/documentation/appkit/nstableview/noteheightofrows(withindexeschanged:) |
| 16 | NSTableView beginUpdates | https://developer.apple.com/documentation/appkit/nstableview/beginupdates() |
| 17 | UITableView estimatedRowHeight | https://developer.apple.com/documentation/uikit/uitableview/estimatedrowheight |
| 18 | Flutter #40340 / #63946（辨析用） | https://github.com/flutter/flutter/issues/40340 · https://github.com/flutter/flutter/issues/63946 |

## 8. 调研限制

- 本会话 `web_search` 后端不可用（HTTP 402），未能做第二路独立检索交叉验证；所有结论均直接抓取一手来源原文（规范全文 / 源码 / 官方文档 JSON / 库文档 raw），来源本身即各领域权威，可靠性以来源等级补偿。
- Chromium 官方设计文档 bit.ly/scroll-anchoring（源码注释中引用）与 bit.ly/sanaclap 因短链跨域重定向被环境拒绝而未解析；工程取舍以源码注释为准（其内容已覆盖设计文档主旨）。
- 任务提示中的 Flutter issue #125701 经核验为 engine roll 公告（已关闭、与本主题无关），已修正为 #99158。
- `Flyer-Chat/flutter_chat_ui` master README 404（疑迁移），pub.dev 聊天包未逐包核验；聊天包结论由 reverse 官方语义 + react-virtuoso Message List 文档支撑。
- UITableView 的 `performBatchUpdates` 文档页 slug 多次尝试未命中 JSON 后端，iOS 事务语义由 `estimatedRowHeight`「actively manages」句 + macOS 侧事务文档旁证。
