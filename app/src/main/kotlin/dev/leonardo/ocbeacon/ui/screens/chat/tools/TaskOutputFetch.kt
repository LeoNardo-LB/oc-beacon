package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part

/**
 * #182（2026-08-21）：Task 工具卡片全量输出拉取的纯函数助手。
 *
 * 策略（grilling Q13 定案）：part 优先（父会话 REST 重拉按 part id 取服务器
 * 全量 output）→ part 截断/缺失时子会话 transcript 回退；两路取长者。
 * DB 仍存 500 字符预览（#79 体积目标不变），仅展开时按需拉取。
 */
object TaskOutputFetch {

    /** 渲染上限：巨型输出（如 subagent 全程日志）不整棵组合（滚动卡顿教训）。 */
    const val MAX_RENDER_CHARS = 20_000

    /** 单片 Markdown 字符数（分片渲染——单棵 MarkdownContent 组合预算 ~4K）。 */
    const val SLICE_CHARS = 4_000

    /** 按工具 part id 从消息页中找全量 output（找不到返回 null）。 */
    fun findToolOutputById(messages: List<MessageWithParts>, partId: String): String? {
        if (partId.isBlank()) return null
        for (mwp in messages) {
            for (p in mwp.parts) {
                if (p is Part.Tool && p.id == partId) {
                    return extractToolOutput(p)
                }
            }
        }
        return null
    }

    /**
     * 子会话 transcript 回退：text part 按消息序拼接（role 前缀区分）。
     * 空会话/无文本返回 null。截断到 [MAX_RENDER_CHARS]。
     */
    fun buildChildTranscript(messages: List<MessageWithParts>): String? {
        val sb = StringBuilder()
        for (mwp in messages) {
            val role = when (mwp.info) {
                is Message.User -> "user"
                is Message.Assistant -> "assistant"
                else -> null
            } ?: continue
            val text = mwp.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text.trim() }.trim()
            if (text.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("[").append(role).append("]\n").append(text)
            if (sb.length >= MAX_RENDER_CHARS) break
        }
        return sb.toString().take(MAX_RENDER_CHARS).ifBlank { null }
    }

    /** 两路结果合并：取更长者（part 与 transcript 均可用时选信息量大的）。 */
    fun pickLonger(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        else -> if (a.length >= b.length) a else b
    }
}
