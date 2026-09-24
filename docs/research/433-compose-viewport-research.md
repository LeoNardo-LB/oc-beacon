# #433 调研：Jetpack Compose 视口控制底层语义

> 调研日期：2026-02。方法：直接阅读 androidx 源码（androidx-main 分支，GitHub 镜像 androidx/androidx 与 android.googlesource.com 同源）、AOSP 源码、developer.android.com 官方文档与官方 compose-samples。所有论断附一手来源；未能从一手来源确证之处明确标注。博客未用作论断依据。

## 1. ScrollableState.dispatchRawDelta(x) 的确切语义

定义位置：androidx.compose.foundation.gestures.ScrollableState（注意：该接口现位于 gestures 包，非旧文献中的 androidx.compose.foundation 根包）。
源码：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollableState.kt

KDoc 原文要点（逐句自源码）：

- "Dispatch scroll delta in pixels avoiding all scroll related mechanisms." —— 绕过一切滚动相关机制。
- "unlike [scroll], dispatching any delta with this method won't trigger nested scroll, won't stop ongoing scroll/drag animation and will bypass scrolling of any priority. This method will also ignore reverseDirection and other parameters set in scrollable."
  - 不触发 nested scroll（既不做 pre-scroll 上抛，也不做 post-scroll 剩余量分发）；
  - 不停止进行中的滚动/拖拽动画；
  - 无视 MutatePriority（绕过互斥锁）；
  - 忽略 reverseDirection 等修饰符参数（delta 是"物理像素直发"）。
- "This method is used internally for nested scrolling dispatch and other low level operations... Manually dispatching delta via this method will likely result in a bad user experience, you must prefer [scroll] method over this one." —— 官方明确：它就是 nested scroll 派发链中"父容器被询问时"的消费通道，手动调用属于低层操作。
- 返回值 = 实际消费的像素量。

实现层（LazyListState）：

- LazyListState.dispatchRawDelta(delta) = scrollableState.dispatchRawDelta(delta)；内部 scrollableState = ScrollableState { -onScroll(-it) }，即直接进入 LazyListState.onScroll。
- onScroll（LazyListState.kt L553-623）：把 delta 累加进 scrollToBeConsumed，然后要么用 copyWithScrollDeltaWithoutRemeasure 只做 re-placement（可见 item 集不变时，零测量），要么调用 remeasurement?.forceRemeasure() 同步执行一次 measure。
  源码：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt
  → 结论：dispatchRawDelta 在 LazyColumn 上是"同步生效"的（当次调用内完成滚动与（必要时）测量），不像挂起版那样排队。

与挂起版本的区别：

| | dispatchRawDelta(x) | scrollBy(x)（挂起扩展） | scroll { }（挂起） |
|---|---|---|---|
| nested scroll | 绕过 | delta 经 ScrollScope.scrollBy，由调用者决定（gesture 路径会走 nested scroll） | 同左，由块内逻辑决定 |
| 互斥（MutatorMutex） | 绕过（"bypass scrolling of any priority"） | 参与：进队并取消进行中滚动 | 参与：可带优先级，取消/被取消 |
| 对进行中滚动 | 不停止、并行叠加 | "Cancels the currently running scroll, if any, and suspends until the cancellation is complete"（ScrollExtensions.kt KDoc） | 同左 |
| 生效时机 | LazyList 上同步（内部 forceRemeasure） | 块内每次 scrollBy 同步消费 | 块内同步消费 |

- scrollBy KDoc："Jump instantly by [value] pixels. Cancels the currently running scroll, if any..." 源码：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollExtensions.kt
- DefaultScrollableState 实现：scroll 走 scrollMutex.mutateWith；dispatchRawDelta 直接 onDelta(delta)，完全不经锁（ScrollableState.kt L185-230）。

## 2. LazyListState.requestScrollToItem(index, offset) 语义

源码：LazyListState.kt L454-506（同上 URL）。KDoc 逐句：

- "Requests the item at [index] to be at the start of the viewport during the next remeasure, offset by [scrollOffset], and schedules a remeasure." —— 不立即生效，在下一次 measure pass 应用。
- "The scroll position will be updated to the requested position rather than maintain the index based on the first visible item key ... but only for the next remeasure." —— 单次性请求，不会持久覆盖基于 key 的位置保持。
- "Any scroll in progress will be cancelled." —— 实现：if (isScrollInProgress) { scope.launch { scroll {} } }（L470-473），用空 scroll 块经互斥锁把进行中的手势/动画/fling 踢掉。
- requestScrollToItem 调 snapToItemIndexInternal(index, offset, forceRemeasure = false)：
  - positionChanged 时 itemAnimator.reset() —— 不触发 item 位移动画（视作滚动而非 placement 变化）；
  - scrollPosition.requestPositionAndForgetLastKnownKey(index, offset) —— 丢弃 first-visible-item key，下一个 measure 不做 key 锚定回移；
  - forceRemeasure=false → measurementScopeInvalidator.invalidateScope()：只调度 remeasure，不阻塞当前线程。

与挂起版 scrollToItem 的区别：scrollToItem = scroll { snapToItemIndexInternal(index, offset, forceRemeasure = true) }（L448-450）——
1. 挂起版参与 MutatorMutex（取消进行中滚动并等待取消完成），非挂起版用独立协程取消、立即返回；
2. 挂起版 forceRemeasure=true：同步完成 measure 后才恢复；非挂起版只调度，measure 发生在本帧稍后的 layout pass；
3. 挂起版需要协程作用域；非挂起版可在重组、非挂起回调（如 onGloballyPositioned、nested scroll 回调）里直接调用——这是它被引入的动机场景。
参考（API 文档）：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState#requestScrollToItem(kotlin.Int,kotlin.Int)

位置请求的"尽力而为"语义（LazyListScrollPosition.kt KDoc，逐句）："there is no guarantee that exactly this index and offset will be applied as it is possible that: a) there will be no item at this index ... b) item at this index will be smaller than the asked scrollOffset ... c) there will be not enough items to fill the viewport ... we would have to compose few elements before the asked index."
源码：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollPosition.kt

## 3. LazyColumn 滚动锚定机制与 reverseLayout 下 item 0 增高的行为

锚定模型：滚动位置 = (firstVisibleItemIndex, firstVisibleItemScrollOffset)，offset = 第一个可见 item 被切出视口起点侧的像素数（非负，见 updateScrollOffset 的 check）。每次 measure 后 applyMeasureResult 回写（updateFromMeasureResult / 可见集未变时 updateScrollOffset）。源码：LazyListState.kt L655-709、LazyListScrollPosition.kt。

measure 内的锚定算法（LazyListMeasure.kt L140-300，https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListMeasure.kt）：

- measure 从保存的 firstVisibleItemIndex + scrollOffset 出发：currentFirstItemScrollOffset = firstVisibleItemScrollOffset - scrollDelta；第一个可见 item 被摆放在 -currentFirstItemScrollOffset（L207、L300 visibleItemsScrollOffset = -currentFirstItemScrollOffset）。
- 即：锚定的是"第一个可见 item 的起始边 + 切除量"，后续 item 依次排在其后。
- item 尺寸变化由 measure 吸收：L273-276 注释 "scrollDelta can be smaller than scrollToBeConsumed if there were not enough items ... or it can be larger if items were resized" —— 高度变化不会产生额外 delta，只是改变后续 item 的绝对位置。

reverseLayout=true 且 firstVisibleItemIndex==0 时 item 0 增高：

- reverseLayout 下 item 0 是"布局方向起点"（屏幕底部）的 item，其 offset 表示从底边被切出的像素量。measure 保持该 offset 不变、把 item 0 按 -offset 贴在视口底侧（placement 反转见 L602-610：mainAxisLayoutSize - absoluteOffset - item.size）。
- 因此 item 0 增高时：item 0 的底边被钉在视口底部不动，新增高度向视口上方扩展，把更早的消息（index 更大者）往上推。这正是聊天"钉底"想要的行为：视口内容向上扩展，底边不动，不会出现内容被顶出屏幕或视口跳走。
- 对照：非 reverse 布局中第一个可见 item 的顶边被钉住，item 增高向下扩展、底部内容下移出屏——即"被推挤"。
- 前提条件：firstVisibleItemIndex 仍为 0 且其 offset 锚定不变。若此时有 pending 的 scrollToBeConsumed 或用户 fling 进行中，delta 会先于锚定被消费（L150-160）。
- 另注意：若给 items 提供了 key，数据在当前 first-visible item 之前插入/删除时，updateScrollPositionIfTheFirstItemWasMoved 会按 key 把锚点迁到同一 item（LazyListScrollPosition.kt）；requestScrollToItem 会清除该 key 一次。

关键推论：在 reverseLayout=true + 保持 firstVisibleItemIndex==0（offset==0 或固定）的条件下，流式增高本身不需要额外偏移补偿——LazyColumn 的 measure 锚定已保证底边钉住。工程上需要处理的是"新 item 追加（数据在 index 0 之前插入）"与"用户已向上滚动后"两种状态，见 §5。

## 4. 一帧内的时序

Choreographer 帧内三阶段：Composition（重组）→ Layout（measure/place）→ Drawing。官方文档：https://developer.android.com/develop/ui/compose/phases 。各回调在该管线中的位置（均有 KDoc 佐证）：

| 回调 | 触发时机 | 一手依据 |
|---|---|---|
| snapshot apply / snapshotFlow | state 写入进入全局 snapshot 后 apply 时，apply observer 发射（conflated）；在下一帧的 composition 之前被收集消费 | snapshotFlow KDoc："if a new Snapshot is applied that changes state accessed by [block], the flow will run [block] again"（https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/SnapshotFlow.kt） |
| composition（重组） | 帧首 | phases 文档（同上） |
| measure/layout pass | 帧中；Modifier.layout 在此执行 | phases 文档 |
| Modifier.onGloballyPositioned | "after a composition when the coordinates are finalized"——即 layout 完成后、draw 前 | OnGloballyPositionedModifier.kt KDoc：https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/OnGloballyPositionedModifier.kt |
| ViewTreeObserver.OnPreDraw | View 体系："when the view tree is about to be drawn. At this point, all views in the tree have been measured and given a frame. Clients can use this to adjust their scroll bounds or even to request a new layout before drawing occurs." | AOSP ViewTreeObserver.java：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewTreeObserver.java |
| draw | 帧尾 | phases 文档 |

注意：Compose 不走 View 的 measure/onDraw，纯 Compose 界面里 OnPreDraw 只有 AndroidComposeView 一个宿主实例，不是逐节点的钩子；在 Compose 内做"draw 前修正"，官方语义可用的锚点是 onGloballyPositioned（layout 后 draw 前）。

「measure 后发现高度变化 → draw 前修正滚动偏移」的官方可行模式（按优先级）：

1. 不要在 measure 之后修，让修正发生在 measure 内：LazyColumn 自身就是把高度变化的吸收做在 measure 锚定里（§3）。用户侧等价物：在 Modifier.layout 里读被测尺寸并调整状态/offset，使修正与同一次 layout pass 融合（这正是 oc-beacon 现行 layout{} 高度补偿的机制依据）。
2. 同帧修正滚动位置：在重组阶段（状态变化引发 recomposition 的同一帧）调用 requestScrollToItem——它"调度一次 remeasure"，该 remeasure 在本帧 layout pass 执行，draw 前生效，不产生额外帧、不触发动画（§2）。
3. onGloballyPositioned 里调用 dispatchRawDelta/挂起 scrollBy 技术可行（layout 后、draw 前回调），但：onGloballyPositioned 内改滚动状态会触发新一轮 measure/draw，同帧内补一帧或跨帧取决于触发点；且 KDoc 未承诺回调内修改布局是安全的——官方文档不将其列为推荐修正通道，仅作观测用。
4. snapshotFlow { ... }.collect { }（默认主线程 immediate 收集）在 snapshot apply 后触发，落在下一帧 composition 前，适合"检测状态→发起滚动请求"，同样能在同帧 layout 生效。

（未找到 developer.android.com 上专门针对"measure 后修正偏移"的官方模式文档；以上 1/2 的依据是源码 KDoc，3/4 的"不推荐/时机"为源码语义推演，已标注。）

## 5. 官方对「聊天列表持续增长且钉底」的推荐实现

官方样本 Jetchat（android/compose-samples）：
源码：https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt

- L340-346：LazyColumn(reverseLayout = true, state = scrollState, ...) —— reverseLayout=true 是官方聊天列表的选型：数据 index 0 = 最新消息在底部，天然钉底。
- L236-240：发送消息后 scope.launch { scrollState.scrollToItem(0) } —— 用挂起版 scrollToItem(0) 归位到底部。
- L386-391：derivedStateOf { scrollState.firstVisibleItemIndex != 0 || scrollState.firstVisibleItemScrollOffset > jumpThreshold } —— "是否在底部"的官方判定 = firstVisibleItemIndex==0 && offset 小于阈值，用 derivedStateOf 降低重组压力。
- L393-400：离底时显示 JumpToBottom 按钮，点击 animateScrollToItem(0)。

综合官方组件语义的推荐拼图（每条对应上文证据）：

1. LazyColumn(reverseLayout=true)：流式内容增高由 measure 锚定自动保持底边（§3）。
2. 新消息追加（在 index 0 前插入数据）且用户本来在底：若 key 稳定，锚定按 key 保持 item 0；若要强制回底，用 requestScrollToItem(0, 0) 在重组阶段无动画、同帧生效（§2）；发送用户自己的消息这种确定性归位，Jetchat 用 scrollToItem(0)。
3. "在底部"判定用 firstVisibleItemIndex==0 && firstVisibleItemScrollOffset < ε + derivedStateOf（Jetchat L386-391）。
4. 高度补偿逻辑放在 measure 阶段（Modifier.layout）或重组阶段调用 requestScrollToItem，避免 onGloballyPositioned/draw 前的二次布局（§4）。
5. 偏移微调用 dispatchRawDelta 时须自行保证不与进行中滚动/嵌套滚动冲突（它绕过一切机制，§1）；常规程序化滚动优先挂起 scrollBy/scroll{}（互斥安全）。

## 附：来源清单

- androidx ScrollableState.kt（gestures 包）：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollableState.kt
- androidx ScrollExtensions.kt（scrollBy/animateScrollBy）：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollExtensions.kt
- androidx LazyListState.kt：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt
- androidx LazyListScrollPosition.kt：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollPosition.kt
- androidx LazyListMeasure.kt：https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListMeasure.kt
- androidx OnGloballyPositionedModifier.kt：https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/OnGloballyPositionedModifier.kt
- androidx SnapshotFlow.kt：https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/SnapshotFlow.kt
- AOSP ViewTreeObserver.java：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewTreeObserver.java
- Compose phases 官方文档：https://developer.android.com/develop/ui/compose/phases
- LazyListState API 参考：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
- Jetchat Conversation.kt：https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt

未能一手确证/留空项：

- requestScrollToItem 引入的精确版本号与 issuetracker 动机单号：release notes 页抓取被截断、googlesource 历史需登录，未取到；如需请人工在 https://issuetracker.google.com 搜 "requestScrollToItem" 与 Compose Foundation 1.8.x release notes 确认。
- issuetracker 具体条目未引用（页面为 SPA，抓取受限）；本文所有行为论断均以源码 KDoc/实现为据。
