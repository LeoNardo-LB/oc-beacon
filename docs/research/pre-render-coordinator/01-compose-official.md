# 调研：Jetpack Compose 官方机制 —— LazyColumn(reverseLayout) 条目高度动画时的视口稳定

- 主题：Compose 帧相位模型 / LazyList 锚定内部机制 / LazyListState 滚动 API 契约 / animateItem 与条目尺寸变化 / 官方逐帧滚动账本
- 日期：2026-09-21
- 调研方法：本会话 `web_search` 端点余额不足（HTTP 402）、通用搜索引擎（DDG/Bing/Google）均被反爬或 consent 墙拦截，故全部证据改为**直接抓取第一方原文**：
  - androidx-main 源码（android.googlesource.com，`?format=TEXT` 原文解码，抓取日期即本文日期）；
  - developer.android.com 官方文档页与 API 参考页（直接抓取 HTML 提取正文）；
  - androidx 官方 GitHub 镜像仓库 API（issue/PR 检索）。
- 局限声明：issuetracker.google.com 为 SPA，本环境无法机读 issue 正文；issue 编号一律以 **androidx 源码注释与官方 release notes 中的第一方引用**为锚（文中所列 b/ 编号均来自这两处，非猜测）。cs.android.com 等价浏览入口：https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/

以下源码引用格式：`文件名:行号`（androidx-main，本文抓取日版本），URL 指向 googlesource 永久路径（`refs/heads/androidx-main`）。

---

## 1. Compose 帧相位模型

来源（官方文档）：
- https://developer.android.com/develop/ui/compose/phases （"Jetpack Compose phases"，页面标注 Last updated 2026-09-11 UTC）

### 1.1 三相位与单向数据流

官方文档要点（phases 页）：

- 一帧经过 **Composition（What）→ Layout（Where，含 measurement 与 placement 两步）→ Drawing（How）** 三相位，"The order of these phases is generally the same, allowing data to flow in one direction ... (also known as unidirectional data flow)"。
- **相位在同一帧内不会倒退**：官方在 "Recomposition loop (cyclic phase dependency)" 一节明确写道 "the phases of Compose are always invoked in the same order, and that there is no way to go backwards while in the same frame"。
- Compose 按**相位归因状态读取**：composition 相位读 → 重组；measure/placement 相位读 → 只重布局（必要时连带 draw）；draw 相位读 → 只重绘。从而 "Compose performs only the minimum amount of work required to update the UI"。
- **Key Point（官方原文）**："The measurement step and the placement step have separate restart scopes" —— measure 与 placement 是两个独立的重启作用域。

### 1.2 哪些变更合法：反写（backwards write）一帧滞后铁律

官方文档给出的唯一"相位间传递"反例与结论（phases 页 "Recomposition loop" 一节，逐句核对过原文）：

- 模式：`Modifier.onSizeChanged()` / `onGloballyPositioned()` 等布局回调里**写状态**，再把该状态作为 `padding()` / `height()` 等布局输入 → "be careful of this general pattern"。
- 时序：第 1 帧 composition 读到初值 → layout 相位里 onSizeChanged 更新状态 → "Compose then schedules a recomposition **for the next frame**. However, during the current drawing phase, the text renders with a padding of 0, as the updated value is not yet reflected" → 第 2 帧重组才生效，且官方明说代价："will result in producing a frame with overlapping content"（一帧错版）。
- **源码级佐证**（onSizeChanged 的 KDoc，官方原文）："Using the `onSizeChanged` size value in a MutableState to update layout causes the new size value to be read and the layout to be recomposed in the succeeding frame, **resulting in a one frame lag**."
  来源：OnRemeasuredModifier.kt:28-44 — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/OnRemeasuredModifier.kt
- **快照系统的 apply 时机**（Snapshot.kt）：
  - `registerApplyObserver`："called back when snapshots are applied to the global state"（Snapshot.kt:635-647）。
  - `sendApplyNotifications`："Apply notifications for state objects modified outside snapshot are **deferred** until method is called"，由 "Composition schedules this to be called after changes to state objects are detected"（Snapshot.kt:689-702）。
  - `registerGlobalWriteObserver` 的 KDoc 直接写明用途："Composition uses this to **schedule a new composition** whenever a state object that was read in composition is modified"（Snapshot.kt:649-672）。
  - 即：在 layout/draw 相位里写 snapshot 状态，apply 通知 → 重组调度 → **下一帧** composition。当帧内不可能"改了状态立刻重测/重组"。
  来源：Snapshot.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/snapshots/Snapshot.kt

### 1.3 onPlaced / onGloballyPositioned / drawWithContent 时机契约

- **onPlaced**（OnPlacedModifier.kt:26-35, 80-88）："Invoke onPlaced **after the parent LayoutModifier and parent layout has been placed and before child LayoutModifier is placed**" —— 它是 placement 过程内部的钩子（父已放置、子未放置），仍属于 layout 相位。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/OnPlacedModifier.kt
- **onGloballyPositioned**（OnGloballyPositionedModifier.kt:26-41, 90-97）："it will be called **after a composition when the coordinates are finalized**"；接口文档："Called with the final LayoutCoordinates of the Layout **after measuring**"。即：本帧 layout 完成后、（语义上）draw 前被调用；保证至少一次，位置在 window 内变化即回调，但不保证屏幕绝对位置变化必回调。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/OnGloballyPositionedModifier.kt
- **drawWithContent**（DrawModifier.kt:436-441）："Creates a DrawModifier that allows the developer to draw before or after the layout's contents" —— 在 draw 相位执行；phases 页明确 "State reads during drawing code ... When the state's value changes, Compose UI runs **only the draw phase**"。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/DrawModifier.kt

### 1.4 结论：布局后、绘制前的窗口里能做什么

- **公开钩子**（onPlaced/onGloballyPositioned/onRemeasured、placement lambda 如 `Modifier.offset { }`）都在 layout 相位内或紧随其后；在此**写普通 snapshot 状态 → 只能驱动下一帧**（§1.2 铁律），当帧 draw 用的还是旧值。
- 官方自己"布局后当帧改滚动位"不走状态回写，而是走 **scroll 事务内的同步测量**：`LazyListState.onScroll` 直接调 `remeasurement?.forceRemeasure()` 或免重测的 `copyWithScrollDeltaWithoutRemeasure`（见 §3.5），全部发生在 `scroll {}` 互斥块内，与帧相位解耦。这是官方允许"当帧生效"的唯一路径形态——**同步 Remeasurement API，而非状态写**。

---

## 2. LazyList 滚动锚定内部机制

来源（androidx-main 源码，lazy/ 目录）：
- LazyListScrollPosition.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollPosition.kt
- LazyListMeasure.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListMeasure.kt
- LazyListMeasureResult.kt / LazyListLayoutInfo.kt 同目录（URL 见 §2.5）。

### 2.1 锚点的表示：index + offset + lastKnownFirstItemKey

- `LazyListScrollPosition`："Contains the current scroll position represented by **the first visible item index and the first visible item scroll offset**"（LazyListScrollPosition.kt:28-36）。锚 = `index`（可写 state）+ `scrollOffset`（永远 **≥ 0**，有 check：59、67 行 `checkPrecondition(scrollOffset >= 0f)`）。
- `updateFromMeasureResult`（51-64 行）：每次测量把结果回写锚点；首个有内容的测量之前不覆盖传入初值。
- **按 key 校正（内容锚定的核心）**`updateScrollPositionIfTheFirstItemWasMoved`（88-105 行）："In addition to keeping the first visible item index we also store **the key** of this item. When the user provided custom keys for the items this mechanism allows us to **detect when there were items added or removed before our current first visible item and keep this item as the first visible one** even given that its index has been changed." —— 即：锚点之前的插入/删除由 **key 映射**吸收，锚条目内容保持不动（index 自动改写）。
- `requestPositionAndForgetLastKnownKey`（71-86 行）：程序性滚动直接设 index+offset，并**清空 key**，让下一次 key 校正不要覆盖这次请求；KDoc 同时诚实说明该请求只是"下一帧组合的起点"，实际位置以测量结果为准（条目可能比 offset 短、条目可能不够填满视口等）。
- key→index 映射用滑窗优化：`NearestItemsSlidingWindowSize = 30`、`NearestItemsExtraItemCount = 100`（115-122 行）。

### 2.2 测量期如何消费滚动与尺寸变化

`measureLazyList`（LazyListMeasure.kt:54-484）的关键事实：

- 测量以锚 `firstVisibleItemIndex + firstVisibleItemScrollOffset` 为起点组合条目；请求的滚动先全额施加（`currentFirstItemScrollOffset -= scrollDelta`，154 行），再逐项测量修正。
- 向回滚时**补组合锚之前的条目**并重算锚 offset（185-200 行）；条目尺寸就是在这个循环里进入账本的（`currentFirstItemScrollOffset += measuredItem.mainAxisSizeWithSpacings`，190 行）。
- **官方注释直接承认尺寸变化折算进消费量**（273-276 行）："scrollDelta can be smaller than scrollToBeConsumed if there were not enough items ... **or it can be larger if items were resized**, or if, for example, we were previously displaying the item 15, but now we have only 10 items in total" —— 即锚后内容尺寸变化时，差值被当作"已消费的滚动"处理，锚（index+offset）本身不动。
- 不足一屏时反向回滚（scroll-back，248-271 行）。
- 放置阶段用 `withMotionFrameOfReferencePlacement` 打标（444-455 行）："This allows the consumer of this placement offset to differentiate this offset vs. offsets from structural changes. ... signals a preference to **directly apply changes rather than animating, to avoid a chasing effect to scrolling**." —— 官方在 placement 层面区分"滚动位移"与"结构位移"，前者不参与动画。

### 2.3 免重测快速路径：copyWithScrollDeltaWithoutRemeasure

- LazyListMeasureResult.kt:88-164："In some cases we can apply small scroll deltas by **just changing the offsets for each visibleItemsInfo** ... If new layout info is returned, **only the placement phase is needed**"；不可安全套用时返回 null，走完整重测。可套用条件：delta 后首条目不变（109-110 行）、无 sticky header（116-119 行）、不会新增/移除可见项（120-133 行）。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListMeasureResult.kt

### 2.4 估高（average size）的用途

- `visibleItemsAverageSize()`（LazyListLayoutInfo.kt:90-95）：**可见条目的平均主轴尺寸 + itemSpacing**。仅此而已——它是可见项的实测均值，不是缓存的历史估高表。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListLayoutInfo.kt
- 三个消费点：
  1. `LazyListState._scrollIndicatorState.calculateScrollOffset`（LazyListState.kt:429-432）：滚动条位置 = 平均尺寸 × index + offset；
  2. `LazyListLayoutInfo.calculateContentSize`（LazyListLayoutInfo.kt:97-106）：内容总尺寸估算；
  3. `LazyLayoutScrollScope.calculateDistanceTo`（LazyListScrollScope.kt:55-67）：目标条目不可见时用 `averageSize × indexDiff` 估距 —— 即 **animateScrollToItem 的远距离弹道估算**。
- 结论：估高只用于"不可见区域的近似"，从不参与可见区像素对账；可见区一律以实测为准。

### 2.5 reverseLayout 的偏移处理

- 布局参数仅一句："reverse the direction of scrolling and layout"（LazyList.kt:70-71）。来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyList.kt
- content padding 语义随布局方向翻转：`beforeContentPadding` = "滚动方向上的首缘 padding"（"top content padding for LazyColumn with reverseLayout set to **false**"，LazyListLayoutInfo.kt:71-76）；LazyList.kt:296-299 按 `isVertical && reverseLayout` 选 bottomPadding。
- 放置时（内容不足一屏的 arrange 分支）官方注释："when reverseLayout == true, offsets are stored in the **reversed order** to items" + "inverse offset to align with scroll direction for positioning"（LazyListMeasure.kt:578-616，`mainAxisLayoutSize - absoluteOffset - item.size`）。
- 锚定语义与布局方向无关：锚永远是"布局序首缘"（start 边）。reverseLayout=true 时 start 边在**视口底部**——聊天列表（新消息 index 0 在底）贴底时，锚条目就是最新消息，其底缘（start 缘）被 index+offset 钉住。

---

## 3. LazyListState 滚动 API 契约

来源：
- LazyListState.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt
- ScrollableState.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollableState.kt
- ScrollExtensions.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollExtensions.kt
- API 参考：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState

### 3.1 scrollToItem / requestScrollToItem

- `scrollToItem(index, scrollOffset)`（LazyListState.kt:440-450）："**Instantly** brings the item at [index] to the top of the viewport"（参考页原文）。实现：`scroll { snapToItemIndexInternal(index, scrollOffset, forceRemeasure = true) }` —— 走 scroll 互斥事务 + **同步强制重测**（当帧生效）。
- `requestScrollToItem(index, scrollOffset)`（LazyListState.kt:454-476）："Requests the item at [index] to be at the start of the viewport **during the next remeasure**, offset by [scrollOffset], and **schedules a remeasure**"；关键语义："The scroll position will be updated to the requested position **rather than maintain the index based on the first visible item key** ... but *only* for the next remeasure. Any scroll in progress will be cancelled."。实现不进 scroll 块（`forceRemeasure = false`，走 `measurementScopeInvalidator.invalidateScope()`）。
- 出处：requestScrollToItem 的引入记录在 compose-foundation 1.7 release notes："Clients of LazyColumn/LazyRow may now opt-out of maintaining an index based on the key for the upcoming measure-pass by calling a non-suspend LazyListState.requestToScroll. (I98036, b/209652366)" — https://developer.android.com/jetpack/androidx/releases/compose-foundation
- `snapToItemIndexInternal`（LazyListState.kt:482-506）藏着一个对聊天场景极关键的分支（注释原文）："sometimes this method is called **not to scroll, but to stay on the same index** when the data changes ... when this happens **we don't need to reset the animations** as from the user perspective we didn't scroll anywhere and if there is an offset change for an item, this change **should be animated** [as placement]. however, when the request is to really scroll to a different position, we have to reset previously known item positions as **we don't want offset changes to be animated. this offset should be considered as a scroll, not the placement change**." —— 位置真的变了才 `itemAnimator.reset()`。

### 3.2 offset 符号（reverseLayout 重点）

- `firstVisibleItemScrollOffset`："The scroll offset of the first visible item. **Scrolling forward is positive** - i.e., the amount that the item is offset backwards"（LazyListState.kt:255-257）。
- `scrollToItem` 的 scrollOffset 参数（官方参考与 KDoc 一致）："positive offset refers to forward scroll, so in a **top-to-bottom list**, positive offset will scroll the item **further upward** (taking it partly offscreen)"（LazyListState.kt:444-447）。
- **符号约定与视觉方向解耦**：forward = 布局序前进方向。reverseLayout 下条目从底部向顶部排，forward 滚动视觉上向上；所以 reverseLayout 里 scrollToItem 的**正 offset 是把目标条目往视口底缘外压**（与 top-to-bottom 时的"向上"直觉相反——两者表述都成立，因为锚条目都是"start 缘露出 offset 像素"）。官方自己处理指示条时也对 reverseLayout 显式翻转（`_scrollIndicatorState`，LazyListState.kt:410-427），旁证"内部恒为 forward-正"。
- offset 恒非负（§2.1 的 check）。

### 3.3 dispatchRawDelta

契约原文（ScrollableState.kt:58-73）："Dispatch scroll delta in pixels **avoiding all scroll related mechanisms**. ... dispatching any delta with this method **won't trigger nested scroll, won't stop ongoing scroll/drag animation and will bypass scrolling of any priority**. This method will also ignore `reverseDirection` ... **Manually dispatching delta via this method will likely result in a bad user experience, you must prefer [scroll] method over this one.**" 它是框架内部供 nested scroll 派发用的低层通道。LazyListState 直接委托（LazyListState.kt:526）。

### 3.4 isScrollInProgress 的置位路径

- 实现即互斥锁状态：`DefaultScrollableState.isScrollInProgress = isScrollingState.value`，而 `isScrollingState.value = true` 只在 `scrollMutex.mutateWith(...) { ... }` 的 `scroll {}` 事务内置位（ScrollableState.kt:185-223）。
- 因此为 true 的路径：手势 drag、fling（经 scrollable 的 fling）、`animateScrollToItem`、`scrollToItem`、`scrollBy` 扩展、`stopScroll` —— 全部包在 `scroll {}` 里。
- **不为 true 的路径**：`dispatchRawDelta`（绕过锁，§3.3）；`requestScrollToItem`（不走 scroll 块；它反而会**取消**进行中的滚动，LazyListState.kt:470-473）。
- `scroll {}` 入口还处理首次布局前的等待：`awaitLayoutModifier.waitForFirstLayout()`（LazyListState.kt:516-524）。

### 3.5 拖拽/程序滚动的统一账本：onScroll + scrollToBeConsumed

- `scrollToBeConsumed`："The amount of scroll to be consumed in the next layout pass. **Scrolling forward is negative** - that is, it is the amount that the items are offset in y"（LazyListState.kt:297-301）。注意与 firstVisibleItemScrollOffset 的"forward 为正"**符号相反**。
- `onScroll`（LazyListState.kt:553-623）流程：不可滚方向直接吃 0（554-556 行，贴底死区的官方处理）→ 累计 `scrollToBeConsumed += distance` → 先试 `copyWithScrollDeltaWithoutRemeasure`（免重测，只触发 re-placement：`placementScopeInvalidator.invalidateScope()`）→ 失败才 `remeasurement?.forceRemeasure()` → 小数残量保留下次（612-622 行）。
- 越界回弹量 `scrollBackAmount` 在 measure 内计算（LazyListMeasure.kt:287-294）。

---

## 4. 官方对"条目尺寸变化动画"的方案：Modifier.animateItem()

### 4.1 animateItem 是什么（1.7.0）

官方 release notes（compose-foundation 1.7.0，原文）："Item appearance and disappearance animation support was added into LazyColumn and LazyRow. Previously it was possible to add Modifier.animateItemPlacement() ... We deprecated this modifier and introduced a new **non-experimental** modifier called **Modifier.animateItem()** which allows you to support **all three animation types: appearance (fade in), disappearance (fade out) and reordering**. (I2d7f7, b/150812265)"
来源：https://developer.android.com/jetpack/androidx/releases/compose-foundation ；issue 链接：https://issuetracker.google.com/issues/150812265

KDoc 契约（LazyItemScope.kt:82-106）："This modifier animates the item **appearance** (fade in), **disappearance** (fade out) and **placement changes** (such as an item reordering). You should also provide a **key** ... for this modifier to enable animations." placementSpec 的说明值得注意："Aside from item reordering **all other position changes** caused by events like arrangement or alignment changes will also be animated."
来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyItemScope.kt

**边界：三类动画里没有 size 动画**。条目自身高度变化不产生"同 key 条目 target offset 差"以外的动效——高度变化对自身位置的映射是"start 缘不动、尺寸伸缩"，不在 appearance/disappearance/placement 任何一个通道里。即 **animateItem 官方就不负责"展开/收起"的尺寸过渡本身**。

### 4.2 实现结构（对自研的参考价值）

- `Modifier.animateItem(...)` 在 item scope 里只是挂一个 **ParentDataModifierNode** 携带三个 spec（LazyItemScopeImpl.kt:72-81；LazyLayoutAnimateItemModifier.kt:47-85，`LazyLayoutAnimationSpecsNode : Modifier.Node(), ParentDataModifierNode`）。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/layout/LazyLayoutAnimateItemModifier.kt
- 真正的动效引擎在**测量期**：`LazyLayoutItemAnimator.onMeasured`（LazyLayoutItemAnimator.kt:63-354）：
  - "Should be called **after the measuring** so we can detect position changes, figuring out start/end offsets and starting the animations"（35-42 行）；
  - **滚动不动画**："the consumed scroll is considered as a delta we don't need to animate"（99-105 行，consumedScroll 直接 `applyScrollOffset`）；
  - **target 值来源**："Only setup animations when we have access to **target value** in the current pass, which means **lookahead pass**, or regular pass when not in a lookahead scope"（107-109 行）—— 官方 placement 动画依赖 lookahead/approach 双通道拿"目标位"再从旧位补间；
  - 消失项用 graphics layer 在 draw 层补画（`onDrawDisappearingItems`，538-547 行），不占布局。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/layout/LazyLayoutItemAnimator.kt
- **与滚动的互斥约定**（LazyListState.kt）：
  - `animateScrollToItem` 全程 `skipItemPlacementAnimation = true`（640-653 行）——程序性动画滚动期间禁用条目位移动画；
  - `snapToItemIndexInternal` 真滚动才 `itemAnimator.reset()`（§3.1）；
  - measure 放置打 `withMotionFrameOfReferencePlacement` 标（§2.2，"avoid a chasing effect to scrolling"）。

### 4.3 已知 bug / 争议（第一方锚点）

- 源码内 TODO 承认的缺陷（LazyLayoutItemAnimator.kt:217-222）："In some cases, keyToItemInfoMap and movingAwayKeys can get out of sync. If that's the case, we can not play an animation in any case as the item is already gone (**b/352482051**). Follow-up: **b/354695943**"。issue：https://issuetracker.google.com/issues/352482051 、https://issuetracker.google.com/issues/354695943
- layer 属性失效 workaround 的关联 bug：b/329417380（LazyLayoutItemAnimator.kt:524-527 注释）。
- 1.7 前 animateItemPlacement 的崩溃修复：b/253195989（release notes）。
- **锚定语义的官方立场**：androidx 官方仓库 PR #851 "Add preserveFirstVisibleItem parameter and lint check for LazyColumn/LazyRow"（社区提交，已关闭）尝试给"锚点跟随 key"加开关，PR 正文确认现状："when the first visible item is moved to a different position, **the scroll position forcibly follows that item**" —— 即按 key 锚定是刻意设计且目前无 opt-out。
  来源：https://github.com/androidx/androidx/pull/851
- 说明：issuetracker 本环境不可机读（SPA），未能直接枚举 "item height change jump/flicker" 的 issue 正文；上表全部编号均取自 androidx 源码注释与官方 release notes 的第一方引用，可直接点击链接由人工打开核对。

---

## 5. 官方"逐帧滚动 + 消费"账本：SnapFlingBehavior 与 animateScrollToItem

来源：
- SnapFlingBehavior.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/snapping/SnapFlingBehavior.kt
- LazyLayoutScrollScope.kt（animateScrollToItem 实现）— https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/layout/LazyLayoutScrollScope.kt
- LazyListScrollScope.kt — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollScope.kt
- ScrollExtensions.kt（animateScrollBy/scrollBy/stopScroll）— URL 见 §3

### 5.1 animateScrollToItem：分段弹道 + ItemFoundInScroll 接力

（LazyLayoutScrollScope.kt:104-298）

1. 目标不可见时按 **2500.dp 目标距 / 1500.dp 边界距 / 50.dp 最小距**（35-40 行）分段：每段 `anim.animateTo(target)`，逐帧 `scrollBy(coercedValue - prevValue)` —— **prevValue 差值账本**：每帧只把"动画值 − 已发出量"交给 ScrollScope，消费不满即碰壁。
2. 条目进入可见区时 `throw ItemFoundInScroll(targetItemOffset, anim)`（30-33 行：**用 CancellationException 携带 AnimationState**），catch 后以 `sequentialAnimation = (velocity != 0f)` **无缝续速**，精确逼近目标 offset（260-292 行），同样 coerce 防过冲（"Springs can overshoot their target, clamp to the desired range"）。
3. 过冲检测 `isOvershot()`（135-159 行）→ `snapToItem(index, offset)` 一步钉死。
4. 终点必 snap：注释原文（293-297 行）"Once we're finished the animation, **snap to the exact position to account for rounding error** (otherwise we tend to end up with the previous item scrolled the tiniest bit onscreen)"。
5. 远距离 teleport：距离仍远且 loops≥2 时 `snapToItem(index ∓ 100)` 跳过中间条目避免组合（221-235 行；`NumberOfItemsToTeleport = 100`，LazyListState.kt:821）。

### 5.2 SnapFlingBehavior：approach + settle 两段账本

- `performFling` → `fling`（96-163 行）：`calculateApproachOffset` 决定 approach 目标；`remainingScrollOffset = abs(initialOffset) * sign(initialVelocity)` 为剩余量账本，每步 `remainingScrollOffset -= consumed` 并上报 `onRemainingDistanceUpdated`。
- approach 选 decay（快）或 spring（慢）：`isDecayApproachPossible` = decay 算出的位移够不够到 approach 目标（197-204 行）。
- `animateDecay` / `animateWithTarget`（288-378 行）共同模式：
  - `previousValue` / `consumedUpToNow` 累加账本，`delta = coerce(value) - 账本值`；
  - `consumeDelta`：`consumed = scrollBy(delta)`，`if (abs(delta - consumed) > 0.5f) cancelAnimation()` —— **半像素阈值判碰壁**；
  - `coerceToTarget`（380-383 行）把每帧值钳到目标一侧防过冲；
  - 结束时 "Always course correct velocity"（373 行）把速度钳回初速方向，防止残速放大。
- settle 段换 `snapAnimationSpec`（默认 spring StiffnessMediumLow，rememberSnapFlingBehavior:228-241）。

### 5.3 animateScrollBy / scrollBy / stopScroll

ScrollExtensions.kt:35-114：`animateScrollBy` = `scroll { animate(0f, value) { ... previousValue += scrollBy(currentValue - previousValue) } }`；`scrollBy` = `scroll { scrollBy(value) }`；`stopScroll` = 空操作占锁。三行就是"事务 + 账本"的最小官方形。

---

## 6. 对本项目的启示（ChatScreen reverseLayout 视口稳定）

### 6.1 可以直接借力的官方机制

1. **LazyListState 自带 key 锚定**（`updateScrollPositionIfTheFirstItemWasMoved`，§2.1）：聊天列表 `items(..., key = { it.id })` 是必须项——锚点之前（reverseLayout 下 = 视口底缘之下）的插入/删除由官方 key 映射吸收，视口不动。**不要自研锚定**；这也解释了无 key 时任何"下方"增删必跳。
2. **reverseLayout 的锚 = 视口底缘**：贴底时锚条目就是最新消息，其 start（底）缘被 index+offset 钉死；条目高度变化时官方策略是"锚不动、尺寸变化折算成已消费滚动"（§2.2 注释）。展开/收起发生在锚条目**之上**（视觉上方 = 布局序更高 index）时，锚底缘天然稳定——这正是聊天视口稳定要的锚。
3. **animateItem 用于增删/重排**（新消息入场 fade、撤回消息 fade）：与滚动的互斥官方已处理好（consumed scroll 不动画、motion-frame-of-reference 打标，§4.2）。
4. **animateItem 不管 size**：条目自身展开/收起的尺寸过渡官方没有方案，仍需自研——但可对照 LazyLayoutItemAnimator 的骨架：**measure 期检测 target 差、lookahead 拿目标位、draw 层补画消失项、consumed scroll 排除在动画外**。
5. **requestScrollToItem vs scrollToItem 的选型**（§3.1）：需要"保组合、下一帧重测"的轻量跳位（如新消息到达时的贴底）用 `requestScrollToItem`；需要当帧同步钉死的（如会话切换恢复位置）用 `scrollToItem`。且 `snapToItemIndexInternal` 的"位置没变不 reset 动画"分支提示：贴底 no-op 调用不会误杀进行中的动画。
6. **自研协调器必须走 `scroll {}` 事务**：白拿互斥（MutatorMutex）、nested scroll 一致性、`isScrollInProgress` 置位、以及 `awaitFirstLayout`。直接 `dispatchRawDelta` 会绕过以上全部（官方文档明言 "likely result in a bad user experience"）。
7. **官方同步路径的形态**：布局后当帧改滚动位 ≠ 状态回写，而是 scroll 事务内 `remeasurement?.forceRemeasure()` / `copyWithScrollDeltaWithoutRemeasure`（§1.4、§3.5）。我们"pre-render 协调器"若要在同一帧内完成"测量已知 → 修正滚动位"，应对齐这个形态，而不是 `onGloballyPositioned` 里写 state（那必然一帧滞后，见 §1.2）。

### 6.2 我们自研时踩过/会踩的语义坑（官方原文佐证）

1. **reverseLayout 的 offset 符号**：内部恒为 "forward 为正"（`firstVisibleItemScrollOffset` ≥ 0），视觉方向随 reverseLayout 翻转；`scrollToItem` 的正 offset 在 reverseLayout 下把条目往**底缘外**压（§3.2）。任何把"视觉向下 = 正"写进自研账本的假设都会上下抖。
2. **两套相反符号共存**：`scrollToBeConsumed` 是 "forward 为负"（LazyListState.kt:297-301），而 `firstVisibleItemScrollOffset` 是 "forward 为正"——对账时勿混用。
3. **估高只可估不可账**：`visibleItemsAverageSize()` 仅来自当前可见项（§2.4），远距 animateScrollToItem 用它算弹道，终点靠 `snapToItem` 修正舍入误差（§5.1 第 4 条）。自研"按估高长程滚动"必须配终点校 snap，否则必有漂移。
4. **半像素阈值与贴底死区**：官方以 `abs(delta - consumed) > 0.5f` 判定"没吃满 = 碰壁/不可滚"；`onScroll` 对不可滚方向直接返回 0 消费（554-556 行）——我们的账本需要同样的容差与死区处理，否则贴底时容易死循环或提前取消。
5. **反写一帧滞后**：`layout{}`/布局回调里写状态驱动滚动补偿 = 官方文档点名的反模式（§1.2），这从第一方证实了我们"渲染前协调"动机的正确性；正解是事务内同步测量或 placement 快速路径。
6. **程序性滚动期间禁用 item 动画**（`skipItemPlacementAnimation`，§4.2）：我们做"新消息到位动画 + 流式高度补偿"并存时，同样要区分"滚动位移"与"布局位移"两个通道，混流会出现官方注释里说的 **chasing effect**（§2.2）。

### 6.3 遗留未决（本调研覆盖不了的）

- issuetracker 上针对 "reverseLayout + 条目高度动画跳变" 的专项 issue 无法机读确认编号（§4.3 说明）；建议人工在 https://issuetracker.google.com/issues 搜 "LazyColumn animateItem jump" 复核。
- lookahead/approach 双通道（`hasLookaheadOccurred` / `approachLayoutInfo`，LazyListState.kt:221-225、665-676）与 `LazyLayoutScrollDeltaBetweenPasses` 的完整交互未逐行展开；若自研协调器要做 lookahead 预测布局，需另立一篇精读。
