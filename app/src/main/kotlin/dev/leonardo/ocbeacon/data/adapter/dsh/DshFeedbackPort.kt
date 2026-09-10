package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.feedback.FeedbackApi
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * DSH 消息反馈域端口实现（#391 切片3）——薄委托到协议客户端。
 */
class DshFeedbackPort(
    private val dsh: DshApiClient,
) : FeedbackApi {

    override suspend fun messageFeedbackPut(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        rating: MessageFeedbackRating,
        note: String?,
        ifVersion: String?,
    ): MessageFeedbackPutResult = dsh.messageFeedbackPut(conn, sessionId, messageId, rating, note, ifVersion)

    override suspend fun messageFeedbackDelete(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        ifVersion: String,
    ): MessageFeedbackDeleteResult = dsh.messageFeedbackDelete(conn, sessionId, messageId, ifVersion)

    override suspend fun messageFeedbackList(
        conn: ServerConnection,
        sessionId: String,
    ): List<MessageFeedbackItem> = dsh.messageFeedbackList(conn, sessionId)
}
