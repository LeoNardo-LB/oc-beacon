# 06 · 高度修改 API 能力边界地图 — 公开面 vs 内部面 · 反射风险与先例

> 为 PreRenderCoordinator(#432) 的「布局后、绘制前」渲染前计算能力选择提供 API 边界地图。
> 方法：androidx-main 稀疏克隆（/tmp/androidx-main @ b0e5f9fe34e7，2026-09-21）逐符号核验可见性 → 版本化 api/*.txt 官方面追踪定版本 → 官方发布 **sources.jar 源码级 + AAR classes.jar javap 字节码级**双通道核销 → Sourcegraph 公共代码检索找生态先例。
> 行号约定：androidx 源码行号对应 androidx-main @ b0e5f9fe34e7（GitHub mirror 行号一致）；本仓行号为 2026-09-22 工作区快照。**只调研，不含实现代码。**

## 0. 结论速览

1. **官方确未开放「绘制前改高度/滚动位且不经下一帧」的直接公开 API**——证实此前调研的判断；但存在两条完全公开的间接路径（Modifier.layout 上报控制 + 帧界 requestScrollToItem 排空），本仓现行架构已覆盖全部硬需求。
2. **「同步强制重测」本身是公开 API**：Remeasurement.forceRemeasure() / RemeasurementModifier.onRemeasurementAvailable() 自 ui **1.0.0-beta02** 起就是公开接口（api 文件实证）。缺的只是公开的「待消费 delta」通道——那才是 internal 的。
3. 本仓现有反射（LazyListReflection 三成员）在当前 BOM 2026.08.00（foundation-android **1.12.0**）**仍然可用，但其中 measurementScopeInvalidator 是靠 value-class unboxed 字节码表示的巧合在工作**——源码声明类型早已改变，且注释在写下的当天就与真实源码不符（§3.4）。
4. 反射三大现实风险全部拿到实证：**R8 混淆**（release isMinifyEnabled=true）、**Kotlin internal 混名后缀跨版本漂移**（$foundation_release → $foundation，1.5.0 vs 1.12.0 AAR 对比实证）、**内部类型静默变更**（§5）。
5. 官方态度：androidx 非平台库**不受 hidden-api 黑名单限制**（官方文档明说「运行时无调用限制、反射调用 IDE 也不警告」），但 intra-library 内部 API 的设计前提是「**假设永远无人调用**」→ **零兼容承诺**（§6）。

---

## 1. 证据等级与方法

| 证据 | 等级 | 获取方式 |
|---|---|---|
| androidx 版本化 api/*.txt（1.0.0-beta02.txt … current.txt） | ★★★ 官方 API 面追踪，androidx CI 强制校验 | 稀疏克隆 compose/*/*/api/ |
| 源码可见性声明（public/internal/private） | ★★★ 一手源码 | compose/**/src/commonMain/kotlin/ |
| **官方发布 AAR 反编译（javap）** | ★★★ 字节码级事实（R8 看到的就是这个） | dl.google.com 下载 foundation-android-<v>.aar → unzip classes.jar → javap -p |
| 官方发布 sources.jar | ★★☆ 源码级（但≠字节码表示） | dl.google.com/.../foundation-android-<v>-sources.jar |
| 官方文档/指南（androidx 仓内 docs/） | ★★★ 政策一手 | docs/api_guidelines/ |
| BOM POM 版本映射 | ★★★ | dl.google.com/.../compose-bom/<v>/*.pom |
| Sourcegraph 公共代码检索 | ★★☆ 有限采样 | sourcegraph.com/.api/search/stream |

**版本映射锚点**：本仓 app/build.gradle.kts:187 = compose-bom:2026.08.00 → POM 实证映射 **foundation-android 1.12.0 / ui-android 1.12.0**（[BOM 2026.08.00 POM](https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.08.00/compose-bom-2026.08.00.pom)）。历史锚点：BOM 2026.05.01 → foundation-android 1.11.2（同法验证，§3.4 用到）。

---

## 2. 线 1：逐 API 核验（androidx-main 源码 + api 面文件）

### 2.1 Modifier.layout{} 自定义上报高度 — **公开 ✓，自 ui 1.0 起**

- api 面：compose/ui/ui/api/current.txt:3201（LayoutModifierKt.layout 为 public static）；1.0.0-beta02.txt 已收录。
- 源码：[Layout.kt / Modifier 扩展](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/Layout.kt)。
- 本仓使用点：ScrollCompensation.kt:146（deferredRevealCompensation）——「测量遍里决定上报高度」的公开合法路径，无需任何反射。
- **测量中返回自定义 layout(w, h) 的 Placeable 是 LayoutModifier 契约的一部分，无版本风险。**

### 2.2 SubcomposeLayout 预测量 — **公开 ✓，自 ui 1.0 起**

- api 面：current.txt:3522-3531（SubcomposeLayoutKt + public final class SubcomposeLayoutState）；1.0.0-beta02.txt:1797-1800 已收录 SubcomposeLayout + SubcomposeMeasureScope。
- 预测量能力 = subcompose 在测量作用域内先组合测量子内容，属公开面。
- 结论：**预测量无需内部 API**。

### 2.3 Remeasurement / onRemeasurementAvailable / forceRemeasure() — **公开 ✓，自 ui 1.0.0-beta02 起**

⚠️ 任务前提纠偏：**不存在 Modifier.onRemeasurementAvailable 扩展函数**。真实形态是公开接口 RemeasurementModifier 的方法，自己实现该接口挂到链上即可收到回调：

> [RemeasurementModifier.kt:27-48](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/RemeasurementModifier.kt#L27)
>
>     public interface RemeasurementModifier : Modifier.Element {
>         public fun onRemeasurementAvailable(remeasurement: Remeasurement)   // L34
>     }
>     public interface Remeasurement {
>         public fun forceRemeasure()   // L48，KDoc 明言："during scrolling you need to re-execute the measure block … in a blocking way"
>     }

- api 面起源：compose/ui/ui/api/1.0.0-beta02.txt:2277-2282 两者均已收录为 public interface → **自 Compose 诞生（1.0）即公开，非新 API**。
- 官方自己在用：LazyListState.kt:332-337 内部 remeasurementModifier = object : RemeasurementModifier 拿到 Remeasurement（字段本身 internal var remeasurement，:328）。
- **能力边界**：对「自己写的节点」同步重测 = 纯公开可实现；对「LazyList 整体节点」强制同步重测，公开面没有拿 Remeasurement 的入口（internal 字段），见 §2.9 表。

### 2.4 LazyListState.scroll / dispatchRawDelta / scrollToItem — **全部公开 ✓，自 foundation 1.0**；requestScrollToItem 公开，自 **1.7.0**

| API | 可见性 | 证据 |
|---|---|---|
| suspend fun scroll(priority, block) | public（ScrollableState 实现） | [LazyListState.kt:516](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt#L516)；ScrollableState 公开自 1.0.0-beta02 |
| fun dispatchRawDelta(delta): Float | public override | [LazyListState.kt:526](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt#L526)；接口成员自 1.0.0-beta02 |
| suspend fun scrollToItem(index, scrollOffset) | public | [LazyListState.kt:448](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt#L448)（内部走 scroll { snapToItemIndexInternal(…, forceRemeasure = true) }） |
| fun requestScrollToItem(index, scrollOffset) | public，**非 Experimental** | [LazyListState.kt:469](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt#L469)；api 文件**首现于 1.7.0-beta01.txt**，@ExperimentalFoundationApi 在各版本 api txt 均未出现（该注解会被 api txt 记录——current.txt 有 64 处，反证成立） |

⚠️ 本仓注释纠偏：ScrollCompensation.kt:252 称 requestScrollToItem 为 @ExperimentalFoundationApi——按 api 面证据它至迟 1.7.0-beta01 已是非实验公开 API；该 OptIn 现为冗余（无害）。

**requestScrollToItem 的关键行为**（LazyListState.kt:469-476）：

    public fun requestScrollToItem(index: Int, scrollOffset: Int = 0) {
        // Cancel any scroll in progress.
        if (isScrollInProgress) {
            layoutInfoState.value.coroutineScope.launch { scroll {} }   // ← 本仓要绕过的「杀 fling」行为
        }
        snapToItemIndexInternal(index, scrollOffset, forceRemeasure = false)
    }

### 2.5 copyWithScrollDeltaWithoutRemeasure — **internal**（前提纠偏：归属在 MeasureResult，不在 LazyLayoutIntervalContent / LazyLayoutScrollPosition）

- 真实归属：**internal class LazyListMeasureResult** 的成员方法（[LazyListMeasureResult.kt:30](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListMeasureResult.kt#L30) 类声明；:100 方法定义）：
- 签名：fun copyWithScrollDeltaWithoutRemeasure(delta: Int, updateAnimations: Boolean): LazyListMeasureResult?（返回 null = 必须回退完整 measure 遍）
- 同族：LazyGridMeasureResult / LazyStaggeredGridMeasureResult:173 同名方法。**不存在名为 LazyLayoutScrollPosition 的类型**（只有 LazyListScrollPosition，§2.6）；LazyLayoutIntervalContent 是公开抽象类（lazy/layout/LazyLayoutIntervalContent.kt:25），但与该方法无关。
- **唯一调用者**：LazyListState.onScroll（[LazyListState.kt:571,579](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt#L571)）——官方滚动消费的内部组合：scrollToBeConsumed(internal var, :301) → copyWithScrollDeltaWithoutRemeasure 算出「只需 placement 就能应用的新 layoutInfo」→ remeasurement?.forceRemeasure()（:606）同步重测。**这就是「绘制前同步改滚动位、避免全量重测」的官方内部机制全貌。**
- 引用 internal 类 ⇒ 无法用任何公开类型签名描述 ⇒ 若要走此路**必然是纯反射**（连参数类型都 internal）。

### 2.6 LazyListScrollPosition 锚点维护方法 — **internal class 全家**

> [LazyListScrollPosition.kt:32](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollPosition.kt#L32)：internal class LazyListScrollPosition

| 成员 | 行号 | 本仓是否依赖 |
|---|---|---|
| index / scrollOffset | :33,:35 | 间接 |
| updateFromMeasureResult(measureResult) | :51 | 遍末回写点（LazyListState 调用） |
| updateScrollOffset(scrollOffset)（带 checkPrecondition(scrollOffset >= 0)） | :66-69 | 否 |
| **requestPositionAndForgetLastKnownKey(index, scrollOffset)** | :81-86 | **是（反射核心目标）** |
| updateScrollPositionIfTheFirstItemWasMoved(itemProvider, index) | :95-100 | 否（框架锚定自维护） |

- 外层包装 LazyListState.updateScrollPositionIfTheFirstItemWasMoved 亦 internal（[LazyListState.kt:729](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt#L729)）。
- 锚点维护（item 增删后按 key 跟随）是框架自动行为，公开面无干预入口、也**没有干预需求**。

### 2.7 「绘制前同步改 scroll offset 不经下一帧」的公开 API — **不存在**；Animatable/MutatorMutex 均公开

- **结论：没有任何公开 API 能在绘制前同步改滚动位并跳过下一帧 measure 遍**。官方内部靠 §2.5 的 copyWithScrollDeltaWithoutRemeasure + forceRemeasure 组合实现；公开最近似物是 dispatchRawDelta（**同步**消费 delta、无动画、可跨 item 边界——但几何应用仍在下一遍 measure）。
- 公开的「同步重测」原语 Remeasurement.forceRemeasure() 存在（§2.3），但没有公开的「待消费 delta」通道喂给它，单独调用只是立刻重跑一遍 measure。
- Animatable：**public final class**，animation-core 自 1.0.0-beta02（api txt:7；现 Animatable.kt:50）。
- MutatorMutex：**public final class @Stable**，androidx.compose.foundation 自 1.0.0-beta02（api txt:81；现 foundation/api/current.txt:330）——纠正：**不在 animation-core**（animation-core 里那份是注释明言的 internal copy：InternalMutatorMutex.kt:20 "This is an internal copy of androidx.compose.foundation.MutatorMutex"）。另公开提供全局单例 GlobalMutatorMutex（current.txt:62-63）。

### 2.8 LookaheadScope / animatePlacement / Experimental 状态

- **LookaheadScope：公开且已稳定**。api 面演变：1.5.0-beta01.txt 带 @ExperimentalComposeUiApi → **1.6.0-beta01.txt 起注解消失（晋级稳定）** → 现存 ui/api/current.txt:3204 public interface LookaheadScope。源码公开面：LookaheadScope.kt:48（LookaheadScope{} 组合子）、:102（**Modifier.approachLayout**）、:203（接口）、:255（lookaheadScopeCoordinates）。
- **Modifier.animatePlacement：从来不是 androidx API**——ui 全树与 api 面均无此符号；它是 lookahead codelab/社区示例里的**自定义 Modifier 写法**。
- **Modifier.intermediateLayout 的完整生命周期（API 面流动性的最佳标本）**：1.5.0-beta01 以 deprecated 4 参形态收录 → 1.6.0-beta01 改 @ExperimentalComposeUiApi 3 参 → **当前 current.txt 与 restricted_current.txt 均 0 命中 = 已整体退场**；继任者为公开的 Modifier.approachLayout（LookaheadScope.kt:102）。→ **对「实验 API 会消失」给出了官方一手实例**。

### 2.9 线 1 结论表：能力 → 公开路径 → 内部路径 → 是否必须反射

| # | 能力 | 公开路径 | 内部路径（类#方法） | 必须反射？ |
|---|---|---|---|---|
| 1 | 测量遍自定义上报高度（延迟揭示/裁剪） | ✅ Modifier.layout{}（1.0 起） | — | 否 |
| 2 | 预测量（先组合测量后布局） | ✅ SubcomposeLayout（1.0 起） | — | 否 |
| 3 | 同步强制重测（自有节点） | ✅ 实现 RemeasurementModifier 收 forceRemeasure()（1.0 起） | — | 否 |
| 4 | 同步强制重测（LazyList 整体） | ⚠️ 无入口（requestScrollToItem 等价近似） | LazyListState#remeasurement（internal 字段 :328）→ forceRemeasure() | 近似可公开；精确需反射 |
| 5 | 帧界注入滚动位移（下一帧 measure 遍首生效） | ✅ 公开组合：MonotonicFrameClock.withFrameNanos 排空 + requestScrollToItem（1.7.0 起，副作用=杀 fling） | requestPositionAndForgetLastKnownKey + measurementScopeInvalidator（internal :452） | 增强（免杀 fling）需反射，可降级 |
| 6 | 不取消 fling 的定位 | ❌ 无（requestScrollToItem/scrollToItem 必经 scroll{} 互斥锁） | 同上 internal 对 | **是**（本仓现状，有降级） |
| 7 | 绘制前同步消费 delta、不全量重测 | ❌ 无（dispatchRawDelta 是最近公开近似） | LazyListMeasureResult#copyWithScrollDeltaWithoutRemeasure(Int,Boolean) + remeasurement.forceRemeasure()（internal 组合） | **是（纯反射，不推荐）** |
| 8 | 锚点 item 移动跟随 | 框架自动维护（无需干预） | LazyListScrollPosition#updateScrollPositionIfTheFirstItemWasMoved（internal :95） | 否（无需求） |
| 9 | 同帧双遍测量（lookahead 预测） | ✅ LookaheadScope/Modifier.approachLayout（1.6.0 稳定；API 仍年轻） | — | 否（实验史风险自担） |
| 10 | 滚动优先级互斥 / 动画原语 | ✅ MutatorMutex（foundation 1.0）/ Animatable（animation-core 1.0） | — | 否 |

---

## 3. 线 2.1：本仓现有反射盘点（含注释级理解）

### 3.1 反射对象与真实形态对照（三成员）

代码位置：app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ScrollCompensation.kt:206-282（internal object LazyListReflection）；探测降级为 #43 引入（commit 34092594，2026-08-10）。

| 反射目标 | 本仓认知 | androidx-main 实况 | 字节码实况（1.12.0 AAR javap） |
|---|---|---|---|
| LazyListState.scrollPosition | private 字段 | private val scrollPosition（LazyListState.kt:231；1.11.2:200 / 1.12.0:200） | private final androidx.compose.foundation.lazy.LazyListScrollPosition scrollPosition ✓ |
| LazyListScrollPosition.requestPositionAndForgetLastKnownKey(Int,Int) | internal 方法 | internal 类上的方法（LazyListScrollPosition.kt:81） | public final void requestPositionAndForgetLastKnownKey(int,int) ✓ **无混名**（public 方法于 internal 类不混名） |
| LazyListState.measurementScopeInvalidator | MutableState<Unit> 字段（注释原话） | **internal val … = ObservableScopeInvalidator()**（main:452；**1.11.2:395、1.12.0:402**）——@JvmInline internal value class 包着 MutableState<Unit>（[ObservableScopeInvalidator.kt:29-39](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/layout/ObservableScopeInvalidator.kt#L29)） | **private final androidx.compose.runtime.MutableState<kotlin.Unit> measurementScopeInvalidator** ——字段以 **unboxed 表示**编译，类型仍是 MutableState！ |

### 3.2 为什么反射（绕过什么）

官方 requestScrollToItem 第一步是 if (isScrollInProgress) launch { scroll {} }（§2.4 引文）——获取 MutatorMutex 互斥锁会**杀死进行中的 fling 惯性**。SSE 流式贴底跟随场景里用户 fling 频繁，杀惯性 = 可感知顿挫。反射路径只做第 ② 步语义（requestPositionAndForgetLastKnownKey 设待定位 + measurementScopeInvalidator.invalidateScope() 使测量作用域失效，等价 snapToItemIndexInternal 的 forceRemeasure=false 分支，LazyListState.kt:482-506），**不经 scroll{} 锁**。调用点：ChatMessageList.kt 经 PreRenderShiftChannel.drain 帧界排空（PreRenderShiftChannel.kt:95-139）；显式导航（jumpTo/jumpToTask）已明确**不用**反射、走官方挂起 scrollToItem（JumpNavigationController.kt:297-303，「取消 fling 是预期语义」）。

### 3.3 已删除的第四个反射目标（血泪先例）

LazyListState.scrollToBeConsumed（internal var，main:301 / 1.11.2:270）曾被反射直写注入残量。用户 drag 起手经 onScroll 对残量有断言：

> LazyListState.kt:557-559（1.11.2:500-501）：checkPrecondition(abs(scrollToBeConsumed) <= 0.5f) { "entered drag with non-zero pending scroll" }

残量存活约一帧撞上用户输入 → checkPrecondition 抛出 → **真机 FATAL**（#258 换道手术，commit 6078029f 删除直写，064c47fc 2026-08-30 裁决收敛）。**教训：反射只能走「无断言通道」；对有状态机断言保护的 internal 字段直写是崩溃放大器。** 当前 onScroll 对「待定滚动位置」通道（requestPosition）**无断言**（PreRenderShiftChannel.kt:15-31 源码级核销记录）。

### 3.4 ⚠️ 注释漂移与「巧合存续」（本次调研最重要的运维发现）

时间线实证：
1. 34092594（2026-08-10，#43）写下注释：「反射依赖的私有成员（Compose BOM 2026.05.01）：measurementScopeInvalidator: MutableState<Unit>」。
2. 但 BOM 2026.05.01 → foundation-android **1.11.2**（POM 实证），而 **1.11.2 sources.jar 的 LazyListState.kt:395 已是 ObservableScopeInvalidator()**。再往前：1.7.0 起即如此，1.5.0 无该文件（引入窗口 = 1.6.0~1.7.0）。
3. ⇒ **注释在写下的当天就是错的**（照抄了 1.5 时代源码记忆）；PreRenderShiftChannel.kt:22 的「foundation-android 1.11.2 sources 源码级核销」核销了**机制**但没核销**类型**。
4. 反射为何仍活着：@JvmInline value class 的属性字段以 **unboxed 表示**编译——字段字节码类型恰好还是底层 MutableState（javap 实证 §3.1 表末行）。field.get(state) 拿到 MutableState → as MutableState<Unit> 成功 → .value = Unit 与 invalidateScope() 等效。
5. ⇒ **这是表示层巧合，不是契约**。任何使 Kotlin 改变 unboxing 决策的编译器版本变化、或把字段改成非 value-class 包装，都会让它无声死亡——降级路径会兜住不崩溃，但「NoCancel」能力静默退化为官方杀 fling 行为，**产品语义回归且无告警**（只有探测 init 时的日志）。

### 3.5 R8 keep 规则现状（app/proguard-rules.pro:45-48）

    # Compose LazyListState — reflection access for SSE drift compensation
    -keep class androidx.compose.foundation.lazy.LazyListState { *; }
    -keep class androidx.compose.foundation.lazy.LazyListScrollPosition { *; }

- 引入 commit：b2cee821（feat: bypass requestScrollToItem mutex cancellation via reflection）。
- 覆盖度评估：两个类名 + 全体成员被保名 → 现有三成员反射在 release 下可用。**缺口**：ObservableScopeInvalidator 类本身未 keep（当前不需要——反射只触其 unboxed 字段），但若未来反射该 value class 的方法则必须补。
- 反向确认 release 有混淆：app/build.gradle.kts:131 isMinifyEnabled = true。

---

## 4. 线 2.2：生态先例（如实采样，含阴性结果）

### 4.1 检索通道实录（2026-09-22）

| 通道 | 结果 |
|---|---|
| Sourcegraph stream API | ✅ 唯一可用（匿名限速） |
| grep.app | ❌ Vercel Security Checkpoint |
| searchcode.com API | ❌ 返回非 JSON |
| GitHub code search API | ❌ 需 token（会话无 GITHUB_TOKEN） |
| Bing / DuckDuckGo HTML | ❌ 拦截/空结果 |

### 4.2 命中与定性

对内部符号做全域代码检索（requestPositionAndForgetLastKnownKey / scrollToBeConsumed，排除 androidx 官方仓）：
1. **Pinball3D/Rabbit-R1**（19 处 scrollToBeConsumed 命中）——Rabbit R1 设备固件的 **jadx 反编译 dump**（original r1/java/sources/… 路径）。价值有二：证明这些 internal 成员**存在于真实上市设备的 release 构建中**；且 dump 中出现 **getScrollToBeConsumed$foundation_release**——野生状态下 internal 混名后缀的直接物证（§5-R2）。定性：**反编译物，非反射库**。
2. **ScriptedAlchemy/robinhood-decompiled**（4 处 requestPositionAndForgetLastKnownKey）、**tsuzcx/qq_apk**（2 处）——同为反编译 dump。
3. **阴性结果**：公开索引中**未发现**以反射方式访问这套 LazyList 内部的活跃一手库/项目（getDeclaredField "androidx.compose" 的 kotlin 检索亦无有效命中）。结合通道受限如实声明：该结论是有限采样下的「未发现」，不是「不存在」。生态现状更接近「逆向/调试时偶发使用」，而非成熟库实践——**没有可依赖的社区兼容层，本仓的探测+降级属自建先例**。
4. 复核建议（取得 GitHub token 后执行）：getDeclaredField "androidx.compose.foundation.lazy" lang:kotlin；"LazyListScrollPosition" "setAccessible"；"copyWithScrollDeltaWithoutRemeasure" -repo:androidx。

---

## 5. 线 2.3：风险清单与缓解（全部实证）

**总前提**：androidx 是普通 Java/Kotlin 库，随 app 一起被 R8 处理；它**不享受**平台 hidden-api 黑名单的保全——R8 想改名就改名（§6 官方文档亦承认运行时无限制）。风险全在构建期与版本期，不在运行时执法。

### R1 · R8 混淆使反射内部类名/成员名失效（release 必然触发）
- 实证：本仓 isMinifyEnabled=true（build.gradle.kts:131）；无 keep 时 LazyListScrollPosition 等 internal 类会被改名/移除 → Class.forName/getDeclaredField 失败。
- 缓解：
  1. **keep 规则**（已有，proguard-rules.pro:47-48）：两类全成员保名。新增反射目标必须同步补规则。
  2. **release 变体冒烟**：keep 规则只 protect 名字，不证明反射成功——CI/真机跑一次 release 包补偿路径，看 LazyListReflection 探测日志是否 null（现仅 debug 日志，建议升级为可观测）。
  3. **探测降级**（已有，#43）：init 一次性探测，任何成员缺失 → 永久走官方 requestScrollToItem（语义差异=杀 fling，无崩溃）。

### R2 · Kotlin internal 混名后缀跨版本漂移（实证：$foundation_release → $foundation）
- 实证：foundation-android **1.5.0** AAR 中 internal 成员为 getScrollToBeConsumed$foundation_release；**1.12.0** 变为 getPrefetchStrategy$foundation（javap 对比）。Rabbit-R1 dump 亦见旧后缀野生证据。
- 影响：凡反射目标含 **internal 函数/getter**（字节码名带模块后缀），后缀一变即 NoSuchMethod。本仓三目标当前**均不混名**（字段名不混名；requestPositionAndForgetLastKnownKey 是 internal 类上的 public 方法）——但这依赖「继续只反射字段与 public-on-internal 方法」的纪律。
- 缓解：反射目标纪律化（**只反射字段 + 无混名方法**，写进代码注释契约）；升级 BOM 时 javap 复核（R3 流程顺带覆盖）。

### R3 · 内部类型/结构静默变更（实证：value class 换血 + 行号漂移）
- 实证 A：measurementScopeInvalidator 源码类型 MutableState<Unit> → ObservableScopeInvalidator（引入窗口 1.6.0~1.7.0；本仓注释至今未更新，§3.4）。
- 实证 B：同一断言行号 1.11.2:496-501 → 1.12.0:503-507 → main:554-559——**任何「按行号核销」的做法单版本内即失效**。
- 缓解：
  1. **BOM 钉死 + 升级 checklist**（本仓已有约定：升级前手动验证成员存在）——把验证手段具体化为：curl sources.jar + unzip -p | grep 三分钟核销（本报告 §1 即模板）。
  2. **升级流程加入 javap 字节码核销**（源码核销不够，R4 是反例）。
  3. 探测降级兜底（已有）。

### R4 · value class 表示层巧合（源码核销 ≠ 字节码正确）
- 实证：§3.1/§3.4——源码里类型已不是 MutableState<Unit>，字节码里仍是。反过来，未来 Kotlin 若为该字段生成 boxed 表示或改名，源码可能反而看起来没变。
- 缓解：把「反射目标在**发布 AAR 字节码层**的类型」作为核销对象（javap -p），而非源码文本；对 cast 目标类型做运行时 try/catch（已有：ClassCastException → 降级）。

### R5 · 断言竞态崩溃（反射直写有状态机保护的字段）
- 实证：scrollToBeConsumed 直写 → checkPrecondition("entered drag with non-zero pending scroll") 真机 FATAL（§3.3，#258）。
- 缓解：只走无断言通道（现行 request-position 通道）；对任何新内部字段先 grep 其全部 checkPrecondition/check 断言再决定是否可写。

### R6 · compose compiler 内联与 lambda 类
- 分析：@Composable 函数体被改写为 ComposableLambda 状态机、inline 函数无独立方法体可反射、编译器生成类（如 ComposableSingletons$…）命名不稳定。**本仓三目标均为普通字段/方法，当前合规**；纪律：反射目标永远限定「数据字段 + 非 inline、非 composable 方法」，不碰 lambda/泛型签名擦除敏感面。
- 缓解：同 R2 纪律 + javap 复核。

---

## 6. 线 2.4：官方态度

一手政策（androidx 仓内 docs/api_guidelines/，androidx-main @ b0e5f9fe）：

1. **运行时无执法、反射无警告**（[annotations.md:132-138](https://github.com/androidx/androidx/blob/androidx-main/docs/api_guidelines/annotations.md#L132)）：
   > "While restricted APIs do not appear in documentation and Android Studio will warn against calling them, hiding an API does *not* provide strong guarantees about usage: **There are no runtime restrictions on calling hidden APIs**; **Android Studio will not warn if hidden APIs are called using reflection**…"
   → 直接证实「androidx 非平台库不受 hidden-api 黑名单限制」：那是 Android 平台 framework 的机制，androidx 是普通库代码，黑名单不适用。
2. **intra-library 内部 API 的设计前提 = 永远无人调用**（同文件 :163-167）：
   > "Restricted API surfaces used within a single library (intra-library APIs) … **may be added or removed without any compatibility considerations**. **It is safe to assume that developers *never* call these APIs, even though it is technically feasible.**"
   → 反射进入 = 主动站到官方「零承诺」区；每次升级都是 break window。
3. **@RestrictTo(LIBRARY_GROUP) = 私有、不入二进制兼容跟踪**（[modules.md:237](https://github.com/androidx/androidx/blob/androidx-main/docs/api_guidelines/modules.md#L237)）；**软移除流程**把 API 从 current.txt 挪入 restricted_current.txt 继续做**库间**兼容检查（[deprecation.md:45-52](https://github.com/androidx/androidx/blob/androidx-main/docs/api_guidelines/deprecation.md#L45)）；restricted 面按版本归档（[onboarding.md:584-588](https://github.com/androidx/androidx/blob/androidx-main/docs/onboarding.md#L584)）——即官方对「内部」的执法是**构建期 API 面检查 + 文档缺席**，不是运行时拦截。
4. **是否存在官方「你们该不该反射」的 discussion**：本会话检索通道受限（issuetracker 为 JS 应用无法抓取；web 搜索端点不可用），未找到直接命中的官方声明。以上 api-guidelines 条文即官方最接近的表态：**技术上拦不住，契约上视为不存在**。@RestrictTo 注解本体定义见 [androidx.annotation.RestrictTo](https://developer.android.com/reference/androidx/annotation/RestrictTo)（LIBRARY / LIBRARY_GROUP / LIBRARY_GROUP_PREFIX 三级）。

---

## 7. 能力边界结论矩阵（渲染前计算能力 × 推荐路径排序）

排序约定：**公开 API > 公开组合 > internal 稳定签名+反射降级 > 纯反射**；R8 列标注 release 混淆下是否需要 keep/探测。

| 能力（渲染前语义） | 推荐实现路径排序 | R8 风险 | 版本风险 |
|---|---|---|---|
| ① 测量遍改上报高度（延迟揭示，永不放置未补偿几何） | **公开 API**：Modifier.layout{} + clipToBounds（现行 deferredRevealCompensation，零反射） | 无 | 无（1.0 契约） |
| ② 帧界注入视窗位移（measure 遍首生效） | **公开组合**：withFrameNanos 帧界排空 + dispatchRawDelta/requestScrollToItem（现行 PreRenderShiftChannel 主体，零反射） | 无 | 低 |
| ③ 不取消 fling 的定位（SSE 期间免顿挫） | **internal 稳定签名+反射降级**（现行 LazyListReflection：字段+public-on-internal 方法，均有 keep+探测）→ 降级=官方 requestScrollToItem | **有**：keep 规则必须保留（proguard:47-48）+ release 冒烟 | **中**：value class 表示巧合（§3.4）+ 混名纪律 |
| ④ 同步重测单节点 | **公开 API**：自实现 RemeasurementModifier 挂链收 forceRemeasure() | 无 | 无（1.0 起） |
| ⑤ 同步重测 LazyList 整体 | **公开组合**：requestScrollToItem（等价触发待定位+失效） ≻ **反射** LazyListState.remeasurement 字段调 forceRemeasure() | 组合路径无；反射路径需补 keep | 组合低；反射中 |
| ⑥ 绘制前同步消费 delta、免全量重测（官方 onScroll 内部组合） | **纯反射**：LazyListMeasureResult#copyWithScrollDeltaWithoutRemeasure(Int,Boolean) + remeasurement.forceRemeasure()——**不推荐**（internal 类型连签名都不可命名；等价收益可由 ②+① 的延迟揭示达成） | **高**：须 keep internal 类（面更大） | 高 |
| ⑦ 预测量（先量后组） | **公开 API**：SubcomposeLayout | 无 | 无 |
| ⑧ 同帧双遍预测（lookahead） | **公开 API**：LookaheadScope/Modifier.approachLayout（1.6.0 稳定）——注意 API 面仍年轻（intermediateLayout 已整体退场的先例，§2.8） | 无 | 中（面会动） |
| ⑨ 锚点 item 移动跟随 | **框架自动**：不干预（无公开入口也无需求） | — | — |
| ⑩ 滚动互斥/优先级 | **公开 API**：MutatorMutex/Animatable（foundation/animation-core 1.0 起） | 无 | 无 |

**三条运维红线**（写给 PreRenderCoordinator 接入者）：
1. **新增反射目标 = 三件套齐上**：proguard keep + init 探测降级 + release 冒烟日志；缺一不入。
2. **升级 BOM = javap 核销**：对 proguard-rules.pro keep 的类跑一遍 javap -p，核对三成员的字节码名与字段类型（本报告 §1/§3.1 即现成命令模板）；源码 grep 不能替代（R4 反例）。
3. **不碰有断言的内部字段**（R5）：新目标先 grep checkPrecondition 断言网，scrollToBeConsumed 是前车之鉴（真机 FATAL 实证）。

---

## 8. 引用清单

androidx 源码（androidx-main @ b0e5f9fe34e7，GitHub mirror 与行号一致；等价 cs.android.com 格式：https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:<path>）：
- RemeasurementModifier/Remeasurement（public，1.0-beta02 起）：compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/RemeasurementModifier.kt#L27-L48
- LazyListState：scrollPosition:231 · scrollToBeConsumed:301 · remeasurementModifier:332-337 · scrollToItem:448 · measurementScopeInvalidator:452 · requestScrollToItem:469-476 · snapToItemIndexInternal:482-506 · scroll:516 · dispatchRawDelta:526 · onScroll+断言:554-559 · copyWith…调用:571,579,606 · updateScrollPositionIfTheFirstItemWasMoved:729 → compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt
- LazyListScrollPosition（internal class）：…/lazy/LazyListScrollPosition.kt#L32,L81,L95
- LazyListMeasureResult（internal class + copy 方法）：…/lazy/LazyListMeasureResult.kt#L30,L100
- ObservableScopeInvalidator（@JvmInline internal value class）：…/lazy/layout/ObservableScopeInvalidator.kt#L29-L39
- LookaheadScope/approachLayout：compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/LookaheadScope.kt#L48,L102,L203
- API 面：compose/ui/ui/api/1.0.0-beta02.txt#L2277-L2282（Remeasurement）· 1.5.0-beta01→1.6.0-beta01（Lookahead 稳定化/intermediateLayout Experimental）· foundation/api/1.7.0-beta01.txt（requestScrollToItem 首现）· animation-core/api/current.txt · foundation/api/current.txt#L330（MutatorMutex）
- 官方政策：docs/api_guidelines/annotations.md#L132-L167 · modules.md#L237 · deprecation.md#L45-L52 · onboarding.md#L584-L588
- 发布物：[BOM 2026.08.00 POM](https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.08.00/compose-bom-2026.08.00.pom)（→foundation 1.12.0）· [BOM 2026.05.01 POM](https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.05.01/compose-bom-2026.05.01.pom)（→foundation 1.11.2）· foundation-android-{1.5.0,1.7.0,1.9.0,1.11.2,1.12.0}-sources.jar/.aar（https://dl.google.com/android/maven2/androidx/compose/foundation/foundation-android/<v>/）
- 参考：[RestrictTo 官方参考](https://developer.android.com/reference/androidx/annotation/RestrictTo) · [R8 shrink 文档](https://developer.android.com/build/shrink-code) · [Kotlin visibility（internal 字节码混名）](https://kotlinlang.org/docs/visibility.html)

本仓：
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ScrollCompensation.kt:192-282（LazyListReflection）· :43-134（DeferredRevealCompensator）· :146-190（deferredRevealCompensation layout）
- …/PreRenderShiftChannel.kt:46-139（帧界排空+降级阶梯）· …/JumpNavigationController.kt:297-303（官方路径裁决）· …/ChatMessageList.kt:486-520
- app/proguard-rules.pro:45-48（keep 规则）· app/build.gradle.kts:131,187
- commit：b2cee821（keep+反射引入）· 34092594（#43 探测降级）· 6078029f（#258 换道删除 scrollToBeConsumed 直写）· 064c47fc（expand 家族退役收敛）
- 关联文档：docs/research/pre-render-coordinator/04-internal-inventory.md（本仓侧盘点）· docs/research/sse-scroll-stability-iron-laws.md（铁律）
