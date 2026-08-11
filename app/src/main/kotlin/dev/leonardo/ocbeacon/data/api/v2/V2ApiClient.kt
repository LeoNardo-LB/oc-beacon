package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.dto.common.ModelSelection
import dev.leonardo.ocbeacon.data.dto.common.PtySocket
import dev.leonardo.ocbeacon.data.dto.request.PromptPart
import dev.leonardo.ocbeacon.data.dto.request.PtyCreateRequest
import dev.leonardo.ocbeacon.data.dto.request.PtySize
import dev.leonardo.ocbeacon.data.dto.request.PtyUpdateRequest
import dev.leonardo.ocbeacon.data.dto.request.QuestionReplyBody
import dev.leonardo.ocbeacon.data.dto.request.ServerConfigPatch
import dev.leonardo.ocbeacon.data.dto.request.ShellRequest
import dev.leonardo.ocbeacon.data.dto.response.AgentInfo
import dev.leonardo.ocbeacon.data.dto.response.CommandInfo
import dev.leonardo.ocbeacon.data.dto.response.FileContentDto
import dev.leonardo.ocbeacon.data.dto.response.FileDiffDto
import dev.leonardo.ocbeacon.data.dto.response.FileNodeDto
import dev.leonardo.ocbeacon.data.dto.response.FileStatusInfo
import dev.leonardo.ocbeacon.data.dto.response.McpStatusEntry
import dev.leonardo.ocbeacon.data.dto.response.PermissionRequest
import dev.leonardo.ocbeacon.data.dto.response.ProviderAuthMethod
import dev.leonardo.ocbeacon.data.dto.response.ProviderCatalogResponse
import dev.leonardo.ocbeacon.data.dto.response.ProviderOauthAuthorization
import dev.leonardo.ocbeacon.data.dto.response.PtyInfo
import dev.leonardo.ocbeacon.data.dto.response.QuestionRequest
import dev.leonardo.ocbeacon.data.dto.response.SearchMatchDto
import dev.leonardo.ocbeacon.data.dto.response.ServerConfigResponse
import dev.leonardo.ocbeacon.data.dto.response.ServerPaths
import dev.leonardo.ocbeacon.data.dto.response.SessionStatusInfo
import dev.leonardo.ocbeacon.data.dto.response.ShellInfo
import dev.leonardo.ocbeacon.data.dto.response.SkillInfo
import dev.leonardo.ocbeacon.data.dto.response.SymbolInfo
import dev.leonardo.ocbeacon.data.dto.response.TodoItem
import dev.leonardo.ocbeacon.data.dto.response.VcsBranchDto
import dev.leonardo.ocbeacon.data.dto.response.VcsChangeDto
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ShellOutput
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "V2Api"

/**
 * V2 API 统一实现——封装所有 OpenCode V2 REST 端点调用。
 *
 * V2 关键差异：
 * - URL 前缀：/api（所有端点）
 * - 响应格式：成功响应包裹在 { "data": <payload> } 中
 * - 列表响应：{ "data": [...], "cursor": { "previous": "...", "next": "..." } }
 * - 消息格式：用 type 判别联合，content 数组替代 parts
 * - Session abort → interrupt
 * - Session patch title → rename (POST /api/session/{id}/rename)
 * - 健康检查：GET /api/health（无 /global 前缀）
 */
@Singleton
class V2ApiClient @Inject constructor(
    private val apiClient: ApiClient
) {
    private val httpClient get() = apiClient.httpClient
    private val json get() = apiClient.json

    private fun parseRoot(bodyText: String): JsonObject =
        json.parseToJsonElement(bodyText).jsonObject

    // ============ Health ============

    suspend fun getHealth(conn: ServerConnection): ServerHealth {
        val response = httpClient.get("${conn.baseUrl}/api/health") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        val root = parseRoot(response.bodyAsText())
        return ServerHealth(
            healthy = root["healthy"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            version = root["version"]?.jsonPrimitive?.contentOrNull
        )
    }

    // ============ Session ============

    suspend fun listSessions(
        conn: ServerConnection,
        directory: String? = null,
        search: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ): List<Session> {
        val response = httpClient.get("${conn.baseUrl}/api/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            search?.let { parameter("search", it) }
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }
        val root = parseRoot(response.bodyAsText())
        val (items, _) = V2ResponseWrapper.unwrapList(root)
        return items.map { V2SessionMapper.toSession(it) }
    }

    suspend fun getSession(conn: ServerConnection, sessionId: String): Session {
        val response = httpClient.get("${conn.baseUrl}/api/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2SessionMapper.toSession(data)
    }

    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/api/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    suspend fun createSession(
        conn: ServerConnection,
        title: String? = null,
        parentId: String? = null,
        directory: String? = null
    ): Session {
        // 使用 JsonObject 构造避免 kotlinx 序列化的混合类型推断问题
        val bodyObj = buildMap<String, kotlinx.serialization.json.JsonElement> {
            title?.let { put("title", kotlinx.serialization.json.JsonPrimitive(it)) }
            parentId?.let { put("parentID", kotlinx.serialization.json.JsonPrimitive(it)) }
            directory?.let {
                put("location", kotlinx.serialization.json.buildJsonObject {
                    put("directory", kotlinx.serialization.json.JsonPrimitive(it))
                })
            }
        }
        val response = httpClient.post("${conn.baseUrl}/api/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject { bodyObj.forEach { (k, v) -> put(k, v) } })
        }
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2SessionMapper.toSession(data)
    }

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/api/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session {
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/rename") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("title" to title))
        }
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2SessionMapper.toSession(data)
    }

    /** V2 用 interrupt 替代 V1 的 abort */
    suspend fun interruptSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/interrupt") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return response.status.isSuccess()
    }

    /**
     * V2 会话状态查询。
     * V2 没有直接的 /session/status 端点，用 /api/session/active 获取活跃会话。
     */
    suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String? = null
    ): Result<Map<String, RestSessionStatusInfo>> {
        return runCatching {
            val response = httpClient.get("${conn.baseUrl}/api/session/active") {
                conn.authHeader?.let { header("Authorization", it) }
                directoryHeader(directory)
            }
            val root = parseRoot(response.bodyAsText())
            // V2 /api/session/active 返回 Map：{data: {sessionID: {type: "running"}}}
            // （2026-08-11 实测；不是 List——原 unwrapList 解析恒为空，
            // 导致 L3/L4 REST 校验永远不知道子会话 running，后台 subagent 无法标记运行中）
            val data = root["data"]?.jsonObject
                ?: return@runCatching emptyMap()
            data.mapNotNull { (sessionId, value) ->
                val type = value.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
                if (sessionId.isBlank() || type == null) null
                else sessionId to RestSessionStatusInfo(
                    type = if (type == "running" || type == "busy") "busy" else type
                )
            }.toMap()
        }
    }

    // ============ Background & Shell ============

    /**
     * 活跃会话列表（GET /api/session/active）。
     * V2 返回 `{data: {sessionID: {type: "running"}}}`——
     * 出现在结果中的是前台活跃会话，absent 的为后台/空闲。
     * 可用于前后台状态判定。
     */
    suspend fun activeSessions(
        conn: ServerConnection,
        directory: String? = null
    ): Map<String, ActiveSessionInfo> {
        val response = httpClient.get("${conn.baseUrl}/api/session/active") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        val root = parseRoot(response.bodyAsText())
        val data = root["data"]?.jsonObject
            ?: return emptyMap()
        return data.mapValues { (_, v) ->
            ActiveSessionInfo(
                type = v.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    }

    /**
     * 后台化（POST /api/session/:sessionID/background）。
     * 将当前会话所有前台可后台化工具（subagent）批量转为后台，
     * 主会话立即恢复交互。无前台可后台化工具时是 no-op。
     */
    suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/background") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    /**
     * 列出运行中的后台 shell 命令（GET /api/shell）。
     * 已退出的命令不包含在列表中。
     */
    suspend fun listShells(
        conn: ServerConnection,
        directory: String? = null
    ): List<ShellJob> {
        val response = httpClient.get("${conn.baseUrl}/api/shell") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return V2ShellMapper.shellList(response.bodyAsText(), json)
    }

    /**
     * 获取单个后台 shell（GET /api/shell/:id），含状态与退出码。
     */
    suspend fun getShell(
        conn: ServerConnection,
        shellId: String,
        directory: String? = null
    ): ShellJob? {
        val response = httpClient.get("${conn.baseUrl}/api/shell/$shellId") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        if (!response.status.isSuccess()) return null
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2ShellMapper.toShellJob(data)
    }

    /**
     * 分页读取后台 shell 输出（GET /api/shell/:id/output）。
     * 按字节游标读取捕获的 stdout/stderr 合并输出。
     */
    suspend fun getShellOutput(
        conn: ServerConnection,
        shellId: String,
        cursor: Long? = null,
        limit: Int? = null,
        directory: String? = null
    ): ShellOutput? {
        val response = httpClient.get("${conn.baseUrl}/api/shell/$shellId/output") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            cursor?.let { parameter("cursor", it) }
            limit?.let { parameter("limit", it) }
        }
        if (!response.status.isSuccess()) return null
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return ShellOutput(
            output = data["output"]?.jsonPrimitive?.contentOrNull ?: "",
            cursor = data["cursor"]?.jsonPrimitive?.long ?: 0L,
            size = data["size"]?.jsonPrimitive?.long ?: 0L,
            truncated = data["truncated"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    /**
     * 终止并删除后台 shell（DELETE /api/shell/:id）。
     */
    suspend fun removeShell(
        conn: ServerConnection,
        shellId: String,
        directory: String? = null
    ): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/api/shell/$shellId") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return response.status.isSuccess()
    }

    /**
     * 更新后台 shell 超时（PATCH /api/shell/:id/timeout）。
     * 从当前时刻重新计时；传 0 清除超时。
     */
    suspend fun updateShellTimeout(
        conn: ServerConnection,
        shellId: String,
        timeoutMillis: Long,
        directory: String? = null
    ): ShellJob? {
        val response = httpClient.patch("${conn.baseUrl}/api/shell/$shellId/timeout") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody("""{"timeout":$timeoutMillis}""")
        }
        if (!response.status.isSuccess()) return null
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2ShellMapper.toShellJob(data)
    }

    // ============ Message ============

    suspend fun listMessages(        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null
    ): MessagePage {
        val response = httpClient.get("${conn.baseUrl}/api/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
            limit?.let { parameter("limit", it) }
        }
        val root = parseRoot(response.bodyAsText())
        val (items, nextCursor) = V2ResponseWrapper.unwrapList(root)
        val messages = items.mapNotNull { V2MessageMapper.toMessageWithParts(it, sessionId) }
        return MessagePage(messages = messages, nextCursor = nextCursor)
    }

    suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/api/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts {
        val response = httpClient.get("${conn.baseUrl}/api/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2MessageMapper.toMessageWithParts(data, sessionId)
            ?: throw RuntimeException("Failed to parse message $messageId")
    }

    /**
     * V2 发送消息。
     * POST /api/session/{sessionID}/prompt
     *
     * 注意：V2 的 prompt body 不含 model 字段。模型切换通过独立端点
     * POST /api/session/{sessionID}/model 实现。调用方应在 prompt 前先调
     * [switchModel]（本方法不做隐式切换，避免每次发消息都多一次往返）。
     */
    suspend fun prompt(
        conn: ServerConnection,
        sessionId: String,
        text: String,
        directory: String? = null,
        agent: String? = null
    ): Boolean {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(text))
            agent?.let {
                put("agents", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.buildJsonObject {
                        put("name", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                )))
            }
        }
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/prompt") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        return response.status.isSuccess()
    }

    /**
     * V2 切换会话模型。
     * POST /api/session/{sessionID}/model
     * Body: { id: modelId, providerID: providerId, variant?: variant }
     */
    suspend fun switchModel(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String,
        variant: String? = null
    ): Boolean {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(modelId))
            put("providerID", kotlinx.serialization.json.JsonPrimitive(providerId))
            variant?.let { put("variant", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/model") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        return response.status.isSuccess()
    }

    suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/api/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    // ============ System / Agents / Commands / Skills ============

    suspend fun listAgents(conn: ServerConnection): List<AgentInfo> {
        val response = httpClient.get("${conn.baseUrl}/api/agent") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        val root = parseRoot(response.bodyAsText())
        val (items, _) = V2ResponseWrapper.unwrapList(root)
        return items.map { obj ->
            AgentInfo(
                name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "primary",
                hidden = obj["hidden"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            )
        }
    }

    suspend fun listCommands(conn: ServerConnection): List<CommandInfo> {
        val response = httpClient.get("${conn.baseUrl}/api/command") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        val root = parseRoot(response.bodyAsText())
        val (items, _) = V2ResponseWrapper.unwrapList(root)
        return items.map { obj ->
            CommandInfo(
                name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                description = obj["description"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    suspend fun listSkills(conn: ServerConnection, directory: String? = null): List<SkillInfo> {
        val response = httpClient.get("${conn.baseUrl}/api/skill") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        val root = parseRoot(response.bodyAsText())
        val (items, _) = V2ResponseWrapper.unwrapList(root)
        return items.map { obj ->
            SkillInfo(
                name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                description = obj["description"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/mcp/$name/connect") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/mcp/$name/disconnect") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    // ============ Provider / Config ============

    suspend fun getProviders(conn: ServerConnection): dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse {
        // V2 provider + model 分两个端点：
        // 1. GET /api/provider → provider 列表（不含模型）
        // 2. GET /api/model → 模型列表（每个模型带 providerID）
        val providerResponse = httpClient.get("${conn.baseUrl}/api/provider") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        val providerRoot = parseRoot(providerResponse.bodyAsText())
        val (providerItems, _) = V2ResponseWrapper.unwrapList(providerRoot)

        // 获取模型列表
        val modelItems = runCatching {
            val modelResponse = httpClient.get("${conn.baseUrl}/api/model") {
                conn.authHeader?.let { header("Authorization", it) }
            }
            val modelRoot = parseRoot(modelResponse.bodyAsText())
            V2ResponseWrapper.unwrapList(modelRoot).first
        }.getOrElse { emptyList() }

        // 按 providerID 分组模型
        val modelsByProvider = modelItems.groupBy { obj ->
            obj["providerID"]?.jsonPrimitive?.contentOrNull ?: ""
        }

        val providers = providerItems.map { obj ->
            val providerId = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val providerName = obj["name"]?.jsonPrimitive?.contentOrNull ?: providerId
            val providerModels = modelsByProvider[providerId].orEmpty()

            // 将 V2 Model JSON 映射为 V1 ProviderModel DTO
            val modelsMap = providerModels.associate { mObj ->
                val modelId = mObj["id"]?.jsonPrimitive?.contentOrNull ?: mObj["modelID"]?.jsonPrimitive?.contentOrNull ?: ""
                val modelName = mObj["name"]?.jsonPrimitive?.contentOrNull ?: modelId
                modelId to dev.leonardo.ocbeacon.data.dto.response.ProviderModel(
                    id = modelId,
                    providerId = providerId,
                    name = modelName,
                    family = mObj["family"]?.jsonPrimitive?.contentOrNull,
                    status = mObj["status"]?.jsonPrimitive?.contentOrNull ?: "active",
                    limit = parseModelLimit(mObj)
                )
            }

            dev.leonardo.ocbeacon.data.dto.response.ProviderInfo(
                id = providerId,
                name = providerName,
                source = "v2",
                models = modelsMap
            )
        }
        return dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse(
            providers = providers,
            default = emptyMap()
        )
    }

    /** 解析 V2 Model JSON 的 limit 字段 */
    private fun parseModelLimit(obj: JsonObject): dev.leonardo.ocbeacon.data.dto.response.ModelLimit? {
        val limitObj = obj["limit"]?.jsonObject ?: return null
        return dev.leonardo.ocbeacon.data.dto.response.ModelLimit(
            context = limitObj["context"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            input = limitObj["input"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            output = limitObj["output"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        )
    }

    // ============ Permission / Question (V2 paths) ============

    suspend fun replyToPermission(
        conn: ServerConnection,
        requestId: String,
        reply: String,
        message: String? = null,
        directory: String? = null
    ): Boolean {
        val effect = when (reply) {
            "reject" -> "deny"
            "always" -> "allow"
            else -> "allow"
        }
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("effect", kotlinx.serialization.json.JsonPrimitive(effect))
            message?.let { put("message", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val response = httpClient.post("${conn.baseUrl}/api/permission/$requestId/reply") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        return response.status.isSuccess()
    }

    suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): Boolean {
        val answersArray = kotlinx.serialization.json.JsonArray(
            answers.map { inner ->
                kotlinx.serialization.json.JsonArray(
                    inner.map { kotlinx.serialization.json.JsonPrimitive(it) }
                )
            }
        )
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("answers", answersArray)
        }
        val response = httpClient.post("${conn.baseUrl}/api/question/$requestId/reply") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        return response.status.isSuccess()
    }

    suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String? = null
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/question/$requestId/reject") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return response.status.isSuccess()
    }

    // ============ Session (supplementary) ============

    suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session {
        val title = fields["title"] as? String
        return if (title != null) renameSession(conn, sessionId, title)
        else getSession(conn, sessionId)
    }

    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> {
        return emptyList() // V2 无 session diff 端点
    }

    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return getSession(conn, sessionId) // V2 无对应端点，返回原 session（no-op）
    }

    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return getSession(conn, sessionId) // V2 无对应端点，返回原 session（no-op）
    }

    suspend fun summarizeSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/compact") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("providerID" to providerId, "modelID" to modelId))
        }
        return response.status.isSuccess()
    }

    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session {
        // V2 两步操作：stage + commit
        httpClient.post("${conn.baseUrl}/api/session/$sessionId/revert/stage") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("messageID" to messageId))
        }
        httpClient.post("${conn.baseUrl}/api/session/$sessionId/revert/commit") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return getSession(conn, sessionId)
    }

    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        httpClient.post("${conn.baseUrl}/api/session/$sessionId/revert/clear") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return getSession(conn, sessionId)
    }

    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            messageId?.let { put("messageID", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val bodyText = httpClient.post("${conn.baseUrl}/api/session/$sessionId/fork") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }.bodyAsText()
        return V2SessionMapper.toSession(V2ResponseWrapper.flexibleObject(bodyText, json))
    }

    suspend fun importSession(conn: ServerConnection, shareUrl: String): Session {
        val bodyText = httpClient.post("${conn.baseUrl}/api/session/import") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject {
                put("url", kotlinx.serialization.json.JsonPrimitive(shareUrl))
            })
        }.bodyAsText()
        return V2SessionMapper.toSession(V2ResponseWrapper.flexibleObject(bodyText, json))
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
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/command") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return response.status.isSuccess()
    }

    suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> {
        return runCatching { listSessions(conn, cursor = null, limit = 50).filter { it.parentId == sessionId } }
            .getOrElse { emptyList() }
    }

    suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> {
        return emptyList()
    }

    suspend fun listSessionStatus(conn: ServerConnection, directory: String? = null): Map<String, SessionStatusInfo> {
        return emptyMap()
    }

    // ============ Message (supplementary) ============

    suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit = {}
    ) {
        var bytesWritten = 0L
        val sessionPath = "/api/session/$sessionId"
        val messagePath = "/api/session/$sessionId/message"
        val sessionJson = httpClient.get("${conn.baseUrl}$sessionPath") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val header = """{"info":$sessionJson,"messages":"""
        outputStream.write(header.toByteArray())
        bytesWritten += header.toByteArray().size
        outputStream.flush()
        onProgress(bytesWritten)

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

    suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ) {
        val text = parts.firstOrNull { it.type == "text" }?.text
            ?: parts.joinToString { it.text ?: "" }
        if (model != null) {
            switchModel(conn, sessionId, model.providerId, model.modelId, variant)
        }
        prompt(conn, sessionId, text, directory, agent)
    }

    suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean {
        return false // V2 无此端点
    }

    suspend fun listPendingPermissions(conn: ServerConnection, directory: String? = null): List<PermissionRequest> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/permission/request") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(PermissionRequest.serializer(), obj)
        }
    }

    suspend fun listPendingQuestions(conn: ServerConnection, directory: String? = null): List<QuestionRequest> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/question/request") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(QuestionRequest.serializer(), obj)
        }
    }

    // ============ System (supplementary) ============

    suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return runCatching {
            val bodyText = httpClient.get("${conn.baseUrl}/api/location") {
                conn.authHeader?.let { header("Authorization", it) }
            }.bodyAsText()
            val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
            json.decodeFromJsonElement(ServerPaths.serializer(), obj)
        }.getOrElse { ServerPaths() }
    }

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/mcp") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        // 实测契约（2026-08-11）：{"location":..., "data":[{name, status:{status, error?}}, ...]}
        // 原 flexibleObject 解析对象包裹 → 遇 data 数组返回根对象 → 遍历 location（无 status）→ 崩溃
        val items = V2ResponseWrapper.flexibleList(bodyText, json)
        return items.mapNotNull { obj ->
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val statusObj = obj["status"]?.jsonObject
            val status = statusObj?.get("status")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name to McpStatusEntry(
                status = status,
                error = statusObj["error"]?.jsonPrimitive?.contentOrNull
            )
        }.toMap()
    }

    // ============ Provider / Config (supplementary) ============

    suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse {
        val providers = getProviders(conn)
        return ProviderCatalogResponse(
            all = providers.providers,
            default = providers.default,
            connected = emptyList()
        )
    }

    suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> {
        return emptyMap()
    }

    suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization? {
        return null
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
        val response = httpClient.patch("${conn.baseUrl}/api/credential/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("type" to "api", "key" to apiKey))
        }
        return response.status.isSuccess()
    }

    suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "removeProviderAuth: DELETE ${conn.baseUrl}/api/credential/$providerId")
        val response = httpClient.delete("${conn.baseUrl}/api/credential/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (BuildConfig.DEBUG) {
            val body = response.bodyAsText()
            AppLogger.d(TAG, "removeProviderAuth: status=${response.status}, body=$body")
        }
        return response.status.isSuccess()
    }

    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse {
        val bodyText = httpClient.get("${conn.baseUrl}/api/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(ServerConfigResponse.serializer(), obj)
    }

    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse {
        val bodyText = httpClient.get("${conn.baseUrl}/api/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(ServerConfigResponse.serializer(), obj)
    }

    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        val bodyText = httpClient.patch("${conn.baseUrl}/api/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(ServerConfigResponse.serializer(), obj)
    }

    suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        val bodyText = httpClient.patch("${conn.baseUrl}/api/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(ServerConfigResponse.serializer(), obj)
    }

    suspend fun disposeGlobal(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/service/stop") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun disposeInstance(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/service/stop") {
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
        val bodyText = httpClient.get("${conn.baseUrl}/api/fs/find") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("query", query)
            type?.let { parameter("type", it) }
            limit?.let { parameter("limit", it) }
            dirs?.let { parameter("dirs", it) }
        }.bodyAsText()
        val root = parseRoot(bodyText)
        val dataField = root["data"]
        return when {
            dataField is JsonArray -> dataField.mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull
                    ?: element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }
            else -> emptyList()
        }
    }

    suspend fun readFile(conn: ServerConnection, path: String, directory: String? = null): FileContentDto {
        val bodyText = httpClient.get("${conn.baseUrl}/api/fs/read") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", path)
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(FileContentDto.serializer(), obj)
    }

    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/fs/find") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("pattern", pattern)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(SearchMatchDto.serializer(), obj)
        }
    }

    suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean {
        val response = httpClient.get("${conn.baseUrl}/api/fs/list") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", "")
        }
        return response.status.isSuccess()
    }

    suspend fun listDirectory(conn: ServerConnection, path: String, directory: String? = null): List<FileNodeDto> {
        val response = httpClient.get("${conn.baseUrl}/api/fs/list") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            return emptyList()
        }
        val bodyText = response.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(FileNodeDto.serializer(), obj)
        }
    }

    suspend fun findSymbols(conn: ServerConnection, query: String, directory: String? = null): List<SymbolInfo> {
        return emptyList() // V2 无独立 symbol search
    }

    suspend fun getFileStatus(conn: ServerConnection, directory: String? = null): List<FileStatusInfo> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(FileStatusInfo.serializer(), obj)
        }
    }

    suspend fun getVcs(conn: ServerConnection, directory: String? = null): VcsBranchDto {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(VcsBranchDto.serializer(), obj)
    }

    suspend fun getVcsStatus(conn: ServerConnection, directory: String? = null): List<VcsChangeDto> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(VcsChangeDto.serializer(), obj)
        }
    }

    suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int = 3, directory: String? = null): List<FileDiffDto> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs/diff") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("mode", mode)
            parameter("context", context)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(FileDiffDto.serializer(), obj)
        }
    }

    suspend fun listProjects(conn: ServerConnection): List<Project> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/project") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(Project.serializer(), obj)
        }
    }

    suspend fun getCurrentProject(conn: ServerConnection): Project {
        val bodyText = httpClient.get("${conn.baseUrl}/api/project/current") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(Project.serializer(), obj)
    }

    // ============ Terminal / Pty ============

    suspend fun createPty(
        conn: ServerConnection,
        title: String? = null,
        cwd: String? = null,
        directory: String? = null
    ): PtyInfo {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "createPty: POST ${conn.baseUrl}/api/pty title=$title cwd=$cwd directory=$directory")
        }
        val response = httpClient.post("${conn.baseUrl}/api/pty") {
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

        runCatching { return json.decodeFromString(PtyInfo.serializer(), trimmed) }

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
        val response = httpClient.delete("${conn.baseUrl}/api/pty/$ptyId") {
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
            AppLogger.d(TAG, "updatePtySize: PUT ${conn.baseUrl}/api/pty/$ptyId body=$jsonStr directory=$directory")
        }
        val response = httpClient.put("${conn.baseUrl}/api/pty/$ptyId") {
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
            url("$wsBase/api/pty/$ptyId/connect?cursor=$cursor")
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }
        return PtySocket(session)
    }

    suspend fun listPtyShells(conn: ServerConnection, directory: String? = null): List<ShellInfo> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/pty/shells") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(ShellInfo.serializer(), obj)
        }
    }

    suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection? = null,
        directory: String? = null
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/shell") {
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
