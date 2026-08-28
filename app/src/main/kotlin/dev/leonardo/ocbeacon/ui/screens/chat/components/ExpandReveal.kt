package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * #241 统一展开/收起过渡（2026-08-28 用户裁决：全部从上到下）——
 * 顶部锚定、高度向下生长/向上收回 + 淡入淡出。所有展开收起面共用，
 * 禁止单面自定义方向（防斜向/左上右下等不一致观感）。
 */
val ExpandEnterTransition: EnterTransition =
    fadeIn() + expandVertically(expandFrom = Alignment.Top)

val ExpandExitTransition: ExitTransition =
    fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)

/** 会话 LazyListState 下传通道（#241 渲染前补偿用）：ChatMessageList 在列表
 *  内容处 provide，展开型组件就地 consume——避免穿 3-5 层签名的模板代码。 */
val LocalChatListState = compositionLocalOf<LazyListState?> { null }

/**
 * #241 展开增量渲染前补偿（一次性延迟揭示，#222 延迟揭示家族同语义）。
 *
 * 用户硬约束（2026-08-27 裁决）：展开把标签行向上顶出视口可以接受为正常行为，
 * **除非**能像流式补偿那样在渲染前移动视窗——事后 animateScrollBy 的可见
 * 滚动动画即「渲染后补偿」，不要。
 *
 * 机制（与 DeferredRevealCompensator 同构，单发简化版）：
 * - 增长遍（tap 展开后首遍测量）：真实高度 = 基线+Δ → 本遍只上报基线（多余
 *   部分被外层 clipToBounds 裁掉，未补偿几何**永不被放置**），同时经
 *   PreRenderShiftChannel.enqueue(+Δ) 入帧界队列——「内容生长 delta 即等量
 *   下移」：帧边界排空注入 request-position 待定位，视窗在下一帧**measure
 *   遍首**应用时下移 Δ（#258 换道：不再反射直写 scrollToBeConsumed，
 *   drag 竞态崩溃根因拆除，视觉时序逐帧不变）；
 * - 揭示遍：遍首待定位置已应用（锚点位移先就位），本遍全量上报真实高度——
 *   揭示与视窗位移严格同遍配对。全程无可见滚动动画。
 *
 * 用法（EventCard 根）：`Modifier.clipToBounds().expandRevealCompensation(...)`
 * ——clip 必须在本 layout **外层**（铁律同 #232）。
 */
internal class ExpandRevealCompensator {
    /** 配对版本号：注入使本节点失效 → 消费遍必然重测（与流式补偿同法）。 */
    var version by mutableStateOf(0)
        private set

    /** 已上报且视窗位移已配对的基准高度。 */
    private var reportedBase = 0

    /** 已注入、待下一遍消费后揭示的增量（可负=收缩上移）。 */
    private var pendingReveal = 0

    /** 是否已测过（区分「条目首次进入视口」与「就地展开」：前者直接全量上报，
     *  后者从 0 基准起全程配对——首帧部分测量（如 Markdown 异步占位）也要
     *  配对，否则首个增量漏注入 = 净漂移，2026-08-27 思考块 -18px 实证）。 */
    private var everMeasured = false

    /**
     * 每遍测量调用。返回 (本遍上报高度, 注入下移量)。
     *
     * **链式逐帧配对**（泛化版）：每遍把「上一遍注入（已在遍首消费）」对齐
     * 揭示（report = 基准+待揭示），本遍新增长/收缩增量继续注入递延——
     * 单帧瞬变（tap 即时展开，两遍配对）与多帧动画（AnimatedVisibility
     * spring 高度、逐帧小增量）同一机制通吃；展开 +Δ 视窗下移、收起 -Δ
     * 视窗上移，位移全部发生在渲染前（用户硬约束 2026-08-27）。
     *
     * @param scrolling 列表滚动中（调用方在 measure 块内同步读
     *   isScrollInProgress，快照零滞后且建立订阅——停滚下降沿自动复测）。
     */
    fun onMeasure(realHeight: Int, scrolling: Boolean): Pair<Int, Int> {
        // 条目首次进入视口（此前从未测过）：直接全量上报，绝不注入——
        // 否则新出现的展开卡会先隐身一帧再跳入。real==0（收起态）**不短路**：
        // 走通用配对（持有旧高 + 注入 -旧高），否则收起裸跟随 = 下坠回归
        // （2026-08-27 思考块 +369 复现实证）。
        if (!everMeasured) {
            everMeasured = true
            reportedBase = realHeight
            return realHeight to 0
        }
        // #258 滚动守卫（#239 holdReveal 同款，expand 家族补装）：滚动中既不
        // 入队也不揭示——真实高度与已配对揭示之差交 clipToBounds 裁剪，视口
        // 零位移；停滚下降沿经 isScrollInProgress 订阅自动复测，走常规配对
        // 恢复。滚动中 tap 展开是默认展开卡片进入视口（everMeasured 分支已
        // 放行）之外唯一的高频注入场景，此前正是 crash 放大器之一。
        if (scrolling) {
            val paired = reportedBase + pendingReveal
            reportedBase = paired
            pendingReveal = 0
            return paired to 0
        }
        val revealHeight = reportedBase + pendingReveal
        val extra = realHeight - revealHeight
        return if (extra != 0) {
            pendingReveal += extra
            version++
            revealHeight to extra
        } else {
            pendingReveal = 0
            reportedBase = realHeight
            realHeight to 0
        }
    }
}

/**
 * [ExpandRevealCompensator] 的 layout 包装（与 deferredRevealCompensation 同构）。
 * 铁律：必须与 clipToBounds 同链且 clip 在本 layout 外层。
 */
internal fun Modifier.expandRevealCompensation(
    listState: LazyListState,
    compensator: ExpandRevealCompensator,
    logTag: String,
): Modifier = this.layout { measurable, constraints ->
    // 订阅版本号：注入 → 本节点失效 → 消费遍重测（配对闭环，见流式补偿同注）
    require(compensator.version >= 0)
    val placeable = measurable.measure(
        constraints.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
    )
    val realHeight = placeable.height
    // measure 块内同步读 isScrollInProgress（快照零滞后；读取同时建立订阅：
    // 停滚下降沿自动失效本节点测量 → 复测恢复配对，同流式家族 #239 机制）。
    val scrolling = listState.isScrollInProgress
    val (reportHeight, injectDelta) = compensator.onMeasure(realHeight, scrolling)
    if (injectDelta != 0) {
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            AppLogger.w(
                "ExpandReveal",
                logTag + " real=" + realHeight + " report=" + reportHeight +
                    " inject=" + injectDelta
            )
        }
        // #258 换道：帧界排队（下一帧 measure 遍首经 request-position 通道应用），
        // 不再反射直写 scrollToBeConsumed——drag 竞态崩溃根因拆除。
        PreRenderShiftChannel.enqueue(listState, injectDelta.toFloat())
    }
    layout(placeable.width, reportHeight) {
        placeable.placeRelative(0, 0)
    }
}
