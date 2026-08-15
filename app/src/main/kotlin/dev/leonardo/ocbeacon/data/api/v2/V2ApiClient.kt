package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.data.api.auth

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.NonJsonResponseException
import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.api.message.PromptAdmission
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

    private fun parseRoot(bodyText: String): JsonObject {
        rejectHtmlResponse(bodyText)
        return json.parseToJsonElement(bodyText).jsonObject
    }

    /**
     * 防御：服务器 SPA fallback 会把不存在的 API 路径返回为 HTML 页面（HTTP 200）。
     * 在 JSON 解析前检测 HTML 特征，抛出可读异常（而非 JsonDecodingException）。
     * 触发条件通常是 API 版本误判（V1 服务器被当成 V2 请求 /api/... 路径）。
     */
    private fun rejectHtmlResponse(bodyText: String) {
        val trimmed = bodyText.trimStart()
        if (trimmed.startsWith("<!doctype html", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            val preview = trimmed.take(120).replace('\n', ' ')
            AppLogger.e(TAG, "Non-JSON (HTML) response from server: $preview")
            throw NonJsonResponseException("服务器返回了 HTML 页面而非 JSON（API 路径可能不存在或版本不匹配）：$preview")
        }
    }

    // ============ Health ============

    suspend fun getHealth(conn: ServerConnection): ServerHealth {
        val response = httpClient.get("${conn.baseUrl}/api/health") {
            auth(conn)
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
            auth(conn)
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
            auth(conn)
        }
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2SessionMapper.toSession(data)
    }

    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/api/session/$sessionId") {
            auth(conn)
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
            auth(conn)
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject { bodyObj.forEach { (k, v) -> put(k, v) } })
        }
        val root = parseRoot(response.bodyAsText())
        val data = V2ResponseWrapper.unwrap(root)
        return V2SessionMapper.toSession(data)
    }

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        // 2026-08-15（research/10 P0 + 实测勘误）：V2 协议无 DELETE /api/session/:id
        //（404 实测）；主干源码 legacy /session/:id 由 InstanceHttpApi 挂载，但
        // **当前部署版（next-17430）未挂载**（405 实测）——两路均探测，兼容
        // 主干部署与旧部署。均失败时 UI 层提示"服务器不支持删除"。
        val apiResp = httpClient.delete("${conn.baseUrl}/api/session/$sessionId") { auth(conn) }
        if (apiResp.status.isSuccess()) return true
        val legacyResp = httpClient.delete("${conn.baseUrl}/session/$sessionId") { auth(conn) }
        if (legacyResp.status.isSuccess()) return true
        AppLogger.w(TAG, "deleteSession: neither /api/session (404) nor legacy /session (405) worked — deployed server may not support delete")
        return false
    }

    suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session {
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/rename") {
            auth(conn)
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
            auth(conn)
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
                auth(conn)
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
            auth(conn)
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
            auth(conn)
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
            auth(conn)
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
            auth(conn)
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
            auth(conn)
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
            auth(conn)
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
            auth(conn)
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

    suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null,
        cursor: String? = null
    ): MessagePage {
        val response = httpClient.get("${conn.baseUrl}/api/session/$sessionId/message") {
            auth(conn)
            limit?.let { parameter("limit", it) }
            // 2026-08-11 实测：服务器翻页参数名是 cursor（before 被忽略）。
            // cursor 值必须用服务器上次响应返回的 cursor.next（base64url 的
            // {"id","order","direction"}）——本地 CursorCodec 的 {"id","time"} 格式不兼容。
            // 2026-08-12 双向分页：cursor 也可由调用方构造（loadAround/loadNewer），
            // direction="next"=更旧、"previous"=更新，服务器据此返回对应方向数据。
            cursor?.let { parameter("cursor", it) }
        }
        // 防御（#87）：非 2xx（404 会话不存在/5xx）返回空页，避免解析错误体。
        if (!response.status.isSuccess()) {
            AppLogger.w(TAG, "listMessages failed: status=${response.status} session=$sessionId")
            return MessagePage(messages = emptyList(), nextCursor = null, previousCursor = null)
        }
        val root = parseRoot(response.bodyAsText())
        // 双向游标：nextCursor（cursor.next）= 更旧方向；previousCursor（cursor.previous）= 更新方向。
        // 原 unwrapList 只提取 next，丢弃 previous —— 双向分页需保留两者。
        val unwrapped = V2ResponseWrapper.unwrapListFull(root)
        val messages = unwrapped.items.mapNotNull { V2MessageMapper.toMessageWithParts(it, sessionId) }
        return MessagePage(
            messages = messages,
            nextCursor = unwrapped.nextCursor,
            previousCursor = unwrapped.previousCursor,
        )
    }

    suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/api/session/$sessionId/message") {
            auth(conn)
        }.bodyAsText()
    }

    suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts {
        val response = httpClient.get("${conn.baseUrl}/api/session/$sessionId/message/$messageId") {
            auth(conn)
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
    ): PromptAdmission? {
        // 2026-08-15（research/09 P0，双契约探测适配）：
        // - 主干 V2 契约：POST /api/session/:id/prompt {prompt:{text,files,agents}, delivery}
        // - 旧部署（next-17403/17430）：平铺 {text, agents:[{name}]}
        // 先发新契约（prompt 包裹 + agent 以 prompt.agents 表达），400 时降级
        // 旧契约（当前实测部署版形态）。响应体双读 prompt.text/payload.text。
        suspend fun postBody(bodyObj: kotlinx.serialization.json.JsonObject) =
            httpClient.post("${conn.baseUrl}/api/session/$sessionId/prompt") {
                auth(conn)
                directoryHeader(directory)
                contentType(ContentType.Application.Json)
                setBody(bodyObj)
            }

        val requestStartMs = System.currentTimeMillis()
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[prompt] POST /api/session/$sessionId/prompt textLen=${text.length} agent=$agent directory=$directory")
        }
        // 新契约（主干）：prompt 包裹；agent 独立字段（switchAgent 语义近似——
        // 旧契约的 agents 数组是 @子代理附件语义，不承载当前 agent 选择）
        val modernBody = kotlinx.serialization.json.buildJsonObject {
            put("prompt", kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(text))
                agent?.let {
                    put("agents", kotlinx.serialization.json.JsonArray(listOf(
                        kotlinx.serialization.json.buildJsonObject {
                            put("name", kotlinx.serialization.json.JsonPrimitive(it))
                        }
                    )))
                }
            })
        }
        var response = postBody(modernBody)
        if (response.status.value == 400) {
            // 降级旧契约（当前部署实测形态：平铺 body）
            val legacyBody = kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(text))
                agent?.let {
                    put("agents", kotlinx.serialization.json.JsonArray(listOf(
                        kotlinx.serialization.json.buildJsonObject {
                            put("name", kotlinx.serialization.json.JsonPrimitive(it))
                        }
                    )))
                }
            }
            response = postBody(legacyBody)
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "[prompt] modern 400 -> legacy body retry status=${response.status.value}")
        }
        val elapsedMs = System.currentTimeMillis() - requestStartMs
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[prompt] POST /prompt status=${response.status.value} elapsed=${elapsedMs}ms session=$sessionId")
        }
        if (!response.status.isSuccess()) return null
        // 2026-08-14 根治：200 响应体即 Inbox 条目
        // {"data":{"id":"msg_xxx","sessionID":"ses_xxx","timeCreated":...,"type":"user",
        //   "payload":{"text":"..."},"delivery":"steer"}}——解析失败仅降级（SSE 兜底）
        // 2026-08-15：双读 payload.text（旧）/ prompt.text（主干 Admitted 契约）
        val admission = runCatching {
            val root = parseRoot(response.bodyAsText())
            val obj = V2ResponseWrapper.unwrap(root)
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sid = obj["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId
            val textValue = obj["payload"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
                ?: obj["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            PromptAdmission(id = id, sessionId = sid, text = textValue)
        }.getOrNull()
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[prompt] admission id=${admission?.id ?: "null"} sid=${admission?.sessionId ?: "null"} (解析失败=null→依赖 SSE 回显)")
        }
        return admission
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
        // 2026-08-14 契约修复（模拟器实测 400 "Missing key at [\"model\"]"）：
        // V2 POST /api/session/{id}/model 的 body 必须是嵌套结构
        // {"model": {"id": ..., "providerID": ..., "variant": ...}}，
        // 扁平 {id, providerID} 会返回 400 InvalidRequestError。
        // 嵌套格式实测返回 204（见 docs/research/dialogue-lifecycle-e2e 证据）。
        val modelObj = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(modelId))
            put("providerID", kotlinx.serialization.json.JsonPrimitive(providerId))
            variant?.let { put("variant", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("model", modelObj)
        }
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[model] POST /api/session/$sessionId/model providerID=$providerId modelID=$modelId variant=$variant")
        }
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/model") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[model] status=${response.status.value} session=$sessionId")
        }
        return response.status.isSuccess()
    }

    suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/api/session/$sessionId/message/$messageId") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    // ============ System / Agents / Commands / Skills ============

    suspend fun listAgents(conn: ServerConnection): List<AgentInfo> {
        val response = httpClient.get("${conn.baseUrl}/api/agent") {
            auth(conn)
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
            auth(conn)
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
            auth(conn)
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
            auth(conn)
        }
        return response.status.isSuccess()
    }

    suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/mcp/$name/disconnect") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    // ============ Provider / Config ============

    suspend fun getProviders(conn: ServerConnection): dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse {
        // V2 provider + model 分两个端点：
        // 1. GET /api/provider → provider 列表（不含模型）
        // 2. GET /api/model → 模型列表（每个模型带 providerID）
        val providerResponse = httpClient.get("${conn.baseUrl}/api/provider") {
            auth(conn)
        }
        val providerRoot = parseRoot(providerResponse.bodyAsText())
        val (providerItems, _) = V2ResponseWrapper.unwrapList(providerRoot)

        // 获取模型列表
        val modelItems = runCatching {
            val modelResponse = httpClient.get("${conn.baseUrl}/api/model") {
                auth(conn)
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
                // 2026-08-15（research/07 P0）：补全 V2 映射——此前丢弃
                // variants/capabilities/cost → variantNames 恒空 → cycleVariant
                // 空转。V2 variants 是**数组** [{id,settings}]（V1 是 map）——
                // 转 map（key=variant id，value=原始元素供后续透传）。
                val variants = mObj["variants"]?.jsonArray
                    ?.mapNotNull { v -> (v as? kotlinx.serialization.json.JsonObject) }
                    ?.filter { v -> v["id"]?.jsonPrimitive?.contentOrNull != null }
                    ?.associate { v -> v["id"]!!.jsonPrimitive.content to v }
                // V2 capabilities 无 reasoning 布尔（{tools,input,output}）——
                // 从 variants 非空推断（官方 transform.ts:1656：仅 reasoning
                // 模型生成 variants）
                val v1Caps = mObj["capabilities"]?.jsonObject?.let { c ->
                    dev.leonardo.ocbeacon.data.dto.response.ModelCapabilities(
                        toolcall = c["tools"]?.jsonPrimitive?.contentOrNull == "true",
                        reasoning = !variants.isNullOrEmpty()
                    )
                } ?: variants?.let {
                    // capabilities 缺失但 variants 非空：至少标记 reasoning
                    dev.leonardo.ocbeacon.data.dto.response.ModelCapabilities(reasoning = true)
                }
                // V2 cost 是数组 [ModelCost]（取首条）
                val v2Cost = mObj["cost"]?.jsonArray
                    ?.firstOrNull()?.let { c -> c as? kotlinx.serialization.json.JsonObject }
                val cost = v2Cost?.let { c ->
                    dev.leonardo.ocbeacon.data.dto.response.ModelCost(
                        input = c["input"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        output = c["output"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    )
                }
                modelId to dev.leonardo.ocbeacon.data.dto.response.ProviderModel(
                    id = modelId,
                    providerId = providerId,
                    name = modelName,
                    family = mObj["family"]?.jsonPrimitive?.contentOrNull,
                    status = mObj["status"]?.jsonPrimitive?.contentOrNull ?: "active",
                    capabilities = v1Caps,
                    cost = cost,
                    limit = parseModelLimit(mObj),
                    variants = variants
                )
            }

            dev.leonardo.ocbeacon.data.dto.response.ProviderInfo(
                id = providerId,
                name = providerName,
                source = "v2",
                models = modelsMap
            )
        }
        // 2026-08-15（research/07 P0）：V2 默认模型——旧实现恒空 map → 回退
        // 退化为"无序第一个模型"。官方语义：provider_default 排序后的默认。
        // 消费方（ModelConfigDelegate:137）按 providerId→modelId 取值。
        val defaultMap: Map<String, String> = modelItems
            .filter { m ->
                (m["enabled"]?.jsonPrimitive?.contentOrNull ?: "true") == "true" &&
                    (m["status"]?.jsonPrimitive?.contentOrNull ?: "active") == "active"
            }
            .groupBy { m -> m["providerID"]?.jsonPrimitive?.contentOrNull ?: "" }
            .mapNotNull { (_, models) ->
                val m = models.first()
                val pid = m["providerID"]?.jsonPrimitive?.contentOrNull ?: ""
                val mid = m["id"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pid.isNotBlank() && mid.isNotBlank()) pid to mid else null
            }
            .toMap()
        return dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse(
            providers = providers,
            default = defaultMap
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
            auth(conn)
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        return response.status.isSuccess()
    }

    /**
     * #130：V2 question 工具已迁移到 form 服务——回复走
     * POST /api/session/{sessionID}/form/{formID}/reply，body {answer:{key:value|[...]}}。
     * 旧 /api/question/{id}/reply 是 stale surface（未来移除）。
     *
     * @param sessionId 表单所属会话（form reply 路径需要 sessionID）
     * @param keyedAnswers 已按 form field key 构造的 answer map（由 V2FormMapper.buildJsonAnswerMap 生成）
     */
    suspend fun replyToForm(
        conn: ServerConnection,
        sessionId: String,
        formId: String,
        keyedAnswers: kotlinx.serialization.json.JsonObject,
        directory: String? = null
    ): Boolean {
        // 2026-08-15（research/09 P0）：question.v2 优先（主干契约，实测 200）：
        // POST /api/session/:id/question/:requestID/reply {answers: string[][]}
        //（按 questions 顺序的数组，每个 answer 是选中 label 数组——与 form
        // 的 keyed map 不同）。form 通道降级（next-17430 中间契约）。
        // keyedAnswers 的 key 即 question key（q0/q1...）——按序转数组。
        val orderedAnswers = keyedAnswers.keys.mapNotNull { k ->
            (keyedAnswers[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.map { listOf(it) }
        val v2Body = kotlinx.serialization.json.buildJsonObject {
            put("answers", kotlinx.serialization.json.JsonArray(
                orderedAnswers.map { ans ->
                    kotlinx.serialization.json.JsonArray(ans.map { kotlinx.serialization.json.JsonPrimitive(it) })
                }
            ))
        }
        val v2Resp = httpClient.post(conn.baseUrl + "/api/session/" + sessionId + "/question/" + formId + "/reply") {
            auth(conn)
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(v2Body)
        }
        if (v2Resp.status.isSuccess()) return true
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "replyToForm: question.v2 status=${v2Resp.status.value} -> fallback form path")
        }
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("answer", keyedAnswers)
        }
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "replyToForm: POST /api/session/" + sessionId + "/form/" + formId + "/reply answer=" + keyedAnswers)
        }
        val response = httpClient.post(conn.baseUrl + "/api/session/" + sessionId + "/form/" + formId + "/reply") {
            auth(conn)
            directoryHeader(directory)
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        return response.status.isSuccess()
    }

    /**
     * #130：V2 取消 form——POST /api/session/{sessionID}/form/{formID}/cancel。
     * 旧 /api/question/{id}/reject 是 stale surface。
     */
    suspend fun rejectForm(
        conn: ServerConnection,
        sessionId: String,
        formId: String,
        directory: String? = null
    ): Boolean {
        // 2026-08-15（research/09 P0）：question.v2 优先（POST .../question/:id/reject）
        // form cancel 降级
        val v2Resp = httpClient.post(conn.baseUrl + "/api/session/" + sessionId + "/question/" + formId + "/reject") {
            auth(conn)
            directoryHeader(directory)
        }
        if (v2Resp.status.isSuccess()) return true
        val response = httpClient.post(conn.baseUrl + "/api/session/" + sessionId + "/form/" + formId + "/cancel") {
            auth(conn)
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
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("providerID" to providerId, "modelID" to modelId))
        }
        return response.status.isSuccess()
    }

    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session {
        // V2 两步操作：stage + commit
        httpClient.post("${conn.baseUrl}/api/session/$sessionId/revert/stage") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("messageID" to messageId))
        }
        httpClient.post("${conn.baseUrl}/api/session/$sessionId/revert/commit") {
            auth(conn)
        }
        return getSession(conn, sessionId)
    }

    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        httpClient.post("${conn.baseUrl}/api/session/$sessionId/revert/clear") {
            auth(conn)
        }
        return getSession(conn, sessionId)
    }

    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            messageId?.let { put("messageID", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val response = httpClient.post("${conn.baseUrl}/api/session/$sessionId/fork") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(bodyObj)
        }
        // 2026-08-12 修复：不检查状态会把 400 错误体解析成空对象 → Session.id=""
        // → 导航进空 id 幽灵会话，后续操作（share 等）打到列表端点崩溃。
        // 服务器 fork 端点当前存在 handle/handleRaw 同路径冲突（任何请求均 400），
        // 此处抛出明确异常，UI 显示失败提示而非静默进入损坏状态。
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Fork session failed: HTTP ${response.status.value}")
        }
        val bodyText = response.bodyAsText()
        return V2SessionMapper.toSession(V2ResponseWrapper.flexibleObject(bodyText, json))
    }

    suspend fun importSession(conn: ServerConnection, shareUrl: String): Session {
        val bodyText = httpClient.post("${conn.baseUrl}/api/session/import") {
            auth(conn)
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
            auth(conn)
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
            auth(conn)
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
    ): PromptAdmission? {
        val text = parts.firstOrNull { it.type == "text" }?.text
            ?: parts.joinToString { it.text ?: "" }
        if (model != null) {
            switchModel(conn, sessionId, model.providerId, model.modelId, variant)
        }
        return prompt(conn, sessionId, text, directory, agent)
    }

    suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean {
        return false // V2 无此端点
    }

    suspend fun listPendingPermissions(conn: ServerConnection, directory: String? = null): List<PermissionRequest> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/permission/request") {
            auth(conn)
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(PermissionRequest.serializer(), obj)
        }
    }

    /**
     * #130：V2 question 工具已迁移到 form 服务——待处理表单从
     * GET /api/form/request 读取（旧 /api/question/request 是 stale surface，
     * 2026-08-14 实测恒返回空）。kind=question 的表单映射为 QuestionRequest DTO，
     * 供轮询兜底/通知复用；其他 kind 的表单忽略。
     */
    suspend fun listPendingQuestions(conn: ServerConnection, directory: String? = null): List<QuestionRequest> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/form/request") {
            auth(conn)
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json)
            .mapNotNull { V2FormMapper.toQuestionRequest(it) }
    }

    // ============ System (supplementary) ============

    suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return runCatching {
            val bodyText = httpClient.get("${conn.baseUrl}/api/location") {
                auth(conn)
            }.bodyAsText()
            val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
            val decoded = json.decodeFromJsonElement(ServerPaths.serializer(), obj)
            // V2 /api/location 只有 directory 字段（无 home）——directory 语义 = 当前工作目录，
            // 回退为 home，否则 OpenProjectDialog 的 homeDir 为空（路径栏/新建文件夹受影响）
            if (decoded.home.isBlank() && decoded.directory.isNotBlank()) {
                decoded.copy(home = decoded.directory)
            } else {
                decoded
            }
        }.getOrElse { ServerPaths() }
    }

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/mcp") {
            auth(conn)
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
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "completeProviderOauth: POST /api/provider/$providerId/oauth/callback body=$body")
        val response = httpClient.post("${conn.baseUrl}/api/provider/$providerId/oauth/callback") {
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
        val response = httpClient.patch("${conn.baseUrl}/api/credential/$providerId") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(mapOf("type" to "api", "key" to apiKey))
        }
        return response.status.isSuccess()
    }

    suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "removeProviderAuth: DELETE ${conn.baseUrl}/api/credential/$providerId")
        val response = httpClient.delete("${conn.baseUrl}/api/credential/$providerId") {
            auth(conn)
        }
        if (BuildConfig.DEBUG) {
            val body = response.bodyAsText()
            AppLogger.d(TAG, "removeProviderAuth: status=${response.status}, body=$body")
        }
        return response.status.isSuccess()
    }

    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse {
        val bodyText = httpClient.get("${conn.baseUrl}/api/config") {
            auth(conn)
        }.bodyAsText()
        return parseConfigBody(bodyText)
    }

    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse {
        val bodyText = httpClient.get("${conn.baseUrl}/api/config") {
            auth(conn)
        }.bodyAsText()
        return parseConfigBody(bodyText)
    }

    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        val bodyText = httpClient.patch("${conn.baseUrl}/api/config") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.bodyAsText()
        return parseConfigBody(bodyText)
    }

    suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        val bodyText = httpClient.patch("${conn.baseUrl}/api/config") {
            auth(conn)
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.bodyAsText()
        return parseConfigBody(bodyText)
    }

    /**
     * 解析 /api/config 响应（2026-08-11 实测契约）：
     * 裸数组 `[{type:"document", path, info:{配置对象}}, ...]`——取第一个元素的 info 子对象。
     * 兼容对象包裹 `{data: ...}` 或直接配置对象（V1 风格）。
     */
    private fun parseConfigBody(bodyText: String): ServerConfigResponse {
        val element = json.parseToJsonElement(bodyText)
        val configObj = when (element) {
            is JsonArray -> element.firstOrNull()?.jsonObject?.get("info")?.jsonObject
                ?: element.firstOrNull()?.jsonObject
            is JsonObject -> element["info"]?.jsonObject ?: element["data"]?.jsonObject ?: element
            else -> null
        }
        return configObj?.let { obj ->
            runCatching { json.decodeFromJsonElement(ServerConfigResponse.serializer(), obj) }
                .getOrNull()
        } ?: ServerConfigResponse()
    }

    suspend fun disposeGlobal(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/service/stop") {
            auth(conn)
        }
        return response.status.isSuccess()
    }

    suspend fun disposeInstance(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/api/service/stop") {
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
        val bodyText = httpClient.get("${conn.baseUrl}/api/fs/find") {
            auth(conn)
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
            auth(conn)
            directoryHeader(directory)
            parameter("path", path)
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        return json.decodeFromJsonElement(FileContentDto.serializer(), obj)
    }

    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/fs/find") {
            auth(conn)
            parameter("pattern", pattern)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(SearchMatchDto.serializer(), obj)
        }
    }

    suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean {
        val response = httpClient.get("${conn.baseUrl}/api/fs/list") {
            auth(conn)
            directoryHeader(directory)
            parameter("path", "")
        }
        return response.status.isSuccess()
    }

    suspend fun listDirectory(conn: ServerConnection, path: String, directory: String? = null): List<FileNodeDto> {
        val response = httpClient.get("${conn.baseUrl}/api/fs/list") {
            auth(conn)
            directoryHeader(directory)
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            return emptyList()
        }
        val bodyText = response.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            val dto = json.decodeFromJsonElement(FileNodeDto.serializer(), obj)
            // V2 /api/fs/list 响应项只有 {path, type}——name/absolute 缺失。
            // 缺 name 时 decode 会抛 MissingFieldException（旧 bug：V2 下 Open other project 空列表）；
            // absolute 缺失时 UI LazyColumn key={it.absolute} 全部为空字符串 → Key "" already used 崩溃（回归 2）。
            if (dto.name.isBlank() || dto.absolute.isNullOrBlank()) {
                val name = dto.name.ifBlank {
                    dto.path.trimEnd('/').substringAfterLast('/').ifBlank { dto.path }
                }
                val absolute = dto.absolute?.takeIf { it.isNotBlank() }
                    ?: buildString {
                        val base = directory?.trimEnd('/') ?: ""
                        if (base.isNotEmpty()) { append(base); append('/') }
                        append(dto.path.trimEnd('/'))
                    }
                dto.copy(name = name, absolute = absolute)
            } else {
                dto
            }
        }
    }

    suspend fun findSymbols(conn: ServerConnection, query: String, directory: String? = null): List<SymbolInfo> {
        return emptyList() // V2 无独立 symbol search
    }

    suspend fun getFileStatus(conn: ServerConnection, directory: String? = null): List<FileStatusInfo> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs/status") {
            auth(conn)
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(FileStatusInfo.serializer(), obj)
        }
    }

    suspend fun getVcs(conn: ServerConnection, directory: String? = null): VcsBranchDto {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs") {
            auth(conn)
            directoryHeader(directory)
        }.bodyAsText()
        val obj = V2ResponseWrapper.flexibleObject(bodyText, json)
        // 实测（2026-08-11）：全局目录下 branch 是空对象 {branch:{}}——decode String 崩溃。
        // 防御：branch 为对象（非文本）时视为 null。
        val branch = obj["branch"]?.let { elem ->
            when {
                elem is JsonPrimitive -> elem.contentOrNull
                else -> null // 对象/数组 → 无分支信息
            }
        }
        val defaultBranch = obj["default_branch"]?.jsonPrimitive?.contentOrNull
        return VcsBranchDto(branch = branch, defaultBranch = defaultBranch)
    }

    suspend fun getVcsStatus(conn: ServerConnection, directory: String? = null): List<VcsChangeDto> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs/status") {
            auth(conn)
            directoryHeader(directory)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(VcsChangeDto.serializer(), obj)
        }
    }

    suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int = 3, directory: String? = null): List<FileDiffDto> {
        val bodyText = httpClient.get("${conn.baseUrl}/api/vcs/diff") {
            auth(conn)
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
            auth(conn)
        }.bodyAsText()
        return V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
            json.decodeFromJsonElement(Project.serializer(), obj)
        }
    }

    suspend fun getCurrentProject(conn: ServerConnection): Project {
        val bodyText = httpClient.get("${conn.baseUrl}/api/project/current") {
            auth(conn)
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
            auth(conn)
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
            auth(conn)
            directoryHeader(directory)
        }
        return PtySocket(session)
    }

    suspend fun listPtyShells(conn: ServerConnection, directory: String? = null): List<ShellInfo> {
        // 实测（2026-08-11）：/api/pty/shells 是错误路径（路由把 shells 当 ptyID）；
        // 正确端点是 /api/pty（location 作用域 PTY 列表）。
        return runCatching {
            val bodyText = httpClient.get("${conn.baseUrl}/api/pty") {
                auth(conn)
                directoryHeader(directory)
            }.bodyAsText()
            V2ResponseWrapper.flexibleList(bodyText, json).map { obj ->
                json.decodeFromJsonElement(ShellInfo.serializer(), obj)
            }
        }.getOrElse { emptyList() }
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
