package dev.leonardo.ocbeacon.data.api.session

import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class SessionApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : SessionApi {

    override suspend fun listSessions(
        conn: ServerConnection,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int
    ): List<Session> =
        if (conn.apiVersion.isV2) v2.listSessions(conn, directory, search, cursor, limit)
        else v1.listSessions(conn, directory, search, cursor, limit)

    override suspend fun getSession(conn: ServerConnection, sessionId: String): Session =
        if (conn.apiVersion.isV2) v2.getSession(conn, sessionId) else v1.getSession(conn, sessionId)

    override suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String =
        if (conn.apiVersion.isV2) v2.getSessionRaw(conn, sessionId) else v1.getSessionRaw(conn, sessionId)

    override suspend fun createSession(
        conn: ServerConnection,
        title: String?,
        parentId: String?,
        directory: String?
    ): Session =
        if (conn.apiVersion.isV2) v2.createSession(conn, title, parentId, directory)
        else v1.createSession(conn, title, parentId, directory)

    override suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean =
        if (conn.apiVersion.isV2) v2.deleteSession(conn, sessionId) else v1.deleteSession(conn, sessionId)

    override suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session =
        if (conn.apiVersion.isV2) v2.renameSession(conn, sessionId, title) else v1.renameSession(conn, sessionId, title)

    override suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session =
        if (conn.apiVersion.isV2) v2.updateSessionFields(conn, sessionId, fields)
        else v1.updateSessionFields(conn, sessionId, fields)

    override suspend fun interruptSession(conn: ServerConnection, sessionId: String, directory: String?): Boolean =
        if (conn.apiVersion.isV2) v2.interruptSession(conn, sessionId, directory)
        else v1.interruptSession(conn, sessionId, directory)

    override suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> =
        if (conn.apiVersion.isV2) v2.getSessionDiff(conn, sessionId) else v1.getSessionDiff(conn, sessionId)

    override suspend fun shareSession(conn: ServerConnection, sessionId: String): Session =
        if (conn.apiVersion.isV2) v2.shareSession(conn, sessionId) else v1.shareSession(conn, sessionId)

    override suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session =
        if (conn.apiVersion.isV2) v2.unshareSession(conn, sessionId) else v1.unshareSession(conn, sessionId)

    override suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean =
        if (conn.apiVersion.isV2) v2.compactSession(conn, sessionId, providerId, modelId)
        else v1.compactSession(conn, sessionId, providerId, modelId)

    override suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session =
        if (conn.apiVersion.isV2) v2.revertSession(conn, sessionId, messageId)
        else v1.revertSession(conn, sessionId, messageId)

    override suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session =
        if (conn.apiVersion.isV2) v2.unrevertSession(conn, sessionId) else v1.unrevertSession(conn, sessionId)

    override suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session =
        if (conn.apiVersion.isV2) v2.forkSession(conn, sessionId, messageId)
        else v1.forkSession(conn, sessionId, messageId)

    override suspend fun importSession(conn: ServerConnection, shareUrl: String): Session =
        if (conn.apiVersion.isV2) v2.importSession(conn, shareUrl) else v1.importSession(conn, shareUrl)

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
    ): Boolean =
        if (conn.apiVersion.isV2) v2.executeCommand(conn, sessionId, command, arguments, directory, agent, model, variant, parts)
        else v1.executeCommand(conn, sessionId, command, arguments, directory, agent, model, variant, parts)

    override suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> =
        if (conn.apiVersion.isV2) v2.listSessionChildren(conn, sessionId)
        else v1.listSessionChildren(conn, sessionId)

    override suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> =
        if (conn.apiVersion.isV2) v2.getSessionTodos(conn, sessionId) else v1.getSessionTodos(conn, sessionId)

    override suspend fun listSessionStatus(conn: ServerConnection, directory: String?): Map<String, SessionStatusInfo> =
        if (conn.apiVersion.isV2) v2.listSessionStatus(conn, directory) else v1.listSessionStatus(conn, directory)

    override suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String?
    ): Result<Map<String, RestSessionStatusInfo>> =
        if (conn.apiVersion.isV2) v2.fetchSessionStatus(conn, directory) else v1.fetchSessionStatus(conn, directory)

    override suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean =
        if (conn.apiVersion.isV2) v2.backgroundSession(conn, sessionId) else false

    override suspend fun activeSessions(conn: ServerConnection): Map<String, ActiveSessionInfo> =
        if (conn.apiVersion.isV2) v2.activeSessions(conn) else emptyMap()
}
