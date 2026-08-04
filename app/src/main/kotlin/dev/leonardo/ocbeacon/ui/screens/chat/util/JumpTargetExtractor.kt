package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/** 可从快速导航面板跳转的用户问题。 */
data class JumpTarget(
    val label: String,        // "Q1"、"Q2" ...
    val timestampMs: Long,    // message.time.created（epoch 毫秒）
    val preview: String,      // 第一个 Part.Text 内容，或占位符
    val rawIndex: Int,        // rawMessages 中的索引
    val msgId: String         // message.id，用于跳转查找
)

/**
 * 提取所有用户问题，按时间升序排列（Q1 = 最旧的问题），
 * 与 rawMessages 存储顺序无关（rawMessages 在生产环境中是最新的在前
 * —— 见 ChatScreen.kt:993）。rawIndex 保留 rawMessages 中的
 * 原始索引用于跳转查找。
 *
 * 纯函数 —— 无 Android/Compose 依赖。
 *
 * @param noTextPlaceholder 无文本时的占位符（由调用方本地化）。
 */
fun extractJumpTargets(
    rawMessages: List<ChatMessage>,
    noTextPlaceholder: String = "(无文本)",
): List<JumpTarget> {
    return rawMessages.withIndex()
        .filter { it.value.isUser }
        .sortedBy { it.value.message.time.created }
        .mapIndexed { i, indexed ->
            val cm = indexed.value
            JumpTarget(
                label = "Q${i + 1}",
                timestampMs = cm.message.time.created,
                preview = cm.parts.firstOrNull { it is Part.Text }
                    ?.let { (it as Part.Text).text }
                    ?.takeIf { it.isNotBlank() }
                    ?: noTextPlaceholder,
                rawIndex = indexed.index,
                msgId = cm.message.id
            )
        }
}

/**
 * 给定一个 rawIndex（任意消息），找到其上或其本身最近的用户消息。
 * 返回其 rawIndex，不存在则返回 null。纯函数。
 */
fun findNearestUserIndexBefore(rawMessages: List<ChatMessage>, rawIdx: Int): Int? {
    if (rawIdx < 0 || rawIdx >= rawMessages.size) return null
    return (rawIdx downTo 0).firstOrNull { rawMessages[it].isUser }
}

/**
 * 识别当前可见顶部消息对应的用户问题。
 *
 * 使用 listState.layoutInfo.visibleItemsInfo + 消息 key 格式
 *（"u_<id>" 为用户，"t_<id>" 为 assistant —— 见 ChatMessageList.kt:391-392）。
 * reverseLayout=true：视觉上最顶部的可见消息 = 最小偏移。
 *
 * 返回当前用户问题的 rawIndex，无法确定时返回 null。
 */
fun findCurrentQuestionRawIndex(
    listState: LazyListState,
    rawMessages: List<ChatMessage>
): Int? {
    val visibleMsgs = listState.layoutInfo.visibleItemsInfo.filter { info ->
        (info.key as? String)?.let { it.startsWith("u_") || it.startsWith("t_") } == true
    }
    val topMsg = visibleMsgs.minByOrNull { it.offset } ?: return null
    val key = topMsg.key as String
    val msgId = key.removePrefix("u_").removePrefix("t_")
    val rawIdx = rawMessages.indexOfFirst { it.message.id == msgId }
    if (rawIdx < 0) return null
    return findNearestUserIndexBefore(rawMessages, rawIdx)
}
