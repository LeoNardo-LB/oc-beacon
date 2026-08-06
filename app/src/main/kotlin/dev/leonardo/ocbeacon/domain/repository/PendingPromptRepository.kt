package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord

/**
 * 待处理 prompt（乐观发送、尚未经服务器确认）的持久化存储接口。
 * 使待处理 prompt 能在应用重启后保留，用于丢失发送的核对。
 */
interface PendingPromptRepository {
    /** 返回 [sessionId] 的所有已持久化待处理 prompt，按从旧到新排序。 */
    fun getForSession(sessionId: String): List<PendingPromptRecord>

    /** 返回所有会话的全部已持久化待处理 prompt，按从旧到新排序。 */
    fun loadAll(): List<PendingPromptRecord>

    /** 同步持久化一条待处理 prompt，以 [PendingPromptRecord.messageId] 为键。 */
    fun save(record: PendingPromptRecord)

    /** 按消息 id 移除待处理 prompt（不存在则为空操作）。 */
    fun remove(messageId: String)

    /** 清除所有已持久化的待处理 prompt。 */
    fun clear()
}
