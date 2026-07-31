package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * Computes turn groups for assistant messages in a chat message list.
 *
 * A "turn" is a consecutive sequence of assistant messages between two user
 * messages (or the start/end of the list).
 *
 * @return Map from message index → list of all ChatMessages in the same turn.
 *         User message indices are NOT included in the map.
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
