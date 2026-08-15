package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.filterRenderableParts

import androidx.compose.runtime.Immutable

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
    val stepFinishes: List<Part.StepFinish>,
    val taskAgentName: String?,
    val copyText: String?,
)

@Immutable
sealed class RenderItem {
    @Immutable
    data class TurnDivider(val msgId: String) : RenderItem()
    @Immutable
    data class GroupedParts(val group: PartGroup) : RenderItem()
    /** synthetic 系统通知卡片（后台任务完成，2026-08-11 嵌入气泡内渲染）。 */
    @Immutable
    data class SyntheticNotice(val msgId: String, val message: ChatMessage) : RenderItem()
}

/**
 * 单次遍历计算一个显示项的全部渲染数据。
 * 在 `remember` 块中调用 —— 仅当消息变化时运行，而非组合期间。
 */
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
    // agent 变化只影响新消息；原实现取"代表消息"（turn 内最新 assistant，
    // ChatScreen displayItems 选择），后台 subagent 注入的 agent=deep-explore
    // 消息会把整个 turn 徽标覆盖掉（用户反馈"agent 跳变"）。
    // 首选 turn 内首条（按 created 最早）；currentMessage 兜底（空 turn）。
    val currentAssistant = currentMessage.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant
    val assistantsForMeta = ordered.mapNotNull { it.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant }
    val firstAssistant = assistantsForMeta.minByOrNull { it.time.created }
    val agentName = (firstAssistant ?: currentAssistant)?.agent
    val modelId = (firstAssistant ?: currentAssistant)?.modelId

    // turn 起点 —— turn 内首条 assistant 消息的 created。
    // turn 分组只含 assistant 消息；minOf 比较时间戳不依赖列表顺序。
    val turnStartMs: Long? = assistantsForMeta.minOfOrNull { it.time.created }

    // 时长 —— turn 级跨度：首条 created → 末条 completed。
    // 仅当 turn 内所有 assistant 消息均 completed 时给值；任一仍流式 → null（流式 ticker 接管）。
    val completedTimes = assistantsForMeta.mapNotNull { it.time.completed }
    val durationMs: Long? = if (turnStartMs != null && completedTimes.size == assistantsForMeta.size) {
        completedTimes.max() - turnStartMs
    } else {
        null
    }

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
        renderItems = renderItems,
        isEmpty = renderItems.isEmpty() && errorText == null,
        errorText = errorText,
        agentName = agentName,
        modelId = modelId,
        durationMs = durationMs,
        turnStartMs = turnStartMs,
        stepFinishes = stepFinishes,
        taskAgentName = taskAgentName,
        copyText = copyText,
    )
}
