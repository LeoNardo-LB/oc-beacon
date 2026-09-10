package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.queue.MessageQueueApi
import dev.leonardo.ocbeacon.domain.model.QueuedInboxItem
import dev.leonardo.ocbeacon.domain.model.QueueActionKind
import dev.leonardo.ocbeacon.domain.model.QueueMutationResult
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * DSH 消息队列域端口实现（#391 切片3）——DSH 的排队由**服务器队列原生承载**
 * （session/queue 快照 + updateQueue）；队列列表经控制帧推送维护，端口读返回 null
 * 让调用方保旧值。
 */
class DshQueuePort(
    private val dsh: DshApiClient,
) : MessageQueueApi {

    override suspend fun updateQueue(
        conn: ServerConnection,
        sessionId: String,
        itemId: String,
        action: QueueActionKind,
        editText: String?,
    ): QueueMutationResult = dsh.updateQueue(conn, sessionId, itemId, action, editText)

    override suspend fun listInbox(conn: ServerConnection, sessionId: String): List<QueuedInboxItem>? =
        dsh.listInbox(conn, sessionId)
}
