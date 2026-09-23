# Compose UI「后台组合/测量」可行性调研（60×6 Markdown 大表格冷启动冻结问题）

> 调研日期：2026-09-23 · 目标环境：Compose foundation/ui 1.12.0，BOM 2026.08.00，Kotlin
> 状态：**Q1/Q2/Q3/Q5 核心已确证；Q4 部分**；末节列明本会话未能完成项

## 方法与限制（如实声明）
- 会话内 `web_search` 插件故障（DeepSeek 搜索端点 401），按全局检索策略降级为**直接抓取一手来源**：androidx 官方 GitHub 镜像（`androidx-main` 分支，与发版同源）、AOSP 官方镜像（aosp-mirror）、Google 官方镜像 developer.android.google.cn。
- `issuetracker.google.com` 与 `developer.android.com` 主站本会话网络不可达 → **issuetracker feature-request 编号/状态未能一手核对**，见文末待补清单；其余结论均有源码级证据。
- 源码行号取自 androidx-main 快照（与 1.12.x 同代）。

---

## 一、结论总表

| # | 问题 | 结论 | 依据（一句话） | 来源 |
|---|------|------|---------------|------|
| Q1 | 组合（composition）+测量（measure/layout）能否放后台线程？ | ❌ **不可后台**：官方无 API，架构性绑定主线程 | 官方重组器 KDoc 明文：重组与组合 effects 在「window 的 UI 线程」执行；UI 子组合源码标注 /*@MainThread*/；测量入口是 ViewGroup | [W1][A1][S1][AV1] |
| Q1b | 1.7~1.12 有无『后台组合 UI 树』官方 API（非 Glance/Tiles 类）？ | ❌ **没有** | runtime 虽线程中立（Glance 实证），但 UI 树（UiApplier/LayoutNode）无任何后台组合入口；新式预组合原语也全部主线程 | [G1][S1] |
| Q2 | 文本测量能否后台预计算再复用？ | ⚠️ **有条件** | TextMeasurer 无线程安全承诺；官方明文背书的后台文本布局是 PrecomputedText(Compat)/StaticLayout（View 体系）；Compose 侧无注入复用通道 | [T1][AP1][P1][P2] |
| Q3 | movableContentOf 做『隐藏保活→展开移动』？ | ✅ **语义支持**（移动不重组、保状态）；完全移出组合时 state 被提取保留、可跨处重插；**dispose 时机无公开文档承诺** | KDoc『moves the remembered state and nodes...』；Composer 源码：movable 内容可存在于组合之外且失效照常记录 | [M1][C1] |
| Q4 | 业界先例 | **部分确证**：AsyncLayoutInflater 官方后台 inflate ✔；Glance 用自建 Emittable applier + 后台 Recomposer ✔；RecyclerView 预取/issuetracker 状态 ⏳ | AsyncLayoutInflater KDoc；Glance AppWidgetComposer 源码 | [AL1][G1][G2] |
| Q5 | 官方对『大内容冻结』的推荐模式 | ✅ 有官方路径：**Lazy 虚拟化 + PausableComposition 预组合分帧 + TextMeasurer LRU 缓存/drawWithCache + movableContentOf**；但预组合仍主线程，只是摊开成本 | 官方性能页 + PausableComposition/createPausableSubcomposition 源码 + TextMeasurer KDoc | [PF1][S1][T1][M1] |

**对背景场景的直接回答：240 个单元格的组合+测量本身无法移到后台线程；能移到后台的是它们上游/周边的工作（markdown 解析、表格模型、StaticLayout 文本布局预测），主线程工作量只能靠虚拟化/缓存/预组合分帧来压缩。**

---

## 二、Q1 组合与测量的线程契约 —— ❌ 不可后台

1. **官方重组器绑定 window UI 线程**。`WindowRecomposerFactory.LifecycleAware` KDoc（androidx-main）：
   > "run [recomposition][Recomposer.runRecomposeAndApplyChanges] and composition effects on the **AndroidUiDispatcher.CurrentThread for the window's UI thread**. The associated [MonotonicFrameClock] will only produce frames when the Lifecycle is at least [Lifecycle.State.STARTED]"
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/WindowRecomposer.android.kt （L153-158）
2. **该 dispatcher 就是主线程**（且自带 Choreographer 帧时钟）：
   > "A [CoroutineDispatcher] that will perform dispatch during a [handler] callback or [choreographer]'s animation frame stage... Use **[Main] to obtain a dispatcher for the process's main thread (i.e. the activity thread)**"
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidUiDispatcher.android.kt （L28-32）
   > 推论：主线程被组合+测量占满时，Choreographer 帧回调不来 → **spinner 等一切主线程动画必然冻结**（帧时钟机制使然，非巧合）。
3. **测量的结构性绑定**：Compose 宿主 `AndroidComposeView` 是 ViewGroup（androidMain 源文件即 View 子类），measure/layout 由 ViewRootImpl 主线程回调下传 LayoutNode 树。
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeView.android.kt
4. **UI 子组合也有显式主线程标注**：`createSubcomposition` 源码上方标注 `/*@MainThread*/`（L26-30）。
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/platform/Subcomposition.kt
5. **Q1b 反证对比（runtime 线程中立仅限非 UI applier）**：Glance 在任意 coroutineContext 上跑完整 Compose 组合：
   ```kotlin
   val applier = Applier(root)                      // androidx.glance.Applier : AbstractApplier<Emittable>
   val recomposer = Recomposer(coroutineContext)    // 非 UI 线程上下文
   val composition = Composition(applier, recomposer)
   composition.setContent { ... }
   withContext(BroadcastFrameClock()) { launch { recomposer.runRecomposeAndApplyChanges() } ... }
   ```
   —— https://github.com/androidx/androidx/blob/androidx-main/glance/glance-appwidget/src/main/java/androidx/glance/appwidget/AppWidgetComposer.kt （L207-219）
   其 applier 作用于自建 Emittable 树（"Applier for the Glance composition"，https://github.com/androidx/androidx/blob/androidx-main/glance/glance/src/main/java/androidx/glance/Applier.kt L23-25），产物 RemoteViews——即用户排除在外的「非 UI applier 场景」。**Compose UI 树（UiApplier/LayoutNode/Owner）没有任何官方后台组合入口（1.7~1.12 皆然）。**

## 三、Q2 文本测量线程化 —— ⚠️ 有条件

1. **TextMeasurer 官方承诺**（KDoc 原文）：
   > "Caches layout results internally using an LRU cache to optimize repeated measure calls. ... Reuses the cached layout when changing only draw-affected parameters ... Layout-affecting changes like text, font size, or constraints calculate a new layout."
   > "@param cacheSize sets the maximum number of cached layouts. Match this to the number of distinct layouts calculated repeatedly"（默认 `DefaultCacheSize = 8`，L43）
   **KDoc 全文无『线程安全』字样**（源文件 grep 无 thread 匹配）→ 官方未承诺跨线程可用。『每线程一个 TextMeasurer』是缓存不共享下的可行工程实践，但**非官方文档化支持（灰色，风险自担）**。
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/TextMeasurer.kt （L33-100）
2. **Compose 段落测量的底层**：`AndroidParagraph` 持有 `private val layout: TextLayout`（L145），源码注释直言 "avoids expensive `StaticLayout` passes"（L161-172）——测量成本=StaticLayout 构建，发生在**调用 measure 的线程（主线程）**。
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-text/src/androidMain/kotlin/androidx/compose/ui/text/AndroidParagraph.android.kt
3. **官方明文背书的后台文本布局是 PrecomputedText(Compat)（View 体系）**：
   > "This can be expensive, so **computing this on a background thread before your text will be presented can save work on the UI thread**."（两处同文）
   > "**PrecomputedText is suited to compute on a background thread**"（L528）；另有 "A helper class for computing text layout in background"（L498）
   —— https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/text/PrecomputedTextCompat.java （L364-366/L498-499/L528）
   —— https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/text/PrecomputedText.java （L395-397）
4. **StaticLayout 本身无线程限制声明**（平台源码 grep thread 无匹配），后台构建经 PrecomputedText 官方实现实证可行：
   —— https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/text/StaticLayout.java
5. **边界**：后台构建出的 TextLayoutResult **没有公开通道注入** Compose 文本节点（TextLayoutResult 由 TextMeasurer/Paragraph 内部创建）；官方路线=在 Compose 之外自建文本模型（后台 StaticLayout/PrecomputedText 预测列宽/行高），主线程 Compose 侧按缓存值快速 measure。

## 四、Q3 movableContentOf —— ✅ 移动保状态（官方语义支持）

1. KDoc 原文（`MovableContent.kt` L21-36，五个重载同文）：
   > "**Convert a lambda into one that moves the remembered state and nodes created in a previous call to the new location it is called.**"（内部走 `insertMovableContent`，L41）
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/MovableContent.kt
   → 移动=复用已有 node+remember 状态，不重组、不丢状态。官方示例：`MovableContentColumnRowSample`/`MovableContentMultiColumnSample`（compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/samples）。
2. **完全移出组合时**：runtime 会把 movable 内容的 slot table **提取为 MovableContentState 保留在组合之外**，且期间产生的重组失效会被记录、重插时补齐：
   > "If any recompose scopes are invalidated **while the movable content is outside a composition**, ensure the reference is updated to contain the invalidation."（Composer.kt L1536-1541）
   > "This will schedule with the root composition parent a call to [insertMovableContent] with the correct [MovableContentState] **if one was released in another part of composition**."（Composer.kt L373-377）
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composer.kt
   → 『隐藏保活』两种实现：①留在组合内（alpha 0/0 尺寸）——保活但**测量成本仍占主线程**（Q1）；②提取态（movable 内容无宿主）——不产生测量成本，但重插与补重组仍发生在主线程。**何时彻底 dispose 无公开 KDoc 承诺**（内部实现细节）⏳。
3. keep-alive 先例文章：因搜索插件故障未能核实 URL，⏳ 待补（不排除该用法公开案例稀少，主要先例为响应式双栏移动/Twitter 组合间共享）。

## 五、Q4 业界先例 —— 部分确证

- **AsyncLayoutInflater（官方，View 体系）**KDoc：
  > "Helper class for inflating layouts **asynchronously**... construct an instance ... **on the UI thread** and call inflate(...). The OnInflateFinishedListener will be invoked **on the UI thread**..."
  —— https://github.com/androidx/androidx/blob/androidx-main/asynclayoutinflater/asynclayoutinflater/src/main/java/androidx/asynclayoutinflater/view/AsyncLayoutInflater.java （L39-44）
  对比：View 体系官方出路是「后台 inflate + 主线程挂载」；**Compose UI 没有对应物**（与 Q1 一致）。
- **Glance 后台组合**（Q1-5 源码证据同上 [G1][G2]）：自建 `AbstractApplier<Emittable>` + `Recomposer(coroutineContext)` + `BroadcastFrameClock`；`GlanceComposable` 是独立 composable target（https://github.com/androidx/androidx/blob/androidx-main/glance/glance/src/main/java/androidx/glance/GlanceComposable.kt L20-37）——其树是 RemoteViews 桥，无 LayoutNode/测量管线，**不能用于普通 UI**。Glance for Wear Tiles 模块存在（glance/wear，Glance→Tiles proto），同属非 UI applier。
- **RecyclerView 预取（GapWorker）** ⏳ 本会话未取得源码引文；官方 API 入口：`LayoutManager.setItemPrefetchEnabled`（文档 https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerView.LayoutManager ）。
- **issuetracker**：`issuetracker.google.com` 本会话不可达，compose 后台组合相关 feature request 的编号与状态**未能一手核对** ⏳（不代表不存在；不得引用未核实编号）。

## 六、Q5 官方对『大内容冻结』的推荐模式

1. **Lazy 虚拟化**（官方性能指南，仅组合/测量可见项）：https://developer.android.com/develop/ui/compose/performance/bestpractices 、https://developer.android.com/develop/ui/compose/performance/laziness （本会话经 google.cn 镜像确认页面存在；正文引文 ⏳，镜像返回机器翻译壳）。
2. **官方预组合原语（1.7+，主线程内分帧摊销）**：runtime 公开 `PausableComposition`/`ReusableComposition`/`PausedComposition`；UI 层 `createPausableSubcomposition`（Subcomposition.kt L32-35）+ `SubcomposeLayoutState.PausedPrecomposition`/`PrecomposedSlotHandle`（SubcomposeLayout.kt L50-51 导入实证）。机制=提前组合、暂停、在测量期恢复应用——**仍是主线程**，但能把重内容的组合成本从关键帧挪走（预热窗口执行）。
   —— https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/SubcomposeLayout.kt
3. **TextMeasurer LRU 缓存 + drawWithCache/Canvas 直绘文本**（KDoc：只变更绘制参数时复用布局；官方 draw-text 指南 https://developer.android.com/develop/ui/compose/text/draw-text ）——60×6 表格用 Canvas+缓存的 TextLayoutResult 直绘可绕开 240 个组合节点的开销。
4. **movableContentOf** 保活/移动重内容（见 Q3）。
5. ⏳ lookahead/subcomposition 预组合的更多细节、strong skipping 默认开启（1.8+传闻）、1.12 新能力清单：release notes 全量扫描因镜像机翻壳+截断未能完成，见下节。

## 七、落地建议（针对背景场景）
1. **不可行线**：不要尝试把 Compose 组合/测量丢进 `Dispatchers.Default`（无官方通道，Q1）；也不要指望后台线程把 spinner 动画救活（主线程帧时钟停摆）。
2. **可后台**（非 UI 工作）：markdown 解析→表格 cell 模型构建；**用后台 StaticLayout/PrecomputedText 预测每列宽度与行高**（Q2 官方背书模式），把结果缓存为主线程快速 measure 的约束输入。
3. **主线程减量**：行虚拟化（Lazy）或 Canvas+TextMeasurer 缓存直绘；展开前用 PausableComposition 预热；展开后用 movableContentOf 保活避免二次组合。
4. 预期收益次序：列宽后台预测 ≈ 虚拟化 > 预组合分帧 > movableContentOf 保活。

## 八、待补清单（诚实声明）
- [ ] issuetracker 上 compose 后台 layout/composition 的 feature request 编号与状态（本会话网络不可达）
- [ ] release notes 1.7→1.12 全量核对（google.cn 镜像返回机翻壳且 100k 截断；GFW 主站不可达）
- [ ] RecyclerView GapWorker 预取源码引文；Wear Tiles 渲染器细节
- [ ] movableContentOf keep-alive 公开先例文章 URL；TextMeasurer 线程性官方问答（Slack 存档）
- [ ] BOM 2026.08.00 ↔ ui 1.12.0 映射表核对（maven.google.com/dl.google.com 本会话不可达，采信任务给定环境）
- [ ] UiApplier/LayoutNode 显式线程断言的逐行核对（已证主线程绑定为结构性+/*@MainThread*/标注，未见逐调用 check）

## 来源索引
- [W1] WindowRecomposer.android.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/WindowRecomposer.android.kt
- [A1] AndroidUiDispatcher.android.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidUiDispatcher.android.kt
- [S1] Subcomposition.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/platform/Subcomposition.kt
- [AV1] AndroidComposeView.android.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeView.android.kt
- [G1] AppWidgetComposer.kt（Glance 后台组合）· https://github.com/androidx/androidx/blob/androidx-main/glance/glance-appwidget/src/main/java/androidx/glance/appwidget/AppWidgetComposer.kt
- [G2] glance Applier.kt · https://github.com/androidx/androidx/blob/androidx-main/glance/glance/src/main/java/androidx/glance/Applier.kt
- [T1] TextMeasurer.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/TextMeasurer.kt
- [AP1] AndroidParagraph.android.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-text/src/androidMain/kotlin/androidx/compose/ui/text/AndroidParagraph.android.kt
- [P1] PrecomputedTextCompat.java · https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/text/PrecomputedTextCompat.java
- [P2] PrecomputedText.java（AOSP）· https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/text/PrecomputedText.java
- [M1] MovableContent.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/MovableContent.kt
- [C1] Composer.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composer.kt
- [AL1] AsyncLayoutInflater.java · https://github.com/androidx/androidx/blob/androidx-main/asynclayoutinflater/asynclayoutinflater/src/main/java/androidx/asynclayoutinflater/view/AsyncLayoutInflater.java
- [SL] StaticLayout.java（AOSP）· https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/text/StaticLayout.java
- [GC] GlanceComposable.kt · https://github.com/androidx/androidx/blob/androidx-main/glance/glance/src/main/java/androidx/glance/GlanceComposable.kt
- [SLY] SubcomposeLayout.kt · https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/SubcomposeLayout.kt
- [PF1] 官方性能指南 · https://developer.android.com/develop/ui/compose/performance/bestpractices · https://developer.android.com/develop/ui/compose/performance/laziness
- [DT] 官方 draw-text 指南 · https://developer.android.com/develop/ui/compose/text/draw-text

---

## 附：本地与会话实证（主会话补充，2026-09-24）

> 与上文网络调研互补的本地证据链（均为本会话仪器取证）：

1. **冻结机理实证**：[Spin429 探针] 计算期信号置位后，折叠行 spinner 的重组与重内容 ε 组合排入同一批帧——巨帧（0.7~2.5s）期间 Choreographer 不派发新帧，spinner 冻结（置位→行首次渲染相隔 2.3s，先行帧修复后 10ms）——与 Q1「主线程被组合占满时 Choreographer 不来帧」的源码推论互证。
2. **成本构成实证**（MIUIScout 长帧栈）：2.5s 巨帧栈 = MultiParagraph/TextAnnotatedStringNode（文本排版）← SimpleMarkdownTable 行高测量 ← CardExpandGeometryNode——即 Q2 所述 StaticLayout 成本 + Q1 所述组合/测量主线程成本。
3. **本地字节码核对**（gradle 缓存 1.12.0 AAR javap）：UiApplier extends AbstractApplier&lt;LayoutNode&gt;（树变更器绑定 UI 节点树）；TextMeasurer 为纯类（构造仅 FontFamily.Resolver/Density/LayoutDirection/cacheSize，无 Looper/View 依赖）——与「API 表面无线程绑定痕迹、但官方无线程安全承诺」一致。
4. **已在后台的部分**：markdown 解析（rememberAsyncMarkdownState，Dispatchers.Default，#428 已加跨组合终态缓存）——即「能后台的早已后台」，剩余冻结部分恰为不可后台部分（Q1）。
