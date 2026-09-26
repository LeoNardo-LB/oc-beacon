package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * 计算聊天消息列表中 assistant 消息的 turn 分组。
 *
 * 一个 "turn" 是两条用户消息（或列表开头/结尾）之间的
 * 连续 assistant 消息序列。
 *
 * **synthetic 独立规则（2026-08-12 用户决策）**：synthetic 合成通知
 * （后台任务/subagent 完成注入）是**独立消息**，渲染为独立气泡
 * （与 user 消息同构）——不并入 assistant turn。
 *
 * @return 从消息索引到同一 turn 中所有 ChatMessages 列表的映射。
 *         用户消息索引不在映射中。
 */
internal fun computeTurnGroups(messages: List<ChatMessage>): Map<Int, List<ChatMessage>> {
    val indexToGroup = mutableMapOf<Int, List<ChatMessage>>()
    for ((range, group) in buildAssistantTurnGroups(messages)) {
        for (i in range) {
            indexToGroup[i] = group
        }
    }
    return indexToGroup
}

/**
 * #440 在位键（槽位锚）：turn 键的稳定锚 = 该轮 Older 侧相邻的非 assistant 消息
 * （即提问的 user 消息——流式开始前已持久存在）。流式宿主（dsh-t{turn}s{step}）
 * 与终态消息（seq-*）在同一轮内互换时，锚不变 → turnKey 不变 → LazyColumn 零
 * remove+add（消除换装跳变）。
 *
 * 排除 pending-* 临时锚（乐观播种的 user 气泡 id 尚未持久，落库后会换装——
 * 期间回退旧公式，代价是该窗口内沿用旧行为）；开放尾组（列表 Older 端无终结者）
 * 无锚，同样回退。
 */
internal fun computeTurnAnchors(messages: List<ChatMessage>): Map<Int, String> {
    val indexToAnchor = mutableMapOf<Int, String>()
    for ((range, _) in buildAssistantTurnGroups(messages)) {
        val terminator = messages.getOrNull(range.last + 1)
        val anchor = terminator?.takeIf { !it.isAssistant && !it.message.id.startsWith("pending-") }?.message?.id
        if (anchor != null) {
            for (i in range) {
                indexToAnchor[i] = anchor
            }
        }
    }
    return indexToAnchor
}

/** 共享走查：assistant 连续段成组（synthetic 独立成泡，2026-08-12 用户决策）。 */
private fun buildAssistantTurnGroups(messages: List<ChatMessage>): List<Pair<IntRange, List<ChatMessage>>> {
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
    return groups
}
