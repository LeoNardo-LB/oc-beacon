package dev.leonardo.ocbeacon.data.api.feedback

import dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * 消息反馈（👍/👎）域端口（#391 切片3）。
 *
 * 端口在场即该能力可用；不提供的类型端口缺席——读返回空、写显式失败
 * （写操作假成功会误导用户）。
 */
interface FeedbackApi {

    suspend fun messageFeedbackPut(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        rating: MessageFeedbackRating,
        note: String? = null,
        ifVersion: String?,
    ): MessageFeedbackPutResult

    suspend fun messageFeedbackDelete(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        ifVersion: String,
    ): MessageFeedbackDeleteResult

    suspend fun messageFeedbackList(
        conn: ServerConnection,
        sessionId: String,
    ): List<MessageFeedbackItem>
}
