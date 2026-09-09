package dev.leonardo.ocbeacon.domain.model

/**
 * DSH 整装消息 id 契约："seq-{seq}"（user/message、assistant/message）。
 *
 * 唯一权威实现（#378 上提：data 层 [dev.leonardo.ocbeacon.data.api.dsh.DshEventMapper]
 * 的 messageId/seqOf 委派到此——UI 层流内归并需要反解 seq，而依赖方向 UI→Domain
 * 禁止反向 import data 层；本对象落在 domain 使两侧共源，前缀改动作必须同步
 * wire 契约注释）。
 */
object DshMessageId {
    const val SEQ_PREFIX = "seq-"

    /** 整装消息 id（正向：seq → id）。 */
    fun id(seq: Long): String = SEQ_PREFIX + seq

    /**
     * 反解：id → seq；其余形态（null/V2 msg_x/流式宿主/工具宿主/残缺/非数/负数）
     * → null（调用方安全降级为无锚点）。
     */
    fun seqOf(messageId: String?): Long? {
        if (messageId.isNullOrEmpty() || !messageId.startsWith(SEQ_PREFIX)) return null
        return messageId.removePrefix(SEQ_PREFIX).toLongOrNull()?.takeIf { it >= 0 }
    }
}
