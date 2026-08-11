package dev.leonardo.ocbeacon.data.api.message

import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import javax.inject.Inject
import javax.inject.Singleton

interface MessageApi {
    suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
    ): MessagePage

    /** 以原始 JSON 字符串返回消息（用于导出而无需重新序列化）。 */
    suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String

    suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit = {}
    )

    suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts

    suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null
    )

    /**
     * 从会话中删除一条消息。
     * DELETE /session/{sessionId}/message/{messageId}
     */
    suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean

    /**
     * 按索引删除消息中的特定部分。
     * DELETE /session/{sessionId}/message/{messageId}/part/{partIndex}
     */
    suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean

    /**
     * 回复权限请求。
     * POST /permission/{requestID}/reply
     * Body: { reply: "once" | "always" | "reject", message?: string }
     */
    suspend fun replyToPermission(
        conn: ServerConnection,
        requestId: String,
        reply: String, // "once"、"always" 或 "reject"
        message: String? = null,
        directory: String? = null
    ): Boolean

    /**
     * 列出待处理的权限请求。
     * GET /permission
     */
    suspend fun listPendingPermissions(conn: ServerConnection, directory: String? = null): List<PermissionRequest>

    /**
     * 回复问题请求。
     * POST /question/{requestID}/reply
     * Body: { answers: string[][] }
     */
    suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): Boolean

    /**
     * 拒绝问题请求。
     * POST /question/{requestID}/reject
     */
    suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String? = null
    ): Boolean

    /**
     * 列出待处理的问题请求。
     * GET /question
     */
    suspend fun listPendingQuestions(conn: ServerConnection, directory: String? = null): List<QuestionRequest>
}

@Singleton
class MessageApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : MessageApi {

    override suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int?,
        before: String?,
    ): MessagePage =
        if (conn.apiVersion.isV2) v2.listMessages(conn, sessionId, limit, cursor = before)
        else v1.listMessages(conn, sessionId, limit, before)

    override suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String =
        if (conn.apiVersion.isV2) v2.listMessagesRaw(conn, sessionId) else v1.listMessagesRaw(conn, sessionId)

    override suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit
    ) {
        if (conn.apiVersion.isV2) v2.exportSessionToStream(conn, sessionId, outputStream, onProgress)
        else v1.exportSessionToStream(conn, sessionId, outputStream, onProgress)
    }

    override suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts =
        if (conn.apiVersion.isV2) v2.getMessage(conn, sessionId, messageId)
        else v1.getMessage(conn, sessionId, messageId)

    override suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ) {
        if (conn.apiVersion.isV2) v2.promptAsync(conn, sessionId, parts, model, agent, variant, directory)
        else v1.promptAsync(conn, sessionId, parts, model, agent, variant, directory)
    }

    override suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean =
        if (conn.apiVersion.isV2) v2.deleteMessage(conn, sessionId, messageId)
        else v1.deleteMessage(conn, sessionId, messageId)

    override suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean =
        if (conn.apiVersion.isV2) v2.deleteMessagePart(conn, sessionId, messageId, partIndex)
        else v1.deleteMessagePart(conn, sessionId, messageId, partIndex)

    override suspend fun replyToPermission(
        conn: ServerConnection,
        requestId: String,
        reply: String,
        message: String?,
        directory: String?
    ): Boolean =
        if (conn.apiVersion.isV2) v2.replyToPermission(conn, requestId, reply, message, directory)
        else v1.replyToPermission(conn, requestId, reply, message, directory)

    override suspend fun listPendingPermissions(conn: ServerConnection, directory: String?): List<PermissionRequest> =
        if (conn.apiVersion.isV2) v2.listPendingPermissions(conn, directory)
        else v1.listPendingPermissions(conn, directory)

    override suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Boolean =
        if (conn.apiVersion.isV2) v2.replyToQuestion(conn, requestId, answers, directory)
        else v1.replyToQuestion(conn, requestId, answers, directory)

    override suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String?
    ): Boolean =
        if (conn.apiVersion.isV2) v2.rejectQuestion(conn, requestId, directory)
        else v1.rejectQuestion(conn, requestId, directory)

    override suspend fun listPendingQuestions(conn: ServerConnection, directory: String?): List<QuestionRequest> =
        if (conn.apiVersion.isV2) v2.listPendingQuestions(conn, directory)
        else v1.listPendingQuestions(conn, directory)
}
