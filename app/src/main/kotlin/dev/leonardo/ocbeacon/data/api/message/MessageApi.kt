package dev.leonardo.ocbeacon.data.api.message

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.SseEvent
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
        directory: String? = null,
        /** #309 批1④：DSH 直发插话（mode=steer）；V1/V2 忽略。 */
        steer: Boolean = false
    ): PromptAdmission?

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
        /** 2026-08-17：V2 新契约需要——权限所属会话（子智能体会话权限传子智能体会话 id）。 */
        sessionId: String,
        requestId: String,
        reply: String, // "once"、"always" 或 "reject"
        message: String? = null,
        directory: String? = null,
        /** #308（2026-09-03）：DSH 回程路由需要 requested 帧稳定 rpcId（PermissionAsked.metadata["rpcId"]，仓储层从 pending 存储补查）；V1/V2 忽略。 */
        metadata: Map<String, String>? = null
    ): Boolean

    /**
     * 列出待处理的权限请求。
     * GET /permission
     */
    /** #314：null = 端点缺席（DSH 无此 REST 面）——不得当「权威回答无待答」清 SSE 存储；非 null（含空表）才是权威快照。 */
    suspend fun listPendingPermissions(conn: ServerConnection, directory: String? = null): List<PermissionRequest>?

    /**
     * 回复问题请求。
     * V1：POST /question/{requestID}/reply，Body: { answers: string[][] }
     * V2（#130）：POST /api/session/{sessionID}/form/{formID}/reply，
     *   Body: { answer: {key: value | [values]} }——form 服务路径。
     *
     * @param question 领域问题（V2 form 需要 sessionId + key/value 映射；
     *   V1 忽略）。为 null 时 V2 分支返回 false（无法构造 form answer）。
     */
    suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null,
        question: SseEvent.QuestionAsked? = null
    ): Boolean

    /**
     * 拒绝/取消问题请求。
     * V1：POST /question/{requestID}/reject
     * V2（#130）：POST /api/session/{sessionID}/form/{formID}/cancel
     *
     * @param sessionId 表单所属会话（V2 cancel 路径需要；V1 忽略）
     */
    suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String? = null,
        sessionId: String? = null
    ): Boolean

    /**
     * 列出待处理的问题请求。
     * GET /question
     */
    /** #314：null = 端点缺席——同 [listPendingPermissions]。 */
    suspend fun listPendingQuestions(conn: ServerConnection, directory: String? = null): List<QuestionRequest>?
}

/**
 * C1-3（2026-08-26 架构走查，Q2-a）：分发层收缩为单点路由 + 逐方法单行委托。
 * [V1ApiClient]/[V2ApiClient] 已直接实现 [MessageApi]，真实 V2 适配
 *（listMessages 的 cursor=before 翻译、replyToQuestion 的 V2FormMapper
 * 答案构造、rejectQuestion 的 sessionId 回退）随之下沉至 V2ApiClient。
 */
@Singleton
class MessageApiImpl @Inject constructor(
    private val adapters: ServerAdapterRegistry,
) : MessageApi {

    /**
     * #391 切片 1：路由改走适配器注册表（唯一 seam）；行为与迁移前逐位一致
     * （DSH 优先于 apiVersion 二分）。
     */
    private fun pick(conn: ServerConnection): MessageApi = adapters.ports(conn).message

    override suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int?,
        before: String?,
    ): MessagePage = pick(conn).listMessages(conn, sessionId, limit, before)

    override suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String =
        pick(conn).listMessagesRaw(conn, sessionId)

    override suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit
    ) {
        pick(conn).exportSessionToStream(conn, sessionId, outputStream, onProgress)
    }

    override suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts =
        pick(conn).getMessage(conn, sessionId, messageId)

    override suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?,
        steer: Boolean
    ): PromptAdmission? =
        pick(conn).promptAsync(conn, sessionId, parts, model, agent, variant, directory, steer)

    override suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean =
        pick(conn).deleteMessage(conn, sessionId, messageId)

    override suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean =
        pick(conn).deleteMessagePart(conn, sessionId, messageId, partIndex)

    override suspend fun replyToPermission(
        conn: ServerConnection,
        sessionId: String,
        requestId: String,
        reply: String,
        message: String?,
        directory: String?,
        metadata: Map<String, String>?
    ): Boolean = pick(conn).replyToPermission(conn, sessionId, requestId, reply, message, directory, metadata)

    override suspend fun listPendingPermissions(conn: ServerConnection, directory: String?): List<PermissionRequest>? =
        pick(conn).listPendingPermissions(conn, directory)

    override suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
        question: SseEvent.QuestionAsked?
    ): Boolean = pick(conn).replyToQuestion(conn, requestId, answers, directory, question)

    override suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String?,
        sessionId: String?
    ): Boolean = pick(conn).rejectQuestion(conn, requestId, directory, sessionId)

    override suspend fun listPendingQuestions(conn: ServerConnection, directory: String?): List<QuestionRequest>? =
        pick(conn).listPendingQuestions(conn, directory)
}
