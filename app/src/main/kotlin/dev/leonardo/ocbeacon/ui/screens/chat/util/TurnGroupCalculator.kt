package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * 计算聊天消息列表中 assistant 消息的 turn 分组。
 *
 * 一个 "turn" 是两条用户消息（或列表开头/结尾）之间的
 * 连续 assistant 消息序列。
 *
 * @return 从消息索引到同一 turn 中所有 ChatMessages 列表的映射。
 *         用户消息索引不在映射中。
 */
internal fun computeTurnGroups(messages: List<ChatMessage>): Map<Int, List<ChatMessage>> {
    val groups = mutableListOf<Pair<IntRange, List<ChatMessage>>>()
    var currentStart = -1
    val currentGroup = mutableListOf<ChatMessage>()

    for ((index, msg) in messages.withIndex()) {
        if (msg.isAssistant) {
            if (currentStart == -1) currentStart = index
            currentGroup.add(msg)
        } else {
            if (currentGroup.isNotEmpty()) {
                groups.add((currentStart until index) to currentGroup.toList())
                currentGroup.clear()
                currentStart = -1
            }
        }
    }
    if (currentGroup.isNotEmpty()) {
        groups.add((currentStart until messages.size) to currentGroup.toList())
    }

    val indexToGroup = mutableMapOf<Int, List<ChatMessage>>()
    for ((range, group) in groups) {
        for (i in range) {
            indexToGroup[i] = group
        }
    }
    return indexToGroup
}
