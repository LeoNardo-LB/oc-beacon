package dev.leonardo.ocbeacon.data.api.message

import dev.leonardo.ocbeacon.domain.model.MessageWithParts

/**
 * 分页消息结果。nextCursor 为空表示已到最早消息（无更早数据）。
 */
data class MessagePage(
    val messages: List<MessageWithParts>,
    val nextCursor: String?,
)
