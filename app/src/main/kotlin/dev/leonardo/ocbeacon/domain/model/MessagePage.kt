package dev.leonardo.ocbeacon.domain.model

/**
 * 分页消息结果。
 * - [nextCursor] 为空表示已到最早消息（无更旧数据，older 方向读尽）。
 * - [previousCursor] 为 V2 双向分页的"更新方向"游标（响应 cursor.previous）；
 *   为空表示已到最新消息（无更新数据，newer 方向读尽）。V1 恒为 null。
 */
data class MessagePage(
    val messages: List<MessageWithParts>,
    val nextCursor: String?,
    val previousCursor: String? = null,
)
