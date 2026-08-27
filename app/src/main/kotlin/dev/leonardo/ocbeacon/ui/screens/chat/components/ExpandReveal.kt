package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import dev.leonardo.ocbeacon.logging.AppLogger

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
 *   LazyListReflection.requestScrollShift(+Δ) 注入 scrollToBeConsumed——
 *   「内容生长 delta 即等量下移」：视窗在下一遍**遍首**消费时下移 Δ；
 * - 揭示遍：遍首注入已消费（锚点位移先就位），本遍全量上报真实高度——
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

    /**
     * 每遍测量调用。返回 (本遍上报高度, 注入下移量)。
     *
     * **链式逐帧配对**（泛化版）：每遍把「上一遍注入（已在遍首消费）」对齐
     * 揭示（report = 基准+待揭示），本遍新增长/收缩增量继续注入递延——
     * 单帧瞬变（tap 即时展开，两遍配对）与多帧动画（AnimatedVisibility
     * spring 高度、逐帧小增量）同一机制通吃；展开 +Δ 视窗下移、收起 -Δ
     * 视窗上移，位移全部发生在渲染前（用户硬约束 2026-08-27）。
     */
    fun onMeasure(realHeight: Int): Pair<Int, Int> {
        // 冷启动：首测全量上报
        if (reportedBase <= 0) {
            reportedBase = realHeight
            return realHeight to 0
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
    val (reportHeight, injectDelta) = compensator.onMeasure(realHeight)
    if (injectDelta != 0) {
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            AppLogger.w(
                "ExpandReveal",
                logTag + " real=" + realHeight + " report=" + reportHeight +
                    " inject=" + injectDelta
            )
        }
        LazyListReflection.requestScrollShift(listState, injectDelta.toFloat())
    }
    layout(placeable.width, reportHeight) {
        placeable.placeRelative(0, 0)
    }
}
