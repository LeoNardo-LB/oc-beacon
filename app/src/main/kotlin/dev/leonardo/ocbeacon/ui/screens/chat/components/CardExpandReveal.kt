package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlin.math.abs

/**
 * #420(2026-09-20):可展开卡片的「原地揭示」补偿——单一时钟同帧配对。
 *
 * **问题**:贴底/mid-list 展开卡片时,LazyList(reverseLayout)把 item 高度变化
 * 全额转译为视口位移(实测展开 +644px/收起 −608px,峰值 220px/帧)——用户观感
 * 「界面闪烁跳动」。
 *
 * **机制**:取代 AnimatedVisibility 的尺寸动画——AV 只留 fadeIn/fadeOut(组合
 * 生命周期 + 交叉淡入),几何尺寸由本组件的 [fraction] 时钟驱动:
 * - 动画相每帧:先 [LazyListState.dispatchRawDelta] 本帧位移 δ,再经 measure
 *   上报 f·H —— 位移先行、揭示配对,同帧完成(#262 退役根因「帧界一帧错位」
 *   由此根治:不存在位移晚于高度的一帧)。
 * - δ = f_new·H_last − lastReported(全导数:时钟分量 + 展开中内容迟到增长的
 *   分量,后者带一帧滞后,量级小)。
 * - 收起负向 δ 在贴底不可消费时返回残量(物理不可约:防列表尾露空白)——
 *   该分支视口由上方内容吸收,与现状一致,日志留痕。
 *
 * **竞态矩阵**(详见 backlog #420 明细):
 * - 流式 turn 内的卡片:降级为裸 AV([LocalInStreamingTurn])——item 级
 *   COMP-MSG 补偿独占,杜绝双重注入;
 * - 守卫(guard):展开累计位移越 [DEPARTURE_THRESHOLD_PX] 即回调
 *   [LocalCardExpandDeparture](关 autoScroll,视为离开跟随模式),否则
 *   250ms 后 GUARD 重锚会把用户拽回底部;
 * - 用户滚动:立即取消时钟,snap 至目标(用户阅读位置优先权铁律);
 * - 快速反向 toggle:Animatable 重定向,f 从当前值回摆;
 * - 冷组合:snap 目标值,仅 visible 转换才动画(滑出视口回收/滑回不重播)。
 *
 * **降级**:任一 local 缺席(单测/预览/宿主未提供)→ 出厂 CardExpand 过渡,
 * 行为与 2026-08-30 终局完全一致。
 */

/** 宿主 LazyListState;null = 降级裸 AV。 */
internal val LocalCardExpandListState = compositionLocalOf<LazyListState?> { null }

/** 展开离底时的「离开跟随」回调(实现:关 autoScroll);null = 降级裸 AV。 */
internal val LocalCardExpandDeparture = compositionLocalOf<(() -> Unit)?> { null }

/** 当前 item 是否属于流式 turn(CML 逐 item 提供);true = 降级裸 AV。 */
internal val LocalInStreamingTurn = staticCompositionLocalOf { false }

/** 展开位移离开贴底区(补偿后)判定阈值,对齐 isAtBottom 的 100px 判据。 */
private const val DEPARTURE_THRESHOLD_PX = 100f

private const val GEOMETRY_TWEEN_MS = 240
private const val FADE_TWEEN_MS = 300

/**
 * 纯时钟状态(可单测):fraction 驱动 + 上报高度记账 + δ 计算。
 *
 * 帧内契约:动画相 [advance] → dispatchRawDelta(δ);measure 相 [onMeasure]
 * → 上报 f·H。两相读写的账本字段:[lastReportedH](上一帧上报)与
 * [lastMeasuredH](最近一次实侧高)。
 */
internal class CardExpandClock(initialFraction: Float) {
    /** 揭示分数(0=折叠,1=全展开)。mutableStateOf:measure 读它建订阅。 */
    var fraction by androidx.compose.runtime.mutableFloatStateOf(initialFraction)
        private set

    /** 最近一次 measure 实侧的完整内容高度(px;0=从未测得)。 */
    var lastMeasuredH by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /**
     * 上一帧「已指令」高度(px)——advance 与 onMeasure 双写:
     * advance 写入指令目标,onMeasure 写入实际揭示(舍入)。连续 advance
     * 不经 measure 时账本连续(取消/回摆路径不双计)。
     */
    var lastReportedH = 0
        private set

    /** 动画进行中(冷启动 snap 与取消 snap 不 dispatch)。 */
    var animating = false

    /** 本集累计已位移(px,带符号)。 */
    var episodeDisplacement = 0f
        private set

    var departureFired = false

    /** 动画相调用:推进 fraction,返回本帧应位移的 δ(全导数)。 */
    fun advance(newFraction: Float): Float {
        val target = newFraction * lastMeasuredH
        val delta = target - lastReportedH
        lastReportedH = target.toInt()
        fraction = newFraction
        return delta
    }

    /** measure 相调用:记账并返回上报高度。 */
    fun onMeasure(realH: Int): Int {
        lastMeasuredH = realH
        val report = (fraction * realH).toInt()
        lastReportedH = report
        return report
    }

    /** 取消/冷启动:snap 目标分数(不产生位移)。 */
    fun snap(target: Float) {
        fraction = target
        animating = false
    }

    fun beginEpisode() {
        episodeDisplacement = 0f
        departureFired = false
    }

    fun recordDisplacement(d: Float) {
        episodeDisplacement += d
    }
}

/**
 * 卡片展开面统一包装(#420):原地揭示 + 同帧位移配对。
 * drop-in 替换各卡片的 AnimatedVisibility(CardExpandEnter/Exit);
 * 无作用域限定(任意父容器可用)。
 */
@Composable
internal fun CardExpandReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val listState = LocalCardExpandListState.current
    val departure = LocalCardExpandDeparture.current
    if (listState == null || LocalInStreamingTurn.current) {
        // 降级:出厂过渡(与 2026-08-30 终局一致)
        AnimatedVisibility(
            visible = visible,
            enter = CardExpandEnterTransition,
            exit = CardExpandExitTransition,
            modifier = modifier,
        ) {
            content()
        }
        return
    }

    val clock = remember { CardExpandClock(initialFraction = if (visible) 1f else 0f) }
    val fraction = remember { Animatable(if (visible) 1f else 0f) }

    // visible 转换 → 驱动时钟(冷组合初值即目标,不动画)
    LaunchedEffect(visible) {
        val target = if (visible) 1f else 0f
        if (abs(fraction.value - target) > 0.001f) {
            clock.animating = true
            clock.beginEpisode()
            try {
                fraction.animateTo(target, tween(GEOMETRY_TWEEN_MS))
            } finally {
                clock.animating = false
            }
        }
    }

    // 时钟相:每帧位移先行(δ = 全导数),再由 measure 揭示配对
    LaunchedEffect(fraction, listState) {
        snapshotFlow { fraction.value }
            .collect { newF ->
                if (!clock.animating) return@collect
                val d = clock.advance(newF)
                if (abs(d) < 0.5f) return@collect
                val consumed = runCatching { listState.dispatchRawDelta(d) }.getOrDefault(0f)
                clock.recordDisplacement(consumed)
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "CardExpand",
                        "[DEBUG-420] dispatch d=" + d.toInt() + " consumed=" + consumed.toInt() +
                            " f=" + "%.2f".format(newF) + " H=" + clock.lastMeasuredH
                    )
                }
                if (abs(d - consumed) > 0.5f) {
                    AppLogger.w(
                        "CardExpand",
                        "[DEBUG-420] residual=" + (d - consumed).toInt() +
                            " (list edge; above-content absorbs)"
                    )
                }
                if (!clock.departureFired && clock.episodeDisplacement > DEPARTURE_THRESHOLD_PX) {
                    clock.departureFired = true
                    departure?.invoke()
                }
            }
    }

    // 用户滚动 → 立即取消(阅读位置优先权铁律)
    LaunchedEffect(fraction, listState, visible) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && clock.animating) {
                    if (BuildConfig.DEBUG) {
                        AppLogger.d("CardExpand", "[DEBUG-420] cancel-on-scroll snap f=" + fraction.value)
                    }
                    clock.snap(if (visible) 1f else 0f)
                    fraction.snapTo(if (visible) 1f else 0f)
                }
            }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .cardExpandGeometry(clock)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(FADE_TWEEN_MS)),
            exit = fadeOut(tween(FADE_TWEEN_MS)),
        ) {
            content()
        }
    }
}

/** 几何上报:无界测量 → 上报 f·H(未揭示部分被外层 clip 裁掉,永不放置)。 */
private fun Modifier.cardExpandGeometry(clock: CardExpandClock): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    val report = clock.onMeasure(placeable.height)
    layout(placeable.width, report) {
        placeable.placeRelative(0, 0)
    }
}
