package dev.leonardo.ocbeacon.data.api.session

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session

    /**
     * 用任意字段更新会话（用于归档等）。
     * PATCH /session/{sessionId}
     */
    suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session

    suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean

    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff>

    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session

    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session

    suspend fun summarizeSession(
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

    suspend fun listSessionStatus(conn: ServerConnection, directory: String? = null): Map<String, SessionStatusInfo>

    suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String? = null
    ): Result<Map<String, RestSessionStatusInfo>>
}

@Singleton
class SessionApiImpl @Inject constructor(
    private val apiClient: ApiClient
) : SessionApi {

    private val httpClient get() = apiClient.httpClient

    override suspend fun listSessions(
        conn: ServerConnection,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int
    ): List<Session> {
        return httpClient.get("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("roots", "true")
            search?.let { parameter("search", it) }
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }.body()
    }

    override suspend fun getSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /** 以原始 JSON 字符串返回会话信息（用于导出而无需重新序列化）。 */
    override suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    override suspend fun createSession(
        conn: ServerConnection,
        title: String?,
        parentId: String?,
        directory: String?
    ): Session {
        val body = buildMap<String, String> {
            title?.let { put("title", it) }
            parentId?.let { put("parentID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    override suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    override suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session {
        return httpClient.patch("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("title" to title))
        }.body()
    }

    /**
     * 用任意字段更新会话（用于归档等）。
     * PATCH /session/{sessionId}
     */
    override suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session {
        return httpClient.patch("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(fields)
        }.body()
    }

    override suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String?): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/abort") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return response.status.isSuccess()
    }

    override suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/diff") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 分享会话，创建可分享链接。
     * POST /session/{sessionId}/share
     */
    override suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 取消分享会话，移除可分享链接。
     * DELETE /session/{sessionId}/share
     */
    override suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.delete("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 总结（压缩）会话以减少上下文。
     * POST /session/{sessionId}/summarize
     */
    override suspend fun summarizeSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/summarize") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("providerID" to providerId, "modelID" to modelId))
        }
        return response.status.isSuccess()
    }

    /**
     * 从给定 messageId 开始回退（撤销）消息。
     * POST /session/{sessionId}/revert
     */
    override suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/revert") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("messageID" to messageId))
        }.body()
    }

    /**
     * 取消回退（重做）会话中最后一条被回退的消息。
     * POST /session/{sessionId}/unrevert
     */
    override suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/unrevert") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 分叉会话（从某条消息点创建新会话）。
     * POST /session/{sessionId}/fork
     */
    override suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session {
        val body = buildMap<String, String> {
            messageId?.let { put("messageID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session/$sessionId/fork") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    /**
     * 从分享 URL 导入会话。
     * POST /session/import
     */
    override suspend fun importSession(conn: ServerConnection, shareUrl: String): Session {
        return httpClient.post("${conn.baseUrl}/session/import") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("url" to shareUrl))
        }.body()
    }

    /**
     * 在会话中执行服务端命令。
     * POST /session/{sessionId}/command
     * Body: { command: String, arguments: String, agent?, model?, variant?, parts? }
     */
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
    ): Boolean {
        val body = mutableMapOf<String, Any>("command" to command, "arguments" to arguments)
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/command") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return response.status.isSuccess()
    }

    /**
     * 列出会话的子会话。
     * GET /session/{sessionId}/children
     */
    override suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/children") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 获取会话 todo 列表。
     * GET /session/{sessionId}/todo
     */
    override suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/todo") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 批量获取会话状态。
     * GET /session/status
     */
    override suspend fun listSessionStatus(conn: ServerConnection, directory: String?): Map<String, SessionStatusInfo> {
        return httpClient.get("${conn.baseUrl}/session/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    /**
     * 从 OpenCode 服务器查询所有会话的当前状态。
     * GET /session/status
     *
     * 在可能错过 SSE 事件时（应用进入后台、连接丢失等）
     * 用作 REST 回退。
     *
     * @return sessionId → RestSessionStatusInfo 的映射，其中 type ∈ {"idle", "busy", "retry"}
     */
    override suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String?
    ): Result<Map<String, RestSessionStatusInfo>> {
        return runCatching {
            val response: Map<String, JsonObject> =
                httpClient.get("${conn.baseUrl}/session/status") {
                    conn.authHeader?.let { header("Authorization", it) }
                    directoryHeader(directory)
                }.body()
            response.mapValues { (_, obj) ->
                RestSessionStatusInfo(
                    type = obj["type"]?.jsonPrimitive?.content ?: "idle",
                    attempt = obj["attempt"]?.jsonPrimitive?.intOrNull,
                    message = obj["message"]?.jsonPrimitive?.contentOrNull,
                    next = obj["next"]?.jsonPrimitive?.longOrNull
                )
            }
        }
    }
}
