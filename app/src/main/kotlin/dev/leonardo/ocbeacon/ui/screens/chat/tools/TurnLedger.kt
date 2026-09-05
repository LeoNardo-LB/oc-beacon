package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.runtime.Immutable
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * #310④ 轨迹台账——轮次台账行的纯投影（自有形态，#313 原则：转录数据 →
 * 节点投影，无专用 RPC）。
 *
 * 输入 = [RenderableTurn]（预计算渲染数据，双后端同构）；输出 = 一行紧凑
 * 台账所需的全部字段。纯函数，JVM 可测（TurnLedgerTest）。
 */
@Immutable
data class TurnLedgerSummary(
    /** 轮次号——当前已加载窗口内按视觉顺序编号（最旧 = 1）。分页窗口变化时
     *  号码随之变化；会话内绝对轮次号无数据源（如实降级，见 #310④ 裁决）。 */
    val turnNumber: Int,
    /** 轮次时长（RenderableTurn.durationMs——turn 内全部消息完结才有值）。 */
    val durationMs: Long?,
    /** 步骤数（RenderableTurn.stepCount = assistant 消息数，双后端）。 */
    val stepCount: Int,
    /** 工具调用总次数（含折叠/分组的全部调用）。 */
    val toolCallCount: Int,
    /** 工具名去重列表（首次出现序）。 */
    val toolNames: List<String>,
    /** token 总量（消息级聚合；数据缺席为 null——「token 若在」）。 */
    val tokensTotal: Long?,
)

/**
 * [RenderableTurn] → [TurnLedgerSummary] 纯投影。
 *
 * 工具计数遍历 renderItems（分组后序列）：
 * - [PartGroup.Single] 工具 part → 1 次；
 * - [PartGroup.Context]（read/glob/grep 批量组）→ 组内全部；
 * - [RenderItem.RepeatingTool]（#247 折叠）→ ×N 全量——台账计的是真实
 *   调用数，折叠只影响渲染密度不影响统计。
 */
fun turnLedgerSummary(turn: RenderableTurn, turnNumber: Int): TurnLedgerSummary {
    var toolCallCount = 0
    val toolNames = LinkedHashSet<String>()
    for (item in turn.renderItems) {
        when (item) {
            is RenderItem.GroupedParts -> when (val group = item.group) {
                is PartGroup.Context -> {
                    toolCallCount += group.parts.size
                    group.parts.forEach { toolNames.add(it.tool) }
                }
                is PartGroup.Single -> if (group.part is Part.Tool) {
                    toolCallCount += 1
                    toolNames.add((group.part as Part.Tool).tool)
                }
            }
            is RenderItem.RepeatingTool -> {
                toolCallCount += item.count
                toolNames.add(item.part.tool)
            }
            else -> Unit
        }
    }
    return TurnLedgerSummary(
        turnNumber = turnNumber,
        durationMs = turn.durationMs,
        stepCount = turn.stepCount,
        toolCallCount = toolCallCount,
        toolNames = toolNames.toList(),
        tokensTotal = turn.tokensTotal,
    )
}

/**
 * 轮次序号表：assistant 锚点消息 id → 窗口内序号（最旧 = 1）。
 *
 * displayItems 为新的在前（降序）；倒序遍历按视觉顺序（旧 → 新）编号。
 * 窗口相对语义（分页加载更旧消息后号码整体平移）——绝对轮次号无数据源。
 */
internal fun turnOrdinalByAnchorId(displayItems: List<Pair<Int, ChatMessage>>): Map<String, Int> {
    val ordinals = HashMap<String, Int>(displayItems.size)
    var ordinal = 0
    for (i in displayItems.indices.reversed()) {
        val msg = displayItems[i].second
        if (msg.isAssistant) {
            ordinal += 1
            ordinals[msg.message.id] = ordinal
        }
    }
    return ordinals
}
