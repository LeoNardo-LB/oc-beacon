package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlin.math.max

/**
 * #262 展开面「渲染前计算」统一组件（spec：docs/specs/2026-08-30-expand-prerender-design.md）。
 *
 * 取代 ExpandReveal 家族（AnimatedVisibility 布局层高度动画 + ExpandRevealCompensator
 * 帧界配对）——四条裁决的落地：
 *
 * 1. **布局层恒为终态（展开侧）**：reveal 首帧 item 高即 finalH，视觉揭示是绘制层
 *    纯 clip 幕布（无 fade、无内容位移）；AnimatedVisibility 从布局层退役。
 * 2. **两路径预移**：
 *    - 缓存命中（二次展开）/ 收起：Δ 在 tap 时刻已知 → tap 处理器内（measure 块外）
 *      直接 dispatchRawDelta / 贴底 request-position 预移 → 下一渲染帧**同帧原子**
 *      落地（视窗已移 + 布局终态）——零帧间错位。
 *    - 首次展开（缓存未命中）：Compose 无测量同步 API → MEASURING 遍 subcompose 测得
 *      finalH（本遍持旧高、多余被 clip 裁掉不放置）→ PreRenderShiftChannel.enqueue
 *      （USER_EXPAND 源，复用已验证的帧界运输层）→ 位移落地遍与全量揭示**严格同遍
 *      配对**——计算遍零渲染，渲染遍一次性终态（8-16ms 配对周期，不可感知）。
 * 3. **收起 = 布局与 clip 同步缓动**（spec §4.3，2026-08-30 用户裁决「下方收上来」
 *    观感）：200ms 内布局高度与幕布同步收缩，动画循环逐帧 dispatchRawDelta(-δ)
 *    （帧回调相，同 drain 时序、measure 块外）——header 锚点每帧静止。
 * 4. **折叠态不组合正文 + finalH 缓存**（保 #258 fling 预算）：IDLE 态 content 不进
 *    组合树；finalH rememberSaveable（contentKey 为键，键变即失效）——二次展开零
 *    延迟 + 展开态卡滚回视口首帧即终高（缓存预留，markdown 异步重解析不再推挤下方）。
 *
 * 语义边界（spec §6）：
 * - 快速连续 toggle：REVEALING 中途收起 = 从当前 fraction 反向缓动；CLOSING 中途
 *   再展开 = content 仍组合、高度已知 → 走 Immediate 路径直接反向。
 * - fling 中 tap：立即执行（7f1777be 拆除的滚动闸门不复活）。
 * - 默认展开卡：首次组合直接 EXPANDED 终态、无动画、无扣留（「滑完才展开」结构性
 *   不复发）；缓存预留使已知高度卡滚入零漂移。
 * - 稳态异步增长（EXPANDED 期内容变高）：沿用配对注入（withhold + enqueue + 落地
 *   揭示）——与今日 EV 行为等价，不回归。
 */
internal enum class PreRenderPhase { IDLE, MEASURING, REVEALING, EXPANDED, CLOSING }

/**
 * 纯状态机（#262 可单测核心，取代 ExpandRevealCompensator 的配对契约）。
 * 不触碰 LazyListState / Android API——运输由 [PreRenderExpandState]/组合件执行。
 *
 * 配对不变量（与 RevealCompensatorsTest 锁定的旧契约同族）：
 * - 未位移的几何**永不被上报**（MEASURING/增长期 report < real，差额经运输层入队）；
 * - version 在每次入队决策时自增（measure 块订阅 → 消费遍必然重测）；
 * - 揭示仅在 shiftSettled 后发生（竞态门：杜绝「揭示先于位移」跳变）。
 */
internal class PreRenderExpandMachine {
    var phase by mutableStateOf(PreRenderPhase.IDLE)
        private set

    /** 配对版本号：入队决策自增 → measure 块订阅失效 → 消费遍重测（旧机制同法）。 */
    var version by mutableStateOf(0)
        private set

    /**
     * 幕布系数：REVEALING 0→1（纯 draw）、CLOSING 1→0（draw + 布局同步缓动）、
     * 其余恒 1/0。measure 块（CLOSING 上报）与 draw（clipRect）双相读。
     */
    var fraction by mutableFloatStateOf(1f)
        private set

    private var reportedContentH = 0
    private var pendingGrowth = 0

    /** [beginExpand] 的启动指令。 */
    internal sealed interface ExpandStart {
        /** 缓存命中：调用方在 tap 处理器内对 listState 施加 deltaPx 预移后进 REVEALING。 */
        data class Immediate(val deltaPx: Int) : ExpandStart

        /** 缓存未命中：进 MEASURING，测量遍配对。 */
        object MeasureFirst : ExpandStart
    }

    /** 每遍测量决策：上报内容高度 / 入队增量 / 是否本遍刚完成首揭示。 */
    internal data class MeasureDecision(
        val reportContentH: Int,
        val enqueueDeltaPx: Int,
        val revealedNow: Boolean,
    )

    /** IDLE 态展开启动：缓存命中给 Immediate（调用方施移），否则 MeasureFirst。 */
    fun beginExpand(cachedContentH: Int): ExpandStart =
        if (cachedContentH > 0) ExpandStart.Immediate(cachedContentH)
        else ExpandStart.MeasureFirst

    fun enterMeasuring() {
        phase = PreRenderPhase.MEASURING
        fraction = 0f
        reportedContentH = 0
        pendingGrowth = 0
    }

    fun enterRevealing() {
        phase = PreRenderPhase.REVEALING
        fraction = 0f
    }

    fun enterExpanded() {
        phase = PreRenderPhase.EXPANDED
        fraction = 1f
    }

    fun beginCollapse() {
        if (phase == PreRenderPhase.REVEALING || phase == PreRenderPhase.EXPANDED) {
            phase = PreRenderPhase.CLOSING
        }
    }

    fun abortMeasuring() {
        if (phase == PreRenderPhase.MEASURING) {
            phase = PreRenderPhase.IDLE
            pendingGrowth = 0
            reportedContentH = 0
            fraction = 0f
        }
    }

    fun closeFinished() {
        if (phase == PreRenderPhase.CLOSING) {
            phase = PreRenderPhase.IDLE
            pendingGrowth = 0
            reportedContentH = 0
            fraction = 0f
        }
    }

    fun onRevealAnimationFinished() {
        if (phase == PreRenderPhase.REVEALING) phase = PreRenderPhase.EXPANDED
    }

    /**
     * 每遍测量调用（content 实际高度 → 上报决策）。
     *
     * @param shiftSettled 运输层无未落地注入（PreRenderShiftChannel.shiftSettled）。
     * @param reservePx 展开态缓存预留高度（重入组合时内容未及缓存终高的占位地板；
     *   0 = 无预留）。
     */
    fun onMeasure(contentH: Int, shiftSettled: Boolean, reservePx: Int = 0): MeasureDecision {
        return when (phase) {
            PreRenderPhase.IDLE -> MeasureDecision(0, 0, false)
            PreRenderPhase.MEASURING -> {
                if (pendingGrowth == 0) {
                    if (contentH <= 0) return MeasureDecision(0, 0, false) // 首遍空测：等待真实高度
                    pendingGrowth = contentH
                    version++
                    MeasureDecision(0, contentH, false) // 持旧高（0）+ 入队全量
                } else if (shiftSettled) {
                    pendingGrowth = 0
                    reportedContentH = contentH
                    phase = PreRenderPhase.REVEALING
                    MeasureDecision(contentH, 0, true) // 位移已落地：同遍全量揭示
                } else {
                    MeasureDecision(0, 0, false) // 竞态门：位移未落地保持裁剪
                }
            }
            PreRenderPhase.REVEALING, PreRenderPhase.EXPANDED -> {
                val floor = if (phase == PreRenderPhase.EXPANDED && reservePx > contentH) reservePx else 0
                val target = max(reportedContentH + pendingGrowth, floor)
                val extra = contentH - target
                return if (extra > 0) {
                    // 稳态异步增长（markdown 迟到解析/流式追加）：withhold + 配对注入
                    pendingGrowth += extra
                    version++
                    MeasureDecision(target, extra, false)
                } else if (pendingGrowth > 0 && !shiftSettled) {
                    MeasureDecision(target, 0, false) // 竞态门
                } else {
                    pendingGrowth = 0
                    reportedContentH = max(contentH, floor)
                    MeasureDecision(reportedContentH, 0, false)
                }
            }
            PreRenderPhase.CLOSING -> {
                // 布局随幕布同步缓动（fraction 由动画循环逐帧写入）；无位移入队——
                // 收起位移由动画循环逐帧 dispatchRawDelta 承担（帧回调相，measure 外）。
                MeasureDecision((fraction * contentH).toInt(), 0, false)
            }
        }
    }

    /** CLOSING 起点系数（可能 <1：揭示中途反向）。动画循环据此算总位移量。 */
    fun collapseStartFraction(): Float = fraction
}

/**
 * 槽位持有态：机器 + 运输副作用（tap 预移）+ finalH 缓存（rememberSaveable 支撑）。
 * EventCard 等六槽位经 [rememberPreRenderExpandState] 取用。
 */
internal class PreRenderExpandState(
    internal val machine: PreRenderExpandMachine,
    internal val listState: LazyListState?,
    private val logTag: String,
    cachedState: androidx.compose.runtime.MutableState<Int>,
) {
    /** 幕布系数的持有点（draw/measure 双相读，动画循环写）。 */
    internal val clipFraction = mutableFloatStateOf(1f)

    /** 最近一次内容实测高度（CLOSING 位移与缓存自愈用；measure 遍写）。 */
    internal var contentHeightPx = 0

    private val cached = cachedState

    /** finalH 缓存（-1 = 未测过）。rememberSaveable：跨组合窗口回收存活。 */
    internal var cachedFinalContentH: Int
        get() = cached.value
        set(value) {
            if (value > cached.value) cached.value = value // 只向上自愈（防陈旧回写）
        }

    /**
     * 槽位 toggle 入口：驱动机器相位并执行 tap 时刻预移（缓存命中路径）。
     * @param currentExpanded 调用方当前逻辑态；返回新逻辑态（调用方写回自己的记忆表）。
     */
    fun toggle(currentExpanded: Boolean): Boolean {
        val next = !currentExpanded
        if (next) {
            when (val start = machine.beginExpand(cachedFinalContentH)) {
                is PreRenderExpandMachine.ExpandStart.Immediate -> {
                    applyTapShift(start.deltaPx)
                    clipFraction.floatValue = 0f
                    machine.enterRevealing()
                    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                        AppLogger.d(
                            "PR-EXPAND",
                            logTag + " immediate d=" + start.deltaPx + " (cache=" + cachedFinalContentH + ")"
                        )
                    }
                }
                PreRenderExpandMachine.ExpandStart.MeasureFirst -> {
                    machine.enterMeasuring()
                    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                        AppLogger.d("PR-EXPAND", logTag + " measure-first (no cache)")
                    }
                }
            }
        } else {
            when (machine.phase) {
                PreRenderPhase.REVEALING, PreRenderPhase.EXPANDED -> machine.beginCollapse()
                PreRenderPhase.MEASURING -> machine.abortMeasuring()
                PreRenderPhase.CLOSING, PreRenderPhase.IDLE -> Unit
            }
        }
        return next
    }

    /**
     * tap 时刻视窗预移（缓存命中路径，measure 块外——同 drain 分支语义）：
     * 贴底 = request-position 预移（off+Δ，下方无余量，上方内容固定）；
     * mid-list = dispatchRawDelta（同步消费、跨 item、无 scrollToBeConsumed 残量）。
     */
    private fun applyTapShift(deltaPx: Int) {
        val ls = listState ?: return
        val atBottomZone = ls.firstVisibleItemIndex == 0 && ls.firstVisibleItemScrollOffset < 120
        try {
            if (atBottomZone) {
                LazyListReflection.requestScrollToItemNoCancel(
                    ls,
                    ls.firstVisibleItemIndex,
                    ls.firstVisibleItemScrollOffset + deltaPx,
                )
            } else {
                ls.dispatchRawDelta(deltaPx.toFloat())
            }
        } catch (t: Throwable) {
            // 降级：预移失败 = 退化为「渲染后推挤一帧」，绝不崩溃（宁推挤不卡渲染）。
            AppLogger.w("PR-EXPAND", logTag + " tap-shift failed: " + t.message)
        }
    }
}

/**
 * 槽位状态工厂。[contentKey] 变更即缓存失效（rememberSaveable 键输入语义）。
 * [initialExpanded]：本次组合起点即展开态（默认展开卡 / 重入视口）——直接 EXPANDED
 * 终态，无动画（spec §6.4 硬约束）。
 */
@Composable
internal fun rememberPreRenderExpandState(
    listState: LazyListState?,
    contentKey: Any,
    logTag: String,
    initialExpanded: Boolean,
): PreRenderExpandState {
    val cachedState = rememberSaveable(contentKey) { mutableStateOf(-1) }
    return remember {
        val machine = PreRenderExpandMachine()
        if (initialExpanded) machine.enterExpanded()
        PreRenderExpandState(machine, listState, logTag, cachedState)
    }
}

/**
 * 展开面组合件：包住可展开正文区（header 由调用方在体外组合——锚点行不属展开域）。
 * IDLE 态 content 不进组合树（#258 fling 预算）；非 IDLE 态 subcompose 于无限高约束
 * 下实测，上报高度由 [PreRenderExpandMachine.onMeasure] 决策。
 */
@Composable
internal fun PreRenderExpand(
    state: PreRenderExpandState,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val machine = state.machine
    val revealAnim = remember { Animatable(1f) }

    // 幕布动画驱动：REVEALING 纯 draw 揭示；CLOSING draw + 布局 + 逐帧位移三同步。
    LaunchedEffect(machine.phase) {
        when (machine.phase) {
            PreRenderPhase.REVEALING -> {
                revealAnim.snapTo(0f)
                revealAnim.animateTo(1f, animationSpec = tween(200, easing = FastOutSlowInEasing)) {
                    state.clipFraction.floatValue = value
                }
                machine.onRevealAnimationFinished()
            }
            PreRenderPhase.CLOSING -> {
                val startFraction = machine.collapseStartFraction()
                var shifted = 0f
                revealAnim.animateTo(0f, animationSpec = tween(200, easing = FastOutSlowInEasing)) {
                    val delta = (state.clipFraction.floatValue - value) * state.contentHeightPx
                    state.clipFraction.floatValue = value
                    // 帧回调相（同 drain 时序、measure 块外）：视窗随布局同步收缩
                    // ——方向与 drain 分支③ 收起负增量一致（下方内容收上来）。
                    if (delta > 0f && state.listState != null) {
                        try {
                            state.listState.dispatchRawDelta(-delta)
                        } catch (t: Throwable) {
                            AppLogger.w("PR-EXPAND", "close-shift failed: " + t.message)
                        }
                        shifted += delta
                    }
                }
                // 浮点累计余量校正（总量 = startFraction × contentH）
                val total = startFraction * state.contentHeightPx
                val residual = total - shifted
                if (residual > 0.5f && state.listState != null) {
                    try {
                        state.listState.dispatchRawDelta(-residual)
                    } catch (_: Throwable) {
                        // 宁推挤不卡渲染
                    }
                }
                machine.closeFinished()
            }
            else -> Unit
        }
    }

    SubcomposeLayout(modifier = modifier.clipToBounds()) { constraints ->
        // 订阅版本号：入队 → 本节点失效 → 消费遍重测（配对闭环，旧机制同法）
        require(machine.version >= 0)
        if (machine.phase == PreRenderPhase.IDLE) {
            val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            layout(w, 0) {}
        } else {
            val placeable = subcompose("pr-expand-content") {
                // 幕布（draw 相读 clipFraction——纯重绘失效，无重组）+ 动画期指针门控
                // （draw clip 不裁剪命中：揭示/收拢 200ms 内不可见内容不可点）。
                ClippedRevealBox(state) { content() }
            }.first().measure(constraints.copy(maxHeight = Constraints.Infinity))
            state.contentHeightPx = placeable.height
            val decision = machine.onMeasure(
                contentH = placeable.height,
                shiftSettled = state.listState?.let { PreRenderShiftChannel.shiftSettled(it) } ?: true,
                reservePx = state.cachedFinalContentH,
            )
            if (decision.enqueueDeltaPx != 0 && state.listState != null) {
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    AppLogger.d(
                        "PR-EXPAND",
                        "reveal-pair real=" + placeable.height + " report=" + decision.reportContentH +
                            " enqueue=" + decision.enqueueDeltaPx + " phase=" + machine.phase
                    )
                }
                PreRenderShiftChannel.enqueue(
                    state.listState,
                    decision.enqueueDeltaPx.toFloat(),
                    PreRenderShiftChannel.ShiftSource.USER_EXPAND,
                )
            }
            if (decision.revealedNow) {
                state.clipFraction.floatValue = 0f
                state.cachedFinalContentH = placeable.height
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    AppLogger.d("PR-EXPAND", "first-reveal h=" + placeable.height + " (cache stored)")
                }
            }
            layout(placeable.width, decision.reportContentH) {
                placeable.placeRelative(0, 0)
            }
        }
    }
}

/** 幕布内容箱：draw 层 clip（fraction 系数）+ 动画期指针吞没。 */
@Composable
private fun ClippedRevealBox(state: PreRenderExpandState, content: @Composable () -> Unit) {
    val animating = state.machine.phase == PreRenderPhase.REVEALING ||
        state.machine.phase == PreRenderPhase.CLOSING
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .drawWithCache {
                val fraction = state.clipFraction
                onDrawWithContent {
                    if (fraction.floatValue >= 1f) {
                        drawContent()
                    } else {
                        clipRect(right = size.width, bottom = size.height * fraction.floatValue) {
                            this@onDrawWithContent.drawContent()
                        }
                    }
                }
            }
            .pointerInput(animating) {
                if (!animating) return@pointerInput
                // 动画期吞没所有指针事件（含滚动手势）——200ms 窗口，结束即解除
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        content()
    }
}
