package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
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
 *
 * #256 勘误（2026-08-28 真机 logcat 取证）：spring 版在动画尾端被
 * 截断——收起序列最后帧单帧 -31px 残差硬切（前 30 帧均为 -1~-8 平滑
 * 小步，EV-REVEAL real=199 report=230 inject=-31 实证），即用户反馈的
 * 「收起动画完毕后跳一下」。spring 的可见结束阈值导致残余高度一帧丢
 * 失。改 tween：确定性时长精确跑到目标值（0），动画帧与稳态无残差。
 */
val ExpandEnterTransition: EnterTransition =
    fadeIn(animationSpec = tween(180)) +
        expandVertically(animationSpec = tween(180), expandFrom = Alignment.Top)

val ExpandExitTransition: ExitTransition =
    fadeOut(animationSpec = tween(150)) +
        shrinkVertically(animationSpec = tween(150), shrinkTowards = Alignment.Top)

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
     */
    fun onMeasure(realHeight: Int): Pair<Int, Int> {
        // 条目首次进入视口（此前从未测过）：直接全量上报，绝不注入——
        // 否则新出现的展开卡会先隐身一帧再跳入。real==0（收起态）**不短路**：
        // 走通用配对（持有旧高 + 注入 -旧高），否则收起裸跟随 = 下坠回归
        // （2026-08-27 思考块 +369 复现实证）。
        if (!everMeasured) {
            everMeasured = true
            reportedBase = realHeight
            return realHeight to 0
        }
        val revealHeight = reportedBase + pendingReveal
        val extra = realHeight - revealHeight
        // #256 勘误（2026-08-28 真机 logcat 完整帧序列取证）：收缩方向（extra<0，
        // 收起动画）**不再走 1 帧延迟配对**——延迟机制在动画结束帧产生
        // 「report>内容」的底部空白帧（EV-REVEAL real=199 report=229 inject=-30
        // 实证）+ 下一帧闭合位移 = 用户反馈的「收起完毕跳一下」。收缩在
        // reverseLayout 钉底锚定下本就自然平滑（底边钉住、上方内容逐帧涌下
        // 填补），直接同步上报 real + 零注入，交由列表自然锚定。基线同步收缩
        // （后续展开从新基准起配对）。增长方向（extra>0，展开）保持延迟配对
        //（#241 展开顶出保护不变）。
        if (extra < 0) {
            pendingReveal = 0
            reportedBase = realHeight
            version++
            return realHeight to 0
        }
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
