package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.filterRenderableParts

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一个显示项（一个 assistant turn 气泡）的预计算渲染数据。
 *
 * 所有过滤、分组和元数据提取都在 [computeRenderableTurn] 中完成，
 * 因此 Composable 只遍历 [renderItems] —— 组合期间零计算。
 *
 * 标记为 @Immutable，使 Compose 可以跳过其 RenderableTurn 未变化的
 * 项的重组（例如分页期间，已有项的数据是相同的）。
 */
@Immutable
data class RenderableTurn(
    val renderItems: List<RenderItem>,
    val isEmpty: Boolean,
    val errorText: String?,
    val agentName: String?,
    val modelId: String?,
    val durationMs: Long?,
    val turnStartMs: Long?,
    /**
     * #343 完结信号（与时长解耦）：turn 内存在 assistant 消息且全部带
     * completed（computeRenderableTurn 语义）。durationMs 只回答「跨度
     * 可测与否」——DSH 整装事件 created==completed 同信封（零跨度）时
     * 轮已完结但时长未知（null → 台账"-"），被中断轮同款。此前台账/产出行
     * 门控用 durationMs==null 兼当完结判定，把零跨度完结轮整行吞掉。
     */
    val allStepsCompleted: Boolean = false,
    val stepFinishes: List<Part.StepFinish>,
    val taskAgentName: String?,
    val copyText: String?,
    /**
     * #310④ 轨迹台账：步骤数 = turn 内 assistant 消息数。
     * 后端无关真相源——V2 每个 step 产出一条 assistant 消息；DSH
     * assistant/message 事件同构（每 step 一帧）。不用 StepFinish parts
     * 计数（DSH step/end 不产 part，见 DshEventMapper LIFECYCLE_NOISE）。
     */
    val stepCount: Int = 0,
    /**
     * #310④ 轨迹台账：token 总量 = Σ 消息级 tokens（total ?: input+output）。
     * 消息级 tokens 双后端均写入（V2 session.step.ended/REST tokens 字段、
     * DSH usage 桶）；任一消息缺席 → null（严格语义，与 durationMs 的
     * 「全完结才给值」同哲学）。
     */
    val tokensTotal: Long? = null,
    /**
     * #311 Task4 deliverables：turn 内产出文件（成功写类工具调用 args 路径，
     * 首见序去重；TurnDeliverables fold 契约 ②）。从**原始 parts** 折（非
     * renderItems——#247 同键折叠会吞后续同键卡的 args）；空列表 = 该轮
     * 无产出（尾部行不挂载）。
     */
    val deliverableFiles: List<String> = emptyList(),
)

@Immutable
sealed class RenderItem {
    @Immutable
    data class TurnDivider(val msgId: String) : RenderItem()
    @Immutable
    data class GroupedParts(val group: PartGroup) : RenderItem()
    /** 合成通知卡片（后台轮次完成；2026-08-12 起独立成泡渲染，不再嵌入气泡）。 */
    @Immutable
    data class SyntheticNotice(val msgId: String, val message: ChatMessage) : RenderItem()

    /**
     * #247（2026-08-28 用户裁决）：回合内连续同键 tool 卡折叠渲染项——
     * 首张 + ×N 徽标（与 #243 合成卡去重同款交互）。[part] 为保留的首张。
     */
    @Immutable
    data class RepeatingTool(val part: Part.Tool, val count: Int) : RenderItem()
}

/**
 * 单次遍历计算一个显示项的全部渲染数据。
 * 在 `remember` 块中调用 —— 仅当消息变化时运行，而非组合期间。
 */
// ---------------------------------------------------------------------------
// #247 回合内连续同键 tool 卡去重（2026-08-28 用户裁决：首张 + ×N，同 #243 先例）
// ---------------------------------------------------------------------------

// #311 Task5：ask_user_question（DSH 恒名，契约 ③）与 skill 每次调用
// 各自成行（答案/指令全文不可被 ×N 折叠吞掉）——同 question 先例入集。
private val NON_DEDUP_TOOLS = setOf("todoread", "todowrite", "question", "ask_user_question", "skill")

private fun ToolState.displayTitle(): String? = when (this) {
    is ToolState.Running -> title
    is ToolState.Completed -> title
    else -> null
}

private fun ToolState.inputMap(): Map<String, kotlinx.serialization.json.JsonElement> = when (this) {
    is ToolState.Pending -> input
    is ToolState.Running -> input
    is ToolState.Completed -> input
    is ToolState.Error -> input
}

/**
 * #247 去重键：工具名 + 命令/标题——callId/id 等易变字段不参与，
 * 因此「同一命令跑 N 次」产生的 N 张卡同键。状态不入键（流式中渐进
 * 完成不反复拆叠）；context 工具（read/glob/grep 已有独立折叠面）与
 * 过滤/特殊渲染工具不参与。
 */
internal fun toolDedupKey(part: Part.Tool): String? {
    val name = part.tool.lowercase()
    if (name in CONTEXT_TOOLS || name in NON_DEDUP_TOOLS) return null
    val command = part.state.inputMap()["command"]?.jsonPrimitive?.contentOrNull
    return listOf(name, command ?: part.state.displayTitle() ?: "").joinToString("\u0001")
}

private fun RenderItem.singleTool(): Part.Tool? =
    (this as? RenderItem.GroupedParts)?.group?.let { g -> g as? PartGroup.Single }?.part as? Part.Tool

/**
 * 折叠回合内连续同键 tool 卡：首张保留为 [RenderItem.RepeatingTool]（×N），
 * 其余抑制；卡间 turn 分隔线随折叠一并消失（它们分隔的是被抑制的卡）。
 * 纯函数，JVM 可测。
 */
internal fun collapseConsecutiveToolCards(items: List<RenderItem>): List<RenderItem> {
    val out = mutableListOf<RenderItem>()
    var i = 0
    while (i < items.size) {
        val item = items[i]
        val tool = item.singleTool()
        val key = tool?.let(::toolDedupKey)
        if (tool == null || key == null) {
            out.add(item)
            i++
            continue
        }
        var j = i + 1
        var count = 1
        val pending = mutableListOf<RenderItem>()
        while (j < items.size) {
            val nxt = items[j]
            if (nxt is RenderItem.TurnDivider) {
                pending.add(nxt)
                j++
                continue
            }
            val nxtTool = nxt.singleTool()
            if (nxtTool != null && toolDedupKey(nxtTool) == key) {
                count++
                j++
                pending.clear()
            } else break
        }
        if (count > 1) {
            out.add(RenderItem.RepeatingTool(tool, count))
        } else {
            out.add(item)
            out.addAll(pending)
        }
        i = j
    }
    return out
}

fun computeRenderableTurn(
    turnMessages: List<ChatMessage>?,
    currentMessage: ChatMessage,
    isTurnLast: Boolean,
    formatError: (dev.leonardo.ocbeacon.domain.model.Message.Assistant.ErrorInfo?) -> String?,
): RenderableTurn {
    val ordered = turnMessages?.reversed() ?: listOf(currentMessage)

    // 单次遍历：过滤 + 分组 + 分隔线 + synthetic 卡片
    val renderItems = mutableListOf<RenderItem>()
    for ((msgIndex, msg) in ordered.withIndex()) {
        if (msg.isSynthetic) {
            // synthetic 通知：不渲染其 text parts（原文是 <task> 结构化标签），
            // 以卡片渲染项嵌入气泡内（2026-08-11 用户要求：不独立成行截断气泡）。
            renderItems.add(RenderItem.SyntheticNotice(msg.message.id, msg))
            if (msgIndex < ordered.lastIndex) {
                renderItems.add(RenderItem.TurnDivider(msg.message.id))
            }
            continue
        }
        val msgParts = filterRenderableParts(msg.parts)
        val groups = groupContextParts(msgParts)
        for (group in groups) {
            renderItems.add(RenderItem.GroupedParts(group))
        }
        if (msgIndex < ordered.lastIndex && msgParts.isNotEmpty()) {
            renderItems.add(RenderItem.TurnDivider(msg.message.id))
        }
    }

    // 错误文本 —— turn 中的第一个错误
    val errorText = ordered.firstNotNullOfOrNull { msg ->
        val am = msg.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant
        formatError(am?.error)
    }

    // 2026-08-15 修复（agent 徽标跳变，对齐官方 TUI 语义）：agent/model 取
    // **turn 内首条** assistant（用户消息开启 turn 的语义）——官方 TUI 每条
    // 消息 agent 创建时写入一次永不改写（tui data.tsx:208-222），turn 内
    // agent 变化只影响新消息；原实现取"代表消息"（turn 内最新 assistant），
    // 后台 subagent 完成注入主会话的 agent=deep-explore 消息（task.ts:216-239
    // synthetic 注入路径）会把整个 turn 徽标覆盖掉（用户反馈"agent 跳变"）。
    // 二次加固：V2SseMapper step.started 用本地 currentTimeMillis——同 turn
    // 多条消息可能同毫秒，minByOrNull 在相等时依赖列表顺序（reversed 后
    // 首项=最新，不稳定）。比较器：(created, id) 双键，稳定取最早。
    val currentAssistant = currentMessage.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant
    val assistantsForMeta = ordered.mapNotNull { it.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant }
    val firstAssistant = assistantsForMeta.minWithOrNull(
        compareBy<dev.leonardo.ocbeacon.domain.model.Message.Assistant> { it.time.created }.thenBy { it.id }
    )
    val agentName = (firstAssistant ?: currentAssistant)?.agent
    val modelId = (firstAssistant ?: currentAssistant)?.modelId

    // turn 起点 —— turn 内首条 assistant 消息的 created。
    // turn 分组只含 assistant 消息；minOf 比较时间戳不依赖列表顺序。
    val turnStartMs: Long? = assistantsForMeta.minOfOrNull { it.time.created }

    // 时长 —— turn 级跨度：首条 created → 末条 completed。
    // 完结信号与时长测量解耦（#343）：allStepsCompleted 回答「轮是否完结」
    // （全部 assistant 消息带 completed），durationMs 只回答「跨度可测与否」。
    val completedTimes = assistantsForMeta.mapNotNull { it.time.completed }
    val allStepsCompleted = assistantsForMeta.isNotEmpty() && completedTimes.size == assistantsForMeta.size
    val durationMs: Long? = if (turnStartMs != null && allStepsCompleted) {
        // #338：零/负跨度 = 时长未知（DSH 整装事件 created=completed 同信封、
        // 被中断轮同款）——null（台账回落 "-"，宁缺毋谎，与 tokensTotal 缺席
        // 即 null 同哲学），不以 0ms 冒充实测值。
        (completedTimes.max() - turnStartMs).takeIf { it > 0 }
    } else {
        null
    }

    // #310④ 轨迹台账预计算（后端无关）：步骤数 = assistant 消息数；
    // token 总量 = Σ 消息级 tokens（严格：任一缺席即 null——混合求和会
    // 低估整轮用量，宁可缺席不撒谎）。UI 侧只做纯投影（TurnLedger.kt）。
    val ledgerStepCount = assistantsForMeta.size
    val ledgerTokensTotal: Long? = assistantsForMeta.mapNotNull { it.tokens }
        .takeIf { it.size == assistantsForMeta.size }
        ?.sumOf { t -> (t.total ?: (t.input + t.output)).toLong() }

    // #311 Task4 deliverables 预计算：成功写类调用 args 路径（首见序去重）。
    // 输入 = 原始 parts（ordered 消息视觉序，含 DSH 工具卡宿主消息）。
    val deliverablePaths = turnProducedFiles(ordered)

    // 用于 token 统计的 StepFinish
    val stepFinishes = if (isTurnLast) {
        ordered.flatMap { msg -> msg.parts.filterIsInstance<Part.StepFinish>() }
    } else {
        emptyList()
    }

    // Task agent 名称（用于 task 工具 parts）
    val taskAgentName = ordered
        .flatMap { it.parts }
        .filterIsInstance<Part.Agent>()
        .firstOrNull()?.name?.takeIf { it.isNotBlank() }

    // 复制文本 —— 所有文本 parts 拼接（跳过 synthetic 的 <task> 结构化原文）
    val copyText = ordered
        .filterNot { it.isSynthetic }
        .flatMap { it.parts.filterIsInstance<Part.Text>() }
        .map { it.text }
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }

    return RenderableTurn(
        // #247：回合内连续同键 tool 卡折叠（首张 + ×N）
        renderItems = collapseConsecutiveToolCards(renderItems),
        isEmpty = renderItems.isEmpty() && errorText == null,
        errorText = errorText,
        agentName = agentName,
        modelId = modelId,
        durationMs = durationMs,
        turnStartMs = turnStartMs,
        allStepsCompleted = allStepsCompleted,
        stepFinishes = stepFinishes,
        taskAgentName = taskAgentName,
        copyText = copyText,
        stepCount = ledgerStepCount,
        tokensTotal = ledgerTokensTotal,
        deliverableFiles = deliverablePaths,
    )
}
