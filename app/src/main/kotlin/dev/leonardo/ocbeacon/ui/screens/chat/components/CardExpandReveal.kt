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
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
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
 *
 * **#430 稳态配对(2026-09-24)**:配对契约从「episode 单时刻」连续化为
 * 「逐帧」——episode 外的迟到增长(分批表格逐组落地/asyncParse/图片)由
 * 稳态账本(measure 记账 Δreport)+ pre-draw flush 派发 +Δ 接管;否则
 * reverseLayout 锚定把每个窗外增量全额转译为上方内容上顶(#429 v4 分批
 * 使其显性化:大表展开 settle 600ms 窗外 ~31kpx 裸增长=「向上顶」主诉)。
 * episode 派发点/收起点/取消点 [CardExpandClock.steadyRebase] 防双配对。
 */

/** 宿主 LazyListState;null = 降级裸 AV。 */
internal val LocalCardExpandListState = compositionLocalOf<LazyListState?> { null }

/** 展开离底时的「离开跟随」回调(实现:关 autoScroll);null = 降级裸 AV。 */
internal val LocalCardExpandDeparture = compositionLocalOf<(() -> Unit)?> { null }

/** 当前 item 是否属于流式 turn(CML 逐 item 提供);true = 降级裸 AV。 */
internal val LocalInStreamingTurn = staticCompositionLocalOf { false }

/**
 * #508:本卡宿主 item 的 key(CML 逐 item 提供)。展开反射目标归一需知 +H 增长
 * 落在哪个 item——宿主=锚 item 时 (fii,fiso+H) 随同遍增长落地恒合法(fiso≤尺寸
 * ⇔ fiso+H≤尺寸+H),永不折叠;宿主在锚 item 上方时才需向旧侧折算且宿主容量
 * 按 +H 计。null=宿主未提供(横幅/预览/其他未挂供给的 item),按宿主=锚 item
 * 兜底(反射路径现存唯一消费域=turn 条目块,恒有供给;横幅走裸 AV 不经此)。
 */
internal val LocalCardExpandHostKey = compositionLocalOf<Any?> { null }

/**
 * #540：列表级 [HeightReserveState]（CML 逐 item 供给；null=宿主未挂/横幅/单测/
 * 预览——降级路径维持裸 AV 现状）。两处消费：流式降级分支的开合「展开配对窗」
 * （窗内帽释放恒配对=消除上推源）；机件原子帧的帽×机件单写者抬/落帽。
 */
internal val LocalCardExpandReserve = compositionLocalOf<HeightReserveState?> { null }

/**
 * #540 全量接入（用户裁决：所有展开卡统一入 #533 进程级终高 floor）：part 域
 * 展开卡的默认缓存键——[PartContent] 逐 part 供给 `part.id`；[ToolCardScaffold]
 * 等以 `显式参数 ?: 本 local` 取键。
 *
 * **键律（竞态稳定性承重，违反即单调 store 跨内容污染=过冲族）**：
 * 1. 键 = 稳定内容身份（part.id / 消息级 eventKey / 对象 id）——part id 跨毕业
 *    稳定（#509 宿主前缀）、msgId 跨换名稳定（MessageIdSwapped 原地改名）；
 * 2. 禁止类型级常量键——内容随状态变化的瞬态卡（状态横幅族）无稳定身份，
 *    显式不入店并以注释声明排除理由；
 * 3. floor 语义集中在 [CardExpandGeometryNode]（1.5s 稳定释放/宽度键控/LRU），
 *    接入只供键，机制全卡一致。
 */
internal val LocalCardExpandCacheKey = compositionLocalOf<Any?> { null }

/** 展开位移离开贴底区(补偿后)判定阈值,对齐 isAtBottom 的 100px 判据。 */
private const val DEPARTURE_THRESHOLD_PX = 100f

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
 * #423 批次十三:空闲预热等待(ms)——折叠卡可见且用户静止后,提前以 ε 高度
 * 组合+测高(大内容「加速」而非拆分,用户裁决 2026-09-22)。
 */
private const val PREWARM_IDLE_MS = 1_200L

/** #430:steady 0 消费欠账重试窗(真机 19:46 定罪:增长落地帧可比 dispatch 晚 ~400ms)。 */
private const val STEADY_RETRY_WINDOW_NS = 2_000_000_000L



/**
 * #427 收起位移终局根因修复：配对派发的残差重试参数（纯决策，JVM 可单测）。
 * 真机取证（#427 两会话复现）：展开 dispatch 需 +20352 实消费 +14190、收起
 * 需 −20352 实消费 −14458——**跨锚点巨位移的测量竞态**（派发滚动途中条目重测，
 * 高度增长/塌缩即时改写可消费空间）令两次欠消费残差不等（6162−5894=268），
 * 开环单发把差值永久漏成视口净漂：中位列位每展开-收起周期恒 −268px（用户
 * 实测「收起后整体对话上移一小段」）；列表边缘（可消费空间对称饱和）零漂。
 * 语义：同相位（渲染前、programmaticShift 豁免内）以「目标−已消费」为下一发
 * 指令重试至全额——#425 吸收账本语义的最小复活，**不是渲染后补偿**（补偿族
 * 两次被用户否决；本修复只把「单发配对」补完为「配对到全额」，时序仍在
 * measure 前的原子相位内）。
 */
internal object PairedDispatch {
    internal const val MAX_TRIES = 4

    /** 下一发指令；null=结束（全额/亚像素/零消费物理边缘/超上限）。 */
    fun nextCommand(requested: Float, consumed: Float, tries: Int): Float? {
        if (tries >= MAX_TRIES) return null
        val remaining = requested - consumed
        if (abs(remaining) < 0.5f) return null // 整数守恒量化阈（同 advance/onMeasure）
        if (abs(consumed) < 0.5f) return null // 物理不可消费（边缘/布局未就绪）——余量由布局吸收
        return remaining
    }
}

/**
 * #427：配对派发执行器（渲染前位移全额版——残差重试至目标）。
 * 展开/收起的原子位移共用；全消费路径与旧行为逐字节一致（首轮即全额）。
 */
internal fun applyPairedPreRenderShift(ls: androidx.compose.foundation.lazy.LazyListState, deltaPx: Float): Float {
    var consumedTotal = 0f
    try {
        var requested = deltaPx
        var tries = 0
        while (true) {
            val consumed = ls.dispatchRawDelta(requested)
            consumedTotal += consumed
            val next = PairedDispatch.nextCommand(deltaPx, consumedTotal, tries) ?: break
            requested = next
            tries++
        }
        if (BuildConfig.DEBUG && abs(consumedTotal - deltaPx) >= 0.5f) {
            AppLogger.d(
                "CardExpand",
                "[DEBUG-427] paired-shift target=" + deltaPx.toInt() +
                    " consumed=" + consumedTotal.toInt() + " tries=" + tries,
            )
        }
    } catch (t: Throwable) {
        AppLogger.w("CardExpand", "paired pre-shift failed: " + t.message)
    }
    return consumedTotal
}

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
 * #529:锚点捕获静止门——指纹计稳帧数(同 settle 判稳约定:连续 N 帧不变)。
 */
private const val ANCHOR_QUIESCE_STABLE_FRAMES = 2

/**
 * #529:锚点捕获静止门墙钟上限(ms)。回收重组合后 item 异步内容落地风暴
 * 有界;超时按现状捕获(E3+ 恒稳,零感知不受影响)。
 */
private const val ANCHOR_QUIESCE_MAX_MS = 200f

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
        // #427 收起位移根因修复：ε 预热窗口(fraction≤WARMUP_FRACTION)上报恒 0——
        // (fraction×H) 在 20k 级内容上 ε 仍 ~20px 布局残高，±H 配对账本每循环净漂
        // ~ε·H（真机用户实测「收起后整体上移一小段」；批次十三 0.7px 零视觉标定
        // 不再成立于大内容）。组合保温(fraction>0)不受影响，仅布局足迹归零。
        val target = if (newFraction <= WARMUP_FRACTION) 0 else (newFraction * lastMeasuredH).toInt()
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
        // #427：同 advance——ε 预热窗口零上报（残高∝H 在大内容上显性为位移净漂）
        val report = if (fraction <= WARMUP_FRACTION) 0 else (fraction * realH).toInt()
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
        // #430:取消路径位置归 snap/用户所有,丢弃稳态未派发增量
        steadyRebase()
        steadyHold = false
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
     * #427 收起位移终局根因(F3 定案)：展开集的**实际消费位移**(px,plain)。
     * 真机取证:展开 dispatch 需 +H 实消费 +14190(可消费空间所限),收起若按
     * 全高 −rep(−20352)回退则过冲 6162——直接把列表砸到最新端边缘(fii=0
     * fiso=0),残差漏成每周期恒定视口净漂(中位 −268/小卡 −62,两会话复现)。
     * 配对契约修正:收起退回**展开实位移**的负值(镜像对称),非内容全高。
     */
    var episodeShiftConsumedPx = 0f

    /**
     * #427(终局)：展开集派发前的锚点状态(fii/fiso,plain)。滚动消费账与几何
     * 位移账永差条目 padding 一档(实测 2972 vs 2910,差=messageSpacing)——
     * 位移镜像仍有 ±spacing 残差。收起的正解=**恢复展开前锚点状态**
     * (scrollToItem 前值,按构造精确);用户滚动过(位置优先权)则回退镜像。
     */
    var episodeAnchorItem = -1
    var episodeAnchorOffset = 0

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

    // ===== #430 稳态配对账本(episode 外迟到增长——分批表格逐组落地/asyncParse 完成等) =====
    // 背景:v4 分批让大表高度增长跨越 settle 窗口(600ms)逐帧落地;此前 episode 只在
    // 单一时刻(dispatch)配对一次,窗外增长全部裸落地——reverseLayout 锚定下逐帧把
    // 上方内容顶起(用户主诉「展开向上顶」)。本账本把配对契约从「瞬时」改为「连续」:
    // measure 相记账(report 变化量),pre-draw flush 相派发,帧内闭合。
    private var steadyLedger = Int.MIN_VALUE

    /** 稳态待配对位移累计(px,带符号;measure 相写,flush 相 [takeSteadyPending] 取走)。 */
    var steadyPending = 0f
        private set

    /**
     * measure 相调用:稳态(任意 fraction)记账 report 变化量。
     * 基线语义:MIN_VALUE=未起账,下一次调用静默建立基线(不配对)——rebase 协议
     * ([steadyRebase])在 episode 派发点/收起点/取消点调用,防 episode 自身的高度
     * 跳变被重复配对。fraction<=0(收起/未组合)恒清零——无布局足迹即无配对义务。
     */
    fun noteSteadyReport(report: Int) {
        if (fraction <= 0f) {
            steadyLedger = Int.MIN_VALUE
            steadyPending = 0f
            return
        }
        if (steadyLedger == Int.MIN_VALUE) {
            steadyLedger = report
            return
        }
        val d = report - steadyLedger
        if (d != 0) {
            steadyPending += d.toFloat()
            steadyLedger = report
            // 新生义务的重试窗同步起算(与欠账同语义)
            steadyRetryDeadlineNs = System.nanoTime() + STEADY_RETRY_WINDOW_NS
        }
    }

    /** 配对基线重置:丢弃未派发增量,下次 measure 静默重建基线。episode 边界专用。 */
    fun steadyRebase() {
        steadyPending = 0f
        steadyLedger = Int.MIN_VALUE
        steadyRetryDeadlineNs = 0L
    }

    /**
     * #430 修正:展开集 dispatch 点的**欠账 rebase**——steadyLedger 锚定派发目标,
     * steadyPending = 目标−实消费。真机定罪(19:34 复现):dispatch 内部测量可能
     * 看不到下一帧才落地的增长(transient consumed=0),旧协议(rebase→MIN)会把
     * 该增长静默吸进新基线=永不配对→2852px 裸上顶。欠账模型下:落地帧 report
     * ==ledger(d=0),欠账由 flush 在幕布期/集后补派(容量已随增长出现)。
     * k==fii 构型全额消费与零消费视觉等价(19:31 vs exp1 双证),残量补派安全。
     */
    fun steadyRebaseAfterEpisodeDispatch(targetRep: Int, consumed: Float) {
        steadyLedger = targetRep
        steadyPending = (targetRep - consumed.toInt()).coerceAtLeast(0).toFloat()
        steadyRetryDeadlineNs = System.nanoTime() + STEADY_RETRY_WINDOW_NS
    }

    /**
     * #430:0 消费欠账保留(flush 相调用)——瞬态边缘(增长未随派发落地/容量
     * 晚一拍出现)下帧重试;[STEADY_RETRY_WINDOW_NS] 窗外不再保留(用户阅读
     * 位置优先权:迟到的纠正比永久偏移更糟)。
     */
    fun restoreSteady(d: Float) {
        steadyPending += d
    }

    /**
     * #430:steady 派发门——true=集内 dispatch 决策点之前(settle/Phase A),
     * flush 挂起(此时集内配对未定,双发风险);beginEpisode 置 true,展开/收起
     * dispatch 点置 false(幕布期即可逐帧纠偏——增长落地帧 pre-draw 同帧补派,
     * 上顶中间态从构造上不上屏)。
     */
    var steadyHold = true

    /** #430:0 消费欠账重试窗(ns;新生义务/欠账统一 +2s 起算)。 */
    var steadyRetryDeadlineNs = 0L
        private set

    /** flush 相取走全部待配对增量(取后即清——用户滚动让位时直接丢弃同理)。 */
    fun takeSteadyPending(): Float {
        val p = steadyPending
        steadyPending = 0f
        return p
    }

    fun beginEpisode() {
        episodeDisplacement = 0f
        departureFired = false
        userScrollCancelled = false
        steadyRebase()
        steadyHold = true
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
 * #540 三轮：驱动盒布局——内容按自然高无约束测高（高度不受盒驱动值压缩），
 * 盒布局高=hProvider()（读快照态，flush 写入即失效重测），顶锚放置+外层
 * clipToBounds=shrinkTowards/expandFrom Top 语义。
 */
private fun Modifier.degradeRevealHeight(hProvider: () -> Int): Modifier =
    this then object : LayoutModifier {
        override fun MeasureScope.measure(
            measurable: Measurable,
            constraints: Constraints,
        ): MeasureResult {
            val child = measurable.measure(
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
            )
            val h = hProvider().coerceIn(0, child.height)
            return layout(constraints.maxWidth, h) { child.place(0, 0) }
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
    /**
     * #427 竞态修复:预热资格谓词——false 则本卡永不预热(默认 null=恒资格)。
     * 切片+账本暖的卡:高度已入账本(展开即时),预热重组窗内怪物片=主线程
     * 2.5s 长块(真机实测)=「收起后整体卡顿」根因;且重组经 heavyComposed
     * 占位门=Spacer 首帧 63px 单帧弹跳(「收起后上推再弹回」成分)。
     */
    prewarmEligible: (() -> Boolean)? = null,
    /**
     * #429:展开集「高度计算期」信号——true=正在计算(等待 settle 落定),宿主
     * 以此渲染 loading 过渡(用户裁决 2026-09-24:先算高度+loading 过渡动画)。
     * 仅用户可见性驱动的展开集触发;null=不关心(存量调用点零改动)。
     */
    onExpandComputing: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val listState = LocalCardExpandListState.current
    val departure = LocalCardExpandDeparture.current
    // #508:宿主 item 身份在组合层读取(LaunchedEffect 协程内不可读 CompositionLocal)
    val hostItemKey = LocalCardExpandHostKey.current
    // #543 探针：组合期捕获（episode 块非组合上下文，不能现场读 local）。
    val cacheKeyAtComposition = LocalCardExpandCacheKey.current
    // #540:列表级帽状态（机件原子帧单写者抬/落帽用；组合层读取同上）
    val hatReserve = LocalCardExpandReserve.current
    if (listState == null || LocalInStreamingTurn.current) {
        // #526 定罪探针：降级路径（无 listState=非会话列表场景 / 流式 turn）不
        // 走反射锚定——DSH 展开方向不稳定疑涉此路径，toggle 时打点判别。
        if (BuildConfig.DEBUG) {
            val prevVis = remember { androidx.compose.runtime.mutableStateOf(visible) }
            if (prevVis.value != visible) {
                AppLogger.d(
                    "CardExpand",
                    "[DEBUG-526] degrade toggle visible=" + visible +
                        " reason=" + (if (listState == null) "no-list-state" else "in-streaming-turn") +
                        " host=" + hostItemKey.toString().take(24),
                )
                prevVis.value = visible
            }
        }
        // #540 方向 A 根修（取代 #526 事后回锚）：流式降级路径的展开/收起增长改经
        // 帽协议 flush 单事务「恒配对」释放——toggle 时开「展开配对窗」（计数制，
        // 同 item 多卡并发共容），尺寸稳定后关窗。窗内每档增量（含并发流式
        // 增长——展开语义=离底让位，与机件路径 departure 同向）原子配对落地，
        // 上推源从构造上消失；#526 的「先上推、稳定后一次性反射回锚」瞬态随之
        // 退役。无 reserve 供给（横幅/预览/宿主未挂/单测）维持裸 AV 现状。
        // #540 三轮（用户三报「收起来回震荡」定罪，2026-10-11）：AV 尺寸动画在
        // 流式场景退役——shrinkVertically 在 measure 相直改布局高（收缩穿透
        // min(child,reserved) 帽天花板），滚动修正却 pending 下一遍 measure →
        // 每帧画「新高度×旧滚动」错位态=震荡（展开常见格被否决门判零写入故稳，
        // 与三报「展开还好、收起震荡」吻合）。驱动盒：布局高=drive.h（flush 单
        // 事务步进，与滚动写同遍 measure 原子消费）；内容常驻组合按自然高测高、
        // 顶锚裁剪=shrinkTowards/expandFrom Top 语义；淡入淡出由 alpha 派生。
        val degradeH = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val pairingReserve = LocalCardExpandReserve.current
        // #540 二轮（表述乙）：揭示盒顶 root-Y——toggle 时刻换算 item 空间几何
        //（stickTop/spanDepth）供 flush 负向配对资格判定（粘揭示缘/区外零操作）。
        val revealTopRootY = remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }
        // #540 三轮：内容自然高（内侧盒无约束测得；-1=未测）与驱动态。
        val contentH = remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        val drive = remember { DegradeDriveState() }
        // #549 Fix A（2026-10-13，r400 slam 定罪）：降级路径终高种子——mech 路径
        // #423 批次十一 finalHCache 的同源语义（rememberSaveable(cacheKey)，跨路径
        // 共存共享）。重展开时 markdown 分期重组（staged 起步小）会把测高拽低 →
        // hTarget 被拽低 → 驱动反步进收盒 → 视口重锚下砸（实测 −931px slam）。
        // 种子使驱动直奔已知终高，分期在裁剪下追平（mech 预移同款 UX）。
        val finalHSeed = androidx.compose.runtime.saveable.rememberSaveable(cacheKey) {
            androidx.compose.runtime.mutableStateOf(-1)
        }
        // 展开相单调护栏时钟：toggle 时刻起 600ms 内 hTarget 只升不降（分期重组
        // 的暂态矮化不得反拽驱动；流式真增长不受限）。600ms 后自由跟踪（重排
        // 收缩=合法补偿，r731 判例）。
        val lastExpandAtMs = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
        val driven = listState != null && pairingReserve != null
        if (driven) {
            val pr = pairingReserve!!
            // 同值守卫（二轮④）：**收起态挂载**不开窗（避免每个折叠卡 mount 都效应）；
            // **展开态挂载**（重组重建槽位 while expanded）与真 toggle 一样开窗——
            // 重挂帧的 +H 全高入组合必须配对。
            //
            // #540 三轮修订（真机 19:02 竞态定罪）：翻转检测+驱动注册+开窗必须在
            // **组合期同步**完成——LaunchedEffect 在本帧布局后才执行，visible 翻转
            // 的重组当帧 contentActive/hProvider 先读到未注册态（driveOwned=false）
            // → 收起内容瞬时 dispose（h0=0）、展开瞬时满高（h0=tgt），驱动动画被
            // 完全旁路成单帧裸落。同步注册后同帧布局即读 driveOwned=true → 盒高
            // 钉在驱动态，塌缩/展开全程走 flush 步进（动画相走帽通道原义）。
            val toggled = remember { androidx.compose.runtime.mutableStateOf(false) }
            // prevVis 初值 false：挂载即展开读作翻转（二轮④语义）；挂载折叠无翻转。
            val prevVis = remember { androidx.compose.runtime.mutableStateOf(false) }
            val toggleSeq = remember { androidx.compose.runtime.mutableIntStateOf(0) }
            if (visible != prevVis.value) {
                prevVis.value = visible
                if (visible || toggled.value) {
                    toggled.value = visible
                    // 驱动注册——四报修订：列表制（多卡并发不互逐）+ 权威续值
                    //（快速重向时 drive.h 是本帧已 pending 的高度真值，degradeH
                    // 是上一放置值滞后一步——覆盖会把动画往回拨一步=连点抖动源）。
                    val mineAlready = pr.degradeDrives.any { it === drive }
                    drive.h = if (mineAlready && drive.animating) drive.h else degradeH.intValue
                    if (visible) lastExpandAtMs.longValue = android.os.SystemClock.elapsedRealtime()
                    drive.hTarget = if (visible) {
                        // #549 Fix A：终高种子优先（重展开已知高度零延迟追靶）；
                        // 未知（首次）维持等测高 -1 语义
                        val staged = if (contentH.intValue >= 0) contentH.intValue else -1
                        val seed = finalHSeed.value
                        when {
                            seed > 0 && seed >= staged -> seed
                            staged > 0 -> staged
                            else -> -1
                        }
                    } else {
                        0
                    }
                    if (!mineAlready) pr.degradeDrives.add(drive)
                    pr.expandPairingWindows++
                    // #551：离底让位视口预算——开窗时刻（事件时刻）置一屏主轴；
                    // 多窗并发共享（重置式），组合离底上限恒一屏
                    listState?.layoutInfo?.viewportSize?.let { vp ->
                        pr.expandDepartureBudgetLeft = vp.height
                    }
                    // 表述乙几何捕获（塌缩不变量：盒顶上方内容收起中不动）——距 item
                    // 顶偏移=盒顶 root-Y − 物主 item 根 root-Y（CML 逐 placement 供给）。
                    // 四报：几何**随卡走**（drive.stickTop/spanDepth）——reserve 级
                    // 镜像字段只服务正向否决门（最新 toggle 意图），负向滚动分量
                    // 已逐卡判定（driveScrollShift），不再被后卡覆盖。
                    val stickTop: Int
                    val spanDepth: Int
                    val itemTop = pr.hostItemTopY
                    val itemH = pr.trueHeight
                    if (!itemTop.isNaN() && itemH >= 0 && !revealTopRootY.value.isNaN()) {
                        stickTop = (revealTopRootY.value - itemTop).toInt().coerceAtLeast(0)
                        spanDepth = degradeH.intValue
                        drive.stickTop = stickTop
                        drive.spanDepth = spanDepth
                        pr.expandCollapseStickTop = stickTop
                        pr.expandCollapseSpanDepth = spanDepth
                    } else {
                        stickTop = -1
                        spanDepth = -1
                        drive.stickTop = -1
                        drive.spanDepth = -1
                    }
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(
                            "CardExpand",
                            "[DEBUG-540] pairing-window open host=" + hostItemKey.toString().take(24) +
                                " windows=" + pr.expandPairingWindows +
                                " reveal-geo stickTop=" + stickTop + " span=" + spanDepth +
                                " itemH=" + itemH + " boxY=" +
                                (if (revealTopRootY.value.isNaN()) "na" else revealTopRootY.value.toInt().toString()) +
                                " drive h0=" + drive.h + " tgt=" + drive.hTarget,
                        )
                    }
                    toggleSeq.intValue++
                }
            }
            LaunchedEffect(toggleSeq.intValue) {
                if (toggleSeq.intValue == 0) return@LaunchedEffect // 挂载折叠：无窗
                try {
                    // E2E 边界定罪（540-fix-w1-r2）：静默期 toggle 时空卡时 AV 内容可
                    // 迟到（实测 +63ms）——toggle 时刻盒高与稳定值同值会让「3 帧稳定」
                    // 在内容落地前触发（窗 211ms 早闭，增量落窗外免配对）。守卫：
                    // 盒高必须**相对 toggle 时刻发生过变化**才允许计稳；零净变化
                    // （空输出卡）由 40 帧上限收窗（窗内无增长=配对零操作，无害）。
                    // 展开态挂载（sawChange 系初值 atToggle=盒高）同守卫适用。
                    val atToggle = degradeH.intValue
                    var sawChange = false
                    var last = -1
                    var stable = 0
                    var waited = 0
                    while (stable < 3 && waited < 40) { // 驱动 ~180ms；上限 ~650ms
                        withFrameNanos { }
                        waited++
                        val s = degradeH.intValue
                        if (s != atToggle) sawChange = true
                        if (!sawChange) continue
                        if (s == last) stable++ else {
                            stable = 0
                            last = s
                        }
                    }
                } finally {
                    pr.expandPairingWindows--
                    if (pr.expandPairingWindows <= 0) {
                        pr.expandCollapseStickTop = -1
                        pr.expandCollapseSpanDepth = -1
                    }
                    // #551：种子 settle 事件锚定——episode 关窗时刻以**当下**内容高
                    // 刷新（高度动态；上窗的值不跨事件滞留）。分期未追平的极端窗
                    // 由 toggle 时刻的 600ms 单调护栏兜底（staged 小值不反拽）。
                    if (contentH.intValue > 0) finalHSeed.value = contentH.intValue
                    // 三轮：驱动完结注销（四报列表制）；未完结（40 帧上限早闭/手势
                    // 冻结）保留在列表给 flush 续步（稳态步进=0 无害）。
                    if (!drive.animating) {
                        pr.degradeDrives.removeAll { it === drive }
                    }
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(
                            "CardExpand",
                            "[DEBUG-540] pairing-window close host=" + hostItemKey.toString().take(24) +
                                " windows=" + pr.expandPairingWindows,
                        )
                    }
                }
            }
        }
        if (!driven) {
            Box(
                modifier = modifier
                    .onSizeChanged { degradeH.intValue = it.height }
                    .onGloballyPositioned { coords ->
                        revealTopRootY.value = coords.positionInRoot().y
                    },
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = CardExpandEnterTransition,
                    exit = CardExpandExitTransition,
                ) {
                    content()
                }
            }
            return
        }
        // 三轮：驱动盒。高度唯一写者=flush（drive.h 仅在 applyPairedShift 事务内
        // 推进）；孤儿回落（列表中被逐，罕见面）=稳态快照（visible?内容高:0）。
        // 五报 F1：**已注册未接管**（hTarget 悬挂等测高）窗口同样走 drive.h——
        // 旧回退读 contentH，而 contentH 由内侧 onSizeChanged 在放置后才写，其
        // 失效触发的布局相重测会经**陈旧 driveOwned 捕获**把全高闪挂一帧
        // （真机定罪：+260 全高挂载配对上跳 → 重组钳回 drive.h 未配对收缩
        // −121 → 框架过滚纠正 fiso 回跳 −178 =「向上展开再跳回」三段式，
        // 2026-10-12 五报 r2）。driveRegistered 在组合期同步注册后恒真、
        // drive.h 为快照态活读——两窗口（等靶/动画中）合一，闪挂源消除。
        val driveRegistered = pairingReserve?.degradeDrives?.contains(drive) == true
        val driveOwned = driveRegistered && drive.animating
        Box(
            modifier = modifier
                .onSizeChanged { degradeH.intValue = it.height }
                .onGloballyPositioned { coords ->
                    revealTopRootY.value = coords.positionInRoot().y
                },
        ) {
            if (visible || driveOwned) {
                Box(
                    androidx.compose.ui.Modifier
                        .degradeRevealHeight {
                            if (driveRegistered) drive.h else if (visible) contentH.intValue.coerceAtLeast(0) else 0
                        }
                        .clipToBounds()
                        .graphicsLayer {
                            val full = contentH.intValue
                            alpha = if (full > 0) {
                                (if (driveRegistered) drive.h else if (visible) full else 0)
                                    .toFloat() / full.toFloat()
                            } else {
                                1f
                            }
                        },
                ) {
                    Box(
                        androidx.compose.ui.Modifier.onSizeChanged { sz ->
                            contentH.intValue = sz.height
                            // #551：种子不再 max 棘轮（七报定罪：跨事件冻结暂态
                            // 膨胀高）——种子的刷新移至窗关时刻（settle 事件锚定）
                            // #549 Fix A：展开相单调护栏——toggle 后 600ms 内 hTarget
                            // 不因分期暂态矮化而下调（r400 slam 根源）；此后自由跟踪
                            //（流式增长恒升格、重排收缩=合法补偿）
                            if (visible) {
                                val desired = if (
                                    android.os.SystemClock.elapsedRealtime() - lastExpandAtMs.longValue < 600
                                ) maxOf(sz.height, drive.hTarget) else sz.height
                                if (drive.hTarget != desired) drive.hTarget = desired
                            }
                        },
                    ) {
                        content()
                    }
                }
            }
        }
        return
    }

    val clock = remember { CardExpandClock(initialFraction = if (visible) 1f else 0f) }

    // #424 闭环位置恢复:reveal 盒顶缘(=折叠行底缘)窗口坐标实测 Y。episode 末
    // 以实测对账修正开环 δ 账本累积的视口漂移(边缘残量/锚点翻转会计误差)。
    val revealTopY = remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }

    /** #526b 取证：reveal 盒实占高度（onSizeChanged 写入；PLACED 探针读）。 */
    val revealBoxH = remember { androidx.compose.runtime.mutableIntStateOf(0) }

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
        // #551 高度参照系裁决（2026-10-13）：事件时刻直写——两处调用点均写
        // settle/落地时刻的实测高（episode 派发落地高 / 预热稳定高），高度动态
        // 变化，max 棘轮会把暂态膨胀高永久钉住（七报 tgt=13807 虚高实证同族）。
        finalHCacheRaw.value = h
        finalHCache.value = h
    }

    /** #423 批次十一:幕布系数(纯 draw 揭示;draw 相读,动画循环写)。 */
    val curtain = remember { androidx.compose.runtime.mutableFloatStateOf(if (visible) 1f else 0f) }

    // #429 时间切片 v3:信号方案退役(两轮定罪:子树 Local 够不到 + async parse
    // 使表格组合晚于计算窗口,窗口与组合时机结构性错位)——大表首组合恒分批
    // (MarkdownTable stagedLimit 起步 1),不再依赖任何计算期信号。

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
     * #423 观测:episode 序号(每集递增;PLACED/DRAW/排干日志共用,帧级对账用)。
     * 仅 DEBUG 且仅动画窗口输出——常态滚动零行,杜绝日志膨胀。
     */
    val episodeSeq = remember { androidx.compose.runtime.mutableIntStateOf(0) }

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
            // #429:展开集计算期开始(收起集无等待语义,不置位)。
            // 关键:置位后先等一帧——spinner 的重组若与 ε 内容组合同帧,
            // 会被压在 2s 级巨帧之后到计算结束才首次渲染(真机 Spin429 探针
            // 定罪:true→ROW computing 相隔 2.3s)。先让 spinner 单独上一帧。
            if (target > 0f) {
                onExpandComputing?.invoke(true)
                withFrameNanos { }
            }
            // #543 定罪探针：episode 起点=hostItemKey+cacheKey 身份对——双宿主
            // （同 item 双实例 / 跨 item 双条目）在此一次分辨。
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "CardExpand",
                    "[DEBUG-543] episode-start hostKey=" + hostItemKey +
                        " cacheKey=" + cacheKeyAtComposition +
                        " target=" + target,
                )
            }
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
                    // #529:锚点捕获静止门——E1(回收重组合后首个展开集)恰逢滚动
                    // 余尾+item 异步内容跨测量遍落地,捕获基在「捕获→待定区消费」
                    // 窗内漂移=反射绝对位双计/半计(真机两脏样本 topY+H/+H/2)。
                    // settle 只管本卡内容;此处把静止契约扩到整列基(滚动+全部可见
                    // item 尺寸),随后才读 H 与锚点(基静后读数即最新)。
                    awaitAnchorQuiescence(listState)
                    // #430:派发目标=**落地高度**(lastMeasuredH),不再 maxOf 缓存——
                    // v4 分批下落地≠缓存终值,按缓存超额派发=中位伪滚动(前向容量被
                    // 提前吃掉)+ 边缘重试白跑;窗外增量由稳态配对(#430)逐帧接管。
                    // 缓存价值回归"预知 floor"(prewarm/settle 提示),不作位移指令。
                    val H = clock.lastMeasuredH.also { if (it > 0) storeFinalH(it) }
                    // #466 三轮(机构替换,2026-09-30 用户复验定罪):非贴底下半
                    // 构型「闪烁→重定位→展开」= dispatchRawDelta 开环位移指令的
                    // 执行竞态(#427 实证跨锚点大位移欠消费 20352→14190 + 残差
                    // 重试链 + steady 多帧补派);上半构型恰好全额消费一帧落地
                    // =「正常」。终态(dispatch 全额消费)语义本身正确——顶侧稳定
                    // +增量朝锚端推出(B1/B2 裁决);错的是开环执行机构。
                    // 统一替换为收起侧已定案的反射锚定原子落位:
                    // requestScrollToItemNoCancel(fii, fiso+H) 待定区写入,与
                    // driveTo(1f) 的增长**同遍 measure 原子生效**——单帧落地,
                    // 无位移指令序列。数学:目标滚动位=当前+H,以 (fii,fso+H)
                    // 分解表达——fii 之下(index 更小侧)无任何增长(卡必在可见
                    // 域),该分解的绝对位恒=当前+H,与卡位置无关;超范围 offset
                    // 由框架在消费遍自动归一(等价重写,绝对位不变)。
                    // 收起镜像(#462)以 consumedPx 记账:反射路径恒记全额 H,
                    // 回退 −H 闭环;贴底终态与 dispatch 全额严格同值(B2 不变)。
                    clock.programmaticShift = true
                    // #466:贴底标记(departure 防回拉)——反射落位后视口必然
                    // 离底,贴底态立即解跟随的语义保留
                    var wasPinnedToBottom = false
                    try {
                        // #427(终局):记录展开前锚点状态——收起 end-restore 兜底用
                        clock.episodeAnchorItem = listState.firstVisibleItemIndex
                        clock.episodeAnchorOffset = listState.firstVisibleItemScrollOffset
                        wasPinnedToBottom = bottomPinnedExpandSkip(
                            listState.firstVisibleItemIndex,
                            listState.firstVisibleItemScrollOffset,
                        )
                        val anchorFii = listState.firstVisibleItemIndex
                        val anchorFiso = listState.firstVisibleItemScrollOffset
                        val anchored =
                            listState.layoutInfo.visibleItemsInfo.isNotEmpty()
                        // 2026-09-29 用户裁决终向:展开=「卡与上方纹丝不动,内容
                        // 向下扩展推走下方」——全域统一反射 (fii, fiso+H)(视口下移
                        // H 与布局增长抵消:上方净零、卡钉住、下方下移 H)。二轮
                        // 「锚定底部零位移」(上方让位=向上扩展)被用户否决,撤销。
                        // 真正的偶发跳变源=半贴底 fiso+H 超界(见下方归一)。
                        if (anchored) {
                            // 超界防御(#466 建立;#508 根修方向修正):折叠方向=
                            // 向旧侧(idx 递增)且宿主容量 +H——原向新侧过滤在逆布局
                            // 下链恒只含锚 item 自身,链尽 fall-through 丢整 item 高度
                            // (展开位移恒=H−锚item尺寸的视口跳变根因)。
                            val rawTarget = anchorFiso + H
                            val visItems = listState.layoutInfo.visibleItemsInfo
                            val hostIndex =
                                if (hostItemKey != null) visItems.firstOrNull { it.key == hostItemKey }?.index ?: -1 else -1
                            val norm = normalizeExpandAnchor(
                                visItems
                                    .filter { it.index >= anchorFii }
                                    .sortedBy { it.index }
                                    .map { it.index to it.size },
                                rawTarget,
                                hostIndex,
                                H,
                            )
                            dev.leonardo.ocbeacon.ui.screens.chat.components.LazyListReflection
                                .requestScrollToItemNoCancel(
                                    listState,
                                    norm.first,
                                    norm.second,
                                )
                            clock.episodeShiftConsumedPx = H.toFloat()
                            if (BuildConfig.DEBUG) {
                                AppLogger.d(
                                    "CardExpand",
                                    "[DEBUG-466] expand-anchor fii=" + anchorFii +
                                        " fiso=" + anchorFiso + " +H=" + H +
                                        " host=" + hostIndex +
                                        (if (norm.first != anchorFii) " norm->" + norm.first + ":" + norm.second else "") +
                                        " pinned=" + wasPinnedToBottom,
                                )
                            }
                        }
                        // 增长快照:反射路径=待定区写入后(同遍 measure 原子生效,
                        // 收起同序);withMutableSnapshot 同步应用保证消费遍见终高。
                        clock.tweening = true
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            clock.driveTo(1f)
                            // #540 帽×机件单写者：宿主=帽物主（#440 宽限窗，turn 刚完结
                            // 帽未卸）时，抬帽与增长同快照——反射 (fii,fiso+H) 不被
                            // min(child,reserved) 钳一帧，flush 亦无残差二次释放。
                            hatReserve?.let { r ->
                                if (r.itemKey == hostItemKey) {
                                    r.reserved = hatPairedRaise(r.reserved, r.itemKey, hostItemKey, H)
                                }
                            }
                        }
                        clock.tweening = false
                        if (!anchored) {
                            // fallback:布局未就绪(无可见条目)退 dispatch 旧路径
                            // (#427 残差重试)——增长已先行,内部测量见终高。
                            clock.episodeShiftConsumedPx =
                                applyPairedPreRenderShift(listState, H.toFloat())
                        }
                    } finally {
                        clock.programmaticShift = false
                    }
                    // #430(修正):欠账 rebase——残量=目标−实消费(transient 0 消费
                    // 的配对义务不丢);基线锚定目标,幕布期增量(report>ledger)逐帧
                    // 记账。steadyHold 放行:增长落地帧 flush 同帧补派,上顶不上屏。
                    clock.steadyRebaseAfterEpisodeDispatch(H, clock.episodeShiftConsumedPx)
                    clock.steadyHold = false
                    // 离底解跟随(真机定案:预移后 atBot=false 而 autoOn=true,
                    // 仲裁器集后把整个列表拽回底=「其他元素移动」主诉):
                    // 位移超阈即视为用户意图离底,关 autoScroll(旧引擎同款钩);
                    // #466:贴底态 dispatch 必然离底——不等阈值立即解跟随,防
                    // 仲裁器回拉抹掉展开位移(位置归用户)。
                    if (H > DEPARTURE_THRESHOLD_PX || wasPinnedToBottom) {
                        departure?.invoke()
                    }
                    // #429:高度已定/相位已落——计算期结束,loading 让位幕布揭示
                    onExpandComputing?.invoke(false)
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
                    // ===== 批次十四:收起镜像化(根因修复,与展开同构) =====
                    // 展开的「布局先终态+幕布揭示」真机实证零震荡(同样跨 9 条目
                    // 的位移从不泄露中间帧);旧收起=逐帧缓动布局,重内容每步全测
                    // ~490ms×N(延迟主体)+动画中段跨界致锚点重推导泄露帧(十三c
                    // 定罪的 ±72/−36 震荡)——两个症状同根:逐帧布局参与。
                    // 镜像化后:幕布收拢=纯 draw(零布局零测量)→单步原子闭合
                    // (快照批量 driveTo(0f)+单发 dispatch −rep)——重测只付一次,
                    // 跨界原子性与展开同等。十三b追平/十三c软上限随环退役。
                    clock.tweening = true
                    // ① 幕布收拢(draw-only 200ms;盒高不变→钉位静止,
                    //   下方内容保持推开)
                    val tC = withFrameNanos { it }
                    var vc = 0f
                    while (vc < CURTAIN_MS) {
                        val nc = withFrameNanos { it }
                        if (clock.userScrollCancelled) break
                        vc = minOf(
                            ((nc - tC) / 1_000_000f).coerceAtLeast(0f),
                            vc + MAX_FRAME_STEP_MS,
                        )
                        curtain.floatValue = 1f - FastOutSlowInEasing.transform(
                            (vc / CURTAIN_MS).coerceIn(0f, 1f),
                        )
                    }
                    // ② 单步原子闭合(镜像展开的 withMutableSnapshot+dispatch:
                    //   首个放置即含塌缩+位移,中间态从构造上不存在)
                    // #529:收起锚点解析同走静止门——回收重组合实例的首收起集与
                    // 2026-09-28 已修的回收竞态(close-anchor consumed=0 兜底)同族;
                    // 向后折叠链消费的 item 尺寸谱同样要求静止基。rep 在门后读取。
                    awaitAnchorQuiescence(listState)
                    val rep = clock.lastReportedH
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(
                            "CardExpand",
                            "[DEBUG-427] close-pre rep=" + rep + " meas=" + clock.lastMeasuredH +
                                " fii=" + listState.firstVisibleItemIndex +
                                " fiso=" + listState.firstVisibleItemScrollOffset +
                                " items=" + listState.layoutInfo.visibleItemsInfo.joinToString("|") { it.index.toString() + ":" + it.size },
                        )
                    }
                    clock.tweening = false
                    // #427(终局·无闪烁,用户裁决:渲染前计算+反射设置):收起=镜像
                    // 本卡配对消费的增量锚点(#462 改造:原「恢复展开前绝对快照」
                    // 仅在集间无其他位移时正确——多卡展开态收起 A,绝对快照会把
                    // B 的展开量一并回退=「收起其一视角跳变」;增量镜像目标=
                    // 当前视口−episodeShiftConsumedPx,单卡时与旧绝对恢复数学
                    // 同值,多卡时保其他卡净位移)。反射 requestScrollToItemNoCancel
                    // 把目标位写入**待定区,由下一遍 measure 消费**——与下述快照的
                    // 高度塌缩**同一遍 measure 原子生效**:单帧落地、无中间帧。
                    // (scrollToItem 是独立排布通道:塌缩先渲染一帧再跳位
                    //  =整屏闪烁,真机复验收敛定罪;本质=渲染后修正,弃。)
                    // 用户滚动过(阅读位置优先权铁律)或锚点链不足 → 回退镜像位移。
                    // 展开后回收兜底(2026-09-28 真机 t4 定罪):episodeShiftConsumedPx
                    // 挂在 clock(remember)上,展开→滚离视口→LazyList 回收→重组合新
                    // clock 账本归零——consumed=0 时 resolveCollapseAnchor 短路返回
                    // 原位=零回退,-H 塌缩全额漏成视口跳变(close-anchor consumed=0 +
                    // RESIZE -194 无配对=用户主诉「整体对话内容往下拖动」)。塌缩量
                    // 恒=当前上报高度 rep(稳态 fraction=1 时 rep==账本:展开反射记
                    // H,steady 补派双写同步),账本缺失时以 rep 兜底,数学与账本路径
                    // 严格同值;两条收起路径(反射锚点/dispatch 镜像)统一取用。
                    val mirrorConsumed =
                        clock.episodeShiftConsumedPx.takeIf { it > 0f } ?: rep.toFloat()
                    val collapseAnchor = if (!clock.userScrollCancelled) {
                        val vis = listState.layoutInfo.visibleItemsInfo
                        resolveCollapseAnchor(
                            listState.firstVisibleItemIndex,
                            listState.firstVisibleItemScrollOffset,
                            mirrorConsumed,
                            vis.filter { it.index < listState.firstVisibleItemIndex }
                                .map { it.size }
                                .reversed(),
                        )
                    } else {
                        null
                    }
                    val anchorKnown = collapseAnchor != null
                    if (anchorKnown) {
                        val (aItem, aOffset) = collapseAnchor!!
                        dev.leonardo.ocbeacon.ui.screens.chat.components.LazyListReflection
                            .requestScrollToItemNoCancel(
                                listState,
                                aItem,
                                aOffset,
                            )
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(
                                "CardExpand",
                                "[DEBUG-427] close-anchor-request fii=" + aItem +
                                    " fiso=" + aOffset +
                                    " (mirror consumed=" + mirrorConsumed.toInt() +
                                    " ledger=" + clock.episodeShiftConsumedPx.toInt() + " rep=" + rep + ")",
                            )
                        }
                    }
                    clock.tweening = false
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        clock.driveTo(0f)
                        // #540 镜像：物主 item 上的收起塌缩同快照落帽——防 reserved
                        // 残留旧高使后续增长绕过一帧缓冲（帽协议失效=裸落地）。
                        hatReserve?.let { r ->
                            if (r.itemKey == hostItemKey) {
                                r.reserved = hatPairedLower(
                                    r.reserved, r.itemKey, hostItemKey, mirrorConsumed.toInt(),
                                )
                            }
                        }
                    }
                    if (!anchorKnown && !clock.userScrollCancelled) {
                        // 用户滚动打断路径不再派发(取消相已反射恢复锚点;fling 中
                        // 追加派发=与手势竞态,双错位)
                        clock.programmaticShift = true
                        try {
                            val backPx = -mirrorConsumed
                            applyPairedPreRenderShift(listState, backPx)
                        } finally {
                            clock.programmaticShift = false
                        }
                    }
                    clock.episodeShiftConsumedPx = 0f
                    clock.episodeAnchorItem = -1
                    // #430:收起侧 rebase——driveTo(0) 塌缩的 measure 会以 report=0
                    // 记账,不 rebase 则集末 flush 会把 −H 再派发一次(双配对)。
                    // 收起无欠账语义(锚点恢复/镜像已精确落位),plain rebase。
                    clock.steadyRebase()
                    clock.steadyHold = false
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(
                            "CardExpand",
                            "[DEBUG-427] close-post fii=" + listState.firstVisibleItemIndex +
                                " fiso=" + listState.firstVisibleItemScrollOffset +
                                " items=" + listState.layoutInfo.visibleItemsInfo.joinToString("|") { it.index.toString() + ":" + it.size },
                        )
                    }
                    curtain.floatValue = 0f
                }
                completed = true
            } catch (t: Throwable) {
                // #423 批次三诊断:区分取消/异常(真机大组集 54ms 早退未明因)
                if (BuildConfig.DEBUG) {
                    AppLogger.e(
                        "CardExpand",
                        "[DEBUG-423] episode exit-" + (if (t is CancellationException) "CANCELLED" else "THREW") +
                            " f=" + "%.3f".format(clock.fraction),
                        t,
                    )
                }
                throw t
            } finally {
                // #429:任何退出路径(取消/异常)都不得悬挂 loading
                onExpandComputing?.invoke(false)
                clock.tweening = false
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
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(
                            "CardExpand",
                            "[DEBUG-427] close+2f fii=" + listState.firstVisibleItemIndex +
                                " fiso=" + listState.firstVisibleItemScrollOffset +
                                " items=" + listState.layoutInfo.visibleItemsInfo.joinToString("|") { it.index.toString() + ":" + it.size },
                        )
                    }
                    // #430(修正):迟到增量一次性 catch-up 退役——稳态配对按帧接管
                    // 全部窗外增量。放行 flush(幕布期欠账/增量可补派);仅取消/异常
                    // 路径(!completed)防御性 rebase(正常完成集的欠账必须保留——
                    // transient 0 消费的残量在集后由 flush 继续补派)。
                    clock.steadyHold = false
                    if (!completed) clock.steadyRebase()
                } catch (_: CancellationException) {
                    // 取消(snap)路径:落位已由 snap 完成,无需补偿
                }
                // #540 事后回锚清剿：#424 end-restore（episode 终态上屏 2 帧后
                // dispatchRawDelta 纠偏）删除——反射降级（Compose 升级破坏私有成员）
                // 时改走「降级即弃配」：dispatch fallback 本身全额重试（PairedDispatch），
                // 残差不再以上屏后修正兜底（用户裁决：消除位移源，不留事后补偿）。
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "CardExpand",
                        "[DEBUG-422] episode done f=" + "%.3f".format(clock.fraction) +
                            " totalMs=" + ((System.nanoTime() - episodeStart) / 1_000_000f).toInt() +
                            " completed=" + completed,
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

    // ===== #423 批次十三:空闲预热(大内容「加速」而非拆分,用户裁决) =====
    // 折叠卡可见且用户静止 PREWARM_IDLE_MS 后,以 ε 高度组合+沉降内容
    // (fraction>0 即组合,内容保温在树内;finalH 缓存同步抬升)。点击时
    // H 已知、内容已热 → settle 一两帧即稳 → 原子落地+幕布,首开即快。
    // 预热不 dispatch、不动滚动位;滚动中/已展开/流式降级分支不参与。
    // 20k px 级怪物组的预热组合仍是一帧长块(空闲期付账);拆块正解=L3
    // AST 切片(backlog)。
    // #425 队列化(2026-09-28):原 per-card 裸并发(各卡共用同一静止时钟,
    // 滚动停止后同帧齐发 ε 组合,实测 Skipped 53 帧 ≈880ms)→ 协调器
    // withPrewarmGrant 串行授权:任意时刻至多 1 卡在途;批次十三b episode
    // 让位环语义上提进授权门(250ms×8 有界,超时弃窗);新增槽间隔防排队
    // 唤醒同帧接续。本卡动画态(clock.animating)从「持约等待」改为执行前
    // 复查即弃——animating 期几乎总伴随全局租约,授权门已在等,不占队空转。
    LaunchedEffect(visible, listState) {
        if (visible || listState == null) return@LaunchedEffect
        // #427 竞态修复:资格谓词拦截——账本暖的切片组跳过预热(见参数文档)
        if (prewarmEligible?.invoke() == false) return@LaunchedEffect
        kotlinx.coroutines.delay(PREWARM_IDLE_MS)
        if (visible || listState.isScrollInProgress) return@LaunchedEffect
        PreRenderCoordinator.withPrewarmGrant {
            // 排队/门等/槽间隔期间状态可能已变:执行前全量复查前置
            if (visible || listState.isScrollInProgress) return@withPrewarmGrant
            if (clock.animating) return@withPrewarmGrant
            if (clock.fraction > WARMUP_FRACTION) return@withPrewarmGrant
            clock.warmup()
            settleUntilContentStable(clock)
            clock.lastMeasuredH.takeIf { it > 0 }?.let { storeFinalH(it) }
            if (BuildConfig.DEBUG) {
                AppLogger.d("CardExpand", "[PRD-warm] H=" + clock.lastMeasuredH)
            }
        }
    }

    // ===== #430 稳态配对:episode 外迟到增长的渲染前位移(与 #420 同一契约的连续化) =====
    // 分批表格(MarkdownTable stagedLimit 逐组落地)/asyncParse 完成等增量落在
    // episode 窗口之外(settle 600ms 上限/幕布期/集后)——此前无任何配对,
    // reverseLayout 锚定把每个增量全额转译为「上方内容上顶」(用户主诉)。
    // 机制:measure 相 [CardExpandClock.noteSteadyReport] 记账 Δreport →
    // pre-draw flush 单点派发 applyPairedPreRenderShift(+Δ)——同帧闭合,
    // 中间态不上屏(增长帧 draw 前位移已落地)。
    // 让位矩阵:episode 进行中(animating)挂起不派发(集内自配对,幕布期增量
    // 累计至集末一并派发);用户滚动(isScrollInProgress)即弃(阅读位置优先权
    // 铁律,弃配不追补);无 FLUSH 宿主(预览/单测)不注册=降级现状。
    LaunchedEffect(clock.fraction > 0f, listState) {
        if (clock.fraction <= 0f) return@LaunchedEffect
        if (!PreRenderCoordinator.isFlushHostAttached) return@LaunchedEffect
        val task = PreDrawFlushTask {
            if (clock.steadyHold) return@PreDrawFlushTask true // 集内 dispatch 决策点前:episode 独占,挂账
            if (listState.isScrollInProgress) {
                clock.steadyRebase() // 用户滚动:位置归用户,欠账弃配(不与手势竞速)
                return@PreDrawFlushTask true
            }
            // #435 贴底豁免(#432 episode 点不变量补齐 steady 相):贴底跟随态
            // (严格 fii==0∧fiso==0)的迟到增长=追加语义(增长向上自然扩展,源码级
            // 钉底自动成立,LazyListMeasure),dispatch 反而把视口推离贴底。
            if (bottomPinnedExpandSkip(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                )
            ) {
                if (BuildConfig.DEBUG && clock.steadyPending != 0f) {
                    AppLogger.d("CardExpand", "[STEADY] drop-at-bottom d=" + clock.steadyPending.toInt())
                }
                clock.steadyRebase()
                return@PreDrawFlushTask true
            }
            // #430(19:46 定罪):欠账/增量派发门控在「增长已落地」——rep 仍为 0 时
            // 布局里尚无配对容量(dispatch 内测见旧高),派发必 0 消费且欠账被
            // takeSteadyPending 清账=死亡(实测 topY −22969 裸上顶)。落地证据=
            // lastReportedH>0(f=1 首测已上报);未落地等下一 pre-draw。
            if (clock.lastReportedH <= 0) return@PreDrawFlushTask true
            val d = clock.takeSteadyPending()
            if (d == 0f) return@PreDrawFlushTask true
            clock.programmaticShift = true
            try {
                val consumed = applyPairedPreRenderShift(listState, d)
                // #430:瞬态欠消费(0 消费=增长/容量晚一拍;部分残量=边缘趋近)
                // 保留残量下帧重试;重试窗外(真物理边缘)丢弃——迟到的纠正比
                // 永久偏移更糟(位置优先权)。
                if (abs(d - consumed) >= 0.5f &&
                    System.nanoTime() < clock.steadyRetryDeadlineNs
                ) {
                    clock.restoreSteady(d - consumed)
                }
                clock.recordDisplacement(consumed)
                // 收起镜像账本同步:稳态派发也是"展开位移"的一部分,收起回退须含它
                clock.episodeShiftConsumedPx += consumed
                if (!clock.departureFired && abs(clock.episodeDisplacement) > DEPARTURE_THRESHOLD_PX) {
                    clock.departureFired = true
                    departure?.invoke()
                }
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "CardExpand",
                        "[STEADY] pair d=" + d.toInt() + " consumed=" + consumed.toInt() +
                            " rep=" + clock.lastReportedH +
                            " fii=" + listState.firstVisibleItemIndex +
                            " fiso=" + listState.firstVisibleItemScrollOffset,
                    )
                }
            } finally {
                clock.programmaticShift = false
            }
            true
        }
        PreRenderCoordinator.registerFlushTask(task)
        try {
            snapshotFlow { clock.fraction > 0f }.first { !it }
        } finally {
            PreRenderCoordinator.unregisterFlushTask(task)
        }
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
                    // #427 竞态修复:收起被滚动打断时,snap(0f) 的即时塌缩此前
                    // **无任何配对**=内容下方整体跳位(小卡也闪,真机复验定罪);
                    // 且塌缩集的镜像派发会在用户 fling 中与手势竞争(双重错位)。
                    // 正解:打断即反射恢复展开前锚点(与 snap 同遍 measure 原子),
                    // 并消费锚点令集内回退派发让位(防双发)。
                    if (!currentVisible && clock.episodeAnchorItem >= 0) {
                        dev.leonardo.ocbeacon.ui.screens.chat.components.LazyListReflection
                            .requestScrollToItemNoCancel(
                                listState,
                                clock.episodeAnchorItem,
                                clock.episodeAnchorOffset,
                            )
                        clock.episodeAnchorItem = -1
                        clock.episodeShiftConsumedPx = 0f
                        if (BuildConfig.DEBUG) {
                            AppLogger.d("CardExpand", "[DEBUG-427] cancel-anchor-restore")
                        }
                    }
                    clock.snap(if (currentVisible) 1f else 0f)
                    drawFraction.floatValue = if (currentVisible) 1f else 0f
                }
            }
    }

    // #540 事后回锚清剿：#423 钉位修正器（pinPending 永假的死设备——批次九
    // 「渲染前计算+反射」已使其退役）整体删除；#430 稳态账本（pre-draw 同帧）
    // 是迟到增长的唯一接管者。
    // 冷组合抬底数据源:finalHCache(rememberSaveable)跨回收存活,-1=无缓存
    // (抬底条件 floor>measured 自然失效)。remember(finalHCache) 稳定引用,
    // lambda 闭包读 state=布局相订阅,缓存更新自动失效重测。
    // #533:saveable 只覆盖 LazyList item 级回收;SliceHost 槽位出窗即弃时
    // 随槽位销毁——进程级 CardExpandFinalHStore((cacheKey,width) 键控)补位,
    // 节点测量相融合取 max 并回写真实内容高。
    val floorProvider = remember(finalHCache) { { finalHCache.value } }
        Box(
        modifier = modifier
            .clipToBounds()
            .cardExpandGeometry(clock, floorProvider, cacheKey?.toString())
            .onSizeChanged { revealBoxH.intValue = it.height }
            .onPlaced {
                // #423 批次四:布局相写入。真机取证(09-22):onGloballyPositioned 实测
                // 在绘制期才派发——晚于 pre-draw。onPlaced 在放置相派发(先于 pre-draw)。
                // #528:isAttached 守卫——压缩/换装等批量移除窗口内,LazyList 对已在
                // 拆除途中的节点仍会派发放置回调,此刻 positionInRoot 抛
                // IllegalStateException(真机 19:21:41 FATAL,压缩报错后 40s 复现)。
                // 已脱离节点的坐标无意义,跳过写入(episode 协程随组合销毁同归于尽)。
                if (it.isAttached) {
                    revealTopY.floatValue = it.positionInRoot().y
                }
            }
            .onGloballyPositioned {
                if (it.isAttached) {
                    revealTopY.floatValue = it.positionInRoot().y
                }
                if (BuildConfig.DEBUG && clock.animating) {
                    // #526b 取证增补：boxH（reveal 盒实占高）+ host（宿主 item 尺寸）——
                    // E1 脏样本（回收后首展开 topY+H）解码需要分清「盒高漂移」还是
                    // 「item 内其它增长」还是「滚动双算」。
                    AppLogger.d(
                        "PRD",
                        "E" + episodeSeq.intValue + " PLACED t=" + System.nanoTime() +
                            " topY=" + revealTopY.floatValue.toInt() +
                            " boxH=" + revealBoxH.intValue +
                            " rep=" + clock.lastReportedH + " abs=" + clock.absorbedPx +
                            " f=" + "%.3f".format(clock.fraction) +
                            " fii=" + listState.firstVisibleItemIndex +
                            " fiso=" + listState.firstVisibleItemScrollOffset +
                            " host=" + (listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == hostItemKey }?.size ?: -1),
                    )
                }
            }
    ) {
        // #420 追诊:content 组合生命周期归几何时钟统一拥有(fraction>0 即组合)。
        // 原方案 AV fadeOut(300ms) 与几何 tween(240ms) 两时钟分离——掉帧时
        // 几何未走完而 AV 已把 content 移出组合 → H 突塌 f·738px 无补偿
        // (实测 flow-b 收起终末 −217px 跳变)。fade 改由分数驱动(前/后 30%),
        // 与几何同起止,杜绝第二时钟。
        //
        // #429 B 勘误(2026-09-24 真机否定,撤除):movableContentOf 移回虽免组合,
        // 但 36612px 全量**测量**仍在单帧执行——re-expand 实测连续 Skipped
        // 41/40/45/51/53 帧(~400ms×5),劣于纯 staged 路径(266ms 零跳帧,
        // 分批同样覆盖再展开)。组合免了、测量没免,B 收益为负。
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
 * #432 贴底免派发判定(纯函数可单测):严格贴底=最新 item 锚定(fii==0 ∧ offset==0)。
 * 此构型布局把塌高向上扩展——header 与底部内容天然屏位不变,steady 迟到增长
 * 的 dispatch 反而把视口推离贴底。fii==0 但 offset>0 的半贴底域不启用
 * (2026-09-29 四轮用户裁决:展开=向下扩展+上方不动,半贴底同走反射归一路径)。
 */
internal fun bottomPinnedExpandSkip(fii: Int, fiso: Int): Boolean = fii == 0 && fiso == 0

/**
 * 展开反射目标归一(#466 建立,#508 根修):目标滚动位=当前+H,分解 (fii,fiso+H)
 * 的合法性只取决于**锚 item 的增长后尺寸**——锚 item 唯一会变大的场合是宿主=锚
 * item 本身(卡在可见域,增长与待定位同遍 measure 落地),此时 fiso≤尺寸 ⇔
 * fiso+H≤尺寸+H 恒成立,永不折叠。宿主在锚 item 上方(锚 item 不增长)且
 * fiso+H 超其尺寸时,才沿可见链**向旧侧**(idx 递增,逆布局下即折叠的正方向)
 * 折算——宿主 item 容量按 +H 计,否则途经宿主的折叠会错一个 H(绝对位偏移)。
 *
 * #508 根因(2026-10-03 T9 定罪):原实现向**新侧**过滤折叠链——逆布局可见链
 * 是 fii..最高可见 idx,新侧(更小 idx)已滚过不可见,链恒只含锚 item 自身;
 * rawTarget 超锚尺寸时单步折叠后链尽,fall-through 返回 (锚idx, fiso+H−旧尺寸)
 * ——越界穿越的整 item 高度被静默丢弃,目标恒短一个锚 item 尺寸,实际位移=
 * H−锚item尺寸(与 fiso 无关;真机两例同值 −1024px)。原 fii==0 设计场景 item0
 * 为流式巨轮恒不触发折叠,潜伏至中位小 item 构型(2359px 分片条目)。
 *
 * @param itemsOldward 自锚 item 起 idx **递增**的 (index,height) 链
 * @param hostIndex 宿主 item idx;-1=未知(按宿主=链首=锚 item 兜底)
 * @param hostGrowth 宿主本集增长量(+H)
 */
internal fun normalizeExpandAnchor(
    itemsOldward: List<Pair<Int, Int>>,
    rawTarget: Int,
    hostIndex: Int,
    hostGrowth: Int,
): Pair<Int, Int> {
    var off = rawTarget
    val host = if (hostIndex >= 0) hostIndex else itemsOldward.firstOrNull()?.first ?: -1
    for ((idx, h) in itemsOldward) {
        val cap = if (idx == host) h + hostGrowth else h
        if (off < cap) return idx to off
        off -= cap
    }
    val last = itemsOldward.lastOrNull()
    return (last?.first ?: 0) to off.coerceAtLeast(0)
}

/**
 * #462(2026-09-29):收起锚点解析——增量镜像语义(纯函数可单测)。
 *
 * 根因:收起原用「本卡展开前绝对快照」恢复视口,仅在集间无其他位移时正确;
 * 多卡展开态 V=Pa+H_A+H_B,收起 A 绝对恢复 Pa → 偏差 −H_B(视口多退 B 的
 * 展开量)=「收起其一视角跳变」(用户报告,数学定罪+真机日志证实:展开配对
 * 后 fiso+=H、快照恢复=−H)。增量镜像恒正确:目标=当前视口−本卡实际配对
 * 消费 [episodeShiftConsumedPx]——任何其他「配对过的位移」(别卡展开/稳态
 * 增长)都不破坏该语义;单卡时与旧绝对恢复严格等价(数学同值)。
 *
 * @param itemSizesBackward 自 fii−1 起向前的可见 item 高链(跨界折算用;
 *   链不足返回 null → 调用方退 dispatch 镜像回退)
 */
internal fun resolveCollapseAnchor(
    fii: Int,
    fiso: Int,
    consumedPx: Float,
    itemSizesBackward: List<Int>,
): Pair<Int, Int>? {
    val c = consumedPx.toInt()
    if (c <= 0) return fii to fiso
    var item = fii
    var offset = fiso - c
    var depth = 0
    while (offset < 0) {
        if (item <= 0) return null
        val size = itemSizesBackward.getOrNull(depth) ?: return null
        item--
        offset += size
        depth++
    }
    return item to offset
}

/**
 * #432 settle 判稳帧推进(纯函数可单测):测量计数静止 ∧ 实测高度>0。
 *
 * H>0 门槛是 #432 真机取证新增:prewarm/展开集的 settle 曾在内容组合完成前
 * (async parse/表格分批未落地,实测同秒 9 卡 H=0/18/75/264 混杂)以「测量静止
 * 但 H=0/部分高」判稳——早熟 H 进入 dispatch 后展开足迹偏小,迟到增长全部
 * 交给 steady 逐帧补派=「展开后高度自己变化/偏移」的直接根源。内容真正
 * 组合出来的高度恒>0;H<=0 的静止是组合间隙,不得计稳。
 */
internal fun settleFrameAdvances(measures: Int, lastMeasures: Int, measuredH: Int): Boolean =
    measures > 0 && measures == lastMeasures && measuredH > 0

/**
 * #529 soak 根修(纯函数可单测,2026-10-08 04:42 单帧定罪):冷抬底资格。
 * 窗口 = 经典冷窗(measureCount≤2)∨ **内容未组合(measuredH==0)**——重内容
 * 异步组合可逾经典冷窗(真机实测 ~150ms/count3+ 仍 measured=0),floor 过期
 * →盒上报 0→item −H 手势中塌缩→落地 +H 回长(RESIZE 15982→11492→15982)。
 * 零高绝不可能是 finalHCache>0 展开卡的合法终态,故零高续抬无陈旧风险
 * (内容合法变矮≠内容为零,变矮仍由 count>2 真测接管走 steady 配对)。
 */
internal fun coldFloorEligible(fraction: Float, animating: Boolean, measureCount: Int, measuredH: Int): Boolean =
    fraction >= 1f && !animating && (measureCount <= 2 || measuredH == 0)

/**
 * #422 内容沉降:展开 tween 前等待「内容驱动的重测」静止。
 *
 * 为什么必须:placeable 缓存只在 tween 窗口生效,而首测往往不是终测——
 * 表格 containerWidth 经 onSizeChanged 回写后第二拍才收敛、async markdown
 * 解析完成后内容突增。若带过期 H 进入缓存窗口,tween 全程锁死在旧高度
 * (展开只有一小截),残差全部堆到 episode 末一次性跳变(原叠压 bug 的
 * 同族根因)。判定:连续 [SETTLE_STABLE_FRAMES] 帧 [settleFrameAdvances]
 * (测量静止 ∧ H>0);上限 [MAX_SETTLE_MS] 防无限等。
 */
private suspend fun settleUntilContentStable(clock: CardExpandClock) {
    val t0 = withFrameNanos { it }
    var lastCount = clock.measureCount
    var stableFrames = 0
    while (stableFrames < SETTLE_STABLE_FRAMES) {
        val now = withFrameNanos { it }
        if ((now - t0) / 1_000_000f >= MAX_SETTLE_MS) break
        val c = clock.measureCount
        if (settleFrameAdvances(c, lastCount, clock.lastMeasuredH)) {
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
 * #529 锚点捕获静止门判定(纯函数可单测):本帧基指纹与上帧一致即计稳一帧。
 * null=首帧(无基线,不得计稳——防「未观测≠静止」的假稳,同 settle 判稳语义)。
 */
internal fun anchorQuiesceAdvances(currentFp: String, lastFp: String?): Boolean =
    lastFp != null && currentFp == lastFp

/**
 * #529:锚点基指纹——fii/fiso + 可见 item 尺寸谱。
 * 反射目标 (fii,fiso±H) 的绝对位语义(=捕获位±H,同遍 measure 原子消费)建立在
 * 「捕获→待定区消费之间该基不变」上:任何可见 item 的尺寸变化或锚位漂移都会
 * 使同一 (idx,off) 请求的绝对落点漂移——正是 E1 脏样本(回收重组合后首个
 * 展开集,真机 topY+H 整额双计/+H/2 半计)的竞态面。settle 只保证**本卡**
 * 内容静止;item 内其它部分(同轮其余卡/文本/图片)与滚动余尾不在其管辖。
 */
internal fun anchorBasisFingerprint(fii: Int, fiso: Int, itemSizes: List<Pair<Int, Int>>): String {
    val sb = StringBuilder(24 + itemSizes.size * 10)
    sb.append(fii).append(':').append(fiso).append('|')
    for ((idx, size) in itemSizes) sb.append(idx).append('=').append(size).append(',')
    return sb.toString()
}

private fun LazyListState.anchorBasisFingerprint(): String = anchorBasisFingerprint(
    firstVisibleItemIndex,
    firstVisibleItemScrollOffset,
    layoutInfo.visibleItemsInfo.map { it.index to it.size },
)

/**
 * #529:锚点捕获静止门——展开/收起的反射锚点捕获前等待布局基静止:
 * 滚动余尾(isScrollInProgress)期间基恒漂,直接重置计稳;之后要求基指纹
 * 连续 [ANCHOR_QUIESCE_STABLE_FRAMES] 帧不变。有界([ANCHOR_QUIESCE_MAX_MS]):
 * 超时按现状捕获,不回退任何既有行为(同实例后续集 E3+ 恒稳,典型代价
 * 2-3 帧 ≈17-50ms,被 computing 过渡/幕布吸收不可感知)。
 */
private suspend fun awaitAnchorQuiescence(listState: LazyListState) {
    val t0 = System.nanoTime()
    var lastFp: String? = null
    var stableFrames = 0
    while (stableFrames < ANCHOR_QUIESCE_STABLE_FRAMES) {
        if ((System.nanoTime() - t0) / 1_000_000f >= ANCHOR_QUIESCE_MAX_MS) break
        withFrameNanos { }
        if (listState.isScrollInProgress) {
            // 滚动余尾/兄弟卡稳态派发(dispatchRawDelta 亦置位):基恒在漂,不计稳
            stableFrames = 0
            lastFp = null
            continue
        }
        val fp = listState.anchorBasisFingerprint()
        if (anchorQuiesceAdvances(fp, lastFp)) {
            stableFrames++
        } else {
            stableFrames = 0
            lastFp = fp
        }
    }
    if (BuildConfig.DEBUG) {
        AppLogger.d(
            "CardExpand",
            "[DEBUG-529] anchor-quiesce stable=" + stableFrames +
                " ms=" + ((System.nanoTime() - t0) / 1_000_000f).toInt(),
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
    var floorProvider: () -> Int = { 0 },
    /** #533:槽位键（cacheKey 字符串化;null=不入进程店——工具卡等无键卡维持现状）。 */
    var storeKey: String? = null,
    /** #533:欠测桥接（measured<floor 落地期持有;注入时钟可单测）。 */
    var floorBridge: CardExpandFloorBridge = CardExpandFloorBridge(),
) : Modifier.Node(), LayoutModifierNode {

    private var cachedPlaceable: Placeable? = null
    private var cachedMeasurable: Measurable? = null
    private var cachedWidth: Int = Int.MIN_VALUE
    private var cachedAtEpoch = -1
    // #427 观测：稳态(非动画相)上报高度变化的探针账——收起/展开集之外任何
    // 高度变化(如 ε 预热残高回树)都是位移净漂的候选源，逐变化留痕。
    private var lastSteadyReport = Int.MIN_VALUE

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
        // 冷组合抬底(回收跳变根修,2026-09-28 真机录屏定罪):展开态卡滚离视口
        // →LazyList 回收→重组合(clock/measureCount 归零),asyncParse/内容组合的
        // 高度迟到落地发生在用户滚回手势中(inProgress)——steady 按位置优先权
        // 弃配,+H 裸顶视口(录屏 t=9.4s 反向-24/连跳+75 帧实证)。finalHCache
        // (rememberSaveable)跨回收存活:冷首测(measureCount≤2)以已知终高占位,
        // 迟到内容落地时高度已正确=零增量零弃配;陈旧 floor(内容变矮)由 count>2
        // 真测接管,差值走 steady 正常配对。animating 期不抬(episode 独占)。
        // #529 soak 定罪(2026-10-08 04:42 单帧):重内容异步组合可逾 count≤2 窗
        // (~150ms,measured 恒 0 至 count3+)——floor 过期→盒上报 0→item −H 塌缩
        // →内容落地 +H 回长(RESIZE 15982→11492→15982,147ms)=手势中可见闪跳。
        // 根修:内容未组合(measured==0)期间 floor 持续有效——零高绝不可能是
        // finalHCache>0 展开卡的合法终态,无陈旧抬底风险(内容变矮≠内容为零)。
        // #533 soak 定罪(2026-10-08 05:32:55 三帧):SliceHost 槽位出窗即弃
        // (subcompose 无 SaveableStateHolder)→槽内 finalHCache 随槽位销毁→抬底
        // 在切片域被缴械,展开卡重入窗盒瞬态欠测→与片高账本稳态落差=−H 塌缩
        // 脉冲(86ms-83s 回长族,5h 13 次);部分高欠测(分段落地)为另一族级联。
        // 根修①:floorProvider 融合进程级 CardExpandFinalHStore((cacheKey,width)
        // 键控,槽位销毁/回收全存活);②floor 持有经 CardExpandFloorBridge 扩到
        // 整个 measured<floor 落地期(1.5s 稳定释放=合法收缩,差异走引擎配对)。
        val storeFloor = storeKey?.let { CardExpandFinalHStore.get(it, width) } ?: -1
        val saveableFloor = floorProvider()
        val floorCandidate = maxOf(storeFloor, saveableFloor)
        val floorH = if (floorCandidate > 0 && floorBridge.holds(
                clock.fraction, clock.animating, clock.measureCount, placeable.height, floorCandidate
            )
        ) {
            floorCandidate
        } else {
            0
        }
        val effH = if (floorH > placeable.height) floorH else placeable.height
        if (BuildConfig.DEBUG && effH != placeable.height) {
            AppLogger.d(
                "CardExpand",
                "[DEBUG-FLOOR] cold-floor effH=" + effH + " measured=" + placeable.height +
                    " count=" + clock.measureCount,
            )
        }
        // #533:真实内容高回写进程店(只升不降)——重入窗/回收重组合时 floor 由此存活。
        val sk = storeKey
        if (sk != null && placeable.height > 0) {
            CardExpandFinalHStore.record(sk, width, placeable.height)
        }
        val report = clock.onMeasure(effH)
        // #430:稳态记账(measure 相;基线/rebase 协议见 CardExpandClock)——
        // episode 窗口外的任何 report 变化(分批表格逐组/asyncParse/图片)入账,
        // pre-draw flush 相派发配对位移。
        clock.noteSteadyReport(report)
        if (BuildConfig.DEBUG && !clock.animating && report != lastSteadyReport) {
            if (lastSteadyReport != Int.MIN_VALUE) {
                AppLogger.d(
                    "CardExpand",
                    "[DEBUG-427] steady-report d=" + (report - lastSteadyReport) +
                        " rep=" + report + " H=" + clock.lastMeasuredH +
                        " f=" + "%.3f".format(clock.fraction),
                )
            }
            lastSteadyReport = report
        }
        return layout(placeable.width, report) {
            placeable.placeRelative(0, 0)
        }
    }
}

/**
 * #422:Element 包装——clock 以身份相等 remember 期内稳定,节点与缓存
 * 跨重组存活(lambda 版每帧新建 element 正是缓存无法附着的原因)。
 * #533:storeKey 入相等(键变化=换卡身份,节点须重建以重置桥接态)。
 */
private class CardExpandGeometryElement(
    private val clock: CardExpandClock,
    private val floorProvider: () -> Int,
    private val storeKey: String?,
) : ModifierNodeElement<CardExpandGeometryNode>() {
    override fun create(): CardExpandGeometryNode =
        CardExpandGeometryNode(clock, floorProvider, storeKey)

    override fun update(node: CardExpandGeometryNode) {
        node.clock = clock
        node.floorProvider = floorProvider
        node.storeKey = storeKey
    }

    override fun equals(other: Any?): Boolean =
        other is CardExpandGeometryElement && other.clock === clock &&
            other.floorProvider === floorProvider && other.storeKey == storeKey

    override fun hashCode(): Int =
        System.identityHashCode(clock) * 31 + System.identityHashCode(floorProvider) * 7 +
            (storeKey?.hashCode() ?: 0)
}

private fun Modifier.cardExpandGeometry(
    clock: CardExpandClock,
    floorProvider: () -> Int,
    storeKey: String?,
): Modifier = this.then(CardExpandGeometryElement(clock, floorProvider, storeKey))
