package dev.leonardo.ocbeacon.domain.model

/**
 * 分页消息结果。
 * - [nextCursor] 为空表示已到最早消息（无更旧数据，older 方向读尽）。
 * - [previousCursor] 为 V2 双向分页的"更新方向"游标（响应 cursor.previous）；
 *   为空表示已到最新消息（无更新数据，newer 方向读尽）。V1 恒为 null。
 * - [transcriptEvents]（#378）：同窗历史折叠出的非消息转录事件（命令/压缩卡族
 *   + 表面区间替换）——历史加载方 dispatch 后卡族按 commandId/compactionId 幂等
 *   重建（#376 修复通道：与重连回填等价的消费面）。DSH 独有；V1/V2 恒空。
 */
data class MessagePage(
    val messages: List<MessageWithParts>,
    val nextCursor: String?,
    val previousCursor: String? = null,
    val transcriptEvents: List<SseEvent> = emptyList(),
)
