package dev.leonardo.ocbeacon.data.api.message

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.SseEvent

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
