package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.screens.chat.scroll.PreDrawFlushTask

/**
 * #435 流式增长账本 —— 流式家族(消息/工具横幅/压缩卡)高度增长并入高度引擎配对体系。
 * 设计: docs/specs/2026-09-25-435-height-engine-unification-design.md
 *
 * 取代 DeferredRevealCompensator(#222 延迟揭示状态机)+PreRenderShiftChannel(帧界运输层)。
 * 旧体系为「帧界注入」设计(注入落在下一帧遍首),须裁剪未配对几何+version 代计数竞态门
 * +holdReveal 滚动保持;新体系复用引擎 steady 配对(#430)的**同帧**契约——measure 相记账 Δ,
 * 本帧 pre-draw flush(PreRenderCoordinator 单点,K1)按统一配对规则派发:
 * measure→flush→draw 之间不存在可上屏的未配对中间态,裁剪/代计数/帧界排队整体退役。
 *
 * ## 统一配对规则(锚即意图,纯几何)
 *
 * `pair(Δ) ⟺ anchorIndex == itemIndex ∧ anchorOffset > 0`
 * - **画面保持公式（#437 验收四轮，用户数学模型定案）**：不变量
 *   `scrollPos(t) − ΔH(t) = S₀`——settle 后视口相对「settle 时刻内容底」钉死，
 *   新内容全部在视口下方生长（reverseLayout 主轴正方向=向旧内容滚动补偿）。
 * - 贴底原点(fii==0 ∧ fiso==0)：S₀=0，LazyList 物理自动跟随——免派发（#435 实证）。
 * - 增长源==锚 item 自身（itemIndex == anchorIndex，含 fii==itemIndex 的
 *   深处阅读）：增长发生在 item 主轴起边侧（reverseLayout=视觉底/新文本侧），
 *   (fii,fiso) 字面锚定会指向换了身份的新内容（=跟随）——**每帧 +Δ 补偿**
 *   才能让画面纹丝不动。
 * - 增长源在锚之下（itemIndex < anchorIndex）：**免派发（#437 验收八轮真机
 *   像素证伪 ≤ 全域配对）**——LazyList 锚定默认已保持画面（锚 item 字面
 *   (fii,fiso) 不随其下方 item 增长移动）；此时再 +Δ 是双重补偿：实测画面以
 *   每批 Δpx 上拖（派发量精确等于漂移量），且大额 set 触发 LazyList 重锚
 *   回吐（LEAP off 31252→8480，dOff=-22772 恰等于累计派发）→ 乒乓震荡。
 * - 收缩(Δ<0)一律不配对(旧 COMP 行为保持:全揭示 rebase)。防单帧大 Δ 跳变
 *   由 gate 放行量子化承担(≤400ch/批)。
 *
 * ## 48ms 节奏的结构性继承
 *
 * 账本输入 = 48ms 批处理(MessageEventHandler.scheduleFlush)驱动的 measure;
 * flush 派发节奏 = 48ms 批节奏。无任何额外定时器层。
 */
// [VTRACE 2026-09-26] flush 任务的上一帧视口位（变化检测用；主线程独占）
private var vtraceLastFii = Int.MIN_VALUE
private var vtraceLastLogAt = 0L
private var sgrDropLastLogAt = 0L
private var sgrLastTrue = -2
private var vtraceLastFiso = Int.MIN_VALUE

/**
 * R1 统一配对谓词（#437 二十五世轮根修，架构审查 A1/A5）：合并 ledger 轨与帽轨的
 * 配对决策为单一规则代数，决策表穷举见 StreamingAnchorRuleTest。
 *
 * TDD 收敛：两轨大部分格子同源，唯一真实分歧在「锚在增长源之下」（anchor < growth）：
 * - 锚 == 增长源：增长推移锚所见内容 → +Δ 同帧配对（两轨真机共识）；
 * - 锚 > 增长源（读历史）：LazyList 默认锚定已保持画面 → 免配对（八轮真机像素证据）；
 * - 锚 < 增长源：**源类型决定**——ledger 族（banner/压缩卡）增长由跟随通道
 *   （BANNER bottomFollow/GUARD）派发补偿，引擎再 +Δ = 双重补偿（#435 八轮震荡证据）；
 *   帽族（流式消息 item）增长无任何通道覆盖（GUARD 仅贴底邻域 8px），必须引擎配对
 *   （#437 z3 横幅区浅滑真机证据）。[coveredByFollowFamily] 由挂载点按源类型声明。
 *
 * 贴底原点（fii==0 ∧ fiso<[AT_BOTTOM_ORIGIN_PX]）：物理自动跟随，一律免派发。
 */
internal object StreamingAnchorRule {
    /** 贴底原点阈值(px)——GUARD/MSGEFFECT 微抖 ≤5px 内视为原点（原帽轨 z3 修正语义）。 */
    const val AT_BOTTOM_ORIGIN_PX = 8

    fun pairedDelta(
        anchorIndex: Int,
        anchorOffset: Int,
        growthIndex: Int?,
        growthPx: Float,
        coveredByFollowFamily: Boolean = false,
    ): Float = when {
        growthPx <= 0f -> 0f                                        // 收缩/零增量：不配对
        anchorIndex == 0 && anchorOffset < AT_BOTTOM_ORIGIN_PX -> 0f // 贴底原点：物理跟随
        growthIndex == null || growthIndex < 0 -> 0f                // 增长源不可见：丢弃
        anchorIndex == growthIndex -> growthPx                      // 锚==增长源：同帧配对
        anchorIndex < growthIndex ->                                // 锚在源之下：源类型决定
            if (coveredByFollowFamily) 0f else growthPx
        else -> 0f                                                  // 读历史：免
    }
}

/** 兼容缝（R1 后由 [StreamingAnchorRule] 统一；ledger 源族=跟随通道覆盖语义）。 */
internal object StreamingPairingRule {
    fun pairedDelta(anchorIndex: Int, anchorOffset: Int, itemIndex: Int, growthPx: Float): Float =
        StreamingAnchorRule.pairedDelta(
            anchorIndex, anchorOffset, itemIndex, growthPx,
            coveredByFollowFamily = true, // ledger 源族（banner/压缩卡）：BANNER bottomFollow 通道覆盖
        )

    /** 贴底邻域阈值(px)——与 ChatScrollController.isAtBottom 的 fiso<100 同源。 */
    const val AT_BOTTOM_PX = 100
}

/**
 * 每列表单一流式账本(ChatMessageList remember;主线程专用——measure/flush 均在 UI 线程)。
 * per-entryKey 基线;itemKey 用于 flush 相的锚位反查(visibleItemsInfo 按 key 找 index)。
 * entryKey 与 itemKey 分离:同一条目内可并存多个增长源(外层消息包裹/内层压缩卡),
 * 锚位判定共用 itemKey,记账身份各自独立(互斥挂载防嵌套双计,见 ChatMessageList 装配)。
 */
internal class StreamingGrowLedger {

    private class Entry(var itemKey: Any, var baseline: Int, var pending: Float)

    /** entryKey → 账目(基线+待配对累计)。 */
    private val entries = HashMap<Any, Entry>()

    /** 是否存在未决增量(flush 任务零成本早退判据)。 */
    val hasPending: Boolean get() = entries.values.any { it.pending != 0f }

    /**
     * item layout 节点 measure 相调用:记 Δ(增长为正)。冷启动(基线<=0)静默建基线不配对
     * —— 防挂载/回收重入的首次测量产生伪增量。同帧多遍测量:等高遍零增量,增长遍逐次
     * 累计(总和=本帧总增长)。
     */
    fun note(entryKey: Any, itemKey: Any, height: Int) {
        val e = entries.getOrPut(entryKey) { Entry(itemKey, 0, 0f) }
        e.itemKey = itemKey
        if (e.baseline <= 0) {
            e.baseline = height
            return
        }
        val d = height - e.baseline
        e.baseline = height
        if (d > 0) {
            e.pending += d.toFloat()
            if (BuildConfig.DEBUG) {
                // [SGR-435 验收七轮·仪表化] measure 相记账取证
                AppLogger.d("SGR-435", "note ek=" + entryKey + " d=" + d + " pend=" + e.pending)
            }
        }
        // 收缩(d<0):不配对,基线已随上式 rebase(旧 COMP「收缩全揭示」语义)
    }

    /** 节点离树(回收/流式结束/条件关闭):清账目——重入走冷启动,杜绝陈旧基线伪增量。 */
    fun forget(entryKey: Any) {
        entries.remove(entryKey)
    }

    /** 用户滚动让位:弃配全部未决增量(位置神圣;引擎 steady 同款语义)。 */
    fun rebaseAll() {
        entries.values.forEach { it.pending = 0f }
    }

    /**
     * flush 相:统一规则求值全部未决增量并清账,返回应派发总量(px;0=免派发)。
     * @param visibleIndex itemKey → lazy index;-1=不在可见布局(丢弃,下帧增长重新起账)
     */
    fun takePaired(anchorIndex: Int, anchorOffset: Int, visibleIndex: (Any) -> Int): Float {
        var total = 0f
        entries.values.forEach { e ->
            if (e.pending != 0f) {
                val idx = visibleIndex(e.itemKey)
                total += StreamingPairingRule.pairedDelta(anchorIndex, anchorOffset, idx, e.pending)
                e.pending = 0f
            }
        }
        return total
    }
}

/**
 * [StreamingGrowLedger] 的 layout 节点:无界测量取真高 → 记账 → **直报真高**(无裁剪——
 * 同帧 flush 保证未配对几何不上屏,延迟揭示不再需要)。节点式(element 身份相等复用):
 * onDetach 即 forget,回收/卸载自动清基线。
 */
private class StreamingGrowNode : Modifier.Node(), LayoutModifierNode {
    var ledger: StreamingGrowLedger? = null
    var entryKey: Any? = null
    var itemKey: Any? = null

    override fun onDetach() {
        val l = ledger
        val k = entryKey
        if (l != null && k != null) l.forget(k)
        super.onDetach()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
        val h = placeable.height
        val l = ledger
        val ek = entryKey
        val ik = itemKey
        if (l != null && ek != null && ik != null) l.note(ek, ik, h)
        return layout(placeable.width, h) { placeable.placeRelative(0, 0) }
    }
}

private class StreamingGrowElement(
    private val ledger: StreamingGrowLedger,
    private val entryKey: Any,
    private val itemKey: Any,
) : ModifierNodeElement<StreamingGrowNode>() {
    override fun create(): StreamingGrowNode = StreamingGrowNode().apply {
        ledger = this@StreamingGrowElement.ledger
        entryKey = this@StreamingGrowElement.entryKey
        itemKey = this@StreamingGrowElement.itemKey
    }

    override fun update(node: StreamingGrowNode) {
        node.ledger = ledger
        node.entryKey = entryKey
        node.itemKey = itemKey
    }

    override fun equals(other: Any?): Boolean =
        other is StreamingGrowElement &&
            other.ledger === ledger && other.entryKey == entryKey && other.itemKey == itemKey

    override fun hashCode(): Int {
        var result = System.identityHashCode(ledger)
        result = 31 * result + entryKey.hashCode()
        result = 31 * result + itemKey.hashCode()
        return result
    }
}

/**
 * 流式家族增长配对修饰符(#435)。必须与 clipToBounds 同链(沿用旧 COMP 装配约定)。
 * @param ledger 列表级账本
 * @param entryKey 记账身份(唯一;条目内多增长源各自独立)
 * @param itemKey 锚位反查键(Lazy item key;flush 相按它在 visibleItemsInfo 反查 index)
 */
internal fun Modifier.streamingGrowPairing(
    ledger: StreamingGrowLedger,
    entryKey: Any,
    itemKey: Any,
): Modifier = this.then(StreamingGrowElement(ledger, entryKey, itemKey))

// ===================== #437 引擎①：一帧缓冲帽（HeightReserve） =====================
//
// 缺陷链（VDRAW 62 帧实证）：增长帧先画「新高度+旧偏移」，偏移下一帧才追上——
// reject-draw 只挡宿主 View 绘制，item 层 RenderNode 重绘不经它，中间帧必然上屏。
//
// 协议：增长当帧帽保持旧高（增量被外层 clipToBounds 裁掉，零可见变化）；
// measure 相已得真高真值；pre-draw flush 单事务 {帽→真高 + 滚动待定位 +Δ}——
// 下一遍 measure **同 pass** 消费两者（reserved 为快照态，measure 读它=订阅重测），
// 中间帧构造性不存在。帽高单调只增（已上屏永不回改）；手势进行中持帽不放
// （增量保持不可见，手势零位移无豁免）；贴底原点/读历史（锚上方）免滚动配对。

/** 流式项的帽状态（单活流式项，列表级单例）。 */
internal class HeightReserveState {
    /** [RESERVE 诊断] 上次打点高度（非协议态）。 */
    var diagLastMeasuredH: Int = -2
    /** 已上屏帽高 px（单调只增）；-1=未初始化（首帧直通）。快照态：flush 单事务写。 */
    var reserved: Int by androidx.compose.runtime.mutableStateOf(-1)
    /** measure 相记录的当前真高（非快照，仅 flush 读）。 */
    var trueHeight: Int = -1
    /** 帽归属 item key（attach 相登记；换流式项自动重置）。 */
    var itemKey: Any? = null
    /** 对齐策略（用户验收二十二世：底对齐修贴底统计栏、却让阅读态回归推-回闪烁——
     *  底钉增长=每 append 全块上推 Δ，配对释放才补偿=中间帧闪烁。改为随态切换：
     *  贴底原点=底对齐（统计栏钉死、溢出朝上裁）；阅读态=顶对齐（原验证几何）。 */
    var alignBottom: Boolean by androidx.compose.runtime.mutableStateOf(true)
}

/** 帽修饰符：测真高、报帽高；增量越界由外层 clipToBounds 裁剪。 */
internal fun Modifier.streamingHeightReserve(state: HeightReserveState, itemKey: Any): Modifier {
    state.resetIfOwnerChanged(itemKey)
    return this then object : androidx.compose.ui.layout.LayoutModifier {
        override fun MeasureScope.measure(
            measurable: androidx.compose.ui.layout.Measurable,
            constraints: androidx.compose.ui.unit.Constraints,
        ): androidx.compose.ui.layout.MeasureResult {
            val child = measurable.measure(constraints.copy(minHeight = 0))
            // vr 终判：宽限项与新一轮流式项共主互抢（measure 7907/8658 交替）——
            // 所有权主张制：仅物主写真高；非物主（宽限/换主窗口）只读帽高。
            if (state.itemKey == itemKey) state.trueHeight = child.height
            // [RESERVE 诊断] vc 实证「附而不释」——区分 measure 未写/flush 未跑/实例分裂
            if (BuildConfig.DEBUG && child.height != state.diagLastMeasuredH) {
                state.diagLastMeasuredH = child.height
                AppLogger.d("RESERVE", "measure h=" + child.height + " reserved=" + state.reserved)
            }
            val h = if (state.reserved < 0) child.height else minOf(child.height, state.reserved)
            // 对齐随态（flush 置位）：贴底=底对齐（统计栏钉死、溢出朝上）；
            // 阅读=顶对齐（内容固定、新增长溢出朝下被裁——二十轮验证几何）。
            return layout(constraints.maxWidth, h) {
                if (state.alignBottom) child.place(0, h - child.height) else child.place(0, 0)
            }
        }
    }
}

private fun HeightReserveState.resetIfOwnerChanged(itemKey: Any) {
    if (this.itemKey != itemKey) {
        this.itemKey = itemKey
        reserved = -1
        trueHeight = -1
    }
}

/** 释放决策（纯函数，单测缝）。null=本帧不释放（未初始化/无增量/手势持帽）。 */
internal data class ReserveReleasePlan(val delta: Int, val scrollPaired: Boolean)

internal fun reserveReleasePlan(
    reserved: Int,
    trueHeight: Int,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    isScrollInProgress: Boolean,
    growthIndex: Int?,
): ReserveReleasePlan? {
    if (reserved < 0) return null                       // 未初始化（首帧直通由 flush 初始化）
    if (trueHeight <= reserved) return null             // 无增量（或收缩：帽不回改）
    if (isScrollInProgress) return null                 // 手势持帽（零位移无豁免）
    val delta = trueHeight - reserved
    // R1 统一配对谓词（原 z3 锚 index 语义+贴底原点判定合并入 StreamingAnchorRule）：
    // 贴底原点物理跟随免派发；跟随区免双重补偿；锚≤增长源同帧配对；读历史免。
    val paired = StreamingAnchorRule.pairedDelta(
        anchorIndex = firstVisibleIndex,
        anchorOffset = firstVisibleOffset,
        growthIndex = growthIndex,
        growthPx = delta.toFloat(),
    ) != 0f
    return ReserveReleasePlan(delta = delta, scrollPaired = paired)
}

/**
 * 流式家族 flush 任务(#435):挂 PreRenderCoordinator 单点(ChatMessageList 常驻注册,
 * 空账本零成本早退;无宿主=预览/单测降级为零配对,与旧通道无泵降级一致)。
 *
 * 顺序:空账早退 → 用户滚动弃配(位置神圣) → 统一规则求值 → applyPairedPreRenderShift
 * (引擎配对执行器:配对到全额)。免派发分支(贴底跟随族/读历史)从构造上零派发
 * ——震荡根源(无贴底豁免的 dispatch)在此消失。
 */


internal fun streamingGrowFlushTask(
    listState: LazyListState,
    ledger: StreamingGrowLedger,
    reserve: HeightReserveState? = null,
): PreDrawFlushTask = PreDrawFlushTask {
    var pendingReserveRelease: ReserveReleasePlan? = null
    // [#437 引擎①] 一帧缓冲帽释放：measure 相已得真高（增量当帧被帽裁掉不可见），
    // 此处单事务原子施加。reject-draw 对 item 层重绘无效（VDRAW 实证），故不依赖。
    if (reserve != null) {
        // 对齐随态（二十四世刀锋修正）：翻转仅允许在「追平态」（reserved==trueHeight
        // 时 place 偏移=0，两种对齐像素等价=零位移翻转）或「手势进行中」（拖拽自身
        // 掩盖一次性位移）。贴底跟随期 fiso 在 0~20 抖动，无条件切换会在 8px 刀锋上
        // 高频翻转＝振荡闪烁；滚动 settle 批量 append 若落在底对齐态＝大推+补偿大闪。
        val wantBottom = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset < 8
        if (reserve.alignBottom != wantBottom &&
            (reserve.reserved == reserve.trueHeight || listState.isScrollInProgress)
        ) {
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "RESERVE",
                    "align-flip bottom=" + wantBottom +
                        " caughtUp=" + (reserve.reserved == reserve.trueHeight) +
                        " overflow=" + (reserve.trueHeight - reserve.reserved) +
                        " fiso=" + listState.firstVisibleItemScrollOffset,
                )
            }
            reserve.alignBottom = wantBottom
        }
        if (BuildConfig.DEBUG && reserve.trueHeight != sgrLastTrue) {
            sgrLastTrue = reserve.trueHeight
            AppLogger.d("RESERVE", "flush reserved=" + reserve.reserved + " true=" + reserve.trueHeight)
        }
        val plan = reserveReleasePlan(
            reserved = reserve.reserved,
            trueHeight = reserve.trueHeight,
            firstVisibleIndex = listState.firstVisibleItemIndex,
            firstVisibleOffset = listState.firstVisibleItemScrollOffset,
            isScrollInProgress = listState.isScrollInProgress,
            // vd9 实证：条目增删窗口内 firstOrNull 与 firstVisibleItemIndex 短暂错位
            // 导致锚键误判（该配对的释放落 paired=false）——按 index 反查锚键。
            growthIndex = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == reserve.itemKey }?.index,
        )
        // [R1-A2] 单出口：帽 plan 延后与 ledger 配对合并为同帧单次滚动 set——
        // 原先帽 set 先落、ledger set 覆盖（requestPosition 覆盖写非叠加），同帧
        // 双补偿只活一笔；合并后两笔叠加一次原子生效。
        if (plan != null) {
            pendingReserveRelease = plan
        } else if (reserve.reserved < 0 && reserve.trueHeight >= 0) {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                reserve.reserved = reserve.trueHeight
            }
        }
    }
    // [VTRACE 2026-09-26] 逐帧视口轨迹（仅变化时打点）——任何来回跳动在时间线上
    // 直接可读（pair/drop/MSGEFFECT/GUARD/BANNER 行给出成因；用户裁决：精细分析
    // 用日志而非录屏抽帧，瞬态闪烁录屏易漏采）。
    run {
        val fii = listState.firstVisibleItemIndex
        val fiso = listState.firstVisibleItemScrollOffset
        if (fii != vtraceLastFii || fiso != vtraceLastFiso) {
            // 二十四世轮终修：观测者效应——滚动中 fiso 逐帧变化，无门限=每帧一条
            // logcat 写（主线程 I/O）计入帧成本。限频：纯 fiso 变化 ≥200ms 一条；
            // fii 跃迁（item 边界，分析关键）即时打。
            val now = android.os.SystemClock.elapsedRealtime()
            val fiiJump = fii != vtraceLastFii
            if (fiiJump || now - vtraceLastLogAt >= 200) {
                vtraceLastLogAt = now
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "VTRACE",
                        "t=" + now +
                            " fii=" + fii + " fiso=" + fiso + " ip=" + listState.isScrollInProgress
                    )
                }
            }
            vtraceLastFii = fii
            vtraceLastFiso = fiso
        }
    }
    // 用户验收二十一轮：滚动/惯性期置位流式暂缓（fling 卡顿修复——settle 后追平）
    val scrollingNow = listState.isScrollInProgress
    if (dev.leonardo.ocbeacon.ui.screens.chat.markdown.StreamingScrollHold.holding != scrollingNow) {
        dev.leonardo.ocbeacon.ui.screens.chat.markdown.StreamingScrollHold.holding = scrollingNow
    }
    if (!ledger.hasPending && pendingReserveRelease == null) return@PreDrawFlushTask true
    if (BuildConfig.DEBUG) {
        // [SGR-435 验收七轮·仪表化] flush 相进入取证（含弃配分支可辨）
        AppLogger.d(
            "SGR-435",
            "flush t=" + android.os.SystemClock.elapsedRealtime() +
                " fii=" + listState.firstVisibleItemIndex +
                " fiso=" + listState.firstVisibleItemScrollOffset +
                " ip=" + listState.isScrollInProgress,
        )
    }
    if (listState.isScrollInProgress) {
        ledger.rebaseAll()
        return@PreDrawFlushTask true
    }
    val fii = listState.firstVisibleItemIndex
    val fiso = listState.firstVisibleItemScrollOffset
    val infos = listState.layoutInfo.visibleItemsInfo
    val ledgerTotal = ledger.takePaired(fii, fiso) { ik -> infos.firstOrNull { it.key == ik }?.index ?: -1 }
    // [R1-A2] 单出口：帽配对 shift 与 ledger 配对 shift 同帧叠加，单事务一次 set。
    val pendingPlan = pendingReserveRelease
    val reserveShift = if (pendingPlan?.scrollPaired == true) pendingPlan.delta.toFloat() else 0f
    val total = reserveShift + ledgerTotal
    // 二十四世轮审查（B4 观测者效应）：贴底跟随时此分支每 flush 一条 logcat——限频 500ms
    if (BuildConfig.DEBUG && total == 0f && infos.isNotEmpty() &&
        android.os.SystemClock.elapsedRealtime() - sgrDropLastLogAt >= 500
    ) {
        sgrDropLastLogAt = android.os.SystemClock.elapsedRealtime()
        AppLogger.d(
            "SGR-435",
            "drop(append/reading-away) t=" + android.os.SystemClock.elapsedRealtime() +
                " fii=" + fii + " fiso=" + fiso
        )
    }
    if (total != 0f || pendingPlan != null) {
        // #437 验收五轮（用户裁决，对齐 #427 引擎先例）：渲染前计算目标位+
        // 反射 requestPosition 写入待定区，由下一遍 measure 原子消费——与
        // dispatchRawDelta（渲染后滚动修正=先画增长态再跳位，整屏闪烁）的
        // 本质区别在「计算先行、measure 原子生效」。
        // 目标位=用户公式 scrollPos+Δ：fiso += total（锚 item 内偏移推大），
        // 溢出沿可见 items 向 index 增大换算（reverseLayout 视觉向上）。
        var targetFii = fii
        var targetFiso = fiso + total.toInt()
        var targetKey: Any? = null
        var guard = 0
        while (guard++ < 64) {
            val anchor = infos.firstOrNull { it.index == targetFii } ?: break
            if (targetFiso < anchor.size) { targetKey = anchor.key; break }
            targetFiso -= anchor.size
            targetFii++
        }
        var writtenReserve = false
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            // 帽释放（若有）：与滚动待定位同一事务——高度扩展与位移下一遍 measure 同 pass 消费
            if (pendingPlan != null && reserve != null) {
                reserve.reserved = reserve.trueHeight
                writtenReserve = true
            }
            if (total != 0f) {
                LazyListReflection.requestScrollToItemNoCancel(listState, targetFii, targetFiso, targetKey)
            }
        }
        if (BuildConfig.DEBUG && (total != 0f || writtenReserve)) {
            AppLogger.d(
                "SGR-435",
                "release t=" + android.os.SystemClock.elapsedRealtime() +
                    " capd=" + (pendingPlan?.delta ?: 0) + " led=" + ledgerTotal.toInt() +
                    " set(fii=" + targetFii + ",fiso=" + targetFiso + ")" +
                    " h->" + (if (writtenReserve) reserve?.trueHeight.toString() else "-"),
            )
        }
        return@PreDrawFlushTask total != 0f // ledger 派发才拒绘（帽路径画增长前态，语义保持）
    }
    true
}

// --- 反射:绕过官方 requestScrollToItem 的 scroll{} 互斥锁取消机制 ---
// 官方 requestScrollToItem(@ExperimentalFoundationApi)做两件事:
//   ① if (isScrollInProgress) scroll {} ← 获取互斥锁,杀死 fling
//   ② scrollPosition.requestPosition + invalidateScope ← 设置待定位置
// 我们只想要 ② —— 设置待定位置而不杀死 fling 惯性。
// #435 后仅剩引擎收起锚点恢复/滚动打断锚点恢复两处调用(#423/#427 路径);
// 流式家族已改走 dispatchRawDelta 同帧配对,不再依赖本反射。
//
// ⚠️ 反射依赖的私有成员(Compose BOM 2026.05.01,见 app/build.gradle.kts:132):
//   - 字段 androidx.compose.foundation.lazy.LazyListState.scrollPosition
//   - 方法 scrollPosition.requestPositionAndForgetLastKnownKey(Int, Int)
//   - 字段 androidx.compose.foundation.lazy.LazyListState.measurementScopeInvalidator: MutableState<Unit>
// Compose 版本升级前必须手动测试这些成员仍存在(AGENTS.md「精准修改」规则)。
// 升级后若成员消失/改名 → 初始化探测一次性失败 → 降级为官方 requestScrollToItem
//(取消 fling 但功能等价、不崩溃)。
// #423 Phase 0:该核销已自动化——LazyListReflectionTest 在 JVM 上对当前 classpath
// 钉死三成员的真实签名,BOM 升级若破坏未装箱巧合,单测先红(先于发版)。
/** #423 Phase 0:反射探针三件套(scrollPosition 字段 / 定位方法 / 失效器字段)。 */
internal data class LazyListProbes(
    val scrollPositionField: java.lang.reflect.Field,
    val requestPositionMethod: java.lang.reflect.Method,
    val invalidatorField: java.lang.reflect.Field,
    /**
     * 2026-09-25 键保持通道（可选）：requestPositionAndForgetLastKnownKey 会遗忘
     * 锚 item 的 lastKnownKey——其后 item 插入/重排按字面 index 重锚 → 大额 set 后
     * 视觉跳变（R9 LEAP 实证）。set 同帧回写与目标位一致的 key 即消除。字段缺失
     * （版本漂移）= 通道降级为旧行为，不影响探针整体可用。
     */
    val lastKnownKeyField: java.lang.reflect.Field?,
)

/**
 * #423 Phase 0(D5):一次性探针解析——抽出为可注入类解析器的顶层函数,JVM 单测
 * 可模拟「类消失/成员签名漂移」验证降级路径(LazyListReflectionTest);
 * 冒烟用例同步钉死当前 BOM 的真实签名(value-class 未装箱巧合,K9)。
 * 任一成员失败 → 返回 null,调用方永久走官方 requestScrollToItem 降级。
 */
internal fun resolveLazyListProbes(
    resolveClass: (String) -> Class<*>? = ::defaultResolveClass,
): LazyListProbes? {
    val stateClass = resolveClass("androidx.compose.foundation.lazy.LazyListState") ?: return null
    val scrollPositionField = lookupField(stateClass, "scrollPosition") ?: return null
    val requestPositionMethod =
        lookupMethod(
            scrollPositionField.type,
            "requestPositionAndForgetLastKnownKey",
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
        ) ?: return null
    val invalidatorField = lookupField(stateClass, "measurementScopeInvalidator") ?: return null
    val lastKnownKeyField = lookupField(scrollPositionField.type, "lastKnownFirstItemKey")
    return LazyListProbes(scrollPositionField, requestPositionMethod, invalidatorField, lastKnownKeyField)
}

private fun defaultResolveClass(name: String): Class<*>? = try {
    Class.forName(name)
} catch (t: Throwable) {
    AppLogger.w("LazyListReflection", "class $name not found: ${t.message}")
    null
}

private fun lookupField(type: Class<*>, name: String): java.lang.reflect.Field? = try {
    type.getDeclaredField(name).apply { isAccessible = true }
} catch (t: Throwable) {
    // NoSuchFieldException / NoSuchFieldError 等
    AppLogger.w("LazyListReflection", "field ${type.name}.$name not found: ${t.message}")
    null
}

private fun lookupMethod(
    type: Class<*>,
    name: String,
    vararg params: Class<*>,
): java.lang.reflect.Method? = try {
    type.getDeclaredMethod(name, *params).apply { isAccessible = true }
} catch (t: Throwable) {
    // NoSuchMethodException / NoSuchMethodError 等
    AppLogger.w("LazyListReflection", "method ${type.name}.$name not found: ${t.message}")
    null
}

internal object LazyListReflection {
    // 一次性探测:失败返回 null,后续永久走降级路径。
    // #423 Phase 0 起经 resolveLazyListProbes 统一解析(可测缝,见 LazyListReflectionTest)。
    private val probes: LazyListProbes? = resolveLazyListProbes()

    /** 批次九轮2:反射探针可用(=引擎收起锚点恢复通道在役)。end-restore 据此让位。 */
    val probesResolved: Boolean get() = probes != null

    // #258 换道手术(2026-08-29):scrollToBeConsumed 反射直写已整体删除(用户 drag 竞态
    // FATAL 定罪)。#435(2026-09-25):流式家族注入通道(PreRenderShiftChannel)退役,
    // 流式增长改走 dispatchRawDelta 同帧配对(StreamingGrowLedger);本反射仅存锚点恢复用途。

    /**
     * 设置待定滚动位置但不取消进行中的 fling(反射路径)。
     * 反射初始化失败或运行时 invoke 抛异常时降级为官方 [LazyListState.requestScrollToItem]
     *@ExperimentalFoundationApi;会取消 fling,但保证位置设置生效、不崩溃)。
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun requestScrollToItemNoCancel(state: LazyListState, index: Int, scrollOffset: Int, key: Any? = null) {
        // smart-cast 友好:局部非空变量
        val p = probes
        if (p != null) {
            try {
                val pos = p.scrollPositionField.get(state)
                p.requestPositionMethod.invoke(pos, index, scrollOffset)
                // 键保持（2026-09-25）：目标位 item 的 key 回写 lastKnownKey——
                // 插入/重排后 LazyList 仍按 key 重锚，消除字面 index 失配跳变。
                if (key != null) {
                    p.lastKnownKeyField?.let { f ->
                        try { f.set(pos, key) } catch (t: Throwable) {
                            AppLogger.w("LazyListReflection", "lastKnownKey set failed: " + t.message)
                        }
                    }
                }
                @Suppress("UNCHECKED_CAST")
                (p.invalidatorField.get(state) as MutableState<Unit>).value = Unit
                return
            } catch (t: Throwable) {
                // IllegalAccessException / IllegalArgumentException / ClassCastException 等
                AppLogger.w("LazyListReflection", "invoke failed, fallback: ${t.message}")
            }
        }
        // 降级:官方 API。语义差异 = 通过 scroll{} 互斥锁取消 fling,可接受。
        state.requestScrollToItem(index, scrollOffset)
    }
}
