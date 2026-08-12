package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跳转定位状态机（架构根治 2026-08-13——架构评审候选①+②+③）。
 *
 * 根因（"重复乱跳"）：旧流程"移动目标"（视口中部→顶部两次定位）→ 目标滚出
 * 视口 → LazyColumn 回收 → MarkdownState 重建 → 重新解析（214）→ 稳定（331）
 * → 收敛修正又移动 → 振荡循环。
 *
 * 根治：**一次定位到最终位置**——目标进入视口后不再移动（不回收、不重建、
 * 不重测），测量/收敛都在目标位置进行；收敛只做小位移。
 *
 * 状态机（决策可单测）：
 *   Idle → Preparing(蒙版: 预解析+估算定位) → Measuring(透明: 测量+列表同步)
 *        → Settling(收敛修正) → Displayed(显示+稳定窗口 1.5s) / Failed(超时)
 *
 * UI 派生（单一真相源——蒙版/门控不再各自为政）：
 *   - showMask = Preparing || Measuring || Settling
 *   - gateOpen = Displayed || Failed
 */
sealed interface JumpPhase {
    data object Idle : JumpPhase
    data class Preparing(val msgId: String) : JumpPhase
    data class Measuring(val msgId: String) : JumpPhase
    data class Settling(val msgId: String) : JumpPhase
    data class Displayed(val msgId: String) : JumpPhase
    data class Failed(val msgId: String, val reason: String) : JumpPhase
}

/** 状态转移事件（纯逻辑单测入口）。 */
internal sealed interface JumpEvent {
    data object PrepareStarted : JumpEvent
    data object ParsedReady : JumpEvent          // 预解析完成
    data object MeasureReady : JumpEvent         // 列表尺寸稳定（Ready + 同步）
    data object Settled : JumpEvent              // 收敛完成
    data class TimedOut(val stage: String) : JumpEvent
    data object Abort : JumpEvent
}

// ============ 纯函数（单测目标——本会话反复出错的计算） ============

/** 目标底边距视口底部的目标偏移：顶边贴视口顶（含 contentPaddingTop 修正）。 */
internal fun computeDesiredOffset(viewportHeight: Float, itemHeight: Float, contentPaddingTop: Float): Float =
    viewportHeight - itemHeight - contentPaddingTop

/** 顶边偏差：0 = 贴视口顶；正 = 超出（顶边在视口上方）。 */
internal fun computeGap(itemOffset: Int, itemSize: Int, viewportHeight: Float, contentPaddingTop: Float): Float =
    itemOffset + itemSize - (viewportHeight - contentPaddingTop)

/** 状态转移（纯函数——事件驱动）。 */
internal fun jumpTransition(current: JumpPhase, event: JumpEvent): JumpPhase = when (event) {
    is JumpEvent.PrepareStarted -> when (current) {
        is JumpPhase.Idle -> JumpPhase.Preparing(currentMsgIdOf(current))
        else -> current
    }
    is JumpEvent.ParsedReady -> when (current) {
        is JumpPhase.Preparing -> JumpPhase.Measuring(current.msgId)
        else -> current
    }
    is JumpEvent.MeasureReady -> when (current) {
        is JumpPhase.Measuring -> JumpPhase.Settling(current.msgId)
        else -> current
    }
    is JumpEvent.Settled -> when (current) {
        is JumpPhase.Settling -> JumpPhase.Displayed(current.msgId)
        else -> current
    }
    is JumpEvent.TimedOut -> when (current) {
        is JumpPhase.Preparing -> JumpPhase.Failed(current.msgId, "预解析超时(${event.stage})")
        is JumpPhase.Measuring -> JumpPhase.Failed(current.msgId, "测量超时(${event.stage})")
        is JumpPhase.Settling -> JumpPhase.Failed(current.msgId, "收敛超时(${event.stage})")
        else -> current
    }
    is JumpEvent.Abort -> JumpPhase.Idle
}

private fun currentMsgIdOf(current: JumpPhase): String = when (current) {
    is JumpPhase.Preparing -> current.msgId
    is JumpPhase.Measuring -> current.msgId
    is JumpPhase.Settling -> current.msgId
    is JumpPhase.Displayed -> current.msgId
    is JumpPhase.Failed -> current.msgId
    else -> ""
}

// ============ 控制器（状态机 + 执行器） ============

/** 组合中访问跳转状态机（ChatMessageList 提供）。 */
val LocalJumpController = androidx.compose.runtime.staticCompositionLocalOf<JumpNavigationController> {
    error("LocalJumpController not provided")
}

/**
 * 跳转定位控制器——状态机编排（决策）+ 执行器（滚动/收敛细节）。
 * 状态转移纯逻辑可单测；执行器（requestScroll 取反/scrollBy/await）模拟器回归。
 */
class JumpNavigationController(
    private val listState: LazyListState,
    private val readiness: RenderReadinessRegistry,
    private val scope: CoroutineScope,
) {
    private val _phase = MutableStateFlow<JumpPhase>(JumpPhase.Idle)
    val phase: StateFlow<JumpPhase> = _phase

    /** 当前跳转目标 msgId（MessageCardUser 门控判断用）。 */
    var currentTargetMsgId: String? = null
        private set

    /** UI 派生：蒙版显示（Preparing/Measuring/Settling）。 */
    val showMask: Boolean
        get() = _phase.value is JumpPhase.Preparing ||
            _phase.value is JumpPhase.Measuring ||
            _phase.value is JumpPhase.Settling

    /** UI 派生：目标可显示（Displayed/Failed——settled 取代）。 */
    val gateOpen: Boolean
        get() = _phase.value is JumpPhase.Displayed || _phase.value is JumpPhase.Failed

    /** 跳转（快速导航——user 消息目标）。 */
    fun jumpTo(msgId: String, lazyIndex: Int, preParseText: String?) {
        currentTargetMsgId = msgId
        _phase.value = JumpPhase.Preparing(msgId)
        scope.launch {
            // Preparing：预解析（后台）→ ParsedReady
            if (preParseText != null) readiness.preParse(msgId, preParseText, scope)
            val parsed = withTimeoutOrNull(2500) {
                readiness.flow(msgId).first { it is RenderReadiness.Parsed || it is RenderReadiness.Failed }
            }
            if (parsed == null) {
                _phase.value = jumpTransition(_phase.value, JumpEvent.TimedOut("parsing"))
                return@launch
            }
            _phase.value = jumpTransition(_phase.value, JumpEvent.ParsedReady)
            // Measuring：一次定位到最终位置（估算高度——目标不再移动，避免回收振荡）
            measureAndSettle(msgId, lazyIndex)
        }
    }

    /** 定位发起卡片（assistant 目标——同状态机，参数化目标）。 */
    fun jumpToTask(lazyIndex: Int, targetMsgId: String) {
        currentTargetMsgId = targetMsgId
        _phase.value = JumpPhase.Preparing(targetMsgId)
        scope.launch {
            // 无预解析（assistant 目标）——直接进入测量
            _phase.value = jumpTransition(_phase.value, JumpEvent.ParsedReady)
            measureAndSettle(targetMsgId, lazyIndex)
        }
    }

    /** 复位（跳转结束/失败后）。 */
    fun reset() {
        currentTargetMsgId = null
        _phase.value = JumpPhase.Idle
    }

    /**
     * 测量 + 收敛（目标在最终位置——一次定位）。
     * 估算高度定位 → 透明测量（Ready + 列表同步）→ 收敛小修正 → Displayed + 稳定窗口。
     */
    private suspend fun measureAndSettle(msgId: String, lazyIndex: Int) {
        val vh = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toFloat()
        val contentPaddingTop = -listState.layoutInfo.viewportStartOffset.toFloat()

        // 一次定位：估算高度（无测量——目标未进入视口）——目标直接到最终位置附近。
        // **注意**：requestScroll 的 scrollOffset 在 reverse 布局取反（实测 req=347
        // → offset=-278）→ 传负值。估算偏差由收敛修正（小位移——目标不滚出视口）。
        val estimatedHeight = 240f
        val desired = computeDesiredOffset(vh, estimatedHeight, contentPaddingTop)
        LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, -(desired.toInt()))
        kotlinx.coroutines.delay(32)  // 等 2 帧（约 16ms/帧）——非组合环境用 delay 替代 withFrameNanos

        // Measuring：等待 Ready（组件测量稳定）+ 列表尺寸同步（Mikepenz 渐进测量
        // 214→331——列表布局与组件测量可能不同步）
        val ready = withTimeoutOrNull(2500) { readiness.awaitReady(msgId, 2500) }
        if (ready == null) {
            _phase.value = jumpTransition(_phase.value, JumpEvent.TimedOut("measuring"))
            return
        }
        withTimeoutOrNull(1500) {
            while (true) {
                val syncItem = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.key == "u_$msgId" || it.key == "t_$msgId"
                }
                if (syncItem != null && syncItem.size >= ready.finalHeight - 2) break
                kotlinx.coroutines.delay(50)
            }
        }
        _phase.value = jumpTransition(_phase.value, JumpEvent.MeasureReady)

        // Settling：收敛小修正（目标在视口内——不回收——修正后稳定）
        var converged = 0
        var settled = false
        for (round in 1..6) {
            kotlinx.coroutines.delay(150)
            listState.scroll {
                val info3 = listState.layoutInfo
                val it3 = info3.visibleItemsInfo.firstOrNull { it.key == "u_$msgId" || it.key == "t_$msgId" }
                if (it3 != null) {
                    val vh3 = (info3.viewportEndOffset - info3.viewportStartOffset).toFloat()
                    val pt3 = -info3.viewportStartOffset.toFloat()
                    val gap3 = computeGap(it3.offset, it3.size, vh3, pt3)
                    if (BuildConfig.DEBUG) {
                        AppLogger.d("ChatPaging", "jump: 收敛 round=$round gap=$gap3 size=${it3.size}")
                    }
                    if (kotlin.math.abs(gap3) > 2f) {
                        scrollBy(gap3)
                        converged = 0
                    } else {
                        converged++
                    }
                }
            }
            if (converged >= 2) { settled = true; break }
        }
        if (!settled) {
            _phase.value = jumpTransition(_phase.value, JumpEvent.TimedOut("settling"))
            return
        }
        _phase.value = jumpTransition(_phase.value, JumpEvent.Settled)
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: Displayed（收敛完成）")

        // 稳定窗口：显示后 1.5s 静默监控——gap 变化（SSE/布局重测量）则静默修正
        for (round in 1..10) {
            kotlinx.coroutines.delay(150)
            listState.scroll {
                val info4 = listState.layoutInfo
                val it4 = info4.visibleItemsInfo.firstOrNull { it.key == "u_$msgId" || it.key == "t_$msgId" }
                if (it4 != null) {
                    val vh4 = (info4.viewportEndOffset - info4.viewportStartOffset).toFloat()
                    val pt4 = -info4.viewportStartOffset.toFloat()
                    val gap4 = computeGap(it4.offset, it4.size, vh4, pt4)
                    if (kotlin.math.abs(gap4) > 2f) scrollBy(gap4)
                }
            }
        }
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 稳定窗口结束")
    }
}
