package dev.leonardo.ocbeacon.data.api.v1

import dev.leonardo.ocbeacon.data.api.auth

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.apiCall
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.api.logApiError
import dev.leonardo.ocbeacon.data.api.toApiError
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.message.PromptAdmission
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
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
import kotlinx.serialization.builtins.ListSerializer
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
 * 与 [dev.leonardo.ocbeacon.data.api.v2.V2ApiClient] 对称。
 *
 * C1-2/C1-3（2026-08-26 架构走查，Q2-a）：直接实现 [SessionApi] 与
 * [MessageApi]——域 Impl 退化为单点 pick + 逐方法委托，本类承担 V1 侧
 * 真实适配（含 backgroundSession/activeSessions 的 V1 降级；V1 契约里
 * replyToPermission/replyToQuestion/rejectQuestion 的会话定位参数仅满足
 * 域接口签名，V1 路径不使用）。
 *
 * V1 关键特征：
 * - URL 无 /api 前缀
 * - 响应直接返回数据（无 data 包裹层）
 * - Session abort、patch title、prompt_async 等 V1 原生端点
 */
@Singleton
class V1ApiClient @Inject constructor(
    private val apiClient: ApiClient
) : SessionApi, MessageApi, SystemApi, TerminalApi {
    private val httpClient get() = apiClient.httpClient
    private val json get() = apiClient.json

    // ============ Session ============

    override suspend fun listSessions(
        conn: ServerConnection,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int
    ): List<Session> {
        // D2-22（#121，2026-08-19）：V1 接入 HTML 防御——版本误判（V2 服务器
        // + V1 路径）时 SPA fallback 返回 HTML（HTTP 200），无防御时
        // .body<List<Session>>() 抛 ContentTransformationException 难定位。
        val bodyText = httpClient.get("${conn.baseUrl}/session") {
            auth(conn)
            directoryHeader(directory)
            parameter("roots", "true")
            search?.let { parameter("search", it) }
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }.bodyAsText()
        dev.leonardo.ocbeacon.data.api.rejectHtmlResponse(bodyText, TAG)
        return json.decodeFromString(ListSerializer(Session.serializer()), bodyText)
    }

    override suspend fun getSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            auth(conn)
        }.body()
    }

    override suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            auth(conn)
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
            auth(conn)
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    override suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    override suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session {
        return httpClient.patch("${conn.baseUrl}/session/$sessionId") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("title" to title))
        }.body()
    }

    override suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session {
        return httpClient.patch("${conn.baseUrl}/session/$sessionId") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(fields)
        }.body()
    }

    override suspend fun interruptSession(conn: ServerConnection, sessionId: String, directory: String?): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/abort") {
            auth(conn)
            directoryHeader(directory)
        }
        return response.status.isSuccess()
    }

    override suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/diff") {
            auth(conn)
        }.body()
    }

    override suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/share") {
            auth(conn)
        }.body()
    }

    override suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.delete("${conn.baseUrl}/session/$sessionId/share") {
            auth(conn)
        }.body()
    }

    override suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/summarize") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("providerID" to providerId, "modelID" to modelId))
        }
        return response.status.isSuccess()
    }

    override suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/revert") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("messageID" to messageId))
        }.body()
    }

    override suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/unrevert") {
            auth(conn)
        }.body()
    }

    override suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session {
        val body = buildMap<String, String> {
            messageId?.let { put("messageID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session/$sessionId/fork") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    override suspend fun importSession(conn: ServerConnection, shareUrl: String): Session {
        return httpClient.post("${conn.baseUrl}/session/import") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("url" to shareUrl))
        }.body()
    }

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
        // #200 F03：可选字段非空才进请求体（V1 契约 CommandPayload；空值省略与原行为一致）
        val body = mutableMapOf<String, Any>("command" to command, "arguments" to arguments)
        agent?.let { body["agent"] = it }
        model?.let { body["model"] = it }
        variant?.let { body["variant"] = it }
        parts?.let { body["parts"] = it }
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/command") {
            auth(conn)
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return response.status.isSuccess()
    }

    override suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/children") {
            auth(conn)
        }.body()
    }

    override suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/todo") {
            auth(conn)
        }.body()
    }

    override suspend fun listSessionStatus(conn: ServerConnection, directory: String?): Map<String, SessionStatusInfo> {
        return httpClient.get("${conn.baseUrl}/session/status") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    override suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String?
    ): Result<Map<String, RestSessionStatusInfo>> {
        return runCatching {
            val response: Map<String, JsonObject> =
                httpClient.get("${conn.baseUrl}/session/status") {
                    auth(conn)
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

    /** V1 不支持后台化（原 SessionApiImpl 降级逻辑下沉，C1-2）——恒 false。 */
    override suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean = false

    /** V1 无活跃会话端点（原 SessionApiImpl 降级逻辑下沉，C1-2）——恒空。 */
    override suspend fun activeSessions(conn: ServerConnection): Map<String, ActiveSessionInfo> = emptyMap()

    // ============ Message ============

    override suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int?,
        before: String?
    ): MessagePage = apiCall(TAG, "listMessages session=$sessionId") {
        val response = httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            auth(conn)
            limit?.let { parameter("limit", it) }
            before?.let { parameter("before", it) }
        }
        // 防御（#87）：会话已删除/不存在（404）或服务器错误时返回空页——
        // 旧代码直接 body<List>() 会把 404 JSON 错误体（对象）按数组解析 →
        // JsonConvertException 刷日志（压测实测 302 次/25 分钟，5 秒周期 L2 stale 轮询）。
        // C8（2026-08-26）：非 2xx 升级为 ApiError 分类学日志（401/403/404/429/5xx
        // 精确分类 + isTransient），空页返回语义不变。
        if (!response.status.isSuccess()) {
            logApiError(TAG, response.toApiError(), "listMessages status=${response.status.value} session=$sessionId")
            return@apiCall MessagePage(messages = emptyList(), nextCursor = null)
        }
        val messages = response.body<List<MessageWithParts>>()
        // #230（对称防御，与 V2Mappers 同规则）：V1 服务器 parts 同样可能携带
        // SSE started 残留的空 text/reasoning——REST 快照零信息项在源头丢弃
        //（进会话 prefetch 会原样写 Room，绕过 merge 侧过滤；空 part 进 turn
        // 渐进测量还会引发 item 初测后大幅增长=渲染重叠）。REST 不收 delta 无副作用。
        val sanitized = messages.map { m ->
            m.copy(parts = m.parts.filter { p ->
                !((p is Part.Text && p.text.isBlank()) ||
                    (p is Part.Reasoning && p.text.isBlank()))
            })
        }
        val nextCursor = response.headers["X-Next-Cursor"]
        MessagePage(messages = sanitized, nextCursor = nextCursor)
    }

    override suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            auth(conn)
        }.bodyAsText()
    }

    /**
     * 将会话导出 JSON 直接流式写入 OutputStream——共享实现（C1-1），
     * 主体见 [dev.leonardo.ocbeacon.data.api.exportSessionToStream]。
     */
    override suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit
    ) = dev.leonardo.ocbeacon.data.api.exportSessionToStream(
        httpClient, conn, sessionId, outputStream, onProgress
    )

    override suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            auth(conn)
        }.body()
    }

    override suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ): PromptAdmission? {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/prompt_async") {
            auth(conn)
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
        // V1 prompt_async 为 204 无响应体——无法本地播种，依赖 SSE 回显
        return null
    }

    override suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    override suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId/message/$messageId/part/$partIndex") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    /** V1 契约为无会话前缀路径 POST /permission/{id}/reply——[sessionId] 仅满足域接口签名（C1-3），V1 忽略。 */
    override suspend fun replyToPermission(
        conn: ServerConnection,
        sessionId: String,
        requestId: String,
        reply: String,
        message: String?,
        directory: String?
    ): Boolean {
        val body = buildMap<String, String> {
            put("reply", reply)
            message?.let { put("message", it) }
        }
        val result = httpClient.post("${conn.baseUrl}/permission/$requestId/reply") {
            auth(conn)
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return result.status.isSuccess()
    }

    override suspend fun listPendingPermissions(conn: ServerConnection, directory: String?): List<PermissionRequest> {
        return httpClient.get("${conn.baseUrl}/permission") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    /** V1 路径 POST /question/{id}/reply 不需要会话定位——[question] 仅满足域接口签名（C1-3），V1 忽略。 */
    override suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
        question: SseEvent.QuestionAsked?
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reply"
        val bodyJson = json.encodeToString(QuestionReplyBody.serializer(), QuestionReplyBody(answers = answers))
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "replyToQuestion: POST $url, directory=$directory, bodyJson=$bodyJson")
        val result = httpClient.post(url) {
            auth(conn)
            directoryHeader(directory)
            setBody(io.ktor.http.content.TextContent(bodyJson, ContentType.Application.Json))
        }
        val responseBody = result.bodyAsText()
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "replyToQuestion: status=${result.status}, responseBody=$responseBody")
        return result.status.isSuccess()
    }

    /** V1 路径 POST /question/{id}/reject 不需要会话定位——[sessionId] 仅满足域接口签名（C1-3），V1 忽略。 */
    override suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String?,
        sessionId: String?
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reject"
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "rejectQuestion: POST $url, directory=$directory")
        val result = httpClient.post(url) {
            auth(conn)
            directoryHeader(directory)
        }
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "rejectQuestion: status=${result.status}")
        return result.status.isSuccess()
    }

    override suspend fun listPendingQuestions(conn: ServerConnection, directory: String?): List<QuestionRequest> {
        return httpClient.get("${conn.baseUrl}/question") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    // ============ System ============

    override suspend fun getHealth(conn: ServerConnection): ServerHealth {
        return httpClient.get("${conn.baseUrl}/global/health") {
            auth(conn)
        }.body()
    }

    override suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return httpClient.get("${conn.baseUrl}/path") {
            auth(conn)
        }.body()
    }

    override suspend fun listAgents(conn: ServerConnection): List<AgentInfo> {
        return httpClient.get("${conn.baseUrl}/agent") {
            auth(conn)
        }.body()
    }

    override suspend fun listCommands(conn: ServerConnection): List<CommandInfo> {
        return httpClient.get("${conn.baseUrl}/command") {
            auth(conn)
        }.body()
    }

    override suspend fun listSkills(conn: ServerConnection, directory: String?): List<SkillInfo> {
        return httpClient.get("${conn.baseUrl}/skill") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    override suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> {
        return httpClient.get("${conn.baseUrl}/mcp") {
            auth(conn)
        }.body()
    }

    override suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/connect") {
            auth(conn)
        }.body()
    }

    override suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/disconnect") {
            auth(conn)
        }.body()
    }

    // ============ Provider / Config ============

    suspend fun getProviders(conn: ServerConnection): ProvidersResponse {
        return httpClient.get("${conn.baseUrl}/config/providers") {
            auth(conn)
        }.body()
    }

    suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse {
        return httpClient.get("${conn.baseUrl}/provider") {
            auth(conn)
        }.body()
    }

    suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> {
        return httpClient.get("${conn.baseUrl}/provider/auth") {
            auth(conn)
        }.body()
    }

    suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization? {
        val response = httpClient.post("${conn.baseUrl}/provider/$providerId/oauth/authorize") {
            auth(conn)
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
            auth(conn)
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
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("type" to "api", "key" to apiKey))
        }
        return response.status.isSuccess()
    }

    suspend fun removeProviderCredential(conn: ServerConnection, providerId: String): Boolean {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "removeProviderCredential: DELETE ${conn.baseUrl}/auth/$providerId")
        val response = httpClient.delete("${conn.baseUrl}/auth/$providerId") {
            auth(conn)
        }
        if (BuildConfig.DEBUG) {
            val body = response.bodyAsText()
            AppLogger.d(TAG, "removeProviderCredential: status=${response.status}, body=$body")
        }
        return response.status.isSuccess()
    }

    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/config") {
            auth(conn)
        }.body()
    }

    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/global/config") {
            auth(conn)
        }.body()
    }

    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/config") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/global/config") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    suspend fun disposeGlobal(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/global/dispose") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    suspend fun disposeInstance(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/instance/dispose") {
            auth(conn)
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
            auth(conn)
            directoryHeader(directory)
            parameter("query", query)
            type?.let { parameter("type", it) }
            limit?.let { parameter("limit", it) }
            dirs?.let { parameter("dirs", it) }
        }.body()
    }

    suspend fun readFile(conn: ServerConnection, path: String, directory: String? = null): FileContentDto {
        return httpClient.get("${conn.baseUrl}/file/content") {
            auth(conn)
            directoryHeader(directory)
            parameter("path", path)
        }.body()
    }

    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> {
        return httpClient.get("${conn.baseUrl}/find") {
            auth(conn)
            parameter("pattern", pattern)
        }.body()
    }

    suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean {
        val response = httpClient.get("${conn.baseUrl}/file") {
            auth(conn)
            directoryHeader(directory)
            parameter("path", "")
        }
        return response.status.isSuccess()
    }

    suspend fun listDirectory(conn: ServerConnection, path: String, directory: String? = null): List<FileNodeDto> {
        val response = httpClient.get("${conn.baseUrl}/file") {
            auth(conn)
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
            auth(conn)
            directoryHeader(directory)
            parameter("query", query)
        }.body()
    }

    suspend fun getFileStatus(conn: ServerConnection, directory: String? = null): List<FileStatusInfo> {
        return httpClient.get("${conn.baseUrl}/file/status") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    suspend fun getVcs(conn: ServerConnection, directory: String? = null): VcsBranchDto {
        return httpClient.get("${conn.baseUrl}/vcs") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    suspend fun getVcsStatus(conn: ServerConnection, directory: String? = null): List<VcsChangeDto> {
        return httpClient.get("${conn.baseUrl}/vcs/status") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int = 3, directory: String? = null): List<FileDiffDto> {
        return httpClient.get("${conn.baseUrl}/vcs/diff") {
            auth(conn)
            directoryHeader(directory)
            parameter("mode", mode)
            parameter("context", context)
        }.body()
    }

    suspend fun listProjects(conn: ServerConnection): List<Project> {
        return httpClient.get("${conn.baseUrl}/project") {
            auth(conn)
        }.body()
    }

    suspend fun getCurrentProject(conn: ServerConnection): Project {
        return httpClient.get("${conn.baseUrl}/project/current") {
            auth(conn)
        }.body()
    }

    // ============ Terminal / Pty ============

    override suspend fun createPty(
        conn: ServerConnection,
        title: String?,
        cwd: String?,
        directory: String?
    ): PtyInfo {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "createPty: POST ${conn.baseUrl}/pty title=$title cwd=$cwd directory=$directory")
        }
        val response = httpClient.post("${conn.baseUrl}/pty") {
            auth(conn)
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

        val info = dev.leonardo.ocbeacon.data.api.parsePtyInfoFromCreateResponse(json, body, title, cwd)
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "createPty: response status=${response.status} ptyId=${info.id}")
        }
        return info
    }

    override suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/pty/$ptyId") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    override suspend fun updatePtySize(
        conn: ServerConnection,
        ptyId: String,
        cols: Int,
        rows: Int,
        directory: String?
    ): Boolean {
        val body = PtyUpdateRequest(size = PtySize(rows = rows, cols = cols))
        if (BuildConfig.DEBUG) {
            val jsonStr = json.encodeToString(PtyUpdateRequest.serializer(), body)
            AppLogger.d(TAG, "updatePtySize: PUT ${conn.baseUrl}/pty/$ptyId body=$jsonStr directory=$directory")
        }
        val response = httpClient.put("${conn.baseUrl}/pty/$ptyId") {
            auth(conn)
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

    override suspend fun openPtySocket(
        conn: ServerConnection,
        ptyId: String,
        cursor: Int,
        directory: String?
    ): PtySocket {
        val wsBase = when {
            conn.baseUrl.startsWith("https://") -> conn.baseUrl.replaceFirst("https://", "wss://")
            conn.baseUrl.startsWith("http://") -> conn.baseUrl.replaceFirst("http://", "ws://")
            else -> conn.baseUrl
        }
        val session = httpClient.webSocketSession {
            method = HttpMethod.Get
            url("$wsBase/pty/$ptyId/connect?cursor=$cursor")
            auth(conn)
            directoryHeader(directory)
        }
        return PtySocket(session)
    }

    override suspend fun listPtyShells(conn: ServerConnection, directory: String?): List<ShellInfo> {
        return httpClient.get("${conn.baseUrl}/pty/shells") {
            auth(conn)
            directoryHeader(directory)
        }.body()
    }

    override suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection?,
        directory: String?
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/shell") {
            auth(conn)
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
