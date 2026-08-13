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
    /** 2026-08-13：按 msgId 解析最新 lazy index（displayItems 变化后旧 index 失效——
     * SSE 插入新消息会改变目标 index——轮询 item=null 时重定位用）。 */
    private val resolveLazyIndex: (String) -> Int?,
) {
    /** 2026-08-13 根治"定位到回复"：目标 key 前缀——user 目标只匹配 "u_"，
     * assistant 目标（onLocateTask）只匹配 "t_"——t_/u_ 同 id 时不再歧义
     *（旧逻辑 `u_ || t_` firstOrNull 会匹配到同 id 的 assistant turn）。 */
    private var targetKeyPrefix: String = "u"

    private fun targetKey(msgId: String): String = "${targetKeyPrefix}_$msgId"
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
        targetKeyPrefix = "u"
        currentTargetMsgId = msgId
        _phase.value = JumpPhase.Preparing(msgId)
        scope.launch {
            // Preparing：预解析（后台）→ ParsedReady
            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: Preparing 开始 msg=${msgId.take(12)}")
            if (preParseText != null) readiness.preParse(msgId, preParseText, scope)
            val parsed = withTimeoutOrNull(2500) {
                readiness.flow(msgId).first { it is RenderReadiness.Parsed || it is RenderReadiness.Failed }
            }
            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 预解析 ${if (parsed != null) "完成" else "超时"}")
            if (parsed == null) {
                _phase.value = jumpTransition(_phase.value, JumpEvent.TimedOut("parsing"))
                return@launch
            }
            _phase.value = jumpTransition(_phase.value, JumpEvent.ParsedReady)
            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 进入测量 msg=${msgId.take(12)} idx=$lazyIndex")
            // Measuring：一次定位到最终位置（估算高度——目标不再移动，避免回收振荡）
            measureAndSettle(msgId, lazyIndex)
        }
    }

    /** 定位发起卡片（assistant 目标——同状态机，参数化目标）。 */
    fun jumpToTask(lazyIndex: Int, targetMsgId: String) {
        targetKeyPrefix = "t"
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
        // Measuring 定位：**底部对齐**（requestScroll offset=0——目标底边贴视口
        // 底——一定在视口内，不会因估算高度偏差滚过头/丢失；蒙版遮住后续移动）
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 底部定位 idx=$lazyIndex")
        LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, 0)
        kotlinx.coroutines.delay(32)  // 等 2 帧（约 16ms/帧）——非组合环境用 delay 替代 withFrameNanos
        if (BuildConfig.DEBUG) {
            val vis = listState.layoutInfo.visibleItemsInfo.map { "${it.index}:${it.key}" }.take(12)
            AppLogger.d("ChatPaging", "jump: 底部定位后可见=[$vis]")
        }

        // ===== 渐进定位（2026-08-13 根治——窗口模式实测暴露结构性问题） =====
        // 根因：一次大滚动（vh - H - pt ≈ 1477px）把视口顶部换成大量未组合内容
        // → Markdown 渐进测量（214→331 级跳变）→ 目标被推 → 滚出视口 → item
        // 回收 → 重建重解析 → 振荡（headless 布局时序恰好未触发，窗口模式暴露）。
        // 根治：目标从视口底部**小步逼近**顶部（每步 ≤ vh/2——新进入视口的内容
        // 少、渐进测量量小、稳定快）；每步后等待**区域稳定**（全部可见 item 的
        // key:size 签名连续 4 轮不变——目标及其上下邻居都在渐进测量中也不误判）；
        // 目标全程在视口内（不回收、不重建）——机制上消除振荡。
        var lastRegionSig: String? = null
        var stableCount = 0
        var nullStreak = 0
        var lastRelocateAt = 0L
        var settled = false
        var finalHeight = -1
        withTimeoutOrNull(5000) {
            while (true) {
                kotlinx.coroutines.delay(100)
                listState.scroll {
                    val info = listState.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.key == targetKey(msgId) }
                    if (item == null) {
                        // 防御：极端布局下目标被推出——节流重定位（底部对齐——
                        // 目标回视口内重新渐进）
                        nullStreak++
                        val now = System.currentTimeMillis()
                        if (nullStreak >= 2 && now - lastRelocateAt > 300) {
                            val freshIndex = resolveLazyIndex(msgId) ?: lazyIndex
                            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 重定位 idx=$freshIndex（null=$nullStreak）")
                            LazyListReflection.requestScrollToItemNoCancel(listState, freshIndex, 0)
                            lastRelocateAt = now
                            nullStreak = 0
                        }
                        lastRegionSig = null
                        stableCount = 0
                        return@scroll
                    }
                    nullStreak = 0
                    // 区域签名：全部可见 item 的 key:size——任何 item（含邻居）的
                    // 渐进测量都打破稳定，避免"目标稳定但邻居在变"的误判
                    val regionSig = info.visibleItemsInfo
                        .sortedBy { it.index }
                        .joinToString("|") { "${it.key}:${it.size}" }
                    if (regionSig != lastRegionSig) {
                        lastRegionSig = regionSig
                        stableCount = 0
                        return@scroll
                    }
                    stableCount++
                    if (stableCount < 4) return@scroll
                    // 区域稳定（连续 4 轮签名不变）——计算目标顶边偏差
                    val vhNow = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                    val ptNow = -info.viewportStartOffset.toFloat()
                    val gapToTop = computeGap(item.offset, item.size, vhNow, ptNow)
                    if (BuildConfig.DEBUG) {
                        AppLogger.d("ChatPaging", "jump: 渐进 gap=$gapToTop size=${item.size} region=[$regionSig]")
                    }
                    finalHeight = item.size
                    if (kotlin.math.abs(gapToTop) <= 2f) {
                        settled = true
                        return@scroll
                    }
                    // 小步滚动：偏差 ≤ vh/2 时一步到位；否则步进 vh/2（新内容可控）
                    val step = when {
                        gapToTop < 0 -> maxOf(-(vhNow / 2).toInt(), gapToTop.toInt())
                        else -> minOf((vhNow / 2).toInt(), gapToTop.toInt())
                    }
                    if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 渐进步进 step=$step")
                    scrollBy(step.toFloat())
                    lastRegionSig = null
                    stableCount = 0
                }
                if (settled) break
            }
        }
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 布局稳定 ${if (finalHeight >= 0) "finalHeight=$finalHeight" else "超时"}")
        if (!settled) {
            _phase.value = jumpTransition(_phase.value, JumpEvent.TimedOut("measuring"))
            return
        }
        // 渐进定位完成 = 测量稳定 + 收敛完成（目标已贴视口顶）——状态机直通
        _phase.value = jumpTransition(_phase.value, JumpEvent.MeasureReady)
        _phase.value = jumpTransition(_phase.value, JumpEvent.Settled)
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: Displayed（渐进定位完成）")

        // 稳定窗口：显示后 1.5s 静默监控——gap 变化（SSE/布局重测量）则静默修正
        for (round in 1..10) {
            kotlinx.coroutines.delay(150)
            listState.scroll {
                val info4 = listState.layoutInfo
                val it4 = info4.visibleItemsInfo.firstOrNull { it.key == targetKey(msgId) }
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
