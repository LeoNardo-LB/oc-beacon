package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.screens.chat.scroll.PreDrawFlushTask

/**
 * #435 流式增长账本 —— 流式家族(消息/工具横幅/压缩卡)高度增长并入高度引擎配对体系。
 * 设计: docs/specs/2026-09-25-435-height-engine-unification-design.md
 *
 * 取代 DeferredRevealCompensator(#222 延迟揭示状态机)+PreRenderShiftChannel(帧界运输层)。
 * 旧体系为「帧界注入」设计(注入落在下一帧遍首),须裁剪未配对几何+version 代计数竞态门
 * +holdReveal 滚动保持;新体系复用引擎 steady 配对(#430)的**同帧**契约——measure 相记账 Δ,
 * 本帧 pre-draw flush(PreRenderCoordinator 单点,K1)按统一配对规则派发:
 * measure→flush→draw 之间不存在可上屏的未配对中间态,裁剪/代计数/帧界排队整体退役。
 *
 * ## 统一配对规则(锚即意图,纯几何)
 *
 * `pair(Δ) ⟺ anchorIndex == itemIndex ∧ anchorOffset > 0`
 * - **画面保持公式（#437 验收四轮，用户数学模型定案）**：不变量
 *   `scrollPos(t) − ΔH(t) = S₀`——settle 后视口相对「settle 时刻内容底」钉死，
 *   新内容全部在视口下方生长（reverseLayout 主轴正方向=向旧内容滚动补偿）。
 * - 贴底原点(fii==0 ∧ fiso==0)：S₀=0，LazyList 物理自动跟随——免派发（#435 实证）。
 * - 增长源==锚 item 自身（itemIndex == anchorIndex，含 fii==itemIndex 的
 *   深处阅读）：增长发生在 item 主轴起边侧（reverseLayout=视觉底/新文本侧），
 *   (fii,fiso) 字面锚定会指向换了身份的新内容（=跟随）——**每帧 +Δ 补偿**
 *   才能让画面纹丝不动。
 * - 增长源在锚之下（itemIndex < anchorIndex）：**免派发（#437 验收八轮真机
 *   像素证伪 ≤ 全域配对）**——LazyList 锚定默认已保持画面（锚 item 字面
 *   (fii,fiso) 不随其下方 item 增长移动）；此时再 +Δ 是双重补偿：实测画面以
 *   每批 Δpx 上拖（派发量精确等于漂移量），且大额 set 触发 LazyList 重锚
 *   回吐（LEAP off 31252→8480，dOff=-22772 恰等于累计派发）→ 乒乓震荡。
 * - 收缩(Δ<0)一律不配对(旧 COMP 行为保持:全揭示 rebase)。防单帧大 Δ 跳变
 *   由 gate 放行量子化承担(≤400ch/批)。
 *
 * ## 48ms 节奏的结构性继承
 *
 * 账本输入 = 48ms 批处理(MessageEventHandler.scheduleFlush)驱动的 measure;
 * flush 派发节奏 = 48ms 批节奏。无任何额外定时器层。
 */
// [VTRACE 2026-09-26] flush 任务的上一帧视口位（变化检测用；主线程独占）
private var vtraceLastFii = Int.MIN_VALUE
private var vtraceLastFiso = Int.MIN_VALUE

internal object StreamingPairingRule {
    /**
     * 统一配对规则(纯函数可单测)。
     * @param anchorIndex 列表锚(firstVisibleItemIndex)
     * @param anchorOffset 锚偏移(firstVisibleItemScrollOffset)
     * @param itemIndex 增长源 item 的 lazy index;-1=不在当前可见布局(回收/间隙)→丢弃
     * @param growthPx 本帧累计增长(px);仅正向增长参与配对
     */
    fun pairedDelta(anchorIndex: Int, anchorOffset: Int, itemIndex: Int, growthPx: Float): Float =
        if (growthPx > 0f &&
            itemIndex >= 0 && // 不在可见布局（回收/间隙）→丢弃
            itemIndex == anchorIndex && // #437 验收八轮（真机像素证伪 ≤）：仅锚=item 自身才配对
            !(anchorIndex == 0 && anchorOffset == 0) // 贴底原点：物理跟随，免派发
        ) growthPx else 0f

    /** 贴底邻域阈值(px)——与 ChatScrollController.isAtBottom 的 fiso<100 同源。 */
    const val AT_BOTTOM_PX = 100
}

/**
 * 每列表单一流式账本(ChatMessageList remember;主线程专用——measure/flush 均在 UI 线程)。
 * per-entryKey 基线;itemKey 用于 flush 相的锚位反查(visibleItemsInfo 按 key 找 index)。
 * entryKey 与 itemKey 分离:同一条目内可并存多个增长源(外层消息包裹/内层压缩卡),
 * 锚位判定共用 itemKey,记账身份各自独立(互斥挂载防嵌套双计,见 ChatMessageList 装配)。
 */
internal class StreamingGrowLedger {

    private class Entry(var itemKey: Any, var baseline: Int, var pending: Float)

    /** entryKey → 账目(基线+待配对累计)。 */
    private val entries = HashMap<Any, Entry>()

    /** 是否存在未决增量(flush 任务零成本早退判据)。 */
    val hasPending: Boolean get() = entries.values.any { it.pending != 0f }

    /**
     * item layout 节点 measure 相调用:记 Δ(增长为正)。冷启动(基线<=0)静默建基线不配对
     * —— 防挂载/回收重入的首次测量产生伪增量。同帧多遍测量:等高遍零增量,增长遍逐次
     * 累计(总和=本帧总增长)。
     */
    fun note(entryKey: Any, itemKey: Any, height: Int) {
        val e = entries.getOrPut(entryKey) { Entry(itemKey, 0, 0f) }
        e.itemKey = itemKey
        if (e.baseline <= 0) {
            e.baseline = height
            return
        }
        val d = height - e.baseline
        e.baseline = height
        if (d > 0) {
            e.pending += d.toFloat()
            if (BuildConfig.DEBUG) {
                // [SGR-435 验收七轮·仪表化] measure 相记账取证
                AppLogger.d("SGR-435", "note ek=" + entryKey + " d=" + d + " pend=" + e.pending)
            }
        }
        // 收缩(d<0):不配对,基线已随上式 rebase(旧 COMP「收缩全揭示」语义)
    }

    /** 节点离树(回收/流式结束/条件关闭):清账目——重入走冷启动,杜绝陈旧基线伪增量。 */
    fun forget(entryKey: Any) {
        entries.remove(entryKey)
    }

    /** 用户滚动让位:弃配全部未决增量(位置神圣;引擎 steady 同款语义)。 */
    fun rebaseAll() {
        entries.values.forEach { it.pending = 0f }
    }

    /**
     * flush 相:统一规则求值全部未决增量并清账,返回应派发总量(px;0=免派发)。
     * @param visibleIndex itemKey → lazy index;-1=不在可见布局(丢弃,下帧增长重新起账)
     */
    fun takePaired(anchorIndex: Int, anchorOffset: Int, visibleIndex: (Any) -> Int): Float {
        var total = 0f
        entries.values.forEach { e ->
            if (e.pending != 0f) {
                val idx = visibleIndex(e.itemKey)
                total += StreamingPairingRule.pairedDelta(anchorIndex, anchorOffset, idx, e.pending)
                e.pending = 0f
            }
        }
        return total
    }
}

/**
 * [StreamingGrowLedger] 的 layout 节点:无界测量取真高 → 记账 → **直报真高**(无裁剪——
 * 同帧 flush 保证未配对几何不上屏,延迟揭示不再需要)。节点式(element 身份相等复用):
 * onDetach 即 forget,回收/卸载自动清基线。
 */
private class StreamingGrowNode : Modifier.Node(), LayoutModifierNode {
    var ledger: StreamingGrowLedger? = null
    var entryKey: Any? = null
    var itemKey: Any? = null

    override fun onDetach() {
        val l = ledger
        val k = entryKey
        if (l != null && k != null) l.forget(k)
        super.onDetach()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
        val h = placeable.height
        val l = ledger
        val ek = entryKey
        val ik = itemKey
        if (l != null && ek != null && ik != null) l.note(ek, ik, h)
        return layout(placeable.width, h) { placeable.placeRelative(0, 0) }
    }
}

private class StreamingGrowElement(
    private val ledger: StreamingGrowLedger,
    private val entryKey: Any,
    private val itemKey: Any,
) : ModifierNodeElement<StreamingGrowNode>() {
    override fun create(): StreamingGrowNode = StreamingGrowNode().apply {
        ledger = this@StreamingGrowElement.ledger
        entryKey = this@StreamingGrowElement.entryKey
        itemKey = this@StreamingGrowElement.itemKey
    }

    override fun update(node: StreamingGrowNode) {
        node.ledger = ledger
        node.entryKey = entryKey
        node.itemKey = itemKey
    }

    override fun equals(other: Any?): Boolean =
        other is StreamingGrowElement &&
            other.ledger === ledger && other.entryKey == entryKey && other.itemKey == itemKey

    override fun hashCode(): Int {
        var result = System.identityHashCode(ledger)
        result = 31 * result + entryKey.hashCode()
        result = 31 * result + itemKey.hashCode()
        return result
    }
}

/**
 * 流式家族增长配对修饰符(#435)。必须与 clipToBounds 同链(沿用旧 COMP 装配约定)。
 * @param ledger 列表级账本
 * @param entryKey 记账身份(唯一;条目内多增长源各自独立)
 * @param itemKey 锚位反查键(Lazy item key;flush 相按它在 visibleItemsInfo 反查 index)
 */
internal fun Modifier.streamingGrowPairing(
    ledger: StreamingGrowLedger,
    entryKey: Any,
    itemKey: Any,
): Modifier = this.then(StreamingGrowElement(ledger, entryKey, itemKey))

/**
 * 流式家族 flush 任务(#435):挂 PreRenderCoordinator 单点(ChatMessageList 常驻注册,
 * 空账本零成本早退;无宿主=预览/单测降级为零配对,与旧通道无泵降级一致)。
 *
 * 顺序:空账早退 → 用户滚动弃配(位置神圣) → 统一规则求值 → applyPairedPreRenderShift
 * (引擎配对执行器:配对到全额)。免派发分支(贴底跟随族/读历史)从构造上零派发
 * ——震荡根源(无贴底豁免的 dispatch)在此消失。
 */


internal fun streamingGrowFlushTask(
    listState: LazyListState,
    ledger: StreamingGrowLedger,
): PreDrawFlushTask = PreDrawFlushTask {
    // [VTRACE 2026-09-26] 逐帧视口轨迹（仅变化时打点）——任何来回跳动在时间线上
    // 直接可读（pair/drop/MSGEFFECT/GUARD/BANNER 行给出成因；用户裁决：精细分析
    // 用日志而非录屏抽帧，瞬态闪烁录屏易漏采）。
    run {
        val fii = listState.firstVisibleItemIndex
        val fiso = listState.firstVisibleItemScrollOffset
        if (fii != vtraceLastFii || fiso != vtraceLastFiso) {
            vtraceLastFii = fii
            vtraceLastFiso = fiso
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "VTRACE",
                    "t=" + android.os.SystemClock.elapsedRealtime() +
                        " fii=" + fii + " fiso=" + fiso + " ip=" + listState.isScrollInProgress
                )
            }
        }
    }
    if (!ledger.hasPending) return@PreDrawFlushTask true
    if (BuildConfig.DEBUG) {
        // [SGR-435 验收七轮·仪表化] flush 相进入取证（含弃配分支可辨）
        AppLogger.d(
            "SGR-435",
            "flush t=" + android.os.SystemClock.elapsedRealtime() +
                " fii=" + listState.firstVisibleItemIndex +
                " fiso=" + listState.firstVisibleItemScrollOffset +
                " ip=" + listState.isScrollInProgress,
        )
    }
    if (listState.isScrollInProgress) {
        ledger.rebaseAll()
        return@PreDrawFlushTask true
    }
    val fii = listState.firstVisibleItemIndex
    val fiso = listState.firstVisibleItemScrollOffset
    val infos = listState.layoutInfo.visibleItemsInfo
    val total = ledger.takePaired(fii, fiso) { ik -> infos.firstOrNull { it.key == ik }?.index ?: -1 }
    if (BuildConfig.DEBUG && total == 0f && infos.isNotEmpty()) {
        AppLogger.d(
            "SGR-435",
            "drop(append/reading-away) t=" + android.os.SystemClock.elapsedRealtime() +
                " fii=" + fii + " fiso=" + fiso
        )
    }
    if (total != 0f) {
        // #437 验收五轮（用户裁决，对齐 #427 引擎先例）：渲染前计算目标位+
        // 反射 requestPosition 写入待定区，由下一遍 measure 原子消费——与
        // dispatchRawDelta（渲染后滚动修正=先画增长态再跳位，整屏闪烁）的
        // 本质区别在「计算先行、measure 原子生效」。拒绘一帧：本帧不画
        // （屏面冻结在增长前帧），下一帧待定位+新高度一次画对。
        // 目标位=用户公式 scrollPos+Δ：fiso += total（锚 item 内偏移推大），
        // 溢出沿可见 items 向 index 增大换算（reverseLayout 视觉向上）。
        var targetFii = fii
        var targetFiso = fiso + total.toInt()
        var targetKey: Any? = null
        var guard = 0
        while (guard++ < 64) {
            val anchor = infos.firstOrNull { it.index == targetFii } ?: break
            if (targetFiso < anchor.size) { targetKey = anchor.key; break }
            targetFiso -= anchor.size
            targetFii++
        }
        LazyListReflection.requestScrollToItemNoCancel(listState, targetFii, targetFiso, targetKey)
        if (BuildConfig.DEBUG) {
            AppLogger.d(
                "SGR-435",
                "pair t=" + android.os.SystemClock.elapsedRealtime() +
                    " d=" + total.toInt() + " set(fii=" + targetFii + ", fiso=" + targetFiso + ")",
            )
        }
        return@PreDrawFlushTask false // 拒绘一帧：待定位经下一遍 measure 一次画对
    }
    true
}

// --- 反射:绕过官方 requestScrollToItem 的 scroll{} 互斥锁取消机制 ---
// 官方 requestScrollToItem(@ExperimentalFoundationApi)做两件事:
//   ① if (isScrollInProgress) scroll {} ← 获取互斥锁,杀死 fling
//   ② scrollPosition.requestPosition + invalidateScope ← 设置待定位置
// 我们只想要 ② —— 设置待定位置而不杀死 fling 惯性。
// #435 后仅剩引擎收起锚点恢复/滚动打断锚点恢复两处调用(#423/#427 路径);
// 流式家族已改走 dispatchRawDelta 同帧配对,不再依赖本反射。
//
// ⚠️ 反射依赖的私有成员(Compose BOM 2026.05.01,见 app/build.gradle.kts:132):
//   - 字段 androidx.compose.foundation.lazy.LazyListState.scrollPosition
//   - 方法 scrollPosition.requestPositionAndForgetLastKnownKey(Int, Int)
//   - 字段 androidx.compose.foundation.lazy.LazyListState.measurementScopeInvalidator: MutableState<Unit>
// Compose 版本升级前必须手动测试这些成员仍存在(AGENTS.md「精准修改」规则)。
// 升级后若成员消失/改名 → 初始化探测一次性失败 → 降级为官方 requestScrollToItem
//(取消 fling 但功能等价、不崩溃)。
// #423 Phase 0:该核销已自动化——LazyListReflectionTest 在 JVM 上对当前 classpath
// 钉死三成员的真实签名,BOM 升级若破坏未装箱巧合,单测先红(先于发版)。
/** #423 Phase 0:反射探针三件套(scrollPosition 字段 / 定位方法 / 失效器字段)。 */
internal data class LazyListProbes(
    val scrollPositionField: java.lang.reflect.Field,
    val requestPositionMethod: java.lang.reflect.Method,
    val invalidatorField: java.lang.reflect.Field,
    /**
     * 2026-09-25 键保持通道（可选）：requestPositionAndForgetLastKnownKey 会遗忘
     * 锚 item 的 lastKnownKey——其后 item 插入/重排按字面 index 重锚 → 大额 set 后
     * 视觉跳变（R9 LEAP 实证）。set 同帧回写与目标位一致的 key 即消除。字段缺失
     * （版本漂移）= 通道降级为旧行为，不影响探针整体可用。
     */
    val lastKnownKeyField: java.lang.reflect.Field?,
)

/**
 * #423 Phase 0(D5):一次性探针解析——抽出为可注入类解析器的顶层函数,JVM 单测
 * 可模拟「类消失/成员签名漂移」验证降级路径(LazyListReflectionTest);
 * 冒烟用例同步钉死当前 BOM 的真实签名(value-class 未装箱巧合,K9)。
 * 任一成员失败 → 返回 null,调用方永久走官方 requestScrollToItem 降级。
 */
internal fun resolveLazyListProbes(
    resolveClass: (String) -> Class<*>? = ::defaultResolveClass,
): LazyListProbes? {
    val stateClass = resolveClass("androidx.compose.foundation.lazy.LazyListState") ?: return null
    val scrollPositionField = lookupField(stateClass, "scrollPosition") ?: return null
    val requestPositionMethod =
        lookupMethod(
            scrollPositionField.type,
            "requestPositionAndForgetLastKnownKey",
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
        ) ?: return null
    val invalidatorField = lookupField(stateClass, "measurementScopeInvalidator") ?: return null
    val lastKnownKeyField = lookupField(scrollPositionField.type, "lastKnownFirstItemKey")
    return LazyListProbes(scrollPositionField, requestPositionMethod, invalidatorField, lastKnownKeyField)
}

private fun defaultResolveClass(name: String): Class<*>? = try {
    Class.forName(name)
} catch (t: Throwable) {
    AppLogger.w("LazyListReflection", "class $name not found: ${t.message}")
    null
}

private fun lookupField(type: Class<*>, name: String): java.lang.reflect.Field? = try {
    type.getDeclaredField(name).apply { isAccessible = true }
} catch (t: Throwable) {
    // NoSuchFieldException / NoSuchFieldError 等
    AppLogger.w("LazyListReflection", "field ${type.name}.$name not found: ${t.message}")
    null
}

private fun lookupMethod(
    type: Class<*>,
    name: String,
    vararg params: Class<*>,
): java.lang.reflect.Method? = try {
    type.getDeclaredMethod(name, *params).apply { isAccessible = true }
} catch (t: Throwable) {
    // NoSuchMethodException / NoSuchMethodError 等
    AppLogger.w("LazyListReflection", "method ${type.name}.$name not found: ${t.message}")
    null
}

internal object LazyListReflection {
    // 一次性探测:失败返回 null,后续永久走降级路径。
    // #423 Phase 0 起经 resolveLazyListProbes 统一解析(可测缝,见 LazyListReflectionTest)。
    private val probes: LazyListProbes? = resolveLazyListProbes()

    /** 批次九轮2:反射探针可用(=引擎收起锚点恢复通道在役)。end-restore 据此让位。 */
    val probesResolved: Boolean get() = probes != null

    // #258 换道手术(2026-08-29):scrollToBeConsumed 反射直写已整体删除(用户 drag 竞态
    // FATAL 定罪)。#435(2026-09-25):流式家族注入通道(PreRenderShiftChannel)退役,
    // 流式增长改走 dispatchRawDelta 同帧配对(StreamingGrowLedger);本反射仅存锚点恢复用途。

    /**
     * 设置待定滚动位置但不取消进行中的 fling(反射路径)。
     * 反射初始化失败或运行时 invoke 抛异常时降级为官方 [LazyListState.requestScrollToItem]
     *@ExperimentalFoundationApi;会取消 fling,但保证位置设置生效、不崩溃)。
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun requestScrollToItemNoCancel(state: LazyListState, index: Int, scrollOffset: Int, key: Any? = null) {
        // smart-cast 友好:局部非空变量
        val p = probes
        if (p != null) {
            try {
                val pos = p.scrollPositionField.get(state)
                p.requestPositionMethod.invoke(pos, index, scrollOffset)
                // 键保持（2026-09-25）：目标位 item 的 key 回写 lastKnownKey——
                // 插入/重排后 LazyList 仍按 key 重锚，消除字面 index 失配跳变。
                if (key != null) {
                    p.lastKnownKeyField?.let { f ->
                        try { f.set(pos, key) } catch (t: Throwable) {
                            AppLogger.w("LazyListReflection", "lastKnownKey set failed: " + t.message)
                        }
                    }
                }
                @Suppress("UNCHECKED_CAST")
                (p.invalidatorField.get(state) as MutableState<Unit>).value = Unit
                return
            } catch (t: Throwable) {
                // IllegalAccessException / IllegalArgumentException / ClassCastException 等
                AppLogger.w("LazyListReflection", "invoke failed, fallback: ${t.message}")
            }
        }
        // 降级:官方 API。语义差异 = 通过 scroll{} 互斥锁取消 fling,可接受。
        state.requestScrollToItem(index, scrollOffset)
    }
}
