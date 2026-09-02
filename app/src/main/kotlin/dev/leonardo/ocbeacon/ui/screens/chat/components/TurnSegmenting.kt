package dev.leonardo.ocbeacon.ui.screens.chat.components

import com.mikepenz.markdown.model.State
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn

/**
 * 历史长 turn 的 renderItems 边界分段（#258 Stage B；spec
 * `docs/specs/2026-09-02-258-stage-b-history-turn-chunking-design.md`）。
 *
 * 与 [MdChunkPlan]（流式期「单巨型 text part 的 AST 区间」模型）互补：历史重 turn
 * 的组合成本按 **part 数量** 摊开（Stage A 实测 334ms turn ≈ 55 小 text + 25 tool +
 * 30 reason + 6 长 text），单 part 切片无法覆盖——本模型把 turn 的 renderItems 序列
 * 切成连续段（LazyItem 粒度），巨型 part 独立成段后仍走 AST 区间细分（复用
 * [computeChunkPlan]）。
 *
 * 分段对象 = [RenderableTurn.renderItems]——与渲染层同一序列（[dev.leonardo.ocbeacon.
 * ui.screens.chat.tools.computeRenderableTurn] 纯函数产物），切割天然落在 item 边界，
 * 不拆 ContextToolGroup / #247 RepeatingTool 折叠。
 */
data class TurnSegmentPlan(
    /** "t_<turn 首消息 id>"——与 ChatEntry key 同源。 */
    val turnKey: String,
    /** 指纹校验锚：turn 代表消息 id（计划 vs 现时 turn 的陈旧检测）。 */
    val representativeMsgId: String,
    /** [dev.leonardo.ocbeacon.util.MessageFingerprints.partsFingerprint] 复合指纹。 */
    val fingerprint: Int,
    /** 文档序段列表。 */
    val segments: List<Segment>,
) {
    val chunkCount: Int get() = segments.sumOf { it.chunkCount }

    sealed interface Segment {
        val chunkCount: Int

        /** 连续 renderItems 段（中小 part 聚合；1 chunk）。 */
        data class Items(val from: Int, val to: Int) : Segment {
            override val chunkCount: Int get() = 1
        }

        /** 巨型 part 的 AST 区间段（1..N chunks；产物来自 [computeChunkPlan]）。 */
        data class Giant(
            val partId: String,
            val ranges: List<IntRange>,
            val state: State.Success,
            val anchors: List<String>,
        ) : Segment {
            override val chunkCount: Int get() = ranges.size
        }
    }
}

/**
 * 段切割骨架（同步纯函数产物，无 AST 依赖）——巨型 part 以 [Cut.GiantHole] 占位，
 * 由渲染供给协调器解析完成后装配成 [TurnSegmentPlan.Segment.Giant]。
 */
data class TurnSegmentSkeleton(
    val turnKey: String,
    val representativeMsgId: String,
    val fingerprint: Int,
    val cuts: List<Cut>,
) {
    sealed interface Cut

    data class Items(val from: Int, val to: Int) : Cut

    /** 巨型 part 占位：[itemIndex] = 其在 renderItems 中的位置；[text] 供预解析。 */
    data class GiantHole(val itemIndex: Int, val partId: String, val text: String) : Cut
}

/**
 * 骨架 → 计划装配：[giantPlans] 为已解析巨型 part 的 MdChunkPlan（partId 对位）；
 * 缺席的 GiantHole（解析失败/全空白块切不出）降级为单 item 段（part 走
 * PartContent 异步解析路径，不阻塞分段）。
 */
fun TurnSegmentSkeleton.buildPlan(giantPlans: List<MdChunkPlan>): TurnSegmentPlan {
    val byPart = giantPlans.associateBy { it.partId }
    val segments = cuts.map { cut ->
        when (cut) {
            is TurnSegmentSkeleton.Items -> TurnSegmentPlan.Segment.Items(cut.from, cut.to)
            is TurnSegmentSkeleton.GiantHole -> byPart[cut.partId]?.let {
                TurnSegmentPlan.Segment.Giant(it.partId, it.ranges, it.state, it.rangeAnchors)
            } ?: TurnSegmentPlan.Segment.Items(cut.itemIndex, cut.itemIndex + 1)
        }
    }
    return TurnSegmentPlan(turnKey, representativeMsgId, fingerprint, segments)
}

/**
 * turn 级计划指纹（陈旧检测）：代表消息指纹 + turn 规模（消息数/总 part 数）——
 * buildChatEntries 消费时以同函数复算比对，不等即弃（REST 刷新/分页替换后重算）。
 */
fun turnPlanFingerprint(representative: ChatMessage, turnMsgs: List<ChatMessage>): Int {
    var h = dev.leonardo.ocbeacon.util.MessageFingerprints.messageFingerprint(representative)
    h = h * 31 + turnMsgs.size
    h = h * 31 + turnMsgs.sumOf { it.parts.size }
    return h
}

/**
 * 巨型 part 判定（与 [RenderSupplyCoordinator] 预解析/分片资格同语义：
 * 正文 markdown 且非 synthetic/ignored/提问答案嵌入）。
 */
internal fun giantTextPartOf(item: RenderItem): Part.Text? {
    val part = (item as? RenderItem.GroupedParts)?.group?.let { it as? PartGroup.Single }?.part
    return (part as? Part.Text)
        ?.takeIf {
            it.text.length >= RenderSupplyCoordinator.CHUNK_MIN_CHARS &&
                it.synthetic != true && it.ignored != true &&
                !it.text.contains("User has answered")
        }
}

/**
 * 单 render item 权重（字符当量；标定依据 Stage A per-part 实测，见 spec §2.1）：
 * 折叠 reason 卡 ~2.5ms、tool 卡 ~1.6ms、长文本 ~3ms/KB、包装 ~0.2ms。
 */
internal fun turnItemWeight(item: RenderItem): Int = when (item) {
    is RenderItem.TurnDivider -> 50
    is RenderItem.SyntheticNotice -> 900
    is RenderItem.RepeatingTool -> 550
    is RenderItem.GroupedParts -> when (val g = item.group) {
        is PartGroup.Context -> 700
        is PartGroup.Single -> when (val p = g.part) {
            is Part.Text -> 200 + p.text.length
            is Part.Reasoning -> 700
            else -> 550
        }
    }
}

/**
 * 计算 turn 分段骨架。返回 null = 权重不足 / 切不出 ≥2 段（无需分片）。
 * 纯函数，JVM 可测；切割规则见 spec §2.1（target 权重落刀 + 段内 item 数上限 +
 * 巨型 part 独立成段 + 尾段兜底合并）。
 */
fun computeTurnSegments(
    turnKey: String,
    representativeMsgId: String,
    fingerprint: Int,
    turn: RenderableTurn,
): TurnSegmentSkeleton? {
    val items = turn.renderItems
    if (items.isEmpty()) return null
    var total = 0
    var hasGiant = false
    for (item in items) {
        total += turnItemWeight(item)
        if (giantTextPartOf(item) != null) hasGiant = true
    }
    // #300①：权重门槛仅约束「无巨型 part 的 part 数量主导 turn」；存在巨型 part
    // 即豁免（巨型自身 ≥3200 当量足以切出 ≥2 段）——否则中间带 turn（有巨型但
    // 总权重 <12000）落入旧 MdChunkPlan 粗片路径（单片可达数十 ms，真机实证）。
    if (total < TURN_SEGMENT_MIN_WEIGHT && !hasGiant) return null

    val cuts = mutableListOf<TurnSegmentSkeleton.Cut>()
    var start = 0
    var acc = 0
    var count = 0
    for (i in items.indices) {
        val giant = giantTextPartOf(items[i])
        if (giant != null) {
            if (i > start) cuts += TurnSegmentSkeleton.Items(start, i)
            cuts += TurnSegmentSkeleton.GiantHole(i, giant.id, giant.text)
            start = i + 1
            acc = 0
            count = 0
            continue
        }
        acc += turnItemWeight(items[i])
        count++
        if (i < items.lastIndex && (acc >= TURN_SEGMENT_TARGET_WEIGHT || count >= TURN_SEGMENT_MAX_ITEMS)) {
            cuts += TurnSegmentSkeleton.Items(start, i + 1)
            start = i + 1
            acc = 0
            count = 0
        }
    }
    if (start <= items.lastIndex) cuts += TurnSegmentSkeleton.Items(start, items.size)

    // 尾段兜底合并：末段单个 item 且与前段同为 Items、合并后不超上限 → 并入前段
    //（避免 1-item 碎尾段多出一个 LazyItem 与一次段 chrome）。
    if (cuts.size >= 2) {
        val last = cuts.last()
        val prev = cuts[cuts.size - 2]
        if (last is TurnSegmentSkeleton.Items && prev is TurnSegmentSkeleton.Items &&
            (last.to - last.from) == 1 && (last.to - prev.from) <= TURN_SEGMENT_MAX_ITEMS
        ) {
            cuts[cuts.size - 2] = TurnSegmentSkeleton.Items(prev.from, last.to)
            cuts.removeAt(cuts.size - 1)
        }
    }
    // 单 GiantHole 也是合法分段（AST 展开后 ≥2 chunk）；纯 Items 单段 = 无需分片。
    if (cuts.size <= 1 && cuts.none { it is TurnSegmentSkeleton.GiantHole }) return null
    return TurnSegmentSkeleton(turnKey, representativeMsgId, fingerprint, cuts)
}

/** 分片门槛/预算（标定见 spec §2.1；对齐既有 CHUNK_* 语义为 companion 常量便于单测引用）。 */
const val TURN_SEGMENT_MIN_WEIGHT = 12000
const val TURN_SEGMENT_TARGET_WEIGHT = 3000
const val TURN_SEGMENT_MAX_ITEMS = 10
