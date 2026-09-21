# 调研：Jetpack Compose 官方串行化/事务原语 —— 底层视口/高度变更的「单写者·帧事务」可借力机制

- 主题：MutatorMutex 优先级取消语义 / 快照系统 commit-abort 事务边界 / Choreographer-Compose 帧内确定顺序与 flush 点选型 / LazyList 单写者先例 / 跨域（WAL、ECS）启发
- 日期：2026-09-21
- 调研方法：本会话 web_search 端点余额不足（HTTP 402，与 01 号文件相同）；android.googlesource.com 本机网络不可达（连接超时）；developer.android.com 不可达。故全部源码证据改为抓取一手镜像：
  - androidx 官方 GitHub 镜像 androidx/androidx 分支 androidx-main（raw.githubusercontent.com，内容与 googlesource androidx-main 逐字节同源，抓取日期即本文日期）；
  - AOSP 官方 GitHub 镜像 aosp-mirror/platform_frameworks_base（Choreographer / ViewRootImpl / ViewTreeObserver 的 Javadoc 与实现即 API 文档第一方来源）；
  - api.github.com（目录树/commit 元数据）与 dl.google.com/android/maven2（官方 maven-metadata，版本锚点）。
- 版本锚点：androidx-main 当前对应 1.13.0-alpha 期（foundation/runtime/animation-core 最新 1.13.0-alpha03，稳定线 1.12.1；maven-metadata lastUpdated 2026-09-09，见文末来源 [V1-V3]）。
- 局限声明：① issuetracker/发行注记站本环境不可达，API「首次发布版本」除文中明确给出 commit 日期者外不逐版考证，接入时须以项目 BOM 实际版本验证 API 存在性；② AOSP 行号取 main 分支 2026-09 抓取值，随版本漂移；③ 用户提到的 SnapFlingBus 经源码树探测不存在（§4.5）。

以下源码引用格式：文件:行号（androidx-main / AOSP main，本文抓取日版本）。

---

## 0. 结论速览（对应四项重点产出）

1. 可直接借力的官方原语：MutatorMutex（androidx.compose.foundation 根包，public，含优先级枚举 MutatePriority 与同步快路径 tryMutate）、Snapshot.withMutableSnapshot / takeMutableSnapshot / apply / dispose（runtime，commit/abort 全套）、SnapshotApplyResult/SnapshotApplyConflictException（冲突一等化）、withFrameNanos（runtime）、ViewTreeObserver.OnPreDrawListener（平台层，官方 KDoc 明言用途即 "adjust scroll bounds before drawing"）。详见 §6 表。
2. 帧事务推荐实现：意图层用 MutatorMutex 串行化并按优先级取消；每帧 withFrameNanos（ANIMATION 相位）汇合意图；全部视口/高度状态写包进 withMutableSnapshot——成功即原子 commit，块内异常自动 abort（快照 dispose），冲突返回 SnapshotApplyConflictException 即「本帧事务失败、意图回队」。一帧 = 一次 apply。见 §2.5。
3. flush 点选型：布局后绘制前的全局单点首选 ViewTreeObserver.OnPreDrawListener（经 LocalView 注册；ViewRootImpl 中位于 performLayout 之后、performDraw 之前，且官方支持返回 false 取消当帧重排）；框架内逐节点钩子用 Modifier.onGloballyPositioned（onLayout 内分发、早于 draw）。withFrameNanos 在 ANIMATION 相位，位于布局之前，只能做帧首汇合点、不能做布局后 flush 点。见 §3。
4. 官方「单写者」的强制执行方式：挂起路径 = 唯一写入口过 MutatorMutex（优先级取消，不排队）；非挂起路径 = 主线程同步执行 + 同步 remeasure（线程封闭、天然不可交错）；写入聚合到单一 apply 点（applyMeasureResult / 私有 update）。Animatable 证明同一原语可实现「单消费者动画」。见 §4。

---

## 1. MutatorMutex / MutatePriority：意图层「优先级取消」的官方同构物

来源（androidx-main）：
- https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/MutatorMutex.kt （镜像抓取：raw.githubusercontent.com/androidx/androidx/androidx-main/ 同路径）

### 1.1 位置与公开性（重要：已从 gestures 包迁至根包，且为 public）

- 当前声明 package androidx.compose.foundation（MutatorMutex.kt:17），public class MutatorMutex（:79-80，@Stable）；优先级枚举 public enum class MutatePriority（:34-54）。
- 迁移史：旧路径 .../foundation/gestures/MutatorMutex.kt 在 androidx-main 已 404（本文以 raw 探测验证）；GitHub 树页（有缓存陈旧）仍列出旧位置，raw 为权威。根包路径下该文件至少自 2024-06 起存在于当前形态（GitHub commits API：最早可见 commit 2024-06-07，文件版权头 2020）。ScrollableState.kt:20 的 import androidx.compose.foundation.MutatorMutex 与 Animatable.kt 的同包副本（§4.4）互相印证。
- animation-core 持有逐字拷贝：InternalMutatorMutex.kt:27 原话 **"This is an internal copy of androidx.compose.foundation.MutatorMutex. Do not modify."**（internal enum class MutatePriority :35、internal class MutatorMutex :79）——官方自己也以「复制同一原语」的方式跨模块复用，说明该模式即官方标准答案。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/animation/animation-core/src/commonMain/kotlin/androidx/compose/animation/core/InternalMutatorMutex.kt

### 1.2 取消语义精读：不排队，新 mutation 取消旧的

类 KDoc（MutatorMutex.kt:67-71，原文）：

> "A [MutatorMutex] enforces that **only a single writer can be active at a time** for a particular state resource. **Instead of queueing callers** that would acquire the lock like a traditional Mutex, new attempts to [mutate] the guarded state will **either cancel the current mutator** or if the current mutator has a higher priority, **the new caller will throw CancellationException**."

实现为两段式（这是理解其正确性的关键）：

1. 意图层（所有权）：currentMutator: AtomicReference<Mutator?>（:87）+ CAS 循环 tryMutateOrCancel（:90-100）：
   - Mutator.canInterrupt(other) = priority >= other.priority（:82）——平级也可打断；
   - 可打断 → CAS 换主 → oldMutator.cancel()（:93-96）；
   - 不可打断（对方优先级更高）→ throw CancellationException("Current mutation had a higher priority")（:98）——新调用者自己被取消。
2. 串行化硬底座：mutate 在赢得意图层后仍要 mutex.withLock { block() }（:125-131，kotlinx Mutex）——因为旧持有者的取消是协作式的，新写者的 block 必须等旧 block 真正退出才开始，保证无交叠。退出时 currentMutator.compareAndSet(mutator, null)（:129）。

mutate KDoc 把契约写成文字（:105-110）："If the new caller has a priority equal to or higher than the call in progress, the call in progress will be cancelled… If the call in progress had a higher priority than the new caller, the new caller will throw CancellationException without invoking block."

细节：取消用专用 MutationInterruptedException（:61-62，继承 PlatformOptimizedCancellationException）跳过栈采集——官方连「高频取消」的代价都优化过，侧面证明该原语就是为高频打断设计的。

### 1.3 MutatePriority：三档优先级与官方使用链

枚举（MutatorMutex.kt:34-54）：Default（程序化动画/变更，"should not interrupt user input"）< UserInput（直接用户交互）< PreventUserInput（用户输入不可打断的操作）。KDoc（:30-32）："A mutation of equal or greater priority will interrupt the current mutation in progress."

官方各调用方的取值（即「谁赢谁输」的官方答案）：

| 调用方 | 优先级 | 源码 |
|---|---|---|
| scrollable 触摸拖拽 | UserInput | Scrollable.kt:345 scroll(scrollPriority = MutatePriority.UserInput) |
| scrollable 滚轮/按键/无障碍 | UserInput | Scrollable.kt:468 |
| scrollable fling（onScrollStopped → doFlingAnimation） | Default | Scrollable.kt:728 scroll(scrollPriority = MutatePriority.Default) |
| draggable 手势 drag | UserInput | Draggable.kt:334 |
| LazyListState.scrollToItem / animateScrollToItem | Default | LazyListState.kt:449（经 scroll {} 默认参数） |
| Animatable.animateTo/snapTo/stop | 未传 = Default（animation-core 副本同构） | Animatable.kt:299/380/403 |

来源（上表）：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/Scrollable.kt 、…/gestures/Draggable.kt

即官方互斥链的直接答案：手势拖拽（UserInput）打断进行中的 fling（Default）；新的程序化滚动（Default）也打断旧 fling；进行中的手势（UserInput）不被程序化滚动（Default）打断。fling 本身也过 scroll{} 互斥（Scrollable.kt:724-728），并非独立通道。

### 1.4 tryMutate：同步快路径（对「底层串行化」最关键的新原语）

public inline fun tryMutate(block: () -> Unit): Boolean（MutatorMutex.kt:187-197）：无活动写者则同步持锁执行并返回 true；有则立即返回 false（不排队、不打断，提示应由更高优先级 mutate 先行）。背板是 mutex.tryLock()（:199）。这为 PreRenderCoordinator 提供了官方形态的「同步快速通道」：主线程同步路径直接 tryMutate，失败再走挂起 mutate/取消流程。
（局限：tryMutate 的首次发布版本未逐版考证，androidx-main 现存；接入时验证本地 foundation 版本。）

### 1.5 挂起/直发两条通道的官方契约（ScrollableState/DraggableState）

ScrollableState 接口 KDoc（ScrollableState.kt:49-51，原文）：

> "All actions that change the logical scroll position **must be performed within a [scroll] block** … in order to guarantee that **mutual exclusion is enforced**. If [scroll] is called from elsewhere with the [scrollPriority] higher or equal to ongoing scroll, ongoing scroll will be canceled."

DefaultScrollableState（同文件:198-216）：private val scrollMutex = MutatorMutex()；scroll = scrollMutex.mutateWith(scrollScope, scrollPriority)。

dispatchRawDelta KDoc（:55-61）明确其旁路语义："unlike [scroll], dispatching any delta with this method **won't trigger nested scroll, won't stop ongoing scroll/drag animation and will bypass scrolling of any priority**"——它是主线程同步直调（DefaultScrollableState:218-220 直接 onDelta(delta)），不经过 mutex。即官方把「并发互斥」交给挂起通道，把「即时直调」留给主线程同步通道，两者以线程模型隔离（见 §4.2）。DraggableState.drag/dispatchRawDelta 契约逐字同构（Draggable.kt:98-127，DefaultDraggableState:1320-1341）。

来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollableState.kt

---

## 2. 快照系统的事务语义：commit / abort 的官方边界

来源（androidx-main）：
- https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/snapshots/Snapshot.kt （2644 行，本文抽取全部关键 KDoc 与实现）

### 2.1 原子性边界：all-or-nothing 是 KDoc 契约

Snapshot.takeMutableSnapshot KDoc（Snapshot.kt:344-347，原文）：

> "The global state will either see **all the changes made as one atomic change**, when [MutableSnapshot.apply] is called, or **none of the changes** if the mutable state object is disposed before being applied."

MutableSnapshot.apply KDoc（:815-817）："Once this method returns all changes made to this snapshot are **atomically visible** as the global state of the state object or to the parent snapshot."——写入隔离在快照内，apply（commit）或 dispose（abort）二选一，没有第三种终态。

### 2.2 withMutableSnapshot：官方打包好的帧事务原语

KDoc（Snapshot.kt:444-453，要点原文）：

> "Take a [MutableSnapshot] and run [block] within it. When [block] returns successfully, attempt to [MutableSnapshot.apply] the snapshot. Returns the result of [block] or **throws [SnapshotApplyConflictException] if snapshot changes attempted by [block] could not be applied**." … "When [withMutableSnapshot] returns successfully those changes will be made visible to other threads and any snapshot observers (e.g. snapshotFlow) will be notified." … "**[block] must not suspend**."

实现（:452-472）逐行印证三态语义：takeMutableSnapshot().run { try { enter(block) } catch { hasError=true; throw } finally { if (!hasError) apply().check(); dispose() } }——块内抛异常 → 快照直接 dispose（= abort，改动无痕丢弃）；成功 → apply + check（冲突即抛）→ dispose。

显式事务形态（多阶段）：takeMutableSnapshot → 任意长度的写阶段 → apply() 返回 SnapshotApplyResult，Success/Failure（:1245-1290；Failure.check() = dispose + 抛 SnapshotApplyConflictException，:1272-1277；异常类 :1301-1304）；dispose KDoc（:108-113）警示不入 apply 也不 dispose 会内存泄漏。嵌套快照："For a change to be visible globally, **all the parent snapshots need to be applied** until the root snapshot is applied to the global state"（:773-779）——可做「帧内多阶段、根 apply 才全局可见」的事务分层。

### 2.3 apply 顺序与写冲突检测：对「多个协程各自写、apply 有无顺序保证」的直接回答

- apply 的全局替换在 sync {}（内部锁）中完成（:849-885）——apply 动作本身互斥、成功即原子可见；applyObservers 依次同步回调（:882-902）。
- 但实现者注释原文（:830-832）：**"NOTE: this algorithm currently does not guarantee serializable snapshots as it doesn't prevent crossing writes"**（并引用 arxiv.org/pdf/1412.2324.pdf）——并发多个快照各自 apply 时，官方用 optimistic merge（:836-843 optimisticMerges）尽力合并，检测到冲突写（"A write is considered colliding if any write occurred in a state object in a snapshot applied since the snapshot was taken"，:833-835）则 innerApplyLocked 返回 Failure（:860-863）。
- 结论：快照系统只给 all-or-nothing 边界 + 冲突检测，不给跨快照的顺序保证。多个协程各自 withMutableSnapshot 写同一批视口状态时，谁 apply 成功取决于冲突结局而非提交意图顺序。这正是「单写者必须由上层协议强制」的形式化理由：同一时刻只允许一个 open 的 mutable snapshot 触及视口/高度状态——由 §1 的 MutatorMutex 承担。

### 2.4 观测面：applyObserver / globalWriteObserver / GlobalSnapshotManager

- registerApplyObserver（:640-647）：注册即 advanceGlobalSnapshot（"Ensure observer does not see changes before this call"）；每次 apply 成功后同步回调 (Set<Any> changed, Snapshot) -> Unit。
- registerGlobalWriteObserver（:649-673）KDoc 原话："Composition uses this to **schedule a new composition** whenever a state object that was read in composition is modified"——即「写 → 下一帧重组」调度的来源。
- sendApplyNotifications（:699-704）："Apply notifications for state objects modified outside snapshot are **deferred** until method is called… implicitly called whenever a non-nested MutableSnapshot is applied."
- Android 上的通知管线 GlobalSnapshotManager（全文 40 行）："periodic dispatch of snapshot apply notifications"；实现为 Channel<Unit>(1) 合流（sent AtomicBoolean + trySend，多次写只投递一次）+ 在 AndroidUiDispatcher.Main 上调 Snapshot.sendApplyNotifications()。
  来源：https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/GlobalSnapshotManager.android.kt
- 设计含义：apply/写通知是异步合流的调度信号，不可当帧内精确 flush 点用；精确同步点只能由自己掌握（§3）。

### 2.5 帧事务在快照系统上的推荐实现（机制组合，不含实现代码）

- 事务 = 一帧一次 apply：帧内所有视口/高度状态写集中在同一个 withMutableSnapshot（或 takeMutableSnapshot → 写 → apply）。commit = apply 成功；abort = 块内校验失败抛异常（自动 dispose，无痕）或 apply 返回 Failure（dispose + SnapshotApplyConflictException，本帧事务作废、意图退回队列）。
- 谁有权开事务：MutatorMutex（§1）。持有者 = 本帧唯一写者；新意图按 MutatePriority 打断在途事务（被取消者的快照随协程取消路径 dispose/丢弃）。同步快路径用 tryMutate。
- 事务必须在 ANIMATION 相位内同步完成（block 不得挂起，官方 KDoc 明言）：在 withFrameNanos 恢复点之后、measure 之前 apply，本帧的 measure/layout 读到的即是事务后的完整状态（帧相位依据见 §3）。
- 不要在 layout/draw 相位反写普通快照状态来「当帧生效」——官方 phases 铁律（01 号调研 §1.2，OnRemeasuredModifier.kt:28-44 "one frame lag"）。
- 冲突重试策略上，官方 Failure 不带自动重试语义——重试属上层协议（PreRenderCoordinator 的队列）职责。

---

## 3. 一帧内的确定顺序与 flush 点选型

### 3.1 Choreographer 相位序（AOSP 一手源码）

来源：https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/Choreographer.java

- 相位数组即顺序（Choreographer.java:272-274）：CALLBACK_TRACE_TITLES = { "input", "animation", "insets_animation", "traversal", "commit" }。
- 常量 KDoc：CALLBACK_INPUT = 0 "Runs first"（:276-280）；CALLBACK_ANIMATION = 1 "Runs before CALLBACK_INSETS_ANIMATION"（:282-287）；CALLBACK_TRAVERSAL = 3 "**Handles layout and draw. Runs after all other asynchronous messages**"（:305-310）。
- 关键锚点：postFrameCallbackDelayed 的实现（:678-685）——postCallbackDelayedInternal(**CALLBACK_ANIMATION**, callback, FRAME_CALLBACK_TOKEN, delayMillis)。FrameCallback 即 ANIMATION 档。

### 3.2 Compose on Android 的帧流水线（withFrameNanos 在 ANIMATION 相位恢复）

- AndroidUiDispatcher KDoc（AndroidUiDispatcher.kt:28-31，原文）："A [CoroutineDispatcher] that will perform dispatch during a handler callback or **[choreographer]'s animation frame stage**, whichever comes first. Use [Main] to obtain a dispatcher for the process's main thread."；帧回调经 choreographer.postFrameCallback(dispatchCallback) 注册（:115-123）→ 由 3.1 锚点即落在 CALLBACK_ANIMATION 档。
- AndroidUiFrameClock.withFrameNanos（AndroidUiFrameClock.kt:25-46）：以 Choreographer.FrameCallback 恢复协程；注释（:38-43）明确 "onFrame will run on the current choreographer frame if one is already in progress, but **withFrameNanos will not resume until the frame is complete**"，防止同帧重复派发。
- 推论（两条源码锚点直接相连）：Compose 中 withFrameNanos 的恢复点 = ANIMATION 相位、严格早于 traversal（measure/layout/draw）。Recomposer/动画逐帧驱动走同一时钟与主 dispatcher，故重组+apply（含 SideEffect/applyObserver）发生在 traversal 之前——「帧首」即此。

### 3.3 View 层顺序链：measure → layout → onPreDraw → draw（同一函数内的行号序）

来源：https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/ViewRootImpl.java

performTraversals()（ViewRootImpl.java:3454）内部的确定性顺序（main 分支行号）：

| 步骤 | 实现定义 | performTraversals 内调用点 |
|---|---|---|
| measure | performMeasure :4917 | :3293-4110 多分支 |
| layout | performLayout :4983 | :4148 |
| **onPreDraw** | dispatchOnPreDraw（ViewTreeObserver.java:1166） | **:4347** boolean cancelDueToPreDrawListener = mAttachInfo.mTreeObserver.dispatchOnPreDraw(); |
| draw | performDraw :5383 | :4412 |

且 onPreDraw 可否决当帧：返回 false → cancelAndRedraw → 不执行 draw、重新调度帧（:4347-4352）。这正是「布局完成后、绘制前插入同步点」的平台级官方挂点。

ViewTreeObserver.OnPreDrawListener KDoc（ViewTreeObserver.java:155-171，原文）——官方用途与我们的场景逐字吻合：

> "Callback method to be invoked when the view tree is about to be drawn. **At this point, all views in the tree have been measured and given a frame. Clients can use this to adjust their scroll bounds or even to request a new layout before drawing occurs.** @return Return true to proceed with the current drawing pass, or false to cancel."

### 3.4 onGloballyPositioned：框架内「布局后、绘制前」的逐节点钩子

- KDoc（OnGloballyPositionedModifier.kt:24-27）："Invoke [onGloballyPositioned] with the [LayoutCoordinates] of the element… it will be called **after a composition when the coordinates are finalized**"；接口方法 KDoc（:91-95）："Called with the **final LayoutCoordinates of the Layout after measuring**"。
- 分发点源码（AndroidComposeView.android.kt）：onLayout（:2244-2272）内注释原文——"**we postpone onPositioned callbacks until onLayout** as LayoutCoordinates are currently wrong if you try to get the global(activity) coordinates - View is not yet laid out."（:2250-2252），随后 updatePositionCacheAndDispatch() → measureAndLayoutDelegate.dispatchOnPositionedCallbacks(forceDispatch = positionChanged)（:2308）。即回调在 View.onLayout（traversal 内、draw 前）集中分发；另有 measureAndLayout 路径同点分发（:2088/2102）。
- Compose 自身不使用 OnPreDraw 驱动布局流程（AndroidComposeView.kt 全文 grep "preDraw" 0 命中）；它属于宿主/业务层可用的平台钩子（经 LocalView）。

### 3.5 flush 点选型结论

| 候选钩子 | 帧内时机 | 单点性 | 依据 | 适用 |
|---|---|---|---|---|
| withFrameNanos | ANIMATION 相位（traversal 前） | 全局 | §3.1-3.2 | 帧首意图汇合 + 帧事务 apply 点；不能做布局后 flush |
| Modifier.onGloballyPositioned | View.onLayout 内集中分发（draw 前） | 逐节点 | §3.4 | 框架内逐节点验证/记账 |
| ViewTreeObserver.OnPreDrawListener | layout 后 draw 前，全局一次，可否决当帧 | **全局单点** | §3.3 | 布局后绘制前的唯一 flush/校验点（首选） |
| Snapshot.applyObserver / GlobalSnapshotManager | apply 即刻（通常 ANIMATION 相位）+ 异步合流通知 | 非帧同步 | §2.4 | 跨帧对账，不作 flush 点 |

---

## 4. 官方「单写者」先例：LazyList 全景（含 Animatable 单消费者）

来源：
- https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt
- https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollPosition.kt
- https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/animation/animation-core/src/commonMain/kotlin/androidx/compose/animation/core/Animatable.kt

### 4.1 LazyListScrollPosition：写路径收敛清单

状态本体（LazyListScrollPosition.kt:32-41）：index / scrollOffset 均为 mutableIntStateOf（scrollOffset private set）。改动入口只有三个，全部收敛：

1. updateFromMeasureResult(measureResult)（:51-64，"Updates the current scroll position based on the results of the **last measurement**"）→ 调私有 update()（:107-112，唯一真正的赋值点）。
2. requestPositionAndForgetLastKnownKey(index, scrollOffset)（:81-86）→ 同一私有 update()。
3. updateScrollOffset（:66-69，仅 measure 后微调 offset）。

调用侧（LazyListState.kt）：1 号由 applyMeasureResult 调用（:692），而 applyMeasureResult（:655-700）是 measure 结果回写的唯一汇合点（连同 canScrollForward/Backward、layoutInfoState、scrollToBeConsumed 的扣减，:676-689；内部还用 Snapshot.withoutReadObservation（:670）包裹停动画的读边处理）；2/3 号由 snapToItemIndexInternal（:482-503）调用，而后者只被 scrollToItem（:449，挂起，经 scroll {}）、requestScrollToItem（:468-476，非挂起）、以及 measure 期间的位置保持逻辑触达。

### 4.2 串行化机制：互斥（挂起通道）+ 线程封闭（同步通道）

- LazyListState 持 private val scrollableState = ScrollableState { -onScroll(-it) }（:313，工厂即 DefaultScrollableState → MutatorMutex，§1.5）。override suspend fun scroll(scrollPriority, block)（:516-524）先 awaitLayoutModifier.waitForFirstLayout() 再转发 scrollableState.scroll(...)——一切挂起式滚动（含 fling、scrollToItem、animateScrollToItem）共用这一把 MutatorMutex，新滚动按优先级取消旧滚动。
- override fun dispatchRawDelta(delta) = scrollableState.dispatchRawDelta(delta)（:526）→ 同步直调 onScroll（:553 起）。onScroll 内注释原文："scrollToBeConsumed will be consumed **synchronously during the forceRemeasure invocation**"（:564-565）——同步通道靠「主线程 + 同步执行（不可挂起、不可交错）」保证原子性，与挂起通道的互斥由「同为主线程 + MutatorMutex 挡住挂起者」衔接。
- 即官方强制单写者的完整答案：挂起写 → mutex 优先级取消；同步写 → 线程封闭 + 同步 remeasure；两者状态变化最终都汇入 applyMeasureResult 单点。

### 4.3 requestScrollToItem：官方的「非挂起快路径」样例

requestScrollToItem（LazyListState.kt:468-476）KDoc："Any scroll **in progress will be cancelled**."实现：if (isScrollInProgress) { layoutInfoState.value.coroutineScope.launch { scroll {} } } 然后同步 snapToItemIndexInternal(index, scrollOffset, forceRemeasure = false)。——官方示范了 PreRenderCoordinator 需要的同款分解：取消在途写者可以异步化（launch 一个 Default 优先级滚动去打断），而写入本身保持同步；snapToItemIndexInternal(forceRemeasure = true) 分支（scrollToItem 用）甚至直接 remeasurement?.forceRemeasure()（:498-500）做同步重排。

### 4.4 Animatable：单消费者的官方实证

类 KDoc（Animatable.kt:47-50，原文）："Unlike [AnimationState], [Animatable] ensures **mutual exclusiveness** on its animations. To achieve this, when a new animation is started via [animateTo] (or [animateDecay]), **any ongoing animation will be canceled via a CancellationException**."实现：private val mutatorMutex = MutatorMutex()（:121）；animateTo :299、snapTo :380、stop :403 全部包进 mutatorMutex.mutate { }——三个入口共用一个写者身份，新动画打断旧动画且速度连续。其锁即 §1.1 的同构内部副本。

### 4.5 SnapFlingBus 考证：不存在

对 foundation/lazy/SnapFlingBus.kt、foundation/lazy/layout/SnapFlingBus.kt、foundation/lazy/SnapFlingBehavior.kt 做 raw 探测均 404（androidx-main，2026-09-21）；androidx 中不存在该类型。官方对应物是 FlingBehavior/snapFlingBehavior（gestures/FlingBehavior.kt）产生 fling 速度，而 fling 的消费必经 scroll(MutatePriority.Default) 互斥（Scrollable.kt:724-728，§1.3）——不存在独立的「fling 总线」写通道。

---

## 5. 跨域参照（简，仅提炼启发）

### 5.1 数据库 WAL / 事务日志（SQLite 官方文档）

来源：https://www.sqlite.org/wal.html （一手原文，逐句核对）

- "The original content is preserved in the database file and the changes are **appended into a separate WAL file**. A COMMIT occurs when a special record indicating a commit **is appended to the WAL**… **Multiple transactions can be appended to the end of a single WAL file.**"（原子提交一节）
- "WAL provides more concurrency as **readers do not block writers and a writer does not block readers**."
- 把 WAL 内容搬回主库的批处理称 **checkpoint**（"transferring WAL-file transactions back into the database is called a checkpoint"）；"WAL works best with smaller transactions."

对本设计的启发（不展开）：① 上层并行请求只允许追加到队列（append-only intent log），绝不直接改状态；② 真正的状态变更由单写者按帧 checkpoint 批量应用（一帧一次 apply ≈ 一次 checkpoint）；③ 读者（measure/composition）永远看主库（全局快照），不与队列写互斥——与快照系统的读写隔离天然同构。

### 5.2 游戏 ECS：并行 job + 帧内 sync point（Bevy 官方源码）

来源：https://github.com/bevyengine/bevy/blob/main/crates/bevy_ecs/src/schedule/schedule.rs （官方实现自述；Unity DOTS 文档站本环境不可达，Bevy 为同模式一手实现，术语同为 sync point）

- "non-conflicting systems are free to run in any order (**including in parallel**)"（:1427）——按冲突检测最大化并行；
- "Whether or not to insert **sync points** between systems in this chain"（:296-297）与 "a sync point inserted by a build pass splits an edge in two"（:1503）——deferred（结构性）变更不在并行段执行，攒到显式 sync point 单线程统一 apply（auto_insert_apply_deferred :1895-1900，可手动只在一帧末 sync）；
- 冲突（读写同一资源）时回退串行，或整调度退 SingleThreadedExecutor（:1915-1916）。

启发：① 「模拟可并行、写必须过 sync point」——对应「多个 UI 交互可并行发起、视口/高度写只在帧内 flush 点应用」；② sync point 插入是调度器自动推导 + 允许手动指定——对应 PreRenderCoordinator 应自动聚合每帧意图、同时保留「关键帧手动强制 flush」的口；③ 结构变更（增删=高度变化）比数据变更更贵、必须过点——对应 LazyList 项高度/位置变化一律视为结构性事件进队列。

---

## 6. 可借力官方原语清单（含版本锚点）

| 原语 | 模块 / 包 | 公开性 | 版本锚点 | 对 PreRenderCoordinator 的用途 |
|---|---|---|---|---|
| MutatorMutex + mutate/mutateWith | foundation / androidx.compose.foundation | **public** | androidx-main 现存；根包路径至少 2024-06 起（compose 1.7/1.8 期）；版权头 2020 | 意图层单写者 + 优先级取消（直用或照抄 ~120 行） |
| MutatePriority（Default/UserInput/PreventUserInput） | foundation，同上 | **public** | 同上 | 交互（连点卡片=可升 UserInput）> 程序化展开 的官方档位 |
| MutatorMutex.tryMutate | foundation，同上 | **public** | androidx-main 现存（首次发布版本未考证） | 主线程同步快路径（同步 remeasure 类写） |
| Snapshot.withMutableSnapshot | runtime / snapshots | public | androidx-main 现存；冲突抛 SnapshotApplyConflictException | 帧事务 commit/abort 一站式 |
| takeMutableSnapshot / apply / dispose / SnapshotApplyResult | runtime / snapshots | public | 同上（apply 返回 Result + 冲突类为较新形态） | 多阶段显式事务；冲突检测一等化 |
| registerApplyObserver / registerGlobalWriteObserver / sendApplyNotifications | runtime / snapshots | public | 长期存在 | 跨帧对账/审计；不作 flush 点 |
| withFrameNanos（MonotonicFrameClock；Android 实现 AndroidUiFrameClock） | runtime / ui(android) | public | 1.0 起；Android 实现见 §3.2 | 帧首汇合点（ANIMATION 相位） |
| ScrollableState.scroll(priority) / DraggableState.drag(priority) | foundation | public | 1.0 起（priority 形参同上） | 若协调器要接管滚动型视口变更，直接走此契约 |
| Modifier.onGloballyPositioned | ui / layout | public | 1.0 起 | 逐节点布局后验证钩子 |
| ViewTreeObserver.OnPreDrawListener（经 LocalView） | 平台 | public API | API 1+ | **全局唯一 flush/校验点；可否决当帧** |

---

## 7. 来源汇总

androidx（canonical googlesource 永久路径，本文经 GitHub 镜像 raw.githubusercontent.com/androidx/androidx/androidx-main/ 抓取同源内容）：

- [M1] MutatorMutex.kt — …/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/MutatorMutex.kt
- [M2] InternalMutatorMutex.kt — …/compose/animation/animation-core/src/commonMain/kotlin/androidx/compose/animation/core/InternalMutatorMutex.kt
- [M3] ScrollableState.kt — …/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/ScrollableState.kt
- [M4] Draggable.kt — …/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/Draggable.kt
- [M5] Scrollable.kt — …/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/Scrollable.kt
- [M6] Animatable.kt — …/compose/animation/animation-core/src/commonMain/kotlin/androidx/compose/animation/core/Animatable.kt
- [M7] Snapshot.kt — …/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/snapshots/Snapshot.kt
- [M8] MonotonicFrameClock.kt — …/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/MonotonicFrameClock.kt
- [M9] AndroidUiDispatcher.android.kt — …/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidUiDispatcher.android.kt
- [M10] AndroidUiFrameClock.android.kt — …/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidUiFrameClock.android.kt
- [M11] AndroidComposeView.android.kt — …/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeView.android.kt
- [M12] GlobalSnapshotManager.android.kt — …/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/GlobalSnapshotManager.android.kt
- [M13] LazyListState.kt / LazyListScrollPosition.kt — …/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/
- [M14] OnGloballyPositionedModifier.kt — …/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/OnGloballyPositionedModifier.kt

平台（AOSP 镜像 main）：

- [P1] Choreographer.java — https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/Choreographer.java
- [P2] ViewRootImpl.java — https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/ViewRootImpl.java
- [P3] ViewTreeObserver.java — https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/ViewTreeObserver.java

版本与其他：

- [V1] foundation maven-metadata — https://dl.google.com/android/maven2/androidx/compose/foundation/foundation/maven-metadata.xml
- [V2] runtime maven-metadata — https://dl.google.com/android/maven2/androidx/compose/runtime/runtime/maven-metadata.xml
- [V3] animation-core maven-metadata — https://dl.google.com/android/maven2/androidx/compose/animation/animation-core/maven-metadata.xml
- [V4] MutatorMutex.kt commit 历史 — https://api.github.com/repos/androidx/androidx/commits?path=compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/MutatorMutex.kt
- [X1] SQLite WAL — https://www.sqlite.org/wal.html
- [X2] Bevy bevy_ecs schedule — https://github.com/bevyengine/bevy/blob/main/crates/bevy_ecs/src/schedule/schedule.rs
- 关联：[01-compose-official.md](01-compose-official.md)（帧相位模型、backwards-write 一帧滞后铁律、onPlaced/onGloballyPositioned/drawWithContent 契约——本篇不再重复，直接引用其结论）

