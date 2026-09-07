package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * #348 堆积消息（本地排队）：busy 时用户选择「堆积消息」的草稿——
 * 本地保存、本轮结束后自动按序发送（2026-08-20 ce8cbc1e 语义复刻，
 * 2026-09-07 用户裁决定案恢复；#289 拆除后精益重建：DataStore 持久化
 * 取代 Room 表，跨进程重启存活）。
 */
@Serializable
data class StackedMessage(
    val id: String,
    val sessionId: String,
    val serverId: String,
    val text: String,
    val createdAt: Long,
)
