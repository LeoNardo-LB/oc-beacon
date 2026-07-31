package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlin.math.ceil

enum class BreakdownRole { USER, ASSISTANT, TOOL, OTHER }

data class ContextBreakdownSegment(
    val role: BreakdownRole,
    val estimatedTokens: Int,
    val percent: Float
)

data class ContextBreakdown(val segments: List<ContextBreakdownSegment>)

data class MessageCount(val user: Int, val assistant: Int)

data class ProviderModel(val providerId: String?, val modelId: String?)

data class SessionTimestamps(val created: Long, val updated: Long)

data class ContextDetailState(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val totalCost: Double = 0.0,
    val contextWindow: Int = 0,
    val contextTokens: Int = 0,
    val messageCount: MessageCount? = null,
    val providerModel: ProviderModel? = null,
    val timestamps: SessionTimestamps? = null,
    val cacheHitRate: Float? = null,
    val breakdown: ContextBreakdown? = null
)

private fun estimateTokens(chars: Int): Int = if (chars == 0) 0 else ceil(chars / 4.0).toInt()

/** user part 字符数 */
private fun charsFromUserPart(part: Part): Int = when (part) {
    is Part.Text -> part.text.length
    is Part.File -> part.source?.toString()?.length ?: 0
    is Part.Agent -> part.source?.toString()?.length ?: 0
    else -> 0
}

/** assistant part 字符数 -> (assistantChars, toolChars) */
private fun charsFromAssistantPart(part: Part): Pair<Int, Int> = when (part) {
    is Part.Text -> part.text.length to 0
    is Part.Reasoning -> part.text.length to 0
    is Part.Tool -> 0 to ((part.state as? ToolState.Completed)?.output?.length ?: 0)
    else -> 0 to 0
}

/**
 * 估算上下文按角色拆分。对应 opencode estimateSessionContextBreakdown。
 * OC Remote 没有 system prompt，故无 system 桶，差额归 OTHER（含 system 等）。
 *
 * @param realInput 真实 input token（来自最后 StepFinish.Tokens.input）
 */
fun estimateContextBreakdown(messages: List<MessageWithParts>, realInput: Int): ContextBreakdown {
    var userChars = 0
    var assistantChars = 0
    var toolChars = 0

    for (msg in messages) {
        val role = msg.info.role
        for (part in msg.parts) {
            when (role) {
                "user" -> userChars += charsFromUserPart(part)
                "assistant" -> {
                    val (a, t) = charsFromAssistantPart(part)
                    assistantChars += a
                    toolChars += t
                }
            }
        }
    }

    val userTokens = estimateTokens(userChars)
    val assistantTokens = estimateTokens(assistantChars)
    val toolTokens = estimateTokens(toolChars)
    val estimated = userTokens + assistantTokens + toolTokens
    val denominator = maxOf(estimated, realInput)
    val otherTokens = (realInput - estimated).coerceAtLeast(0)

    fun pct(tokens: Int) = if (denominator <= 0) 0f else tokens.toFloat() / denominator

    val segments = listOf(
        ContextBreakdownSegment(BreakdownRole.USER, userTokens, pct(userTokens)),
        ContextBreakdownSegment(BreakdownRole.ASSISTANT, assistantTokens, pct(assistantTokens)),
        ContextBreakdownSegment(BreakdownRole.TOOL, toolTokens, pct(toolTokens)),
        ContextBreakdownSegment(BreakdownRole.OTHER, otherTokens, pct(otherTokens))
    ).filter { it.estimatedTokens > 0 }

    return ContextBreakdown(segments)
}

/** 统计 user/assistant 消息数 */
fun countMessages(messages: List<Message>): MessageCount {
    var user = 0
    var assistant = 0
    for (m in messages) {
        when (m.role) {
            "user" -> user++
            "assistant" -> assistant++
        }
    }
    return MessageCount(user, assistant)
}

/** 缓存命中率 = cacheRead / (input + cacheRead) */
fun cacheHitRate(cacheRead: Int, input: Int): Float? {
    val total = input + cacheRead
    return if (total <= 0) null else cacheRead.toFloat() / total
}
