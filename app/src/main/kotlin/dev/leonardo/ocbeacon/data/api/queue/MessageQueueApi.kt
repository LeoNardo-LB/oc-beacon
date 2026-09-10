package dev.leonardo.ocbeacon.data.api.queue

import dev.leonardo.ocbeacon.domain.model.QueuedInboxItem
import dev.leonardo.ocbeacon.domain.model.QueueActionKind
import dev.leonardo.ocbeacon.domain.model.QueueMutationResult
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * 消息队列域端口（#391 切片3）——**通用命名，不暗示实现来源**。
 *
 * 该端口既可能由服务器队列原生提供，也可能由客户端实现（进程内队列）挂载；端口在场
 * 只表达"客户端能提供该能力"。端口内子能力（是否可编辑）由能力位收敛，不改端口形状。
 */
interface MessageQueueApi {

    /** 排队项变更（edit / remove / steer）；不支持的动作由实现返回 Failed。 */
    suspend fun updateQueue(
        conn: ServerConnection,
        sessionId: String,
        itemId: String,
        action: QueueActionKind,
        editText: String?,
    ): QueueMutationResult

    /** 排队列表；无可见域时实现返回 null（调用方保旧值）。 */
    suspend fun listInbox(
        conn: ServerConnection,
        sessionId: String,
    ): List<QueuedInboxItem>?
}
