package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import kotlinx.serialization.Serializable

/** 冷存桶内单条消息（整桶序列化后 zstd 压缩）。 */
@Serializable
data class ArchivedMessageDto(
    val info: Message,
    val parts: List<Part>,
)
