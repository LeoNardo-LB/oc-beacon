package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * 计算聊天消息列表中 assistant 消息的 turn 分组。
 *
 * 一个 "turn" 是两条用户消息（或列表开头/结尾）之间的
 * 连续 assistant 消息序列。
 *
 * **synthetic 嵌入规则（2026-08-11 用户要求）**：synthetic 系统通知
 * （后台任务/subagent 完成注入）如果**紧邻 assistant 消息**（前一条或
 * 后一条是 assistant），则并入该 turn——气泡内渲染卡片，不再独立成行
 * 截断回复气泡。孤立 synthetic（前后都非 assistant）保持独立条目。
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
        } else if (msg.isSynthetic && isAdjacentToAssistant(messages, index)) {
            // synthetic 通知嵌入相邻 assistant turn（不中断气泡）
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

/** synthetic 消息是否紧邻 assistant（前一条或后一条是 assistant）。 */
internal fun isAdjacentToAssistant(messages: List<ChatMessage>, index: Int): Boolean {
    val prevIsAssistant = messages.getOrNull(index - 1)?.isAssistant == true
    val nextIsAssistant = messages.getOrNull(index + 1)?.isAssistant == true
    return prevIsAssistant || nextIsAssistant
}
