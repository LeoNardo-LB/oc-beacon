package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part

/**
 * #224（2026-08-25，用户指令「V1/V2 压缩形态做成一致」）：V1 压缩消息归一化。
 *
 * 服务器语义差异（实测，docs/real-device-testing.md「V1 测试服务器快速搭建」）：
 * - V2：compact 产物是独立 type=compaction 消息（role="compaction"），
 *   V2Mappers 生成 [Part.Compaction] → UI 渲染分割线形态；
 * - V1：compact 产物是**常规 assistant 消息**（agent="compaction"，
 *   摘要在 text part，另有 step-start/reasoning/step-finish），
 *   此前渲染为普通气泡——与 V2 分割线形态不一致。
 *
 * 本归一化把 V1 形态折叠为 V2 形态：assistant(agent=compaction) 且已完结
 *（completed 非空或 error 存在）且 text 摘要非空 → 整条消息的 parts 替换为
 * 单个 [Part.Compaction]（summary=全文拼接，failed=error 存在）。识别条件
 * 只可能由 V1 产生（V2 assistant 不携带 agent=compaction），对 V2 无操作。
 *
 * 完结守卫的原因：V1 压缩进行中该消息可能已带 agent（SSE 先发 info 后发
 * text delta），未完结即折叠会把流式半文固化为完成态分割线——与 V2 的
 * deltaText 流式展开语义冲突。未完结消息保持普通气泡，完结事件到达时
 * 在此归一化（REST 刷新或 message.updated 均覆盖）。
 */
object CompactionNormalizer {

    fun normalizeAll(messages: List<MessageWithParts>): List<MessageWithParts> =
        messages.map(::normalize)

    fun normalize(message: MessageWithParts): MessageWithParts {
        val info = message.info
        if (info !is Message.Assistant) return message
        if (info.agent != "compaction") return message
        val finished = info.time.completed != null || info.error != null
        if (!finished) return message
        val textParts = message.parts.filterIsInstance<Part.Text>()
        val summary = textParts.joinToString("\n\n") { it.text }.trim()
        if (summary.isEmpty()) return message
        val compactionPart = Part.Compaction(
            id = textParts.first().id.ifBlank { "${info.id}_compaction" },
            sessionId = info.sessionId,
            messageId = info.id,
            summary = summary,
            failed = info.error != null,
        )
        // 分割线形态独占该消息（与 V2 一致：step/reasoning 噪声不参与渲染）
        return message.copy(parts = listOf(compactionPart))
    }
}
