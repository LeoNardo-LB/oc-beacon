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
 * ## 批节奏的结构性继承（cadence = [STREAM_FLUSH_INTERVAL_MS]，终审 S4 收编引擎域）
 *
 * 账本输入 = 数据层批处理(MessageEventHandler.scheduleFlush，周期消费引擎域常量)
 * 驱动的 measure;flush 派发节奏 = 批节奏。无任何额外定时器层。
 * （历史值 48ms → 100ms（#437 cadence 快赢）；文档漂移已随 #442 批次 C 修正。）
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
/**
 * #437 spec 裁决 3 收编（终审 S4）：SSE→UI flush cadence 常量归引擎域定义
 * （高度引擎对节奏的单一真相源；数据层 MessageEventHandler 消费）。
 */
internal const val STREAM_FLUSH_INTERVAL_MS = 100L

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

/**
 * 新bug根修缝：配对让位判定——读位 ≠ 上批 set 目标 ⇒ 存在未被 measure 消费的
 * 外部 pending（GUARD/ForceScroll 显式意图的 requestScrollToItem 写入）——配对
 * requestPosition 是覆盖写，此时配对会**抵消显式意图**（取证：GUARD 触底 pending(0)
 * 被配对 set(7, 66) 覆盖 → 触底失败 + 每批 +Δ 持续推走视口 = 「上方内容闪烁消失」）。
 * 让位语义：位置神圣——显式意图 > 引擎配对。null 目标=首批不让位。
 */
internal fun shouldYieldPairing(
    readFii: Int,
    readFiso: Int,
    lastSetFii: Int?,
    lastSetFiso: Int?,
): Boolean = if (lastSetFii == null || lastSetFiso == null) {
    false
} else {
    readFii != lastSetFii || readFiso != lastSetFiso
}

/**
 * #502：divergence 静止判定——读位与上一帧 yield 检查点观测值相同 **且** lastSet
 * 已陈旧（连续 ≥2 帧引擎无 set 写入）⇒ 已定居的外部滚动（用户手势停下的新位
 * 置 / 外部显式意图消费后的落点），**不是**引擎待消费 pending。此时须采纳为新
 * 配对基线恢复正常求值：否则让位永久化——lastSet 仅由 applyPairedShift 写，引
 * 擎因让位不 apply ⇒ 读位永 ≠ lastSet ⇒ 每帧作废帽释放计划 ⇒ 帽冻结在旧值
 * （min(真高, 冻结帽高) + clipToBounds = 流式消息被裁剪在视口一小块固定区域，
 * 完结换装 reset 才全量展示——真机定罪 2026-10-02：上翻阅读期间引擎 set(7,393)
 * → 甩回底 (0,0) → yield×1448 / reserved 冻 685 达 12s / 真高涨至 4303）。
 *
 * 陈旧度守卫（防回归口）：引擎 set 是请求时即写 lastSet（measure 消费前），
 * pending 未消费窗口内读位=旧位且静止——裸静止判定会误采纳旧位毁掉 pending
 * 保护（「上方内容闪烁消失」根修回归）。只认「引擎近期确无 set」的静止。
 *
 * 「上一帧」为 null（首批/无历史）⇒ 不采纳（保持一帧让位观察期——真 pending
 * 消费中或 fling 刚 settle，下一帧即达稳态）。
 */
internal fun shouldAdoptExternalPosition(
    readFii: Int,
    readFiso: Int,
    prevFii: Int?,
    prevFiso: Int?,
    framesSinceLastSet: Long,
): Boolean = prevFii != null && prevFiso != null &&
    readFii == prevFii && readFiso == prevFiso && framesSinceLastSet >= 2


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

    /** 节点复用换 itemKey（同 entryKey）：基线清零重学——防旧基线首测伪增量。 */
    fun relearnBaseline(entryKey: Any) {
        entries[entryKey]?.baseline = 0
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
                // R2 批缝退役迁移：ledger 源族直调统一谓词（covered=true——BANNER
                // bottomFollow 通道覆盖语义，原 StreamingPairingRule 兼容缝删除）。
                total += StreamingAnchorRule.pairedDelta(
                    anchorIndex, anchorOffset, idx, e.pending,
                    coveredByFollowFamily = true,
                )
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
        //（2026-10-03 审计修复：节点复用换键——旧 entryKey 不清账=泄漏+陈旧
        // pending；itemKey 换代而 entryKey 不变=旧基线首测伪 Δ（审计「一次假
        // Δ 自愈」项的根治）。换键即清账/重学基线，与 onDetach 同语义。）
        val oldLedger = node.ledger
        val oldEntryKey = node.entryKey
        if (oldLedger != null && oldEntryKey != null && oldEntryKey != entryKey) {
            oldLedger.forget(oldEntryKey)
        }
        if (oldLedger === ledger && oldEntryKey == entryKey && node.itemKey != itemKey) {
            ledger.relearnBaseline(entryKey)
        }
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
     * 底钉增长=每 append 全块上推 Δ，配对释放才补偿=中间帧闪烁。改为随态切换：
     * 贴底原点=底对齐（统计栏钉死、溢出朝上裁）；阅读态=顶对齐（原验证几何）。 */
    var alignBottom: Boolean by androidx.compose.runtime.mutableStateOf(true)

    /**
     * #540 方向 A：展开配对窗计数（>0 = 物主 item 上有展开/收起集进行中）。
     * 窗内帽释放/收缩**恒配对**（覆盖贴底原点/读历史免派发格）——流式降级路径
     * 的展开增长经帽通道逐档原子落地，上推源从构造上消失（#526 事后回锚退役）。
     * 计数制支持同 item 多卡并发窗。**只由卡侧窗协程配对维护**（开窗 ++/关窗
     * finally --）——换主 reset 不得清零：在飞窗被外部清零后关窗减成负数，
     * `>0` 恒配对判据自此永久失效（2026-10-10 review 轮定罪，步边界换主可触）。
     */
    var expandPairingWindows: Int = 0

    /**
     * #540 二轮（表述乙·揭示缘参照，裁决 2026-10-11）：宿主 item 根的 root-Y
     * （CML item Box onGloballyPositioned 逐 placement 更新；仅帽物主 item 写，
     * 键不等守卫防跨 item 互抢）。卡侧 toggle 时刻把揭示盒 root-Y 换算成
     * item 空间几何写入 [expandCollapseStickTop]/[expandCollapseSpanDepth]。
     */
    var hostItemTopY: Float = Float.NaN

    /**
     * #540 二轮：当前窗卡揭示盒顶距 item 顶的偏移（塌缩不变量——盒顶上方
     * 内容在收起中不动）；-1=无在飞窗/未捕获。flush 负向配对资格判定用。
     */
    var expandCollapseStickTop: Int = -1

    /** #540 二轮：toggle 时刻揭示盒高（塌缩区深度，资格判定界）。-1=未捕获。 */
    var expandCollapseSpanDepth: Int = -1

    /**
     * #551（2026-10-13 七报+高度参照系裁决）：**离底让位视口预算**——单次展开
     * 事件的正向配对总位移上限（卡侧 toggle 时刻置为一屏主轴）。超视口揭示的
     * 全高让位会把视口推飞数屏（实证 fiso 2155→13813≈5 屏「跳到上面」）；
     * 预算内照常配对（小卡全高=预算内不受影响），预算耗尽后驱动盒照常长高、
     * 增长自然溢出折叠下方（用户滚动自取）。生命周期：开窗重置为一屏、关窗
     * 不清零（残留驱动步进续受钳制；稳态锚规则配对经 hatAnchorP 豁免不受
     * 残值影响——八报语义）。并发窗各自开窗重置=单窗上限一屏（多窗组合可
     * 累计超一屏）。消耗点：applyPairedShift 正向分支。
     */
    var expandDepartureBudgetLeft: Int = Int.MAX_VALUE

    /**
     * #540 三轮：在飞降级驱动（**四报改列表**——同流式 item 多卡并发 toggle 时
     * 单槽被后卡覆盖、先卡孤儿化瞬跳，四报竞态定罪之一）。flush 每帧遍历步进；
     * 卡侧 toggle 组合期注册、窗关 finally 注销（仅 !animating）。
     */
    val degradeDrives = mutableListOf<DegradeDriveState>()

    /** 便捷判定：存在在飞驱动（flush 早退门用）。 */
    val anyDriveAnimating: Boolean get() = degradeDrives.any { it.animating }
}

/**
 * #540 三轮（用户三报「收起来回震荡」定罪）：降级 toggle 高度驱动态——盒布局
 * 高由 flush 单事务步进（帽通道），取代 AnimatedVisibility 的帧内自驱尺寸动画。
 *
 * 定罪链：shrinkVertically 每帧在 measure 相直改布局高（收缩穿透 `min(child,
 * reserved)` 帽天花板——帽只遮增长不遮收缩），而滚动修正是 pending 待定位、
 * 下一遍 measure 才消费 → 每帧 draw 画在「新高度×旧滚动」错位态、下帧回位
 * =逐帧交替震荡；展开常见几何被揭示缘否决门判零写入（单写者=LazyList 默认
 * 锚定本就正确）故稳定——与用户三报「展开还好、收起震荡」精确吻合。soak
 * 日志断言只验写入目标（数学正确），相位错不可见=五小时 13 次收起全绿仍震荡。
 *
 * 驱动后：高度步进与滚动写同快照、同遍 measure 原子生效（帽协议增长向的
 * 对称复刻）；让位/手势帧步进随 plan 丢弃（幂等重算）→ 冻结而非错位。
 *
 * 四报竞态修订（2026-10-11 深夜）：**逐卡几何**——stickTop/spanDepth 随驱动走
 * （单槽 reserve 级字段被后卡覆盖=先卡镜像目标算错坐标，四报竞态定罪之二）。
 */
internal class DegradeDriveState(
    hInitial: Int = 0,
    /** 终态高：收起=0 / 展开=内容高；-1=展开中等内容测高（挂起相不步进）。 */
    var hTarget: Int = -1,
    /** 本卡揭示盒顶距 item 顶偏移（toggle 捕获）；-1=未捕获。 */
    var stickTop: Int = -1,
    /** 本卡 toggle 时刻盒高（塌缩区深度界）；-1=未捕获。 */
    var spanDepth: Int = -1,
) {
    /** 盒布局驱动高 px（快照态：flush 单写、布局层读）。 */
    var h: Int by androidx.compose.runtime.mutableIntStateOf(hInitial)

    /** 本帧待应用步进（reservePhase 算出、applyPairedShift 事务内消费；幂等重算覆盖）。 */
    var pendingStep: Int = 0

    val animating: Boolean get() = hTarget >= 0 && h != hTarget
}

/**
 * 四报多卡滚动分量纯函数：锚在塌缩区内/上方（anchorTop ≤ stick+span）的卡其
 * 步进需镜像；锚在其下方（新内容侧）的卡零操作。单卡时与旧判定表逐点等价。
 * 未捕获几何（stick<0）保守计入（镜像兜底）。
 *
 * 五报 F2（2026-10-12 定罪修订）：**符号感知**——只有负步进（收起）走塌缩
 * containment 表。正步进（展开增长）混入净负帧时曾被本表以 span=0 的展开态
 * 几何误判 outside-span 拒发（r2 取证 39.855/872 两帧 drv=+56/+38 noop），
 * 高度推进无配对=残差偏移源。正步进的配对资格由
 * [drivePositivePairedShift] 逐卡判定，不经此表。
 */
internal fun driveScrollShift(anchorTop: Int, drives: List<DegradeDriveState>): Int =
    drives.sumOf { d ->
        if (d.pendingStep >= 0) {
            0
        } else if (d.stickTop < 0 || d.spanDepth < 0 || anchorTop <= d.stickTop + d.spanDepth) {
            d.pendingStep
        } else {
            0
        }
    }

/**
 * 五报 F3：正向步进（展开增长）逐卡配对分量——锚在某卡揭示缘上方
 * （anchorTop < stickTop，阅读位高于被展开卡）时该卡增长位于锚下方，配对=
 * 自造位移 → 计 0（否决）；锚在揭示缘及下方（含贴底原点/读位在卡下）恒配对
 * （离底让位语义）。未捕获几何（stick<0）保守配对（legacy 盲配）。
 *
 * 取代 reserve 级单槽镜像（expandCollapseStickTop）在驱动通道上的读法——
 * 单槽持「最后 toggle」几何，并发/连 toggle 时先卡被后卡几何误判（四报只把
 * 负向半边逐卡化，本函数补齐正向半边）。
 */
internal fun drivePositivePairedShift(anchorTop: Int, drives: List<DegradeDriveState>): Int =
    drives.sumOf { d ->
        if (d.pendingStep <= 0) {
            0
        } else if (d.stickTop < 0 || anchorTop >= d.stickTop) {
            d.pendingStep
        } else {
            0
        }
    }

/**
 * #551：离底让位预算截断（纯函数）——episode 内正配对分量（驱动逐卡+窗内帽）
 * 之和不超过剩余预算；负分量钳 0（负帽不占预算）。稳态锚规则配对（八报保护
 * 对象）不经此函数。
 */
internal fun capDepartureByBudget(drivePos: Int, hatPos: Int, budgetLeft: Int): Int =
    minOf(drivePos + hatPos.coerceAtLeast(0), budgetLeft.coerceAtLeast(0))

/** 驱动步进纯函数（指数逼近 ~180ms@60fps，AV spring 观感等价）。0=稳态/等测高。 */
internal fun degradeDriveStep(hCur: Int, hTarget: Int): Int {
    if (hTarget < 0) return 0
    val remaining = hTarget - hCur
    if (remaining == 0) return 0
    if (kotlin.math.abs(remaining) <= DEGRADE_DRIVE_SNAP_PX) return remaining
    val step = (remaining * DEGRADE_DRIVE_FRACTION).toInt()
    return if (step == 0) {
        if (remaining > 0) 1 else -1
    } else {
        step
    }
}

private const val DEGRADE_DRIVE_FRACTION = 0.32f
private const val DEGRADE_DRIVE_SNAP_PX = 2

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
        // expandPairingWindows 不清零：它是列表级引用计数，所有权归卡侧窗协程
        // （开窗 ++/关窗 finally -- 严格配对）。此处清零会让在飞窗的关窗减成
        // 负数 → `>0` 恒配对判据永久失效（review 轮定罪）。
    }
}

/**
 * #442 R2 分片唤醒（A2）：毕业换装帧强制重立基线——reserved/trueHeight 归
 * 未初始化（换装帧 measure 直通真高），itemKey 保持（帽所有权与尾块 item
 * 连续，不动 resetIfOwnerChanged 语义）。不 reset 的话「帽不回改」会使尾块
 * item 永久虚高已冻结前缀的高度（#470 墙的毕业形态）。调用点：ShardController
 * .fire 的 onFire 钩子（发布同协程步先行——换装帧原子见三者）。
 */
internal fun HeightReserveState.hardReset() {
    reserved = -1
    trueHeight = -1
}

/** 释放决策（纯函数，单测缝）。null=本帧不释放（未初始化/无增量/手势持帽）。 */
internal data class ReserveReleasePlan(
    val delta: Int,
    val scrollPaired: Boolean,
    /** #540 三轮：本帧降级驱动步进量（applyPairedShift 事务内推进盒高+帽预记）。 */
    val driveStep: Int = 0,
)

internal fun reserveReleasePlan(
    reserved: Int,
    trueHeight: Int,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    isScrollInProgress: Boolean,
    growthIndex: Int?,
    /** #540：展开配对窗内（物主 item 上展开/收起集进行中）——增长/收缩恒配对。 */
    expandPaired: Boolean = false,
    /**
     * #549 Fix B（2026-10-13）：**正向**窗内配对的独立判据——仅帽负向例外
     * （收起集帽回落）沿用 [expandPaired] 窗语义；正向（展开增长）额外要求
     * 存在动画中的驱动。驱动 settle 后窗未关的尾巴里，帽把流式增长持续升格
     * 「离底让位」→内容短于视口时被框架钳回 (0,0) 的离底写入逐帧重发
     * （emu-r11/r427 定罪：settle 后 ~900ms 打架）。null=回落 [expandPaired]
     * （legacy 语义，纯函数可测）。
     */
    expandPairedPos: Boolean? = null,
    /**
     * #540 二轮修订（用户复测定罪「输出中展开震」）：锚在揭示缘**上方**（阅读位
     * 高于被展开卡）时，窗内增长发生在锚下方——锚所见内容不动，配对位移反而是
     * 自造推动。true=否决窗内配对（走免配对释放）。仅窗内消费；未捕获几何恒
     * false（维持既有盲配=legacy 绿路径）。
     */
    anchorAboveReveal: Boolean = false,
): ReserveReleasePlan? {
    if (reserved < 0) return null                       // 未初始化（首帧直通由 flush 初始化）
    if (isScrollInProgress) return null                 // 手势持帽（零位移无豁免——展开窗同守）
    val delta = trueHeight - reserved
    if (delta == 0) return null                         // 无增量
    if (delta < 0 && !expandPaired) return null         // 收缩：帽不回改（存量语义；窗内例外=收起配对）
    // #549 Fix B：正向配对独立判据（null=回落窗语义——负向例外不受影响）
    val expandPairedPositive = expandPairedPos ?: expandPaired
    // R1 统一配对谓词（原 z3 锚 index 语义+贴底原点判定合并入 StreamingAnchorRule）：
    // 贴底原点物理跟随免派发；跟随区免双重补偿；锚≤增长源同帧配对；读历史免。
    // #540：展开窗内的增长是用户主动揭示意图——锚位格全部让位，恒配对；
    // 二轮修订：锚在揭示缘上方时增长位于锚下方，配对=自造位移 → 否决。
    val paired = if (delta > 0 && expandPairedPositive && anchorAboveReveal) {
        // 仅正向（展开增长）可被否决——负向（收起）走负向分支自己的塌缩几何
        // 判定（锚在揭示缘上方恰是镜像骑行格，不能在此被掐死导致帽不回放）。
        false
    } else if (delta > 0) {
        // #549 Fix B：正向配对用 Pos 判据（动画门槛）；负向沿用窗语义防结算卡死
        expandPairedPositive || StreamingAnchorRule.pairedDelta(
            anchorIndex = firstVisibleIndex,
            anchorOffset = firstVisibleOffset,
            growthIndex = growthIndex,
            growthPx = delta.toFloat(),
        ) != 0f
    } else {
        expandPaired || StreamingAnchorRule.pairedDelta(
            anchorIndex = firstVisibleIndex,
            anchorOffset = firstVisibleOffset,
            growthIndex = growthIndex,
            growthPx = delta.toFloat(),
        ) != 0f
    }
    return ReserveReleasePlan(delta = delta, scrollPaired = paired)
}

/**
 * #540 帽×机件单写者（planned 抬帽，纯函数）：机件原子帧（反射待定位 + driveTo
 * 同遍 measure）落在帽物主 item 上时，抬帽必须与增长**同快照**——否则增长被
 * min(child, reserved) 钳一帧，反射 (+H) 先消费而布局未长，flush 残差释放与机件
 * 反射叠成错帧/双注入（§4.1 宽限帽污染面收编）。非物主/未初始化/零增量=不动。
 */
internal fun hatPairedRaise(reserved: Int, ownerKey: Any?, hostKey: Any?, delta: Int): Int =
    if (ownerKey != null && ownerKey == hostKey && reserved >= 0 && delta > 0) reserved + delta else reserved

/** #540 镜像：收起集落帽（reserved 回落）；下界越界归 -1（未初始化哨兵，flush 首帧直通重建）。 */
internal fun hatPairedLower(reserved: Int, ownerKey: Any?, hostKey: Any?, delta: Int): Int =
    if (ownerKey != null && ownerKey == hostKey && reserved >= 0 && delta > 0) {
        (reserved - delta).let { if (it < 0) -1 else it }
    } else reserved

/**
 * #540 二轮（表述乙·揭示缘参照，裁决 2026-10-11；同日用户复测定罪修订）：
 * 窗内负向（收缩）配对决策（纯函数，单测缝）。
 *
 * 判定表（锚相对塌缩几何的位置，塌缩区=揭示盒 [stickTop, stickTop+spanDepth]）：
 * - **锚在揭示盒底以下**（anchorTop > stickTop+spanDepth，即盒下方新内容侧）：
 *   **零操作（null）**——该侧内容坐标不随塌缩变化（item 底缘与下方内容不动），
 *   LazyList 默认锚定本就正确；旧 dispatch 盲镜像在此发位移=col4 跳变自造
 *   （journal §22 D3 修正读法）。
 * - **锚在盒底及以上**（anchorTop ≤ stickTop+spanDepth：盒内或盒上方旧内容侧）：
 *   **逐帧镜像**（fiso−|Δ| 钳 item 底）——上方内容与揭示缘的坐标随塌缩逐帧
 *   下移（item 底不动、总高缩），保持画面固定=视口逐帧跟骑。修订前的「首帧
 *   跳到揭示缘」在锚位于体内深处时一次性把上半部分拖下一大截（用户复测定罪
 *   「收起拖动上半部分」①面）；「盒上方走零操作」漏配镜像=上半部分被动被拖
 *   （②面）——镜像骑行两 face 同除，落位自然（终态 fiso₀−H，越界钳 item 底
 *   为跨 item 折叠的有界近似：流式物主下方恒为零高横幅群，钳 item 底≈折叠终位）。
 * - **未捕获几何**（stickTop<0 或 spanDepth<0）：保守纯镜像钳底（兜底）。
 *
 * **itemPreH 必须传帧前高度**（帽协议 reserved——评估时刻尚未释放，恰为本帧
 * 收缩前的值；递归自洽：上帧释放后 reserved=上帧后高=本帧前高）。曾用帧后
 * 真高使 anchorTop 被低估一个 δ（塌缩初帧 δ 可达数百 px）——盒下方临界锚
 * 误入镜像侧被拖（根修审计定罪，journal §27）。
 *
 * 返回 null=零操作（仅帽回写，无滚动写入）。
 */
internal fun shrinkPairedTargetFiso(
    fiso: Int,
    itemPreH: Int,
    delta: Int,
    stickTop: Int,
    spanDepth: Int,
): Int? {
    require(delta < 0)
    if (stickTop < 0 || spanDepth < 0) {
        return (fiso + delta).coerceAtLeast(0)
    }
    val anchorTop = itemPreH - fiso
    return if (anchorTop > stickTop + spanDepth) {
        null
    } else {
        (fiso + delta).coerceAtLeast(0)
    }
}

/**
 * #540 二轮（宽豁免裁决）：窗内让位判定——展开/收起配对窗开启期间，toggle
 * 本身就是最新显式意图，外部 pending 让位守卫（shouldYieldPairing）对其豁免；
 * 手势门（isScrollInProgress）在链路上游更硬，不受影响。
 */
internal fun shouldYieldPairingWindowed(
    readFii: Int,
    readFiso: Int,
    lastSetFii: Int?,
    lastSetFiso: Int?,
    windowOpen: Boolean,
): Boolean = !windowOpen && shouldYieldPairing(readFii, readFiso, lastSetFii, lastSetFiso)

/**
 * #438 R-1（2026-09-28 null-key 裸 index 通道根修）：配对目标位解析纯函数。
 *
 * 可见窗内按 layout 索引/尺寸换算（原 while 逻辑）；**落点越窗**时原逻辑
 * targetKey=null → 反射 requestPosition 按字面 index 重锚——插入/重排后即
 * LEAP 残余通道（R9 -7562 实证；817607b4 键回写只覆盖「落点在 visibleItemsInfo
 * 内」的条件式修复）。数据侧 key 投影（dataKeyAt：index → chatEntries key，
 * 装配层提供）兜底：越窗也能 key 锚定，重排后仍正确重锚。offset 用越窗时
 * 的换算余量（近似，key 正确性优先，小误差下帧自愈）。
 */
internal fun resolvePairedTarget(
    fii: Int,
    fiso: Int,
    total: Int,
    visible: List<androidx.compose.foundation.lazy.LazyListItemInfo>,
    dataKeyAt: ((Int) -> Any?)?,
): Triple<Int, Int, Any?> {
    var targetFii = fii
    var targetFiso = fiso + total
    var targetKey: Any? = null
    var guard = 0
    while (guard++ < 64) {
        val anchor = visible.firstOrNull { it.index == targetFii }
        if (anchor == null) {
            // #438 R-1：越窗兜底——数据侧 key 投影；无投影/投影 miss 退旧通道（null key）
            targetKey = dataKeyAt?.invoke(targetFii)
            break
        }
        if (targetFiso < anchor.size) {
            targetKey = anchor.key
            break
        }
        targetFiso -= anchor.size
        targetFii++
    }
    return Triple(targetFii, targetFiso, targetKey)
}

/**
 * flush 任务跨帧记忆（#438 R-2 接线层产物化）：上批配对 set 目标位——
 * [shouldYieldPairing] 让位判定的持久侧。生命周期 = task 实例生命周期
 * （同一 task 跨帧保留；新 task 无历史记忆——见 FlushTaskMemoryTest）。
 */
internal class FlushTaskMemory {
    var lastSetFii: Int? = null
    var lastSetFiso: Int? = null

    /** #502：上一帧 yield 检查点读位——divergence 静止判定（采纳外部定居位）的观测侧。 */
    var prevFii: Int? = null
    var prevFiso: Int? = null

    /** #502：flush 帧序（divergence 检查点递增）——lastSet 陈旧度计算基准。 */
    var frameSeq: Long = 0L

    /** #502：lastSet 写入时的帧序（applyPairedShift 写 set 与 #502 采纳写基线两处戳记）。 */
    var lastSetFrame: Long = -1L
}

/**
 * 流式家族 flush 任务(#435):挂 PreRenderCoordinator 单点(ChatMessageList 常驻注册,
 * 空账本零成本早退;无宿主=预览/单测降级为零配对,与旧通道无泵降级一致)。
 *
 * #442 批次 C 深拆（终审 S2）：单 lambda 八职责 → 独立相位函数，每职责接线级
 * 表征测试钉行为（FlushTaskPhasesTest / FlushTaskMemoryTest）。行为与日志签名集
 * 逐字节不变（#484/#492 判读依赖）。
 *
 * 顺序: 帽相(reservePhase) → 观测(vtraceTick) → 静止写点 → 空账早退 →
 * 用户滚动弃配(位置神圣) → 让位判定 → 统一规则求值 → 单出口执行+拒绘
 * (applyPairedShift)。免派发分支(贴底跟随族/读历史)从构造上零派发
 * ——震荡根源(无贴底豁免的 dispatch)在此消失。
 */
internal fun streamingGrowFlushTask(
    listState: LazyListState,
    ledger: StreamingGrowLedger,
    reserve: HeightReserveState? = null,
    /** #438 R-1：数据侧 key 投影（index → chatEntries key）——越窗落点的键锚兜底。 */
    dataKeyAt: ((Int) -> Any?)? = null,
): PreDrawFlushTask {
    val mem = FlushTaskMemory()
    return PreDrawFlushTask {
        // pendingReserveRelease 是单帧内「帽相 → 单出口」的传递载体：每帧新计划，
        // 跨帧保留反而是 bug（与 mem 的跨帧记忆分工——#438 R-2 注释详见
        // FlushTaskMemory 头注）。
        var pendingReserveRelease = reservePhase(listState, reserve)
        vtraceTick(listState)
        // R3 单信号源写点（唯一）：滚动/惯性期置「活跃」，settle 置「静止」——
        // 消费者（pilot append 暂缓/快照冻结/ledger rebaseAll/帽持帽）全部只读。
        dev.leonardo.ocbeacon.ui.screens.chat.markdown.ScrollQuiescence.onScrollStateChanged(listState.isScrollInProgress)
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
        // 新bug根修：外部 pending 未消费（读位≠上批目标）→ 让位（配对覆盖写会抵消显式意图）
        // #540 二轮（宽豁免裁决 2026-10-11）：窗内豁免——塌缩自带的被动锚漂移
        // 曾被误判为外部意图，收起配对 76 帧饿死（journal §22 D2）；toggle 即意图。
        mem.frameSeq++
        if (shouldYieldPairingWindowed(fii, fiso, mem.lastSetFii, mem.lastSetFiso, windowOpen = (reserve?.expandPairingWindows ?: 0) > 0)) {
            if (shouldAdoptExternalPosition(fii, fiso, mem.prevFii, mem.prevFiso, mem.frameSeq - mem.lastSetFrame)) {
                // #502：静止且 lastSet 陈旧 = 已定居的外部位置（非待消费 pending）——
                // 采纳为配对基线，落回正常求值（帽释放计划当帧生效，防永久让位）
                mem.lastSetFii = fii
                mem.lastSetFiso = fiso
                mem.lastSetFrame = mem.frameSeq
                if (BuildConfig.DEBUG) {
                    AppLogger.d("SGR-435", "adopt(external-settled) t=" + android.os.SystemClock.elapsedRealtime() +
                        " fii=" + fii + ",fiso=" + fiso + " — 外部滚动已定居，重立配对基线（#502 防永久让位）")
                }
            } else {
                ledger.rebaseAll()
                pendingReserveRelease = null
                if (BuildConfig.DEBUG) {
                    AppLogger.d("SGR-435", "yield(external-pending) t=" + android.os.SystemClock.elapsedRealtime() +
                        " read(fii=" + fii + ",fiso=" + fiso + ") last(fii=" + mem.lastSetFii + ",fiso=" + mem.lastSetFiso + ")")
                }
                mem.prevFii = fii
                mem.prevFiso = fiso
                return@PreDrawFlushTask true
            }
        }
        mem.prevFii = fii
        mem.prevFiso = fiso
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
        return@PreDrawFlushTask applyPairedShift(listState, reserve, dataKeyAt, mem, ledgerTotal, pendingPlan, total)
    }
}

/**
 * 职责簇①②（帽相，#442 批次 C 拆出）：对齐随态翻转 + 释放计划计算 + 首帧初始化。
 *
 * 返回本帧待释放计划（null=无增量/手势持帽/未初始化）；首帧（reserved<0 且已有
 * 真高）原子直通 trueHeight。计划由调用方（单出口 applyPairedShift）延迟合并
 * ——不在此处施加。
 */
private fun reservePhase(listState: LazyListState, reserve: HeightReserveState?): ReserveReleasePlan? {
    if (reserve == null) return null
    // [#437 引擎①] 一帧缓冲帽释放：measure 相已得真高（增量当帧被帽裁掉不可见），
    // 此处单事务原子施加。reject-draw 对 item 层重绘无效（VDRAW 实证），故不依赖。
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
        // #540：物主 item 上的展开/收起集窗内恒配对（覆盖贴底原点/读历史格）。
        // 五报 F4（2026-10-12 ep2 定罪）：窗内配对资格排除「**全部驱动悬垂**」的窗
        //——空内容卡 toggle 开窗但驱动永不动画（tgt 恒 -1），窗把普通贴底流式
        // 增长误升格「离底让位」→ 引擎上拉与零高横幅带回贴底逐帧互搏（fiso
        // 391↔0 拉锯，真机 log 实证）。全悬垂窗回落 StreamingAnchorRule（贴底
        // 原点免派发）= 拉锯消失；无驱动窗（纯帽/legacy）与正常窗（任一驱动
        // 已知终态）维持恒配对原语义。
        expandPaired = reserve.expandPairingWindows > 0 &&
            !(reserve.degradeDrives.isNotEmpty() && reserve.degradeDrives.none { it.hTarget >= 0 }),
        // #549 Fix B：正向窗内配对须有**动画中的驱动**——settle 后窗尾巴里帽把
        // 流式增长继续升格离底让位，内容短于视口时被钳回 (0,0) 的写入逐帧重发
        //（emu-r11/r427 定罪 settle 后 ~900ms 打架）。无驱动（legacy/纯帽）与
        // 全悬垂（F4''）分别维持原语义/回落；负向例外不受影响（收起集帽回落
        // 仍走 expandPaired 窗语义，防结算卡死）。
        expandPairedPos = if (reserve.degradeDrives.isEmpty()) null
        else reserve.degradeDrives.any { it.animating },
        // #540 二轮修订：锚在揭示缘上方（阅读位高于被展开卡）——正增长在锚
        // 下方，配对=自造位移，否决。未捕获几何（span=-1）不否决（legacy 盲配）。
        // 锚位用帧前高度（reserved）与负向判定同源无偏（§27）。
        anchorAboveReveal = reserve.expandPairingWindows > 0 &&
            reserve.expandCollapseStickTop >= 0 && reserve.expandCollapseSpanDepth >= 0 &&
            (reserve.reserved - listState.firstVisibleItemScrollOffset) < reserve.expandCollapseStickTop,
    )
    // [R1-A2] 单出口：帽 plan 延后与 ledger 配对合并为同帧单次滚动 set——
    // 原先帽 set 先落、ledger set 覆盖（requestPosition 覆盖写非叠加），同帧
    // 双补偿只活一笔；合并后两笔叠加一次原子生效。
    // #540 三轮：降级驱动步进并入同一计划（步进与滚动配对同源同帧）。手势期
    // 在下方 626 早退丢弃（幂等重算），驱动冻结而非错位。四报：多驱动 Σ
    // （逐卡 pendingStep）。
    // 五报 F3：驱动的**正向配对资格逐卡化**（[drivePositivePairedShift]，消费点
    // applyPairedShift 两分支按 anchorTop 现算）——原单布尔 drivePaired 读
    // reserve 级单槽镜像（最后 toggle 几何），并发/连 toggle 时先卡被误判；
    // plan.scrollPaired 回归**纯帽通道**语义（帽增量=流式内容增长，镜像=最新
    // toggle 意图，对帽通道是正确归属）。
    var dStepSum = 0
    for (d in reserve.degradeDrives) {
        val s = degradeDriveStep(d.h, d.hTarget)
        d.pendingStep = s
        dStepSum += s
    }
    if (dStepSum != 0) {
        return ReserveReleasePlan(
            delta = (plan?.delta ?: 0) + dStepSum,
            scrollPaired = plan?.scrollPaired ?: false,
            driveStep = dStepSum,
        )
    }
    if (plan != null) {
        return plan
    }
    if (reserve.reserved < 0 && reserve.trueHeight >= 0) {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            reserve.reserved = reserve.trueHeight
        }
    }
    return null
}

/**
 * 职责③（观测，#442 批次 C 拆出）：[VTRACE] 逐帧视口轨迹（仅变化时打点）——
 * 任何来回跳动在时间线上直接可读（pair/drop/MSGEFFECT/GUARD/BANNER 行给出
 * 成因；用户裁决：精细分析用日志而非录屏抽帧，瞬态闪烁录屏易漏采）。
 */
private fun vtraceTick(listState: LazyListState) {
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

/**
 * 职责⑦⑧（单出口执行器+拒绘，#442 批次 C 拆出）：帽释放与 ledger 配对同帧
 * 单事务施加。返回值 = PreDrawFlushTask 拒绘契约。
 */
private fun applyPairedShift(
    listState: LazyListState,
    reserve: HeightReserveState?,
    dataKeyAt: ((Int) -> Any?)?,
    mem: FlushTaskMemory,
    ledgerTotal: Float,
    pendingPlan: ReserveReleasePlan?,
    total: Float,
): Boolean {
    if (total != 0f || pendingPlan != null) {
        // 五报 F2/F3：驱动滚动分量逐卡现算（两分支共用）——负步进走塌缩
        // containment（driveScrollShift），正步进走揭示缘逐卡否决
        // （drivePositivePairedShift）；净符号不再决定分量的规则归属。
        val anchorTop0 = reserve?.reserved?.minus(listState.firstVisibleItemScrollOffset) ?: -1
        val driveNegScrollV = if (reserve != null) driveScrollShift(anchorTop0, reserve.degradeDrives) else 0
        val drivePosScrollV = if (reserve != null) drivePositivePairedShift(anchorTop0, reserve.degradeDrives) else 0
        val dStepTotalV = pendingPlan?.driveStep ?: 0
        if (total < 0f || dStepTotalV < 0) {
            // #540 三轮（表述乙·揭示缘参照，裁决 2026-10-11）：负向收缩弃
            // dispatchRawDelta 镜像——底缘钳制缺口（col4 capd=-3655 consumed=-277）
            // 与「锚在塌缩区外时位移本不需发，盲镜像自造跳变」双重定罪（journal
            // §22 D3 及其修正读法）。改走与正向对称的反射位置写：资格判定见
            // [driveScrollShift]——锚在塌缩区内/上方的卡其步进镜像骑行；区外的卡
            // 零操作（LazyList 默认锚定保持本就正确）；未捕获 → 保守镜像钳底。
            //
            // review 轮两加固（2026-10-11）：
            // ①混合帧拆账（P1）：total = 塌缩分量 + ledger 增长——决策只用塌缩
            //   分量，ledger 增长折加到所选目标。noop 格 ledger 非零时仍写
            //   fiso+ledger。四报：塌缩分量=逐卡 containment Σ（driveScrollShift）
            //   + 非驱动负余量保守镜像。
            // ②物主守卫（P2）：fiso 属别的 item 时 anchorTop 是跨 item 垃圾值，
            //   fii≠物主（或物主不可见）一律按区外处理。
            val reserveShift = pendingPlan?.delta?.toFloat() ?: 0f
            val dStepV = pendingPlan?.driveStep ?: 0
            val ownerIdx = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == reserve?.itemKey }?.index
            val anchorIsOwner = reserve != null && ownerIdx != null &&
                ownerIdx == listState.firstVisibleItemIndex
            // 逐卡滚动分量：锚位用帧前高度（reserved）无偏（§27）
            val anchorTopV = anchorTop0
            val driveScrollV = driveNegScrollV
            // 非驱动负余量（罕见：非 toggle 的 item 收缩）沿用 reserve 级几何判定
            //（锚在最新塌缩区下方=零操作；未捕获=保守镜像）——盲镜像会在锚下方
            // 格自造跳变（review 混合帧拆账测试锁定语义）。
            val nonDriveNegV = minOf(0, reserveShift.toInt() - dStepV)
            val rStickV = reserve?.expandCollapseStickTop ?: -1
            val rSpanV = reserve?.expandCollapseSpanDepth ?: -1
            val nonDriveMirror = nonDriveNegV != 0 &&
                (rStickV < 0 || rSpanV < 0 || anchorTopV <= rStickV + rSpanV)
            // 五报 F2：正步进分量（混合帧中与塌缩共存）逐卡折加——线性叠加，
            // 各分量按各自规则判定后求和即净位移
            val collapseShiftV = driveScrollV + drivePosScrollV + (if (nonDriveMirror) nonDriveNegV else 0)
            val stickFiso: Int? = when {
                reserveShift > -1f && collapseShiftV == 0 -> {
                    // 亚像素负余量（|reserveShift|<1）且无驱动塌缩量：只走 ledger 折加
                    //（五报 F2：纯驱动收起帧 reserveShift=0，塌缩分量在此判——
                    // 原次序会把它误吞成零操作）
                    null
                }
                reserve == null -> {
                    // 无 reserve（宿主未挂）：保守净镜像钳底（total 已含 ledger，勿再折）
                    (listState.firstVisibleItemScrollOffset + total.toInt()).coerceAtLeast(0)
                }
                !anchorIsOwner -> {
                    // 锚不在物主 item：按区外零操作（塌缩在锚下方，默认锚定本就正确）
                    null
                }
                collapseShiftV == 0 -> {
                    // 全部驱动在锚下方（新内容侧）：零操作
                    null
                }
                else -> {
                    (listState.firstVisibleItemScrollOffset + collapseShiftV)
                        .coerceAtLeast(0) + ledgerTotal.toInt()
                }
            }
            var writtenReserve = false
            var wroteScroll = false
            var targetFii = listState.firstVisibleItemIndex
            var targetFiso = listState.firstVisibleItemScrollOffset
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                // #540 三轮：驱动步进与滚动写同快照（同遍 measure 原子消费）
                if (dStepV != 0) reserve?.degradeDrives?.forEach { if (it.pendingStep != 0) it.h += it.pendingStep }
                if (pendingPlan != null && reserve != null) {
                    // 三轮：驱动帧帽预记步进——下一 measure 盒高落地后恰好追平；
                    // 旧 `= trueHeight` 会让残差帧再释放一步=重复记账
                    reserve.reserved = reserve.trueHeight + dStepV
                    writtenReserve = true
                }
                // noop 格的 ledger 折加：塌缩零操作，但同帧增长配对义务仍履行
                val effectiveTarget = stickFiso
                    ?: (if (ledgerTotal != 0f && anchorIsOwner)
                        (listState.firstVisibleItemScrollOffset + ledgerTotal.toInt()).coerceAtLeast(0)
                    else null)
                if (effectiveTarget != null) {
                    targetFiso = effectiveTarget
                    wroteScroll = true
                    LazyListReflection.requestScrollToItemNoCancel(listState, targetFii, targetFiso)
                    mem.lastSetFii = targetFii
                    mem.lastSetFiso = targetFiso
                    mem.lastSetFrame = mem.frameSeq
                } else {
                    // 零操作：以当下读位回写 lastSet，yield 守卫保持一致
                    mem.lastSetFii = targetFii
                    mem.lastSetFiso = targetFiso
                    mem.lastSetFrame = mem.frameSeq
                }
            }
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "SGR-435",
                    "release-ref t=" + android.os.SystemClock.elapsedRealtime() +
                        " capd=" + (pendingPlan?.delta ?: 0) + " drv=" + dStepV + " led=" + ledgerTotal.toInt() +
                        (if (wroteScroll) " set(fii=" + targetFii + ",fiso=" + targetFiso + ")" else " noop(outside-span)") +
                        (if (!anchorIsOwner) " anchor-not-owner" else "") +
                        " h->" + (if (writtenReserve) reserve?.trueHeight.toString() else "-"),
                )
            }
            return true // 反射待定位下一遍 measure 原子消费
        }
        // #437 验收五轮（用户裁决，对齐 #427 引擎先例）：渲染前计算目标位+
        // 反射 requestPosition 写入待定区，由下一遍 measure 原子消费——与
        // dispatchRawDelta（渲染后滚动修正=先画增长态再跳位，整屏闪烁）的
        // 本质区别在「计算先行、measure 原子生效」。
        // 目标位=用户公式 scrollPos+Δ：fiso += total（锚 item 内偏移推大），
        // 溢出沿可见 items 向 index 增大换算（reverseLayout 视觉向上）。
        // #438 R-1：换用纯函数（越窗 dataKeyAt 兜底；行为=旧逻辑+投影扩展）
        //
        // 五报 F3（C3 三通道先行）：正向滚动目标=帽通道（plan 门控增量）+
        // 驱动通道（逐卡揭示缘否决）+ ledger，各自按各自规则判定后求和——
        // 原净和 total 把未配对资格的驱动步进一并折入（连 ledger 免派发格
        // 也被净和淹没），单帧错发=概率性上向跳变源。
        val hatDeltaP = (pendingPlan?.delta ?: 0) - dStepTotalV
        // #551 八报热修：预算只管**episode 内**的离底让位（窗内帽+驱动步进）——
        // 稳态阅读跟随（锚规则配对，窗外）绝不受预算截断：窗耗尽预算后若把
        // 阅读跟随也清零，流式增长会把阅读位逐帧往下拖（用户八报实证）。
        val windowOpenP = (reserve?.expandPairingWindows ?: 0) > 0
        val hatWindowP = if (windowOpenP && pendingPlan?.scrollPaired == true) hatDeltaP else 0
        val hatAnchorP = if (!windowOpenP && pendingPlan?.scrollPaired == true) hatDeltaP else 0
        val budgetLeftP = reserve?.expandDepartureBudgetLeft ?: Int.MAX_VALUE
        val allowedP = capDepartureByBudget(drivePosScrollV, hatWindowP, budgetLeftP)
        val scrollTotalP = allowedP + hatAnchorP
        val totalP = scrollTotalP.toFloat() + ledgerTotal
        val (targetFii, targetFiso, targetKey) = resolvePairedTarget(
            fii = listState.firstVisibleItemIndex,
            fiso = listState.firstVisibleItemScrollOffset,
            total = totalP.toInt(),
            visible = listState.layoutInfo.visibleItemsInfo,
            dataKeyAt = dataKeyAt,
        )
        var writtenReserve = false
        val dStepP = pendingPlan?.driveStep ?: 0
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            // 帽释放（若有）：与滚动待定位同一事务——高度扩展与位移下一遍 measure 同 pass 消费
            // #540 三轮：驱动步进同快照推进 + 帽预记（负向分支同款，见上）
            if (dStepP != 0) reserve?.degradeDrives?.forEach { if (it.pendingStep != 0) it.h += it.pendingStep }
            // #551：预算扣减与驱动推进/帽预记同事务（原子性一致）
            if (reserve != null && allowedP > 0) {
                reserve.expandDepartureBudgetLeft = budgetLeftP - allowedP
            }
            if (pendingPlan != null && reserve != null) {
                reserve.reserved = reserve.trueHeight + dStepP
                writtenReserve = true
            }
            if (totalP != 0f) {
                LazyListReflection.requestScrollToItemNoCancel(listState, targetFii, targetFiso, targetKey)
                mem.lastSetFii = targetFii
                mem.lastSetFiso = targetFiso
                // #502：set 写入帧戳记——divergence 陈旧度守卫的基准（pending 未消费
                // 窗口内读位=旧位静止，近帧 set 不采纳防误毁 pending 保护）
                mem.lastSetFrame = mem.frameSeq
            }
        }
        if (BuildConfig.DEBUG && (totalP != 0f || writtenReserve)) {
            // [#492 检测网 2026-10-01] total==0 时下方 guard 不派发滚动——旧日志恒打印
            // set(fii=7,fiso=0)（resolvePairedTarget 穿零高横幅落到的幻影目标）曾误导
            // 两轮定罪；nodis 标记后判读可直接区分「算了没派」与「真派发」。
            val setPart = if (totalP != 0f) {
                "set(fii=" + targetFii + ",fiso=" + targetFiso + ")"
            } else {
                "(nodis target-fii=" + targetFii + ")"
            }
            AppLogger.d(
                "SGR-435",
                "release t=" + android.os.SystemClock.elapsedRealtime() +
                    " capd=" + (pendingPlan?.delta ?: 0) + " dpos=" + drivePosScrollV +
                    " led=" + ledgerTotal.toInt() +
                    " " + setPart +
                    " h->" + (if (writtenReserve) reserve?.trueHeight.toString() else "-"),
            )
        }
        // [终审 P1 修复] false=拒绘（PreRenderCoordinator 契约）。ledger 派发帧必须拒绘
        // ——StreamingGrowNode 直报真高（无裁剪），「新高度+旧偏移」中间帧全靠拒绘挡
        // （I1′ 契约）；帽路径画增长前态（旧帽高布局）无需拒绘。原 total!=0f 方向写反。
        return ledgerTotal == 0f
    }
    return true
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
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    // [#492 检测网 2026-10-01] 反射通道派发审计：此处为引擎滚动的
                    // 唯一反射出口——凡真派发必留痕，与 SGR-435 release 行对照可辨
                    // 「日志声称派发 vs 实际派发」的歧义。
                    AppLogger.d(
                        "LRef",
                        "set idx=" + index + " off=" + scrollOffset +
                            " key=" + (key?.toString()?.take(20) ?: "null"),
                    )
                }
                return
            } catch (t: Throwable) {
                // IllegalAccessException / IllegalArgumentException / ClassCastException 等
                AppLogger.w("LazyListReflection", "invoke failed, fallback: ${t.message}")
            }
        }
        // 降级:官方 API。语义差异 = 通过 scroll{} 互斥锁取消 fling,可接受。
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            AppLogger.d("LRef", "set-fallback idx=" + index + " off=" + scrollOffset)
        }
        state.requestScrollToItem(index, scrollOffset)
    }
}
