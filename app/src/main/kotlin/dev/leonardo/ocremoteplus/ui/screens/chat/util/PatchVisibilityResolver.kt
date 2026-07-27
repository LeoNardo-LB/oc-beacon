package dev.leonardo.ocremoteplus.ui.screens.chat.util

import dev.leonardo.ocremoteplus.domain.model.Part
import dev.leonardo.ocremoteplus.ui.screens.chat.ChatMessage

/**
 * 折叠连续重复 hash 的 patch 卡片。
 *
 * 问题：服务器对未变更 session diff 可能重复推送相同 hash 的 patch part，
 * 导致每个 assistant 消息都显示一张"新"补丁卡片——用户误以为文件又改了。
 *
 * 解决：跟踪 [lastVisiblePatchHash]，连续相同 hash 的 patch 只保留第一个。
 *
 * 边界规则：
 * - 空 hash 的 patch 始终保留（不视为重复）——服务器用空 hash 表示"无变更摘要"
 * - 非 patch 消息（如纯文本）不重置 lastVisiblePatchHash——避免在文本间隔后重复显示
 * - hash 变化时正常显示并更新 lastVisiblePatchHash
 *
 * 启发自上游 oc-remote v1.6.9 commit a906a72b，但适配我们的 ChatMessage 类型。
 */
internal fun suppressRepeatedPatchHashes(messages: List<ChatMessage>): List<ChatMessage> {
    var lastVisiblePatchHash: String? = null

    return messages.map { chatMessage ->
        if (!chatMessage.isAssistant) {
            chatMessage
        } else {
            val filteredParts = buildList {
                for (part in chatMessage.parts) {
                    if (part is Part.Patch) {
                        val normalizedHash = part.hash.trim()
                        val isRepeated = normalizedHash.isNotEmpty() && normalizedHash == lastVisiblePatchHash
                        if (!isRepeated) {
                            add(part)
                            if (normalizedHash.isNotEmpty()) {
                                lastVisiblePatchHash = normalizedHash
                            }
                        }
                    } else {
                        add(part)
                    }
                }
            }
            chatMessage.copy(parts = filteredParts)
        }
    }
}
