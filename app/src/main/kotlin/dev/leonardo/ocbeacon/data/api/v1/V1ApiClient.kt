package dev.leonardo.ocbeacon.data.api.v1

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "V1Api"

/**
 * V1 API 统一实现——封装所有 OpenCode V1 REST 端点调用。
 *
 * 与 [dev.leonardo.ocbeacon.data.api.v2.V2ApiClient] 对称，供各领域 `*ApiImpl`
 * 作为纯分发层委托。
 *
 * V1 关键特征：
 * - URL 无 /api 前缀
 * - 响应直接返回数据（无 data 包裹层）
 * - Session abort、patch title、prompt_async 等 V1 原生端点
 */
@Singleton
class V1ApiClient @Inject constructor(
    private val apiClient: ApiClient
) {
    private val httpClient get() = apiClient.httpClient
    private val json get() = apiClient.json

    // ============ Session ============

    suspend fun listSessions(
        conn: ServerConnection,
        directory: String? = null,
        search: String? = null,
        cursor: String? = null,
        limit: Int = 50
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

    suspend fun getSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    suspend fun createSession(
        conn: ServerConnection,
        title: String? = null,
        parentId: String? = null,
        directory: String? = null
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

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session {
        return httpClient.patch("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("title" to title))
        }.body()
    }

    suspend fun updateSessionFields(
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

    suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/abort") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return response.status.isSuccess()
    }

    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/diff") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.delete("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun summarizeSession(
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

    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/revert") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("messageID" to messageId))
        }.body()
    }

    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/unrevert") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session {
        val body = buildMap<String, String> {
            messageId?.let { put("messageID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session/$sessionId/fork") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    suspend fun importSession(conn: ServerConnection, shareUrl: String): Session {
        return httpClient.post("${conn.baseUrl}/session/import") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("url" to shareUrl))
        }.body()
    }

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

    suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/children") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/todo") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun listSessionStatus(conn: ServerConnection, directory: String? = null): Map<String, SessionStatusInfo> {
        return httpClient.get("${conn.baseUrl}/session/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String? = null
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

    // ============ Message ============

    suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null,
        before: String? = null
    ): MessagePage {
        val response = httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
            limit?.let { parameter("limit", it) }
            before?.let { parameter("before", it) }
        }
        // 防御（#87）：会话已删除/不存在（404）或服务器错误时返回空页——
        // 旧代码直接 body<List>() 会把 404 JSON 错误体（对象）按数组解析 →
        // JsonConvertException 刷日志（压测实测 302 次/25 分钟，5 秒周期 L2 stale 轮询）。
        if (!response.status.isSuccess()) {
            AppLogger.w(TAG, "listMessages failed: status=${response.status} session=$sessionId")
            return MessagePage(messages = emptyList(), nextCursor = null)
        }
        val messages = response.body<List<MessageWithParts>>()
        val nextCursor = response.headers["X-Next-Cursor"]
        return MessagePage(messages = messages, nextCursor = nextCursor)
    }

    suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    /**
     * 将会话导出 JSON 直接流式写入 OutputStream。
     * 写入：{"info":<session>,"messages":<messages>}
     * 使用原始 OkHttp 发起 messages 请求以实现真正的流式传输
     *（Ktor 的 ContentNegotiation 插件会缓冲整个响应）。
     */
    suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit = {}
    ) {
        var bytesWritten = 0L
        val sessionPath = "/session/$sessionId"
        val messagePath = "/session/$sessionId/message"
        // 写入会话信息（较小，可安全保存在内存中）
        val sessionJson = httpClient.get("${conn.baseUrl}$sessionPath") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val header = """{"info":$sessionJson,"messages":"""
        outputStream.write(header.toByteArray())
        bytesWritten += header.toByteArray().size
        outputStream.flush()
        onProgress(bytesWritten)

        // 通过原始 OkHttp 流式传输 messages 以获得真正的字节级流式传输
        val okClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url("${conn.baseUrl}$messagePath")
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

    suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun promptAsync(
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

    suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId/message/$messageId/part/$partIndex") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun replyToPermission(
        conn: ServerConnection,
        requestId: String,
        reply: String,
        message: String? = null,
        directory: String? = null
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

    suspend fun listPendingPermissions(conn: ServerConnection, directory: String? = null): List<PermissionRequest> {
        return httpClient.get("${conn.baseUrl}/permission") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reply"
        val bodyJson = json.encodeToString(QuestionReplyBody.serializer(), QuestionReplyBody(answers = answers))
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "replyToQuestion: POST $url, directory=$directory, bodyJson=$bodyJson")
        val result = httpClient.post(url) {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            setBody(io.ktor.http.content.TextContent(bodyJson, ContentType.Application.Json))
        }
        val responseBody = result.bodyAsText()
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "replyToQuestion: status=${result.status}, responseBody=$responseBody")
        return result.status.isSuccess()
    }

    suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String? = null
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reject"
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "rejectQuestion: POST $url, directory=$directory")
        val result = httpClient.post(url) {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "rejectQuestion: status=${result.status}")
        return result.status.isSuccess()
    }

    suspend fun listPendingQuestions(conn: ServerConnection, directory: String? = null): List<QuestionRequest> {
        return httpClient.get("${conn.baseUrl}/question") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    // ============ System ============

    suspend fun getHealth(conn: ServerConnection): ServerHealth {
        return httpClient.get("${conn.baseUrl}/global/health") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return httpClient.get("${conn.baseUrl}/path") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun listAgents(conn: ServerConnection): List<AgentInfo> {
        return httpClient.get("${conn.baseUrl}/agent") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun listCommands(conn: ServerConnection): List<CommandInfo> {
        return httpClient.get("${conn.baseUrl}/command") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun listSkills(conn: ServerConnection, directory: String? = null): List<SkillInfo> {
        return httpClient.get("${conn.baseUrl}/skill") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> {
        return httpClient.get("${conn.baseUrl}/mcp") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/connect") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/disconnect") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Provider / Config ============

    suspend fun getProviders(conn: ServerConnection): ProvidersResponse {
        return httpClient.get("${conn.baseUrl}/config/providers") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse {
        return httpClient.get("${conn.baseUrl}/provider") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> {
        return httpClient.get("${conn.baseUrl}/provider/auth") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization? {
        val response = httpClient.post("${conn.baseUrl}/provider/$providerId/oauth/authorize") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("method" to methodIndex))
        }
        val body = response.bodyAsText().trim()
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "authorizeProviderOauth: status=${response.status} body=$body")
        }

        if (!response.status.isSuccess()) return null
        if (body.isBlank() || body == "null") return ProviderOauthAuthorization()

        return runCatching {
            json.decodeFromString(ProviderOauthAuthorization.serializer(), body)
        }.getOrElse {
            // 某些服务器版本在 headless 模式下返回空对象。
            ProviderOauthAuthorization()
        }
    }

    suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String? = null
    ): Boolean {
        val body = if (code != null) mapOf("method" to methodIndex, "code" to code)
        else mapOf("method" to methodIndex)
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "completeProviderOauth: POST /provider/$providerId/oauth/callback body=$body")
        val response = httpClient.post("${conn.baseUrl}/provider/$providerId/oauth/callback") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (BuildConfig.DEBUG) {
            val responseBody = response.bodyAsText()
            AppLogger.d(TAG, "completeProviderOauth: status=${response.status}, body=$responseBody")
        }
        return response.status.isSuccess()
    }

    suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean {
        val response = httpClient.put("${conn.baseUrl}/auth/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("type" to "api", "key" to apiKey))
        }
        return response.status.isSuccess()
    }

    suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "removeProviderAuth: DELETE ${conn.baseUrl}/auth/$providerId")
        val response = httpClient.delete("${conn.baseUrl}/auth/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (BuildConfig.DEBUG) {
            val body = response.bodyAsText()
            AppLogger.d(TAG, "removeProviderAuth: status=${response.status}, body=$body")
        }
        return response.status.isSuccess()
    }

    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    suspend fun disposeGlobal(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/global/dispose") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun disposeInstance(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/instance/dispose") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    // ============ File / VCS ============

    suspend fun findFiles(
        conn: ServerConnection,
        query: String,
        type: String? = null,
        directory: String? = null,
        limit: Int? = null,
        dirs: String? = null
    ): List<String> {
        return httpClient.get("${conn.baseUrl}/find/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("query", query)
            type?.let { parameter("type", it) }
            limit?.let { parameter("limit", it) }
            dirs?.let { parameter("dirs", it) }
        }.body()
    }

    suspend fun readFile(conn: ServerConnection, path: String, directory: String? = null): FileContentDto {
        return httpClient.get("${conn.baseUrl}/file/content") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", path)
        }.body()
    }

    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> {
        return httpClient.get("${conn.baseUrl}/find") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("pattern", pattern)
        }.body()
    }

    suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean {
        val response = httpClient.get("${conn.baseUrl}/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", "")
        }
        return response.status.isSuccess()
    }

    suspend fun listDirectory(conn: ServerConnection, path: String, directory: String? = null): List<FileNodeDto> {
        val response = httpClient.get("${conn.baseUrl}/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            return emptyList()
        }
        return response.body()
    }

    suspend fun findSymbols(conn: ServerConnection, query: String, directory: String? = null): List<SymbolInfo> {
        return httpClient.get("${conn.baseUrl}/find/symbol") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("query", query)
        }.body()
    }

    suspend fun getFileStatus(conn: ServerConnection, directory: String? = null): List<FileStatusInfo> {
        return httpClient.get("${conn.baseUrl}/file/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    suspend fun getVcs(conn: ServerConnection, directory: String? = null): VcsBranchDto {
        return httpClient.get("${conn.baseUrl}/vcs") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    suspend fun getVcsStatus(conn: ServerConnection, directory: String? = null): List<VcsChangeDto> {
        return httpClient.get("${conn.baseUrl}/vcs/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int = 3, directory: String? = null): List<FileDiffDto> {
        return httpClient.get("${conn.baseUrl}/vcs/diff") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("mode", mode)
            parameter("context", context)
        }.body()
    }

    suspend fun listProjects(conn: ServerConnection): List<Project> {
        return httpClient.get("${conn.baseUrl}/project") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getCurrentProject(conn: ServerConnection): Project {
        return httpClient.get("${conn.baseUrl}/project/current") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Terminal / Pty ============

    suspend fun createPty(
        conn: ServerConnection,
        title: String? = null,
        cwd: String? = null,
        directory: String? = null
    ): PtyInfo {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "createPty: POST ${conn.baseUrl}/pty title=$title cwd=$cwd directory=$directory")
        }
        val response = httpClient.post("${conn.baseUrl}/pty") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(PtyCreateRequest(title = title, cwd = cwd))
        }
        val body = response.bodyAsText()
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "createPty: response status=${response.status} body=$body")
        }
        if (!response.status.isSuccess()) {
            throw java.io.IOException("createPty failed: ${response.status}: $body")
        }

        val info = parsePtyInfoFromCreateResponse(body, title, cwd)
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "createPty: response status=${response.status} ptyId=${info.id}")
        }
        return info
    }

    private fun parsePtyInfoFromCreateResponse(body: String, title: String?, cwd: String?): PtyInfo {
        val trimmed = body.trim()

        // 大多数服务器返回完整的 PtyInfo 对象。
        runCatching { return json.decodeFromString(PtyInfo.serializer(), trimmed) }

        // 某些本地构建仅返回 id 或将其包装在 data/pty 中。
        val id = extractPtyIdFromResponse(trimmed)
            ?: throw java.io.IOException("createPty: could not parse PTY id from response: $trimmed")

        return PtyInfo(
            id = id,
            title = title ?: "Tab",
            command = "/bin/sh",
            args = emptyList(),
            cwd = cwd ?: "/",
            status = "running",
            pid = 0,
        )
    }

    private fun extractPtyIdFromResponse(responseBody: String): String? {
        // 原始字符串 id："pty_xxx" 或 pty_xxx
        val plain = responseBody.removeSurrounding("\"").trim()
        if (plain.startsWith("pty_")) return plain

        return runCatching {
            val root = json.parseToJsonElement(responseBody)
            findPtyId(root)
        }.getOrNull()
    }

    private fun findPtyId(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return null

        obj["id"]?.jsonPrimitive?.contentOrNull?.let {
            if (it.startsWith("pty_")) return it
        }

        obj["pty"]?.let { nested ->
            findPtyId(nested)?.let { return it }
        }
        obj["data"]?.let { nested ->
            findPtyId(nested)?.let { return it }
        }
        obj["result"]?.let { nested ->
            findPtyId(nested)?.let { return it }
        }

        return null
    }

    suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/pty/$ptyId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun updatePtySize(
        conn: ServerConnection,
        ptyId: String,
        cols: Int,
        rows: Int,
        directory: String? = null
    ): Boolean {
        val body = PtyUpdateRequest(size = PtySize(rows = rows, cols = cols))
        if (BuildConfig.DEBUG) {
            val jsonStr = json.encodeToString(PtyUpdateRequest.serializer(), body)
            AppLogger.d(TAG, "updatePtySize: PUT ${conn.baseUrl}/pty/$ptyId body=$jsonStr directory=$directory")
        }
        val response = httpClient.put("${conn.baseUrl}/pty/$ptyId") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (BuildConfig.DEBUG) {
            val respBody = try { response.bodyAsText() } catch (_: Exception) { "<no body>" }
            AppLogger.d(TAG, "updatePtySize: response status=${response.status} body=$respBody")
        }
        return response.status.isSuccess()
    }

    suspend fun openPtySocket(
        conn: ServerConnection,
        ptyId: String,
        cursor: Int = -1,
        directory: String? = null
    ): PtySocket {
        val wsBase = when {
            conn.baseUrl.startsWith("https://") -> conn.baseUrl.replaceFirst("https://", "wss://")
            conn.baseUrl.startsWith("http://") -> conn.baseUrl.replaceFirst("http://", "ws://")
            else -> conn.baseUrl
        }
        val session = httpClient.webSocketSession {
            method = HttpMethod.Get
            url("$wsBase/pty/$ptyId/connect?cursor=$cursor")
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return PtySocket(session)
    }

    suspend fun listPtyShells(conn: ServerConnection, directory: String? = null): List<ShellInfo> {
        return httpClient.get("${conn.baseUrl}/pty/shells") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection? = null,
        directory: String? = null
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/shell") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(
                ShellRequest(
                    agent = agent,
                    model = model,
                    command = command
                )
            )
        }
        return response.status.isSuccess()
    }
}
