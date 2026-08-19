package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.PendingMessage
import kotlinx.coroutines.flow.Flow

/**
 * 堆积消息仓库（turn 结束后待发送的本地暂存队列，按会话作用域）。
 *
 * 设计要点：
 * - 仅自然成功 turn 结束（V2 execution.succeeded / V1 session.status(idle)）
 *   推进队首 1 条；推进方（PendingMessagePipeline）负责触发时机，
 *   本仓库只管存取。
 * - [dequeueHead] 原子弹出——推进方并发触发时不会重复发送同一条。
 */
interface PendingMessageRepository {
    fun observeQueue(sessionId: String): Flow<List<PendingMessage>>

    /** 追加到队尾（输入框非空文本入队）。 */
    suspend fun enqueue(sessionId: String, text: String)

    /** 编辑条目文本。 */
    suspend fun updateText(id: Long, text: String)

    /** 删除单条。 */
    suspend fun delete(id: Long)

    /** 一键清空会话队列（带 UI 确认的调用方责任）。 */
    suspend fun clear(sessionId: String)

    /** 按入参顺序整体重排（拖拽排序落地）。 */
    suspend fun reorder(sessionId: String, orderedIds: List<Long>)

    /** 原子弹出队首；队列空返回 null。 */
    suspend fun dequeueHead(sessionId: String): PendingMessage?

    /** 查看队首（不删除）。推进管线 peek→send→delete 语义用。 */
    suspend fun peekHead(sessionId: String): PendingMessage?

    /** 会话删除时的级联清理（无外键可用，删除路径显式调用）。 */
    suspend fun deleteForSession(sessionId: String)
}
