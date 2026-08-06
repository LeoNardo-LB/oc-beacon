package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 乐观（尚未经服务器确认）prompt 发送的持久化记录。
 *
 * 由 [dev.leonardo.ocbeacon.domain.repository.PendingPromptRepository] 存储，
 * 使待处理 prompt 能在应用重启后保留。下次启动时，
 * [dev.leonardo.ocbeacon.data.repository.missingPendingPromptIds] 会将它们
 * 与服务器的权威消息列表进行核对，以检测丢失的发送。
 */
@Serializable
data class PendingPromptRecord(
    val messageId: String,
    val sessionId: String,
    val parts: List<PromptPart>,
    val model: ModelSelection? = null,
    val agent: String? = null,
    val variant: String? = null,
    val directory: String? = null,
    val createdAt: Long,
)
