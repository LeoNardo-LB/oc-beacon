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

    /** Returns session info as raw JSON string (for export without re-serialization). */
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
     * Update session with arbitrary fields (for archive, etc.).
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

    /** Returns session info as raw JSON string (for export without re-serialization). */
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
     * Update session with arbitrary fields (for archive, etc.).
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
     * Share a session, creating a shareable link.
     * POST /session/{sessionId}/share
     */
    override suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Unshare a session, removing the shareable link.
     * DELETE /session/{sessionId}/share
     */
    override suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.delete("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Summarize (compact) a session to reduce context.
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
     * Revert (undo) messages starting from the given messageId.
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
     * Unrevert (redo) the last reverted message in a session.
     * POST /session/{sessionId}/unrevert
     */
    override suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/unrevert") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Fork a session (create a new session from a message point).
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
     * Import a session from a share URL.
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
     * Execute a server-side command in a session.
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
     * List child sessions of a session.
     * GET /session/{sessionId}/children
     */
    override suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/children") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get session todo list.
     * GET /session/{sessionId}/todo
     */
    override suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/todo") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Batch get session statuses.
     * GET /session/status
     */
    override suspend fun listSessionStatus(conn: ServerConnection, directory: String?): Map<String, SessionStatusInfo> {
        return httpClient.get("${conn.baseUrl}/session/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    /**
     * Query the current status of all sessions from the OpenCode server.
     * GET /session/status
     *
     * Used as a REST fallback when SSE events may have been missed
     * (app backgrounded, connection lost, etc.).
     *
     * @return Map of sessionId → RestSessionStatusInfo where type ∈ {"idle", "busy", "retry"}
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
