package dev.leonardo.ocbeacon.data.api.session

import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.asApiError
import dev.leonardo.ocbeacon.data.api.logApiError
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SessionApi"

interface SessionApi {
    suspend fun listSessions(
        conn: ServerConnection,
        directory: String? = null,
        search: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ): List<Session>

    suspend fun getSession(conn: ServerConnection, sessionId: String): Session

    /** 以原始 JSON 字符串返回会话信息（用于导出而无需重新序列化）。 */
    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String

    suspend fun createSession(
        conn: ServerConnection,
        title: String? = null,
        parentId: String? = null,
        directory: String? = null
    ): Session

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean

    suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session

    /**
     * 用任意字段更新会话（用于归档等）。
     * PATCH /session/{sessionId}
     */
    suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session

    suspend fun interruptSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean

    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff>

    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session

    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session

    suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean

    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session

    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session

    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String? = null): Session

    suspend fun importSession(conn: ServerConnection, shareUrl: String): Session

    suspend fun executeCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        arguments: String = "",
        directory: String? = null,
        agent: String? = null,
        model: String? = null,
        variant: String? = null,
        parts: List<Map<String, String>>? = null
    ): Boolean

    suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session>

    suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem>

    /**
     * 将当前会话所有前台可后台化工具（subagent）批量转为后台（V2）。
     * V1 不支持（返回 false）。
     */
    suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean

    /**
     * 活跃会话查询（V2）：返回前台活跃会话 ID → 类型（"running" 等）。
     * V1 不支持（返回空）。
     */
    suspend fun activeSessions(conn: ServerConnection): Map<String, ActiveSessionInfo>

    suspend fun listSessionStatus(conn: ServerConnection, directory: String? = null): Map<String, SessionStatusInfo>

    suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String? = null
    ): Result<Map<String, RestSessionStatusInfo>>
}

/**
 * C1-2（2026-08-26 架构走查，Q2-a）：分发层收缩为单点路由 + 逐方法单行委托。
 * [V1ApiClient]/[V2ApiClient] 已直接实现 [SessionApi]（含 V1 的
 * backgroundSession=false / activeSessions=emptyMap 降级），本类不再逐方法
 * if (conn.apiVersion.isV2) 分发。
 */
@Singleton
class SessionApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : SessionApi {

    private fun pick(conn: ServerConnection): SessionApi =
        if (conn.apiVersion.isV2) v2 else v1

    override suspend fun listSessions(
        conn: ServerConnection,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int
    ): List<Session> = pick(conn).listSessions(conn, directory, search, cursor, limit)

    override suspend fun getSession(conn: ServerConnection, sessionId: String): Session =
        pick(conn).getSession(conn, sessionId)

    override suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String =
        pick(conn).getSessionRaw(conn, sessionId)

    override suspend fun createSession(
        conn: ServerConnection,
        title: String?,
        parentId: String?,
        directory: String?
    ): Session = pick(conn).createSession(conn, title, parentId, directory)

    override suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean =
        pick(conn).deleteSession(conn, sessionId)

    override suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session =
        pick(conn).renameSession(conn, sessionId, title)

    override suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session = pick(conn).updateSessionFields(conn, sessionId, fields)

    override suspend fun interruptSession(conn: ServerConnection, sessionId: String, directory: String?): Boolean =
        pick(conn).interruptSession(conn, sessionId, directory)

    override suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> =
        pick(conn).getSessionDiff(conn, sessionId)

    override suspend fun shareSession(conn: ServerConnection, sessionId: String): Session =
        pick(conn).shareSession(conn, sessionId)

    override suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session =
        pick(conn).unshareSession(conn, sessionId)

    override suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean = pick(conn).compactSession(conn, sessionId, providerId, modelId)

    override suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session =
        pick(conn).revertSession(conn, sessionId, messageId)

    override suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session =
        pick(conn).unrevertSession(conn, sessionId)

    override suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session =
        pick(conn).forkSession(conn, sessionId, messageId)

    override suspend fun importSession(conn: ServerConnection, shareUrl: String): Session =
        pick(conn).importSession(conn, shareUrl)

    override suspend fun executeCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        arguments: String,
        directory: String?,
        agent: String?,
        model: String?,
        variant: String?,
        parts: List<Map<String, String>>?
    ): Boolean = pick(conn).executeCommand(conn, sessionId, command, arguments, directory, agent, model, variant, parts)

    override suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> =
        pick(conn).listSessionChildren(conn, sessionId)

    override suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> =
        pick(conn).getSessionTodos(conn, sessionId)

    override suspend fun listSessionStatus(conn: ServerConnection, directory: String?): Map<String, SessionStatusInfo> =
        pick(conn).listSessionStatus(conn, directory)

    /**
     * C8（2026-08-26）：错误分类学接线——V1/V2 实现返回的 Result 失败值在分发层
     * 统一翻译为 ApiError taxonomy（recoverCatching { throw e.asApiError() }，
     * GitHub asGitHubError 同款边缘翻译）+ 分类日志。成功语义与 Result 返回类型不变
     *（消费方 getOrNull/getOrDefault 不受影响；onFailure 可按 isTransient 分支）。
     */
    override suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String?
    ): Result<Map<String, RestSessionStatusInfo>> {
        val result = pick(conn).fetchSessionStatus(conn, directory)
        return result.recoverCatching { e ->
            val apiError = e.asApiError()
            logApiError(TAG, apiError, "fetchSessionStatus v2=${conn.apiVersion.isV2} dir=$directory", e)
            throw apiError
        }
    }

    override suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean =
        pick(conn).backgroundSession(conn, sessionId)

    override suspend fun activeSessions(conn: ServerConnection): Map<String, ActiveSessionInfo> =
        pick(conn).activeSessions(conn)
}
