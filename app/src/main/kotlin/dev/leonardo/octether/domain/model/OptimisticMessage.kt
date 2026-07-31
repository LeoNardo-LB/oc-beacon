package dev.leonardo.octether.domain.model

/**
 * Local optimistic message created when user sends, before server confirms.
 * Replaced by real message when SSE message_updated arrives.
 */
data class OptimisticMessage(
    val pendingId: String,
    val message: Message.User,
    val parts: List<Part>,
    val status: UserMsgStatus,
)
