package dev.leonardo.ocbeacon.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * 消息列表指纹/签名工具 —— 用于缓存结构不变的重计算。
 * 纯函数：只依赖入参，不持有任何状态。
 */
object MessageFingerprints {

    /**
     * 消息列表结构签名（id 序列）—— 用于缓存结构不变的重计算。
     * 只含 id 序列与顺序；内容（parts）变化不改变签名。
     */
    fun messagesSignature(messages: List<ChatMessage>): Int {
        var h = messages.size * 31
        for (m in messages) h = h * 31 + m.message.id.hashCode()
        return h
    }

    /**
     * 轻量内容指纹：只覆盖会随 SSE 流式 / 工具输出注入 / 完成替换变异的字段，
     * 避免对整个消息做深 hashCode（大文本逐字符开销）。
     * 覆盖：Text/Reasoning 文本尾部、Tool output 尾部、消息完成时间与错误。
     * 2026-08-15：追加覆盖 modelId/providerId/agent——原注释假设"生命周期内
     * 不变"在 V2 下为假（step.ended 事件不含模型信息会触发字段变异、REST 兜底
     * 也会补值）；不纳入会导致 RenderableTurn 缓存复用陈旧值（统计栏丢模型不恢复）。
     */
    fun messageFingerprint(msg: ChatMessage): Int {
        val m = msg.message
        var h = partsFingerprint(msg.parts)
        h = h * 31 + (m.time.completed ?: 0L).hashCode()
        if (m is Message.Assistant) {
            h = h * 31 + (m.modelId ?: "").hashCode()
            h = h * 31 + (m.providerId ?: "").hashCode()
            h = h * 31 + (m.agent ?: "").hashCode()
            if (m.error != null) {
                h = h * 31 + m.error.name.hashCode() * 31 + (m.error.data?.toString()?.hashCode() ?: 0)
            }
        }
        return h
    }

    fun partsFingerprint(parts: List<Part>): Int {
        var h = parts.size * 31
        for (p in parts) {
            h = h * 31 + when (p) {
                is Part.Text -> p.text.length * 31 + tailHash(p.text)
                is Part.Reasoning -> p.text.length * 31 + tailHash(p.text)
                is Part.Tool -> toolFingerprint(p)
                else -> p.id.hashCode() * 31 + p.javaClass.name.hashCode()
            }
        }
        return h
    }

    fun toolFingerprint(p: Part.Tool): Int {
        var h = p.callId.hashCode() * 31 + p.tool.hashCode() + p.state.javaClass.name.hashCode()
        when (val s = p.state) {
            is ToolState.Running -> h = h * 31 + tailHash(s.output)
            is ToolState.Completed -> h = h * 31 + tailHash(s.output)
            is ToolState.Error -> h = h * 31 + tailHash(s.error)
            else -> {}
        }
        return h
    }

    fun tailHash(s: String): Int {
        val len = s.length
        if (len <= 64) return s.hashCode()
        return s.substring(len - 64).hashCode() * 31 + len
    }
}
