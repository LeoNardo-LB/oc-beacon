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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.screens.chat.scroll.PreDrawFlushTask
import dev.leonardo.ocbeacon.ui.screens.chat.scroll.PreRenderCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
 * #431 B 阶段跳跃起始:Phase A 落地帧排干完成时 drawFraction 直接跳到此值
 * (同帧 pre-draw),B 阶段从此值续坡至 1。消除「高度一帧到位+内容不可见」
 * 的空白拍(小卡四拍顿挫的残余根因);0.3=alpha 满值阈值,首批内容即满
 * 透明度、顶部 30% 裁切可见。
 */
private const val PHASE_B_JUMP_START = 0.3f

/** #423 批次十一:幕布揭示时长(ms)(#262 同款 200ms 纯 draw)。 */
private const val CURTAIN_MS = 200f

/**
 * #423 批次十一(#262 applyTapShift 复活):渲染前位移——measure 块外施加,
 * 下一遍 measure 与布局终态同帧原子落地。
 * 贴底(fii==0 且 fiso<120)=反射 request-position(下方无余量,上方内容固定);
 * mid-list=dispatchRawDelta(同步消费、跨 item、无残量)。
 */
internal fun applyPreRenderShift(ls: androidx.compose.foundation.lazy.LazyListState, deltaPx: Float) {
    // 批次十一校准(真机 DRAW 定案):request-position 在贴底态(0,0)方向反——
    // 实测 +H 写入把内容推上屏外(topY −3540)后框架 10 帧自愈。展开正向位移
    // 恒走 dispatchRawDelta:滚动位 0 即起点,向旧侧永远有空间、同步消费精确
    // (真机反复实证 consumed==d)。反射 request-position 保留给负向不可消费域。
    try {
        ls.dispatchRawDelta(deltaPx)
    } catch (t: Throwable) {
        // 降级:预移失败=退化为渲染后推挤一帧,绝不崩溃(宁推挤不卡渲染)
        dev.leonardo.ocbeacon.logging.AppLogger.w("CardExpand", "pre-shift failed: " + t.message)
    }
}

/** #423 批次四d:hold 呼吸阀——连续拒绘上限(真机证实坐标回调在拒绘遍历中照常
 * 派发,无死锁;阀值仅兜底「贴底残量物理不可约」类永不收敛场景)。 */
private const val HOLD_VENT_FRAMES = 30

/** #423 批次五b:确认后静默守望窗(ms)——rep 无变更持续此时长才允许修正器解散
 * (迟到增长在窗内自动重进确认环被修正)。 */
private const val PIN_QUIET_MS = 1800f

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

    /**
     * #423 批次十一:程序化位移豁免——dispatchRawDelta 会触发 isScrollInProgress,
     * 取消守卫须区分用户手势与引擎位移(真机:预移+4688 被守卫误杀,集 826ms 自裁)。
     */
    var programmaticShift = false

    /**
     * #423 批次四b:本集几何目标分数(plain)。FLUSH 修正器的灭钉门控——
     * 坐标静止计数稳定前,分数必须已到位(settle/warmup 期报告恒 ε,不得灭钉;
     * 否则 driveTo 增长到来时修正器已离场 = 顶开帧直接上屏)。
     */
    var pinTargetFraction = Float.NaN

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
    /** #423 批次十一:finalH 缓存键(msgId/part.id)——二次展开零延迟预移。 */
    cacheKey: Any? = null,
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

    /**
     * #423 批次十:绘制真值(在 drawWithContent 内采样——该时刻读到的布局值
     * 即实际渲染值,天然过滤多遍测量的幻影放置)。逐帧双写的闭环反馈源:
     * 真机绘制级定案(s21 DRAW 时间线):状态写入精确(fiso 增量==δ)但视觉
     * 传递函数≈0.35(写 72 仅移 25,单调上爬 47px=用户「顶」观感)——开环
     * 数学正确、传递函数失真,必须以绘制真值闭环补偿。
     */
    val drawnTopY = remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }

    /**
     * #423 批次十一(#262 复活):finalH 缓存(rememberSaveable,cacheKey 为键)
     * ——二次展开高度提前已知,渲染前双写零延迟;只向上自愈(防陈旧回写)。
     */
    val finalHCacheRaw = androidx.compose.runtime.saveable.rememberSaveable(cacheKey) {
        androidx.compose.runtime.mutableStateOf(-1)
    }
    val finalHCache = remember(cacheKey) {
        androidx.compose.runtime.mutableStateOf(-1).also { it.value = finalHCacheRaw.value }
    }
    fun storeFinalH(h: Int) {
        if (h > finalHCacheRaw.value) finalHCacheRaw.value = h
        if (h > finalHCache.value) finalHCache.value = h
    }

    /** #423 批次十一:幕布系数(纯 draw 揭示;draw 相读,动画循环写)。 */
    val curtain = remember { androidx.compose.runtime.mutableFloatStateOf(if (visible) 1f else 0f) }

    // #425 连点竞态:反向 toggle 取消上一集时携带其锚点——否则新集以「漂后
    // 位置」起锚,逐集链式泄漏(真机 24 连点净漂 −73px)。正常完成/用户滚动
    // 取消则清空(下次以当下位置重新起锚)。
    val carriedAnchor = remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }

    /**
     * #425 两阶段展开的**纯绘制揭示分数**(drawWithContent clipRect 高度系数)。
     * 真机定案:展开方向 LazyList 布局多 pass 不稳定(dispatch 时刻 topY 在
     * 922↔982↔957 逐 pass 振荡,录屏条带 ±136px)——动画期间布局/滚动参与
     * 即震荡。故展开=A 阶段一次性布局落位(本分数=0,内容不可见)+B 阶段
     * 纯绘制揭示(本分数 0→1,零布局零滚动)。收起方向布局稳定(实测逐帧
     * consumed==d、topY 恒定),保持布局裁剪路径,本分数恒 1。
     */
    val drawFraction = remember { androidx.compose.runtime.mutableFloatStateOf(if (visible) 1f else 0f) }

    /**
     * #426 A 阶段排干标志:置位期间布局回调(onGloballyPositioned)补发配对位移。
     */
    val phaseADrain = remember { androidx.compose.runtime.mutableStateOf(false) }

    /**
     * #423 批次三:实测钉位状态(rememberSaveable——LEAP 触发 item 回收时,episode
     * 协程随组合销毁被取消,钉位须由重组合后的新集续跑)。pinAnchor=本集折叠行
     * 目标屏位;pinPending=true 表示钉位闭环未收敛。
     */
    val pinPending = androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val pinAnchor = androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableFloatStateOf(Float.NaN)
    }

    /**
     * #423 观测:episode 序号(每集递增;PLACED/DRAW/排干日志共用,帧级对账用)。
     * 仅 DEBUG 且仅动画窗口输出——常态滚动零行,杜绝日志膨胀。
     */
    val episodeSeq = remember { androidx.compose.runtime.mutableIntStateOf(0) }

    /**
     * #423 批次四c:修正防双发守卫(放置回调派发点与 FLUSH 实测阶段共享)。
     * 坐标回调与 pre-draw 读数各滞后一拍(真机两轮取证),两处都可能对同一
     * 新鲜坐标开火——只允许「坐标自上次派发后刷新过」的第一见者开火。
     */
    val guardCur = remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }

    /**
     * #423 批次四e:坐标同源戳——坐标回调写入 revealTopY 时记录当时 lastReportedH。
     * 坐标回调为绘制驱动(真机三轮定案),增长帧 pre-draw 读到的坐标必属旧布局;
     * 旧坐标恰等于钉锚时构成「假确认」(v3.1 最后一帧漏点)。确认必须要求
     * coordStamp == 当前 rep(坐标与本帧报告同源=坐标属于当前布局)。
     */
    val coordStamp = remember { androidx.compose.runtime.mutableIntStateOf(-1) }

    /**
     * #423 批次四e:账本共享账——Δreport 配对的全局会计。放置回调(pin-placed)、
     * 账本(pin-ledger)、实测(pin-flush)三处 dispatch 的 consumed 全部入账,
     * errLedger = (rep − 基线) − 已派发。防 e6 实证的两路对同一位移双发。
     */
    val pinLedgerBase = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val pinDispatchedTotal = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    /**
     * #423 批次五:泵帧武装(放置回调派发点 → 修正器消费)。pin-placed 派发后
     * 下一帧放行(该帧为修正后布局)驱动坐标回调盖戳——否则须等呼吸阀第 30 帧,
     * 顶开态可见窗被无谓拉长(收起「顶一下再复位」观感的成分之一)。
     */
    val pumpArm = remember { androidx.compose.runtime.mutableStateOf(false) }

    /**
     * #426 同帧配对排干:增长落地当帧(布局完成、draw 之前)补发配对位移。
     *
     * 真机定案([DEBUG-425] 逐帧取证 + 录屏条带):动画相首帧 dispatch 在增长
     * 落地前无法消费——布局里尚无对应空间,consumed=0 → 增长落地帧成为
     * 「内容上顶」闪跳帧(上方内容 -H 持续 1-2 帧后归零),下一帧重试才复位。
     * 本钩子改在 onGloballyPositioned(放置完成、同帧 draw 前)触发:此时本帧
     * 布局已含增长 → dispatchRawDelta 有空间可消费 → 消费即失效重排(仍在本
     * 帧 draw 前)→ 同帧净位移为零,瞬态从构造上消失。
     */
    fun drainPhaseA(cause: String) {
        var tries = 0
        while (tries < 8) {
            // 配对基准=已上报高度(f·H,布局已落地部分)而非实侧全高:两路径统一——
            // A 阶段(f=1)二者相等;小卡逐帧路径 f<1 时只配对本帧已落地增量。
            // 若用全高,逐帧路径首拍会在布局未增长时全额配对(实测:fraction=0.004
            // 即 dispatch 146px)→ 无配对滚动 → LazyList 锚点乱斗 ±H 震荡。
            val pending = clock.lastReportedH - clock.absorbedPx
            if (abs(pending) < 1) {
                // #431:配对完成的落地帧(pre-draw)跳跃起始 B 阶段——高度与首批
                // 内容同帧出现,空白拍从构造上消失。maxOf 防倒放(收起向无 drain)。
                drawFraction.floatValue = maxOf(drawFraction.floatValue, PHASE_B_JUMP_START)
                phaseADrain.value = false
                return
            }
            val consumed = try {
                listState.dispatchRawDelta(pending.toFloat())
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) AppLogger.e("CardExpand", "[DEBUG-426] dispatch threw", t)
                0f
            }
            clock.absorb(consumed)
            clock.recordDisplacement(consumed)
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "CardExpand",
                    "[DEBUG-426] " + cause + " pending=" + pending + " consumed=" + consumed.toInt() +
                        " topY=" + revealTopY.floatValue.toInt() + " H=" + clock.lastMeasuredH +
                        " abs=" + clock.absorbedPx + " fii=" + listState.firstVisibleItemIndex +
                        " fiso=" + listState.firstVisibleItemScrollOffset,
                )
            }
            if (!clock.departureFired && abs(clock.episodeDisplacement) > DEPARTURE_THRESHOLD_PX) {
                clock.departureFired = true
                departure?.invoke()
            }
            if (abs(consumed) < 0.5f) return // 本帧已消费不动,等下一次布局回调
            tries++
        }
    }

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
        // #423 I3(视口租约):episode 全程(含 finally 收尾:end-restore/迟到增量/锚点
        // 携带)持租约——守卫/MSGEFFECT/PENDING/强滚锚底让位,±H 互搏从构造上消除。
        // 快速反向 toggle 取消旧集 → finally 必释放;新集立即重持(计数语义)。
        PreRenderCoordinator.withEpisode {
        val target = if (visible) 1f else 0f
        val needsEpisode = abs(clock.fraction - target) > 0.001f
        if (needsEpisode) {
            // 批次九(用户裁决 2026-09-22):统一高度控制——渲染前计算 + 反射逐帧
            // 设置。钉位武装/FLUSH 修正环/两阶段揭示全部退役:高度分数与滚动位
            // 在同一遍 measure 原子生效,配对从构造上精确(零补偿、零修正环)。
            episodeSeq.intValue = episodeSeq.intValue + 1
            clock.animating = true
            clock.beginEpisode()
            clock.pinTargetFraction = target
            val episodeStart = System.nanoTime()
            // #425:本集起点锚 = 携带锚(重定向链)或当下实测位置(end-restore 兜底用)
            val anchorY = carriedAnchor.value ?: revealTopY.floatValue
            var completed = false
            // #431→#423 批次四:闭环尾循环整体退役(展开向 Phase A 绕过、收起向并入
            // 钉位契约);续跑集(回收后钉位续)不重跑 settle/A/B 相,由下方分支结构保证。
            try {
                // ===== 批次十一(#262 架构复活,用户裁决):渲染前计算 + 渲染前位移 =====
                // 布局层首帧即终态(展开无布局动画);视觉=纯绘制幕布(clipF);
                // 位移在 measure 前施加(tap 后首帧或测量后首帧)——高度与滚动
                // 位同帧原子落地,中间态从构造上不存在(零补偿/零门控/零闭环)。
                if (target > 0f) {
                    // 恒走预热+沉降(缓存命中亦然):收起后内容已离树,跳过组合
                    // 会让 dispatch 内测见旧高→条目回收→协程被杀(真机 53ms
                    // exit-CANCELLED 定案)。缓存的真正价值=跨回收高度预知(floor)。
                    clock.warmup()
                    settleUntilContentStable(clock)
                    val H = maxOf(clock.lastMeasuredH, finalHCache.value).also { if (it > 0) storeFinalH(it) }
                    // 布局终态先行且**同步施加**:mutableStateOf 写入是快照批量的,
                    // 裸写后 dispatch 的内部测量仍见旧高(中间态=卡片屏外 4688px
                    // →条目回收→协程被杀,真机 exit-CANCELLED 829ms 定案)。
                    // withMutableSnapshot 块末同步应用 → dispatch 测量即见终高。
                    clock.tweening = true
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        clock.driveTo(1f)
                    }
                    clock.tweening = false
                    // 渲染后一帧再位移? 不——dispatch 内部 measure 已含增长,
                    // 同帧原子;程序化豁免包裹(防取消守卫误杀)
                    clock.programmaticShift = true
                    try {
                        applyPreRenderShift(listState, H.toFloat())
                    } finally {
                        clock.programmaticShift = false
                    }
                    // 幕布:纯绘制揭示 0→1(200ms,零布局零滚动)
                    val tC = withFrameNanos { it }
                    var vc = 0f
                    while (vc < CURTAIN_MS) {
                        val nc = withFrameNanos { it }
                        if (clock.userScrollCancelled) break
                        vc = minOf(
                            ((nc - tC) / 1_000_000f).coerceAtLeast(0f),
                            vc + MAX_FRAME_STEP_MS,
                        )
                        curtain.floatValue = FastOutSlowInEasing.transform(
                            (vc / CURTAIN_MS).coerceIn(0f, 1f),
                        )
                    }
                    curtain.floatValue = 1f
                } else {
                    // 收起(#262 §3 裁决「下方收上来」):布局+幕布同步缓动,
                    // 逐帧 dispatchRawDelta(−δ)(帧回调相,measure 块外)——
                    // header 锚点每帧静止(旧通道实证语义)
                    val H = clock.lastMeasuredH
                    val startF = clock.fraction
                    clock.tweening = true
                    val t0 = withFrameNanos { it }
                    var vt = 0f
                    var lastRep = clock.lastReportedH
                    while (vt < GEOMETRY_TWEEN_MS) {
                        val now = withFrameNanos { it }
                        if (clock.userScrollCancelled) break
                        vt = minOf(
                            ((now - t0) / 1_000_000f).coerceAtLeast(0f),
                            vt + MAX_FRAME_STEP_MS,
                        )
                        val f = easedFraction(startF, 0f, vt)
                        val rep = (f * H).toInt()
                        val delta = (rep - lastRep).toFloat()
                        clock.driveTo(f)
                        if (delta < -0.5f) {
                            clock.programmaticShift = true
                            try {
                                runCatching { listState.dispatchRawDelta(delta) }
                            } finally {
                                clock.programmaticShift = false
                            }
                        }
                        curtain.floatValue = maxOf(curtain.floatValue, f)
                        lastRep = rep
                    }
                    clock.driveTo(0f)
                    clock.programmaticShift = true
                    try {
                        runCatching { listState.dispatchRawDelta((0 - lastRep).toFloat()) }
                    } finally {
                        clock.programmaticShift = false
                    }
                    clock.tweening = false
                    curtain.floatValue = 0f
                }
                completed = true
            } catch (t: Throwable) {
                // #423 批次三诊断:区分取消/异常(真机大组集 54ms 早退未明因)
                if (BuildConfig.DEBUG) {
                    AppLogger.e(
                        "CardExpand",
                        "[DEBUG-423] episode exit-" + (if (t is CancellationException) "CANCELLED" else "THREW") +
                            " f=" + "%.3f".format(clock.fraction) + " pinPending=" + pinPending.value,
                        t,
                    )
                }
                throw t
            } finally {
                clock.tweening = false
                phaseADrain.value = false
                // #426 追修复(不变量):任何退出路径上,布局占位(fraction>0)必须
                // 配可见内容——纯绘制分数只在 A→B 正常走完时才回到 1;取消/
                // 异常/竞态在此兜底,杜绝「占位不显示」的空白卡死态。
                drawFraction.floatValue = if (clock.fraction > 0.001f) 1f else 0f
                if (clock.fraction > 0.001f && curtain.floatValue < 1f) curtain.floatValue = 1f
                if (clock.fraction <= 0.001f) curtain.floatValue = 0f
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
                        // 批次九:迟到增量兜底同走反射逐帧通道(渲染前配对)
                        val lateDelta = (clock.lastMeasuredH - clock.lastReportedH).toFloat()
                        if (abs(lateDelta) >= 0.5f) {
                            LazyListReflection.preRenderScrollBy(listState, lateDelta)
                        }
                    }
                } catch (_: CancellationException) {
                    // 取消(snap)路径:落位已由 snap 完成,无需补偿
                }
                // #424 闭环位置恢复——批次九轮2 退役:迟沉降窗的 revealTopY 报
                // 多遍瞬态幻影坐标(视频实锤:屏面稳定时它报 −230→1426 爬升),
                // 基于它的修正必过冲并制造可见终端抖动;中段双写数学已被视频
                // dy≈0 与终态滚动位≈钉稳态双证。episodeEndCorrection 保留供单测。
                if (completed && !LazyListReflection.probesResolved) {
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
                            " totalMs=" + ((System.nanoTime() - episodeStart) / 1_000_000f).toInt() +
                            " completed=" + completed + " pinPending=" + pinPending.value,
                    )
                }
                // #425 锚点携带:重定向取消(未正常完成且非用户滚动)→ 携带原锚,
                // 新集首帧反馈即归位;否则清空。
                carriedAnchor.value = if (completed || clock.userScrollCancelled) null else anchorY
                clock.animating = false
            }
        }
        } // withEpisode(缩进未重排:热文件零churn,引擎迁入时整体重构)
    }

    // #426 追修复:cancel 处理器必须读**当下** visible——LaunchedEffect(listState)
    // 不随 visible 重启,闭包捕获停留在首次组合时的值(实测:收起期首次组合的
    // 实例在展开后滚动,snap(0f) 把 fraction 打到 0 → 内容离树,收尾循环又拉回 1
    // → 终态「布局占位 + drawFraction=0 内容隐形」= 折叠行下大面积空白卡死)。
    val currentVisible by rememberUpdatedState(visible)
    // 用户滚动 → 立即取消(阅读位置优先权铁律):snap 即取消动画协程 + 直接落位
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && clock.animating && !clock.programmaticShift) {
                    if (BuildConfig.DEBUG) {
                        AppLogger.d("CardExpand", "[DEBUG-420] cancel-on-scroll snap f=" + "%.3f".format(clock.fraction))
                    }
                    clock.userScrollCancelled = true
                    clock.snap(if (currentVisible) 1f else 0f)
                    drawFraction.floatValue = if (currentVisible) 1f else 0f
                }
            }
    }

    // #423 批次三(终):FLUSH 相实测钉位修正器——增长落地帧在 draw 前测量
    // err=钉锚−折叠行屏位,分段 dispatch(±2000/帧)修正,残差未闭拒绘(≤2/帧);
    // 收敛(连续 3 次|err|<1)或超时(1.5s)清 pinPending 自灭。中间态从不上屏;
    // 无预测 LEAP→无 item 回收→修正循环全程存活(预测配对退役原因见展开分支)。
    // 降级:无 FLUSH 宿主(预览/单测)→ 不置 pinPending,零滚动参与。
    LaunchedEffect(pinPending.value) {
        if (!pinPending.value || !PreRenderCoordinator.isFlushHostAttached) return@LaunchedEffect
        var stableFlushes = 0
        // 批次四d:钉位确认(hold)——分数已到目标但未经「新鲜坐标核销」确认前,
        // 恒拒绘:LEAP/塌陷帧构造性不上屏,屏面冻结在增长前帧,重组卡顿不可见。
        // 真机三轮定案:坐标回调与 pre-draw 读数各滞后一拍,「修好再画」不可达;
        // 账本(Δreport)读数同样滞后且与实测双发——唯一可靠契约是「确认前不画」。
        // 确认的唯一证据 = 新鲜坐标(|err|<1);陈旧/静止坐标不得确认(批次四c 教训:
        // 等值陈旧坐标被当确认 → hold 提前放行 → LEAP 帧上屏)。
        var confirmedSinceTarget = false
        var holdRefusals = 0 // 呼吸阀:连续拒绘上限(防回调若为绘制驱动时的死锁)
        // 泵帧:dispatch 后的下一帧放行——该帧已是修正后布局(dispatch 同步滚动),
        // 绘制即驱动坐标回调盖同源戳,下一 pre-draw 即可确认。破解「确认依赖回调、
        // 回调依赖绘制、hold 拒绘制」的死锁环(批次四e)。
        var pumpPending = false
        // 批次四f:确认时的报告读数——迟到增长(rep 变更)即刻作废确认,重进确认环
        var repAtConfirm = -1
        // 批次五:hold 起算时刻——超时预算不再计入武装→增长间的冻结/settle 窗
        // (真机 red1 定案:冷启首展冻结~700ms+重组~620ms 吃光 1.5s 预算,超时比
        // 修正回调早 68ms 触发 → 修正器阵亡 → 后续漂移无人管 = 用户「顶开不复位」)
        var holdStartNs = 0L
        // 批次五b:静默守望——真机矩阵定案(B/C 红):episode 结束数秒后仍有迟到增长
        // (表格 containerWidth 多拍收敛/asyncParse 等,+61~+78px 实测),修正器若在
        // 稳定后即刻解散则无人接管 → 视口被顶走直到下次收起才被配对顺手修回
        // = 用户「展开顶开不复位/收起顶一下又复位」的统一根因。确认后须等
        // [PIN_QUIET_MS] 无 rep 变更才允许解散;窗内增长自动重进确认环。
        var lastRepSeen = clock.lastReportedH
        var lastRepChangeNs = System.nanoTime()
        val tStart = System.nanoTime()
        val task = PreDrawFlushTask {
            val tgt = pinAnchor.floatValue
            var allow = true
            if (clock.lastReportedH != lastRepSeen) {
                lastRepSeen = clock.lastReportedH
                lastRepChangeNs = System.nanoTime()
            }
            // 批次四f 定案(三轮取证的最终拼图):增长 measure 在 pre-draw 之后、
            // draw 之前执行——pre-draw 读到的 rep 必属旧布局(账本在增长帧永不可
            // 见增长);而 fraction 是 driveTo 同步内存写,pre-draw 时已到位。
            // 「分数已到 ∧ 报告未追」= 增长正在本帧落地 → 恰是必须拒绘的帧。
            // 故:hold 只认分数到位(报告未追的错位帧更须拒);确认认
            // 分数到位 ∧ 报告已追上 ∧ 坐标同源戳——三层防假确认。
            val fractionAtTarget = abs(clock.fraction - clock.pinTargetFraction) < 0.001f
            val reportCaughtUp = clock.lastReportedH == (clock.fraction * clock.lastMeasuredH).toInt()
            val geometryAtTargetNow = fractionAtTarget && reportCaughtUp
            if (confirmedSinceTarget && clock.lastReportedH != repAtConfirm) {
                confirmedSinceTarget = false // 迟到增长:确认作废,重进确认环
            }
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "CardExpand",
                    "[DEBUG-423] flush-entry rep=" + clock.lastReportedH +
                        " f=" + "%.3f".format(clock.fraction) +
                        " topY=" + revealTopY.floatValue.toInt() +
                        " guard=" + guardCur.floatValue.toInt() +
                        " conf=" + confirmedSinceTarget + " geo=" + geometryAtTargetNow,
                )
            }
            if (clock.userScrollCancelled) {
                // 用户滚动优先权铁律——用户已接管,立即自灭不与手指互搏
                pinPending.value = false
            } else when {
                tgt.isNaN() -> stableFlushes = 3 // 无锚,放弃
                // ===== 账本阶段:rep 于 measure 相写,pre-draw 必新鲜——增长落地帧
                // 即时全额修正(坐标回调绘制驱动,等坐标必晚一帧=v3.1 漏点)。
                // 共享会计:三路 dispatch 全部入账,杜绝 e6 实证的双发。=====
                (clock.lastReportedH - pinLedgerBase.floatValue).let { abs(it - pinDispatchedTotal.floatValue) >= 1f } -> {
                    val errLedger = (clock.lastReportedH - pinLedgerBase.floatValue) - pinDispatchedTotal.floatValue
                    val consumed = runCatching { listState.dispatchRawDelta(errLedger) }.getOrDefault(0f)
                    pinDispatchedTotal.floatValue += errLedger
                    clock.recordDisplacement(consumed)
                    if (!clock.departureFired && abs(clock.episodeDisplacement) > DEPARTURE_THRESHOLD_PX) {
                        clock.departureFired = true
                        departure?.invoke()
                    }
                    stableFlushes = 0
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(
                            "CardExpand",
                            "[DEBUG-423] pin-ledger d=" + errLedger.toInt() + " consumed=" + consumed.toInt() +
                                " rep=" + clock.lastReportedH,
                        )
                    }
                    allow = false // 拒绘一拍:修正后的重排落地再画(该帧必为已修正态)
                    pumpPending = true
                }
                revealTopY.floatValue.isNaN() -> { /* 未放置,等下一 pre-draw */ }
                // ===== 实测阶段:新鲜坐标核销残差 + 唯一确认来源 =====
                revealTopY.floatValue == guardCur.floatValue -> {
                    // 坐标静止(放置回调已开火待重排)——计稳定但不确认:
                    // 陈旧等值 ≠ 钉稳证据(批次四c 教训);只认 guardCur(dispatch
                    // 前值)——修正后「回到原位」同值坐标是新鲜证据(批次四d 教训)。
                    stableFlushes++
                }
                else -> {
                    val cur = revealTopY.floatValue
                    val err = tgt - cur
                    val sameEpoch = coordStamp.intValue == clock.lastReportedH
                    if (abs(err) >= 1f && cur != guardCur.floatValue) {
                        // 坐标新鲜才开火(陈旧重复开火 = ±684 振荡,#425 同族)
                        guardCur.floatValue = cur
                        val consumed = runCatching { listState.dispatchRawDelta(err) }.getOrDefault(0f)
                        pinDispatchedTotal.floatValue += consumed
                        clock.recordDisplacement(consumed)
                        if (!clock.departureFired && abs(clock.episodeDisplacement) > DEPARTURE_THRESHOLD_PX) {
                            clock.departureFired = true
                            departure?.invoke()
                        }
                        stableFlushes = 0
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(
                                "CardExpand",
                                "[DEBUG-423] pin-flush err=" + err.toInt() + " consumed=" + consumed.toInt() +
                                    " cur=" + cur.toInt() + "/" + tgt.toInt(),
                            )
                        }
                        allow = false
                        pumpPending = true
                    } else if (abs(err) < 1f && sameEpoch && reportCaughtUp) {
                        // 新鲜 + 无残差 + 坐标同源 + 报告已追上——钉稳确认
                        confirmedSinceTarget = true
                        repAtConfirm = clock.lastReportedH
                        holdRefusals = 0
                        stableFlushes++
                    } else {
                        // err<1 但坐标属旧布局(不同源)——等待坐标刷新,不确认
                        stableFlushes++
                    }
                }
            }
            // 灭钉门控:稳定×3 且几何分数到位(settle 期报告恒 ε,不得灭钉)且
            // 已经确认;超时 1.5s 兜底(亦解 hold)。confirmed=false 的超时退场
            // 意味着钉位失败——episode 末 end-restore 兜底修正。
            val quietMs = (System.nanoTime() - lastRepChangeNs) / 1_000_000f
            if ((stableFlushes >= 3 && geometryAtTargetNow && (confirmedSinceTarget || tgt.isNaN()) &&
                quietMs > PIN_QUIET_MS) ||
                (holdStartNs != 0L && (System.nanoTime() - holdStartNs) / 1_000_000f > 2000f) ||
                (System.nanoTime() - tStart) / 1_000_000f > 6000f
            ) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "CardExpand",
                        "[DEBUG-423] pin done stable=" + stableFlushes +
                            " ms=" + ((System.nanoTime() - tStart) / 1_000_000f).toInt() +
                            " confirmed=" + confirmedSinceTarget,
                    )
                }
                pinPending.value = false
            }
            // hold:分数到位但未确认钉稳 → 拒绘(屏面冻结在增长前帧);呼吸阀:
            // 连续 HOLD_VENT_FRAMES 次拒绘后放行一帧(若坐标回调为绘制驱动,
            // 该帧驱动回调刷新——至多一帧错误态闪现,远优于恒顶开)。
            if (fractionAtTarget && !confirmedSinceTarget && pinPending.value) {
                if (holdStartNs == 0L) holdStartNs = System.nanoTime()
                if (pumpPending || pumpArm.value) {
                    // 泵帧放行:修正后布局上屏,驱动坐标回调盖戳(pin-placed 亦武装)
                    pumpPending = false
                    pumpArm.value = false
                } else if (holdRefusals < HOLD_VENT_FRAMES) {
                    allow = false
                    holdRefusals++
                } else {
                    holdRefusals = 0
                }
            } else {
                holdStartNs = 0L // 不持时重置(重定向后再持有重新计时)
            }
            allow
        }
        PreRenderCoordinator.registerFlushTask(task)
        try {
            snapshotFlow { pinPending.value }.first { !it }
        } finally {
            PreRenderCoordinator.unregisterFlushTask(task)
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .cardExpandGeometry(clock)
            .onPlaced {
                // #423 批次四:布局相写入。真机取证(09-22):onGloballyPositioned 实测
                // 在绘制期才派发——晚于 pre-draw,FLUSH 修正器在增长落地帧只能读到陈旧值
                // (LEAP 帧 pre-draw 读 1144、绘制期才见 -3536 → 顶开帧上屏;err=4 的
                // 小修正同样晚 44ms)。onPlaced 在放置相派发(先于 pre-draw),FLUSH 相
                // 读到的即本帧地面真值。onGloballyPositioned 保留(兜底 + PLACED 日志)。
                revealTopY.floatValue = it.positionInRoot().y
                coordStamp.intValue = clock.lastReportedH
            }
            .onGloballyPositioned {
                revealTopY.floatValue = it.positionInRoot().y
                coordStamp.intValue = clock.lastReportedH
                // #423 批次四c:放置回调即时修正——坐标回调虽晚于 measure,但早于
                // 下一 pre-draw(真机:LEAP 帧 .377 回调/.380 绘制,而 pre-draw 读数
                // 滞后到 .887)。回调到达即 dispatch,把修正提前整整一个卡顿窗;
                // guardCur 保证与 FLUSH 实测阶段不双发(同一坐标只许一个开火)。
                if (pinPending.value && clock.animating && !clock.userScrollCancelled) {
                    val cur2 = revealTopY.floatValue
                    val tgt2 = pinAnchor.floatValue
                    if (!tgt2.isNaN() && !cur2.isNaN() && abs(tgt2 - cur2) >= 1f && cur2 != guardCur.floatValue) {
                        guardCur.floatValue = cur2
                        val consumed2 = runCatching { listState.dispatchRawDelta(tgt2 - cur2) }.getOrDefault(0f)
                        pinDispatchedTotal.floatValue += consumed2
                        pumpArm.value = true
                        clock.recordDisplacement(consumed2)
                        if (!clock.departureFired && abs(clock.episodeDisplacement) > DEPARTURE_THRESHOLD_PX) {
                            clock.departureFired = true
                            departure?.invoke()
                        }
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(
                                "CardExpand",
                                "[DEBUG-423] pin-placed err=" + (tgt2 - cur2).toInt() +
                                    " consumed=" + consumed2.toInt() + " cur=" + cur2.toInt() + "/" + tgt2.toInt(),
                            )
                        }
                    }
                }
                if (BuildConfig.DEBUG && clock.animating) {
                    AppLogger.d(
                        "PRD",
                        "E" + episodeSeq.intValue + " PLACED t=" + System.nanoTime() +
                            " topY=" + revealTopY.floatValue.toInt() +
                            " rep=" + clock.lastReportedH + " abs=" + clock.absorbedPx +
                            " f=" + "%.3f".format(clock.fraction) +
                            " fii=" + listState.firstVisibleItemIndex +
                            " fiso=" + listState.firstVisibleItemScrollOffset,
                    )
                }
                // 批次二:FLUSH 宿主在场时排干由 pre-draw 单点执行(placed 触发
                // 的重排来不及同帧 draw 前生效=瞬态根源);降级路径才走此触发。
                if (phaseADrain.value && !PreRenderCoordinator.isFlushHostAttached) drainPhaseA("placed")
            }
    ) {
        // #420 追诊:content 组合生命周期归几何时钟统一拥有(fraction>0 即组合)。
        // 原方案 AV fadeOut(300ms) 与几何 tween(240ms) 两时钟分离——掉帧时
        // 几何未走完而 AV 已把 content 移出组合 → H 突塌 f·738px 无补偿
        // (实测 flow-b 收起终末 −217px 跳变)。fade 改由分数驱动(前/后 30%),
        // 与几何同起止,杜绝第二时钟。
        if (clock.fraction > 0f) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (minOf(clock.fraction, drawFraction.floatValue) / 0.3f).coerceIn(0f, 1f)
                    }
                    .drawWithContent {
                        // #425 B 阶段纯绘制揭示:clipRect 高度系数,只重绘不重排
                        val w = if (clock.fraction > 0.001f) curtain.floatValue else 0f
                        drawnTopY.floatValue = revealTopY.floatValue // 批次十:绘制真值采样
                        if (BuildConfig.DEBUG && clock.animating) {
                            AppLogger.d(
                                "PRD",
                                "E" + episodeSeq.intValue + " DRAW t=" + System.nanoTime() +
                                    " drawF=" + "%.3f".format(w) +
                                    " boxH=" + size.height +
                                    " clipPx=" + (size.height * w).toInt() +
                                    " residual=" + (clock.lastReportedH - clock.absorbedPx) +
                                    " topY=" + revealTopY.floatValue.toInt(),
                            )
                        }
                        if (w >= 1f) {
                            this@drawWithContent.drawContent()
                        } else {
                            clipRect(right = size.width, bottom = size.height * w) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    },
            ) {
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
 * #425 渲染前指令(纯函数可单测):本帧 dispatch 量 = 目标 − 已吸收账本。
 *
 * 两轮真机教训:
 * 1) (目标−已吸收)+实测偏差 会振荡——实测信号(布局坐标)对滚动位移是瞎的
 *    且滞后,逐帧纠偏反而成为扰动源(录屏条带追踪:±24-38px 来回震荡,
 *    logcat topErr 逐帧抖动 2→8→25→45→57);
 * 2) 纯增量开环(#420 原式)会丢残量——组合滞后期被拒的 δ 永久流失(净漂)。
 * 本式:未消费残量经「目标−已吸收」在下帧自动重试(不丢失),且全程单向——
 * 结构性无振荡。钳制防残量集中释放的单帧暴冲。
 */
internal fun closedLoopCommand(targetRep: Int, absorbed: Int): Int =
    (targetRep - absorbed).coerceIn(-MAX_CLOSED_LOOP_DELTA_PX, MAX_CLOSED_LOOP_DELTA_PX)

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
    anchorY: Float,
    currentTopY: Float,
    unclamped: Boolean = false,
) {
    // 崩溃修复(真机收起末 IllegalState: LayoutNode should be attached):
    // fraction→0 会移除 content,必须先关 placeable 缓存窗口,否则同一帧
    // 布局仍会放置已离树的缓存 placeable。
    if (targetFraction <= 0.001f) clock.tweening = false
    clock.driveTo(targetFraction)
    val cmd = closedLoopCommand(
        targetRep = (targetFraction * clock.lastMeasuredH).toInt(),
        absorbed = clock.absorbedPx,
    )
    val d = (if (unclamped) cmd else cmd.coerceIn(-MAX_CLOSED_LOOP_DELTA_PX, MAX_CLOSED_LOOP_DELTA_PX)).toFloat()
    if (d == 0f) return
    val consumed = runCatching { listState.dispatchRawDelta(d) }.getOrDefault(0f)
    clock.absorb(consumed)
    clock.recordDisplacement(consumed)
    if (BuildConfig.DEBUG) {
        AppLogger.d(
            "CardExpand",
            "[DEBUG-425] cmd d=" + d.toInt() + " consumed=" + consumed.toInt() +
                " topY=" + currentTopY.toInt() + "/" + anchorY.toInt() +
                " absorbed=" + clock.absorbedPx + " rep=" + clock.lastReportedH + " H=" + clock.lastMeasuredH,
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
