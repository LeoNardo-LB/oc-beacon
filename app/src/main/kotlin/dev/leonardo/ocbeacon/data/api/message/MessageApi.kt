package dev.leonardo.ocbeacon.data.api.message

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

interface MessageApi {
    suspend fun listMessages(conn: ServerConnection, sessionId: String, limit: Int? = null): List<MessageWithParts>

    /** Returns messages as raw JSON string (for export without re-serialization). */
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
     * Delete a message from a session.
     * DELETE /session/{sessionId}/message/{messageId}
     */
    suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean

    /**
     * Delete a specific part from a message by index.
     * DELETE /session/{sessionId}/message/{messageId}/part/{partIndex}
     */
    suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean

    /**
     * Reply to a permission request.
     * POST /permission/{requestID}/reply
     * Body: { reply: "once" | "always" | "reject", message?: string }
     */
    suspend fun replyToPermission(
        conn: ServerConnection,
        requestId: String,
        reply: String, // "once", "always", or "reject"
        message: String? = null,
        directory: String? = null
    ): Boolean

    /**
     * List pending permission requests.
     * GET /permission
     */
    suspend fun listPendingPermissions(conn: ServerConnection, directory: String? = null): List<PermissionRequest>

    /**
     * Reply to a question request.
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
     * Reject a question request.
     * POST /question/{requestID}/reject
     */
    suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String? = null
    ): Boolean

    /**
     * List pending question requests.
     * GET /question
     */
    suspend fun listPendingQuestions(conn: ServerConnection, directory: String? = null): List<QuestionRequest>
}

@Singleton
class MessageApiImpl @Inject constructor(
    private val apiClient: ApiClient
) : MessageApi {

    companion object {
        private const val TAG = "MessageApi"
    }

    private val httpClient get() = apiClient.httpClient
    private val json get() = apiClient.json

    override suspend fun listMessages(conn: ServerConnection, sessionId: String, limit: Int?): List<MessageWithParts> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
            limit?.let { parameter("limit", it) }
        }.body()
    }

    /** Returns messages as raw JSON string (for export without re-serialization). */
    override suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    /**
     * Stream session export JSON directly to an OutputStream.
     * Writes: {"info":<session>,"messages":<messages>}
     * Uses raw OkHttp for the messages request to enable true streaming
     * (Ktor's ContentNegotiation plugin buffers the entire response).
     * @param onProgress called with bytes written so far
     */
    override suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit
    ) {
        var bytesWritten = 0L
        // Write session info (small, safe to hold in memory)
        val sessionJson = httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val header = """{"info":$sessionJson,"messages":"""
        outputStream.write(header.toByteArray())
        bytesWritten += header.toByteArray().size
        outputStream.flush()
        onProgress(bytesWritten)

        // Stream messages via raw OkHttp to get true byte-level streaming
        val okClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url("${conn.baseUrl}/session/$sessionId/message")
            .apply { conn.authHeader?.let { addHeader("Authorization", it) } }
            .build()

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            okClient.newCall(request).execute().use { response ->
                val body = response.body
                val source = body.source()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress(bytesWritten)
                }
            }
        }

        outputStream.write("}".toByteArray())
        bytesWritten += 1
        outputStream.flush()
        onProgress(bytesWritten)
    }

    override suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Send a prompt asynchronously (fire-and-forget).
     * Returns 204 No Content immediately.
     * @param directory The session's working directory, sent as x-opencode-directory header
     *                  so the server resolves the correct project context.
     */
    override suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ) {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/prompt_async") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(PromptRequest(
                parts = parts,
                model = model,
                agent = agent,
                variant = variant
            ))
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("prompt_async failed: ${response.status}")
        }
    }

    /**
     * Delete a message from a session.
     * DELETE /session/{sessionId}/message/{messageId}
     */
    override suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    /**
     * Delete a specific part from a message by index.
     * DELETE /session/{sessionId}/message/{messageId}/part/{partIndex}
     */
    override suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId/message/$messageId/part/$partIndex") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    /**
     * Reply to a permission request.
     * POST /permission/{requestID}/reply
     * Body: { reply: "once" | "always" | "reject", message?: string }
     */
    override suspend fun replyToPermission(
        conn: ServerConnection,
        requestId: String,
        reply: String, // "once", "always", or "reject"
        message: String?,
        directory: String?
    ): Boolean {
        val body = buildMap<String, String> {
            put("reply", reply)
            message?.let { put("message", it) }
        }
        val result = httpClient.post("${conn.baseUrl}/permission/$requestId/reply") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return result.status.isSuccess()
    }

    /**
     * List pending permission requests.
     * GET /permission
     */
    override suspend fun listPendingPermissions(conn: ServerConnection, directory: String?): List<PermissionRequest> {
        return httpClient.get("${conn.baseUrl}/permission") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    /**
     * Reply to a question request.
     * POST /question/{requestID}/reply
     * Body: { answers: string[][] }
     */
    override suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reply"
        val bodyJson = json.encodeToString(QuestionReplyBody.serializer(), QuestionReplyBody(answers = answers))
        if (BuildConfig.DEBUG) Log.d(TAG, "replyToQuestion: POST $url, directory=$directory, bodyJson=$bodyJson")
        val result = httpClient.post(url) {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            setBody(io.ktor.http.content.TextContent(bodyJson, ContentType.Application.Json))
        }
        val responseBody = result.bodyAsText()
        if (BuildConfig.DEBUG) Log.d(TAG, "replyToQuestion: status=${result.status}, responseBody=$responseBody")
        return result.status.isSuccess()
    }

    /**
     * Reject a question request.
     * POST /question/{requestID}/reject
     */
    override suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String?
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reject"
        if (BuildConfig.DEBUG) Log.d(TAG, "rejectQuestion: POST $url, directory=$directory")
        val result = httpClient.post(url) {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "rejectQuestion: status=${result.status}")
        return result.status.isSuccess()
    }

    /**
     * List pending question requests.
     * GET /question
     */
    override suspend fun listPendingQuestions(conn: ServerConnection, directory: String?): List<QuestionRequest> {
        return httpClient.get("${conn.baseUrl}/question") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }
}
