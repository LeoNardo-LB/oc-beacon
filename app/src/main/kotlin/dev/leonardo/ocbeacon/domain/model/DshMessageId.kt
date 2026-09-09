package dev.leonardo.ocbeacon.domain.model

/**
 * DSH 整装消息 id 契约："seq-{sessionId}-{seq}"（user/message、assistant/message）。
 *
 * 唯一权威实现（#378 上提：data 层 [dev.leonardo.ocbeacon.data.api.dsh.DshEventMapper]
 * 的 messageId/seqOf 委派到此——UI 层流内归并需要反解 seq，而依赖方向 UI→Domain
 * 禁止反向 import data 层；本对象落在 domain 使两侧共源，前缀改动作必须同步
 * wire 契约注释）。
 *
 * #385b（2026-09-10 跨会话 id 碰撞根修）：DSH wire 的 seq 只在**会话内**唯一，而
 * mux 会把其他已附会话的事件一并推来——裸 "seq-N" 作 cached_messages/cached_parts
 * 主键时，不同会话同 seq 行互相覆盖（实测 ack-four 会话 seq-9..12 每 3 秒在三个
 * 会话间乒乓翻转，消息/部件跨会话串味：注入卡丢 injectionKind、气泡混入外会话
 * 文本）。id 携带会话段后各会话命名空间互斥；[seqOf] 兼容旧裸形态（v8 迁移清理
 * 前的存量行与历史 journal 取证样本）。
 */
object DshMessageId {
    const val SEQ_PREFIX = "seq-"

    /** 整装消息 id（正向：sessionId + seq → id；sessionId 使 Room 主键跨会话唯一）。 */
    fun id(sessionId: String, seq: Long): String = "$SEQ_PREFIX$sessionId-$seq"

    /**
     * 反解：id → seq；其余形态（null/V2 msg_x/流式宿主/工具宿主/残缺/非数/负数）
     * → null（调用方安全降级为无锚点）。取**最后一个 '-' 后**的数字段——新形态
     * "seq-{sessionId}-{n}"（sessionId 自身含 '-'）与旧裸形态 "seq-{n}"（无 '-'）
     * 均正确解析。
     */
    fun seqOf(messageId: String?): Long? {
        if (messageId.isNullOrEmpty() || !messageId.startsWith(SEQ_PREFIX)) return null
        val body = messageId.removePrefix(SEQ_PREFIX)
        val dash = body.lastIndexOf('-')
        // dash <= 0：旧裸形态（无 '-'），或空会话段畸形（"seq--5"——负数体整体拒收，
        // 既有拒收用例保持）。dash > 0：新形态 seq-{sessionId}-{n} 取末段。
        val n = if (dash <= 0) body else body.substring(dash + 1)
        return n.toLongOrNull()?.takeIf { it >= 0 }
    }
}
