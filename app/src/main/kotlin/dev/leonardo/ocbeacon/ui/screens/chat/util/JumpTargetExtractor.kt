package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/** A user question that can be jumped to from the Quick Navigate sheet. */
data class JumpTarget(
    val label: String,        // "Q1", "Q2" ...
    val timestampMs: Long,    // message.time.created (epoch millis)
    val preview: String,      // first Part.Text content, or placeholder
    val rawIndex: Int,        // index in rawMessages
    val msgId: String         // message.id, for jump lookup
)

/**
 * Extract all user questions, sorted by time ascending (Q1 = oldest question),
 * independent of rawMessages storage order (rawMessages is newest-first in
 * production — see ChatScreen.kt:993). rawIndex keeps the original index in
 * rawMessages for jump lookup.
 *
 * Pure function — no Android/Compose dependencies.
 */
fun extractJumpTargets(rawMessages: List<ChatMessage>): List<JumpTarget> {
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
                    ?: "(无文本)",
                rawIndex = indexed.index,
                msgId = cm.message.id
            )
        }
}

/**
 * Given a rawIndex (any message), find the nearest user message at or before it.
 * Returns its rawIndex, or null if none exists. Pure function.
 */
fun findNearestUserIndexBefore(rawMessages: List<ChatMessage>, rawIdx: Int): Int? {
    if (rawIdx < 0 || rawIdx >= rawMessages.size) return null
    return (rawIdx downTo 0).firstOrNull { rawMessages[it].isUser }
}

/**
 * Identify which user question corresponds to the currently-visible top message.
 *
 * Uses listState.layoutInfo.visibleItemsInfo + message key format
 * ("u_<id>" for user, "t_<id>" for assistant — see ChatMessageList.kt:391-392).
 * reverseLayout=true: visually topmost visible message = smallest offset.
 *
 * Returns the rawIndex of the current user question, or null if indeterminate.
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
