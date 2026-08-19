package dev.leonardo.ocbeacon.domain.model

/** 堆积消息（turn 结束后待发送的本地暂存消息）。仅纯文本。 */
data class PendingMessage(
    val id: Long,
    val sessionId: String,
    val position: Int,
    val text: String,
    val createdAt: Long,
)
