package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CancellationException
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
 * #420 追诊:单帧位移钳制(px)。贴底收起时视口上缘暴露更旧 items,
 * LazyList 组合新 item 的成本可掉帧 100-160ms → withFrameNanos 跳帧 →
 * 墙钟驱动的 tween 单步 δ 暴涨(实测 −250px/帧,观感即「跳变」)。
 * 虚拟时钟以本钳制限速:掉帧时动画自适应拉长而非单步暴涨。
 */
private const val MAX_FRAME_DELTA_PX = 100

/** 虚拟时钟单帧最大时间步(ms)——防大时间窗整体放过。 */
private const val MAX_FRAME_STEP_MS = 20f

/**
 * #425 反馈闭环单帧指令钳制(px):锚点翻转实测阶跃 ≤176px,上限 300
 * 保证纠偏一帧到位又不暴冲;缓动分量另受 [MAX_FRAME_DELTA_PX] 钳制。
 */
private const val MAX_CLOSED_LOOP_DELTA_PX = 300

/** #425 episode 墙钟硬顶(ms):缓动 240ms + 反馈排干余量;真死边(物理不可约)到此为止。 */
private const val EPISODE_HARD_CAP_MS = 1800f

/** 展开预热分数:ε·H<1px(trunc=0)零视觉,仅驱动 content 入树首测。 */
private const val WARMUP_FRACTION = 0.001f

/**
 * #422:settle 判稳帧数——连续 N 帧节点 measure 计数不增即认为内容驱动
 * 的重测已静止。表格 containerWidth(onSizeChanged 回写)两拍收敛、async
 * markdown 解析完成等均属此类;2 帧 @120Hz ≈ 17ms,静默内容零感知。
 */
private const val SETTLE_STABLE_FRAMES = 2

/**
 * #422:settle 墙钟上限(ms)。防内容持续抖动导致展开无限等待;超时即带
 * 当前 H 进入 tween,迟到增量由 episode 末强制复测 + 残差补偿兜底。
 */
private const val MAX_SETTLE_MS = 600f

/**
 * 纯时钟状态(可单测):fraction 驱动 + 上报高度记账 + δ 计算。
 *
 * 帧内契约:动画相 [advance] → dispatchRawDelta(δ);measure 相 [onMeasure]
 * → 上报 f·H。两相读写的账本字段:[lastReportedH](上一帧上报)与
 * [lastMeasuredH](最近一次实侧高)。
 *
 * #422 增补:另承载几何缓存窗口状态——[tweening](placeable 复用唯一生效
 * 窗口)、[measureCount](settle 判稳计数)与 [remeasureEpoch](episode 末
 * 强制复测信号)。
 */
internal class CardExpandClock(initialFraction: Float) {
    /** 揭示分数(0=折叠,1=全展开)。mutableStateOf:measure 读它建订阅。 */
    var fraction by androidx.compose.runtime.mutableFloatStateOf(initialFraction)
        private set

    /** 最近一次 measure 实侧的完整内容高度(px;0=从未测得)。 */
    var lastMeasuredH by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /**
     * 上一帧「已落地」高度(px)——#424 起双态账本:
     * - episode 中(动画相):由 [absorb] 独占写入——只前进 dispatchRawDelta
     *   实际消费的部分(吸收驱动;未消费残量留在下帧指令里自然重试,不再丢失);
     * - 稳定态:跟随内容实测 (fraction×H)。
     * 真机教训([DEBUG-425] 逐帧取证):指令账本会把组合滞后残量与 LazyList
     * 锚点翻转误差全部漏成视口漂移(展开循环实测 −98 平台/收起 −60→−328 阶跃)。
     */
    var lastReportedH = 0
        private set

    /** 吸收账本浮点原子(逐帧取整误差 telescoping ≤1px,不做独立量化器)。 */
    private var absorbedF = 0f

    /** 动画进行中(冷启动 snap 与取消 snap 不 dispatch)。 */
    var animating = false

    /**
     * #422:几何 tween 进行中——placeable 缓存的唯一生效窗口。
     * settle/稳定态恒 false(真测,内容失效得以传播);窗口外的任何 measure
     * 都不得复用缓存 placeable(过期 H = 展开只有一小截 + 结束跳变)。
     */
    var tweening = false

    /**
     * #422:节点 measure 计数(plain,非快照——settle 检测在帧回调里轮询,
     * 不需要失效语义)。settle 阶段以「连续 N 帧计数不增」判定内容驱动
     * 的重测已静止(表格 containerWidth 两拍收敛、asyncParse 完成等)。
     */
    var measureCount = 0
        private set

    /**
     * #422:episode 末强制复测信号(快照写)。节点 measure 读它建立订阅,
     * 写入即失效——保证缓存窗口关闭后至少一次真测,迟到内容增量落地。
     */
    var remeasureEpoch by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** 本集累计已位移(px,带符号)。 */
    var episodeDisplacement = 0f
        private set

    var departureFired = false

    /**
     * 动画相调用:推进 fraction,返回本帧应位移的 δ。
     *
     * #420 追诊定案:δ 必须与布局上报共用同一整型量化器——
     * [(fraction × H).toInt()]——否则 float δ 经滚动侧独立取整,
     * 每帧泄漏 ~0.5px 子像素(实测一展开/收起循环净漂 −26px)。
     * 本方法与 [onMeasure] 的 report 公式严格一致 → 整数守恒。
     */
    fun advance(newFraction: Float): Float {
        val target = (newFraction * lastMeasuredH).toInt()
        val delta = (target - lastReportedH).toFloat()
        lastReportedH = target
        fraction = newFraction
        return delta
    }

    /**
     * measure 相调用:记账实测高度并返回上报高度 (fraction×realH)。
     *
     * #425 教训(死锁):上报高度若做成「实际消费」的奴隶,列表端无增长
     * 空间 → dispatchRawDelta 恒消费 0 → 揭示永不出现(真机 cmd d>0
     * consumed=0 连续 218 帧)。上报必须乐观跟随 fraction(位移先行、
     * 揭示配对的 #420 原契约);**指令基准**才用吸收账本 [absorbedPx]——
     * 未消费残量经「目标−已吸收」在下帧指令里自动重试,不再丢失。
     */
    fun onMeasure(realH: Int): Int {
        lastMeasuredH = realH
        val report = (fraction * realH).toInt()
        lastReportedH = report
        return report
    }

    /** episode 开相:吸收账本以当前位移起账(展开 0 / 收起 H)。 */
    fun primeLedger() {
        absorbedF = (fraction * lastMeasuredH)
    }

    /** 动画相:账本只累计实际消费的位移(未消费部分由下帧指令重试)。 */
    fun absorb(delta: Float) {
        absorbedF += delta
    }

    /** 吸收账本整型视图(指令基准/日志/单测)。 */
    val absorbedPx: Int
        get() = absorbedF.toInt()

    /** 取消/冷启动:snap 目标分数(不产生位移;账本同步防后续 δ 暴冲)。 */
    fun snap(target: Float) {
        fraction = target
        lastReportedH = (target * lastMeasuredH).toInt()
        absorbedF = lastReportedH.toFloat()
        animating = false
    }

    /** 展开预热:ε·H<1px 零视觉,仅驱动 content 入树开始首测。 */
    fun warmup() {
        if (fraction < WARMUP_FRACTION) fraction = WARMUP_FRACTION
    }

    /** #425:动画相推进分数(与位移指令同帧写入——单帧配对的一部分)。 */
    fun driveTo(newFraction: Float) {
        fraction = newFraction
    }

    /** #424:本集内用户滚动取消(cancel-on-scroll 置位)——episode 末闭环位置恢复须跳过。 */
    var userScrollCancelled = false

    fun beginEpisode() {
        episodeDisplacement = 0f
        departureFired = false
        userScrollCancelled = false
    }

    fun recordDisplacement(d: Float) {
        episodeDisplacement += d
    }

    /** #422:节点 measure 相调用(settle 计数)。 */
    fun recordMeasure() {
        measureCount++
    }

    /** #422:episode 末调用——强制下一次 measure 走真测路径。 */
    fun requestRemeasure() {
        remeasureEpoch++
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

    // #424 闭环位置恢复:reveal 盒顶缘(=折叠行底缘)窗口坐标实测 Y。episode 末
    // 以实测对账修正开环 δ 账本累积的视口漂移(边缘残量/锚点翻转会计误差)。
    val revealTopY = remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }

    // #425 连点竞态:反向 toggle 取消上一集时携带其锚点——否则新集以「漂后
    // 位置」起锚,逐集链式泄漏(真机 24 连点净漂 −73px)。正常完成/用户滚动
    // 取消则清空(下次以当下位置重新起锚)。
    val carriedAnchor = remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }

    /**
     * visible 转换 → 手写帧循环驱动(冷组合初值即目标,不动画)。
     *
     * #420 追诊定案:animateTo + snapshotFlow 方案有两处致命竞态——
     * ① snapshotFlow 是 conflated:主线程拥塞时中间值合并(实测一帧
     *   δ=−260、164ms 空白、8 次稀疏 dispatch);
     * ② animateTo 的 finally 先关 animating,尾帧 δ 被守卫吞掉 →
     *   动画结束帧高度无补偿塌陷(实测 ~265px 跳变)。
     * 手写循环把「advance → dispatch → 写 fraction(本帧 measure 上报)」
     * 三步强绑在同一帧回调内,循环正常结束点显式 flush 精确落位。
     * 协程取消(用户滚动 snap / 快速反向 toggle 重启)由 LaunchedEffect
     * 语义天然覆盖——新循环自 clock.fraction 当前值续走(R5 回摆保留)。
     */
    LaunchedEffect(visible, listState) {
        val target = if (visible) 1f else 0f
        if (abs(clock.fraction - target) > 0.001f) {
            clock.animating = true
            clock.beginEpisode()
            val episodeStart = System.nanoTime()
            // #425:本集起点锚 = 携带锚(重定向链)或当下实测位置。
            val anchorY = carriedAnchor.value ?: revealTopY.floatValue
            var completed = false
            try {
                if (target > 0f) {
                    // #420 预热:content 入树开始首测(ε·H<1px 零视觉)。注意
                    // lastMeasuredH==0 判定已移除:collapse 末 content 离树后的
                    // 重入(reverse toggle/回收复用)同样需要 settle。
                    clock.warmup()
                    // #422 内容沉降:表格 containerWidth 两拍收敛、asyncParse
                    // 完成等「首测后仍有内容驱动重测」在此吸收完,再开缓存
                    // 窗口——否则 tween 全程锁死在过期 H 上。
                    settleUntilContentStable(clock)
                }
                val startF = clock.fraction
                clock.primeLedger()
                // #422 缓存窗口开启:tween 期间节点复用 settle 末的 placeable,
                // 逐帧只重算 report,子树零重测(表格重测风暴根治点)。
                clock.tweening = true
                val t0 = withFrameNanos { it }
                var vt = 0f // 虚拟时钟(ms):墙钟追赶 + 单帧位移钳制
                // #425 渲染前反馈闭环:每帧指令 = 缓动增量 + 上帧实测偏差(死拍),
                // 账本只记实际吸收——组合滞后残量与锚点翻转误差不再漏成漂移
                // (逐帧取证:旧开环展开 −98 平台/收起 −60→−328 阶跃,收起中段
                // anchor 稳定时 dev=0 证明配对数学本身正确,断点全在损耗侧)。
                // 收敛:缓动走完且指令≈0(残量排干);墙钟硬顶防真死边。
                var prevTargetRep = (startF * clock.lastMeasuredH).toInt()
                while (true) {
                    val now = withFrameNanos { it }
                    val wall = ((now - t0) / 1_000_000f).coerceAtLeast(0f)
                    val easedNow = easedFraction(startF, target, minOf(vt, GEOMETRY_TWEEN_MS.toFloat()))
                    val pending = closedLoopCommand(
                        targetRep = (easedNow * clock.lastMeasuredH).toInt(),
                        prevTargetRep = prevTargetRep,
                        topErr = anchorY - revealTopY.floatValue,
                    )
                    if ((vt >= GEOMETRY_TWEEN_MS && abs(pending) < 1) || wall > EPISODE_HARD_CAP_MS) break
                    // 从 vt+1ms 起试探满足位移钳制的最大虚拟步(二分回退,基准=缓动差分)
                    var nextVt = minOf(wall, vt + MAX_FRAME_STEP_MS)
                    while (nextVt - vt > 1f) {
                        val cand = easedFraction(startF, target, nextVt)
                        val estD = abs((cand * clock.lastMeasuredH).toInt() - prevTargetRep)
                        if (estD > MAX_FRAME_DELTA_PX) nextVt -= (nextVt - vt) / 2f else break
                    }
                    vt = maxOf(nextVt, vt + 0.5f) // 至少微进,防死锁
                    dispatchClosedLoop(listState, clock, departure, easedFraction(startF, target, vt), prevTargetRep, anchorY, revealTopY.floatValue)
                    prevTargetRep = (clock.fraction * clock.lastMeasuredH).toInt()
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(
                            "CardExpand",
                            "[DEBUG-425] frame vt=" + vt.toInt() +
                                " f=" + "%.3f".format(clock.fraction) +
                                " rep=" + clock.lastReportedH +
                                " abs=" + clock.absorbedPx +
                                " H=" + clock.lastMeasuredH +
                                " topY=" + revealTopY.floatValue.toInt() +
                                " anchor=" + anchorY.toInt() +
                                " fii=" + listState.firstVisibleItemIndex +
                                " fiso=" + listState.firstVisibleItemScrollOffset,
                        )
                    }
                }
                // 收尾 flush:目标分数 + 反馈(正常已收敛,此处仅兜底)
                dispatchClosedLoop(listState, clock, departure, target, prevTargetRep, anchorY, revealTopY.floatValue)
                completed = true
            } finally {
                clock.tweening = false
                // #422 episode 末强制真测:epoch 写使节点 measure 失效,且
                // tweening=false → 缓存旁路——迟到内容增量在此落地为新 H。
                clock.requestRemeasure()
                try {
                    withFrameNanos { }
                    withFrameNanos { } // 复测于上一帧 layout 已跑,此处读数可靠
                    // 迟到增量补偿:残差走与 tween 帧同一 δ 配对(账本 telescoping),
                    // 上报(report=f·H_new)与位移同帧——不引入 #262 类错位。
                    if (clock.fraction > 0f && clock.lastMeasuredH != clock.lastReportedH) {
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(
                                "CardExpand",
                                "[DEBUG-422] late-growth catch-up d=" +
                                    (clock.lastMeasuredH - clock.lastReportedH) + " H=" + clock.lastMeasuredH,
                            )
                        }
                        dispatchClosedLoop(listState, clock, departure, clock.fraction, (clock.fraction * clock.lastMeasuredH).toInt(), anchorY, revealTopY.floatValue)
                    }
                } catch (_: CancellationException) {
                    // 取消(snap)路径:落位已由 snap 完成,无需补偿
                }
                // #424 闭环位置恢复:正常完成(反向 toggle 重启/用户滚动取消不修)时,
                // 实测 reveal 顶缘与本集起点的偏差并单次修正 dispatch 回起点。
                if (completed) {
                    val err = episodeEndCorrection(anchorY, revealTopY.floatValue, clock.userScrollCancelled)
                    if (err != null && err != 0f) {
                        val consumed = runCatching { listState.dispatchRawDelta(err) }.getOrDefault(0f)
                        clock.recordDisplacement(consumed)
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(
                                "CardExpand",
                                "[DEBUG-424] end-restore err=" + err.toInt() + " consumed=" + consumed.toInt(),
                            )
                        }
                    }
                }
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "CardExpand",
                        "[DEBUG-422] episode done f=" + "%.3f".format(clock.fraction) +
                            " totalMs=" + ((System.nanoTime() - episodeStart) / 1_000_000f).toInt(),
                    )
                }
                // #425 锚点携带:重定向取消(未正常完成且非用户滚动)→ 携带原锚,
                // 新集首帧反馈即归位;否则清空。
                carriedAnchor.value = if (completed || clock.userScrollCancelled) null else anchorY
                clock.animating = false
            }
        }
    }

    // 用户滚动 → 立即取消(阅读位置优先权铁律):snap 即取消动画协程 + 直接落位
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && clock.animating) {
                    if (BuildConfig.DEBUG) {
                        AppLogger.d("CardExpand", "[DEBUG-420] cancel-on-scroll snap f=" + "%.3f".format(clock.fraction))
                    }
                    clock.userScrollCancelled = true
                    clock.snap(if (visible) 1f else 0f)
                }
            }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .cardExpandGeometry(clock)
            .onGloballyPositioned { revealTopY.floatValue = it.positionInRoot().y }
    ) {
        // #420 追诊:content 组合生命周期归几何时钟统一拥有(fraction>0 即组合)。
        // 原方案 AV fadeOut(300ms) 与几何 tween(240ms) 两时钟分离——掉帧时
        // 几何未走完而 AV 已把 content 移出组合 → H 突塌 f·738px 无补偿
        // (实测 flow-b 收起终末 −217px 跳变)。fade 改由分数驱动(前/后 30%),
        // 与几何同起止,杜绝第二时钟。
        if (clock.fraction > 0f) {
            Box(modifier = Modifier.graphicsLayer { alpha = (clock.fraction / 0.3f).coerceIn(0f, 1f) }) {
                content()
            }
        }
    }
}

/**
 * #424 闭环位置恢复判定(纯函数可单测):episode 末把 reveal 盒顶缘(=折叠行
 * 底缘)拉回本集起点所需的位移 δ;不可恢复(用户滚动取消/坐标缺失)返回
 * null,偏差 <1px 返回 0(免无意义 dispatch)。
 *
 * 背景:开环 δ 配对账本只记「指令」不记「实际消费」——列表边缘 residual、
 * LazyList 锚点翻转的会计误差都会累积为视口净漂移(真机实测小组一个
 * 展开+收起循环净漂 −366px:展开末 −98(边缘残量) + 收起过程 −268)。
 * 闭环以实测位置对账,episode 末单次修正回起点。
 */
internal fun episodeEndCorrection(
    anchorY: Float,
    currentY: Float,
    userScrollCancelled: Boolean,
): Float? {
    if (userScrollCancelled) return null
    if (anchorY.isNaN() || currentY.isNaN()) return null
    val err = anchorY - currentY
    return if (abs(err) >= 1f) err else 0f
}

/** 虚拟时刻 vt(ms) 对应的缓动分数。 */
private fun easedFraction(startF: Float, target: Float, vt: Float): Float {
    val p = (vt / GEOMETRY_TWEEN_MS.toFloat()).coerceIn(0f, 1f)
    return startF + (target - startF) * FastOutSlowInEasing.transform(p)
}

/**
 * #425 渲染前反馈指令(纯函数可单测):本帧 dispatch 量 =
 * 缓动增量(targetRep − prevTargetRep) + 上帧实测偏差 topErr(死拍全量纠偏)。
 *
 * 振荡教训(真机取证 cmd=±154 无限交替):(目标−已吸收)+偏差 是双计——
 * 吸收账本已含历次纠偏,纠偏又被下帧吸收项撤销 → 极限环。偏差是唯一
 * 积分器:未消费指令天然留在下帧偏差里重试,无需吸收项。
 * 钳制防锚点翻转瞬间的单帧暴冲(实测阶跃 ≤176px,300 上限含余量)。
 */
internal fun closedLoopCommand(targetRep: Int, prevTargetRep: Int, topErr: Float): Int =
    (targetRep - prevTargetRep + topErr.toInt()).coerceIn(-MAX_CLOSED_LOOP_DELTA_PX, MAX_CLOSED_LOOP_DELTA_PX)

/**
 * #425 单帧配对(吸收驱动):指令含反馈项 → dispatchRawDelta → 账本只记
 * consumed → 揭示高度=账本。上帧误差在本帧渲染前算进指令——折叠行按
 * 构造钉住;未消费部分留在「目标−账本」差值里,下帧自动重试(不丢失)。
 */
private fun dispatchClosedLoop(
    listState: LazyListState,
    clock: CardExpandClock,
    departure: (() -> Unit)?,
    targetFraction: Float,
    prevTargetRep: Int,
    anchorY: Float,
    currentTopY: Float,
) {
    // 崩溃修复(真机收起末 IllegalState: LayoutNode should be attached):
    // fraction→0 会移除 content,必须先关 placeable 缓存窗口,否则同一帧
    // 布局仍会放置已离树的缓存 placeable。
    if (targetFraction <= 0.001f) clock.tweening = false
    clock.driveTo(targetFraction)
    val topErr = anchorY - currentTopY
    val d = closedLoopCommand(
        targetRep = (targetFraction * clock.lastMeasuredH).toInt(),
        prevTargetRep = prevTargetRep,
        topErr = topErr,
    ).toFloat()
    if (d == 0f) return
    val consumed = runCatching { listState.dispatchRawDelta(d) }.getOrDefault(0f)
    clock.absorb(consumed)
    clock.recordDisplacement(consumed)
    if (BuildConfig.DEBUG) {
        AppLogger.d(
            "CardExpand",
            "[DEBUG-425] cmd d=" + d.toInt() + " consumed=" + consumed.toInt() +
                " topErr=" + topErr.toInt() + " absorbed=" + clock.absorbedPx + " rep=" + clock.lastReportedH + " H=" + clock.lastMeasuredH,
        )
    }
    if (abs(d - consumed) > 0.5f) {
        AppLogger.w("CardExpand", "[DEBUG-425] residual=" + (d - consumed).toInt() + " (retry next frame)")
    }
    if (!clock.departureFired && abs(clock.episodeDisplacement) > DEPARTURE_THRESHOLD_PX) {
        clock.departureFired = true
        departure?.invoke()
    }
}

/**
 * #422 内容沉降:展开 tween 前等待「内容驱动的重测」静止。
 *
 * 为什么必须:placeable 缓存只在 tween 窗口生效,而首测往往不是终测——
 * 表格 containerWidth 经 onSizeChanged 回写后第二拍才收敛、async markdown
 * 解析完成后内容突增。若带过期 H 进入缓存窗口,tween 全程锁死在旧高度
 * (展开只有一小截),残差全部堆到 episode 末一次性跳变(原叠压 bug 的
 * 同族根因)。判定:连续 [SETTLE_STABLE_FRAMES] 帧 [CardExpandClock.measureCount]
 * 不增;上限 [MAX_SETTLE_MS] 防无限等。
 */
private suspend fun settleUntilContentStable(clock: CardExpandClock) {
    val t0 = withFrameNanos { it }
    var lastCount = clock.measureCount
    var stableFrames = 0
    while (stableFrames < SETTLE_STABLE_FRAMES) {
        val now = withFrameNanos { it }
        if ((now - t0) / 1_000_000f >= MAX_SETTLE_MS) break
        val c = clock.measureCount
        if (c > 0 && c == lastCount) {
            stableFrames++
        } else {
            stableFrames = 0
            lastCount = c
        }
    }
    if (BuildConfig.DEBUG) {
        AppLogger.d(
            "CardExpand",
            "[DEBUG-422] settle done frames=" + stableFrames +
                " measures=" + clock.measureCount + " H=" + clock.lastMeasuredH,
        )
    }
}

/**
 * #422 几何上报节点:无界测量 → 上报 f·H(未揭示部分被外层 clip 裁掉)。
 *
 * **性能根因修复**:lambda 版 `Modifier.layout` 每帧 fraction 写都重测整棵
 * 子树;含表格时 SimpleMarkdownTable 的 SubcomposeLayout final 遍必跑
 * (subcompose + 全单元格 measure),15 帧 × 数百 ms = 用户实测「点击后
 * 10s+ 才展开」+ 行高未放置即曝光的叠压。
 *
 * **机制**:tween 窗口内(clock.tweening)复用 settle 末真测的 placeable,
 * 每帧只重算 report = f·cachedH(LazyList 滚动位移动画同款「测一次、放
 * 多次」先例)。窗口外(冷组合/settle/稳定态)恒真测,内容失效得以正常
 * 传播。窗口内子树若失效(asyncParse 迟到等罕见竞态),视觉停留在旧帧
 * ≤240ms,由 episode 末 epoch 强制复测修复——不崩溃、不丢失。
 *
 * 缓存失效条件(任一不满足即真测):
 * 1. `clock.tweening == false`;
 * 2. 宽度约束有界且与缓存一致(旋转/折叠屏);
 * 3. measurable 同一实例(content 离树重入后为不同 LayoutNode);
 * 4. 缓存非空。
 */
private class CardExpandGeometryNode(
    var clock: CardExpandClock,
) : Modifier.Node(), LayoutModifierNode {

    private var cachedPlaceable: Placeable? = null
    private var cachedMeasurable: Measurable? = null
    private var cachedWidth: Int = Int.MIN_VALUE
    private var cachedAtEpoch = -1

    override fun onDetach() {
        super.onDetach()
        invalidateCache()
    }

    private fun invalidateCache() {
        cachedPlaceable = null
        cachedMeasurable = null
        cachedWidth = Int.MIN_VALUE
        cachedAtEpoch = -1
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        clock.recordMeasure()
        // 快照读(承重):epoch 写失效本 measure(episode 末强制复测);且 epoch
        // 变化即清缓存——双保险,即使 tweening 异常为 true 也走真测。
        val epoch = clock.remeasureEpoch
        if (epoch != cachedAtEpoch) {
            invalidateCache()
            cachedAtEpoch = epoch
        }

        val width = constraints.maxWidth
        // 崩溃守卫:fraction<=0 时 content 已/将离树——缓存 placeable 指向
        // 已分离 LayoutNode,放置即崩;一律真测。
        val cacheable = clock.tweening && clock.fraction > 0f && constraints.hasBoundedWidth &&
            cachedWidth == width && cachedMeasurable === measurable
        val cached = cachedPlaceable
        val placeable = if (cacheable && cached != null) {
            cached
        } else {
            measurable.measure(constraints.copy(maxHeight = Constraints.Infinity)).also {
                cachedPlaceable = it
                cachedMeasurable = measurable
                cachedWidth = width
            }
        }
        val report = clock.onMeasure(placeable.height)
        return layout(placeable.width, report) {
            placeable.placeRelative(0, 0)
        }
    }
}

/**
 * #422:Element 包装——clock 以身份相等 remember 期内稳定,节点与缓存
 * 跨重组存活(lambda 版每帧新建 element 正是缓存无法附着的原因)。
 */
private class CardExpandGeometryElement(
    private val clock: CardExpandClock,
) : ModifierNodeElement<CardExpandGeometryNode>() {
    override fun create(): CardExpandGeometryNode = CardExpandGeometryNode(clock)

    override fun update(node: CardExpandGeometryNode) {
        node.clock = clock
    }

    override fun equals(other: Any?): Boolean =
        other is CardExpandGeometryElement && other.clock === clock

    override fun hashCode(): Int = System.identityHashCode(clock)
}

private fun Modifier.cardExpandGeometry(clock: CardExpandClock): Modifier =
    this.then(CardExpandGeometryElement(clock))
