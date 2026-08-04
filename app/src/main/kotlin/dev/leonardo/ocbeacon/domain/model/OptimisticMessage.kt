package dev.leonardo.ocbeacon.domain.model

/**
 * 用户发送时创建的本地乐观消息，在服务器确认之前使用。
 * 当 SSE message_updated 到达时由真实消息替换。
 */
data class OptimisticMessage(
    val pendingId: String,
    val message: Message.User,
    val parts: List<Part>,
    val status: UserMsgStatus,
)
