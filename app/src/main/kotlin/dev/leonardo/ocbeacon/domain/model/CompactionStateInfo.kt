package dev.leonardo.ocbeacon.domain.model

/**
 * 压缩状态的领域模型。
 * 对应 data.repository.handler.CompactionStateInfo。
 */
data class CompactionStateInfo(
    val isActive: Boolean,
    val reason: String = "",
    /** #217 分割线包揽：流式摘要累积（session.compaction.delta）；空 = 尚无输出。 */
    val deltaText: String = "",
    /** 服务器 compaction 消息 id（started 事件 inputID）——消息流内对位判据。 */
    val messageId: String = ""
)
