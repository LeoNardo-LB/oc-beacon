package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.message.PromptAdmission
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.shell.ShellApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.dto.request.PromptPart
import dev.leonardo.ocbeacon.data.dto.request.ServerConfigPatch
import dev.leonardo.ocbeacon.data.dto.response.AgentInfo
import dev.leonardo.ocbeacon.data.dto.response.CommandInfo
import dev.leonardo.ocbeacon.data.dto.response.FileContentDto
import dev.leonardo.ocbeacon.data.dto.response.FileDiffDto
import dev.leonardo.ocbeacon.data.dto.response.FileNodeDto
import dev.leonardo.ocbeacon.data.dto.response.FileStatusInfo
import dev.leonardo.ocbeacon.data.dto.response.McpStatusEntry
import dev.leonardo.ocbeacon.data.dto.response.ModelCapabilities
import dev.leonardo.ocbeacon.data.dto.response.PermissionRequest
import dev.leonardo.ocbeacon.data.dto.response.ProviderAuthMethod
import dev.leonardo.ocbeacon.data.dto.response.ProviderCatalogResponse
import dev.leonardo.ocbeacon.data.dto.response.ProviderInfo
import dev.leonardo.ocbeacon.data.dto.response.ProviderModel
import dev.leonardo.ocbeacon.data.dto.response.ProviderOauthAuthorization
import dev.leonardo.ocbeacon.data.dto.response.PtyInfo
import dev.leonardo.ocbeacon.data.dto.response.QuestionRequest
import dev.leonardo.ocbeacon.data.dto.response.SearchMatchDto
import dev.leonardo.ocbeacon.data.dto.response.ServerConfigResponse
import dev.leonardo.ocbeacon.data.dto.response.ServerPaths
import dev.leonardo.ocbeacon.data.dto.response.SessionStatusInfo
import dev.leonardo.ocbeacon.data.dto.response.ShellInfo
import dev.leonardo.ocbeacon.data.dto.response.SkillInfo
import dev.leonardo.ocbeacon.data.dto.response.SubagentListEntryDto
import dev.leonardo.ocbeacon.data.dto.response.SymbolInfo
import dev.leonardo.ocbeacon.data.dto.response.TodoItem
import dev.leonardo.ocbeacon.data.dto.response.VcsBranchDto
import dev.leonardo.ocbeacon.data.dto.response.VcsChangeDto
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDefault
import dev.leonardo.ocbeacon.domain.model.DshGoalRef
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionPage
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ShellOutput
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DshApi"

/**
 * DSH 七域 API 实现（backlog #276 步骤③；设计 §2.6 方法面 → 域接口映射表）。
 *
 * 与 V1ApiClient/V2ApiClient 并列实现同一批域接口，由七个 *ApiImpl.pick 三分路由。
 * 方法面（52 方法）：session.×12 / subagent.×4 / workspace.×7 / host.×5 / llm.×3 /
 * agentPreset.×6 / goal.×6 / credentials.×3 / settings.×5 / skill.list。
 *
 * 降级先例对齐（§2.1）：V1ApiClient 的常量降级（backgroundSession=false /
 * activeSessions=emptyMap / getSessionDiff=emptyList）+ 缺域抛 [UnsupportedServerCapability]
 *（delete/PTY/shell/文件内容读/配置写——返回形态是对象或静默成功会误导用户的方法）。
 *
 * 回程（§1.6-2）：approval/question 应答走 /api/respond（rpcId 复用 requested 帧
 * 的 pending 注册表 id）。
 */
@Singleton
class DshApiClient @Inject constructor(
    private val rpc: DshRpcClient,
) : SessionApi, MessageApi, SystemApi, FileApi, ProviderApi, TerminalApi, ShellApi {

    private fun unsupported(method: String): Nothing =
        throw UnsupportedServerCapability(method, "Dsh")

    // ============ SessionApi（session.list/create/rename/fork/cancel + 本地降级） ============

    override suspend fun listSessions(
        conn: ServerConnection,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int,
    ): List<Session> {
        // cursor 忽略（P-4 实证未实现，传了仍返全量）；directory/search 本地降级过滤
        val value = rpc.call(conn, "session.list", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "session.list failed: " + e.message)
            throw e
        }
        val items = (value.dshArr("items") ?: emptyList()).filterIsInstance<JsonObject>()
        val filtered = DshSessionMapper.filterByDirectory(items, directory)
        var sessions = filtered.map { DshSessionMapper.toSession(it) }
        search?.takeIf { it.isNotBlank() }?.let { q ->
            sessions = sessions.filter { it.title?.contains(q, ignoreCase = true) == true }
        }
        return sessions
    }

    override suspend fun listSessionsPage(
        conn: ServerConnection,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int,
    ): SessionPage = SessionPage(items = listSessions(conn, directory, search, cursor, limit), nextCursor = null)

    /** 无 session.get——session.list 全量取回后本地查找（52 方法面终局）。 */
    override suspend fun getSession(conn: ServerConnection, sessionId: String): Session =
        listSessions(conn).firstOrNull { it.id == sessionId }
            ?: throw IllegalStateException("DSH session not found: $sessionId")

    override suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        val value = rpc.call(conn, "session.list", buildJsonObject {}) { it }.getOrElse { e -> throw e }
        val item = value.dshArr("items")?.firstOrNull {
            (it as? JsonObject)?.dshStr("sessionId") == sessionId
        }
        return item?.toString() ?: throw IllegalStateException("DSH session not found: $sessionId")
    }

    override suspend fun createSession(
        conn: ServerConnection,
        title: String?,
        parentId: String?,
        directory: String?,
    ): Session {
        val payload = buildJsonObject {
            title?.let { put("title", it) }
            parentId?.let { put("parentSessionId", it) }
            directory?.let { put("cwd", it) }
        }
        val value = rpc.call(conn, "session.create", payload) { it }.getOrElse { e -> throw e }
        return mapSessionEcho(value, fallbackTitle = title, blankByDefault = true)
    }

    /** 52 方法无 delete（§2.6 注记）——删除能力位缺口，UI 卡隐藏入口。 */
    override suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean =
        unsupported("session.delete")

    override suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        val value = rpc.call(conn, "session.rename", payload) { it }.getOrElse { e -> throw e }
        return mapSessionEcho(value, fallbackTitle = title, fallbackId = sessionId)
    }

    override suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>,
    ): Session = unsupported("session.updateFields")

    override suspend fun interruptSession(
        conn: ServerConnection,
        sessionId: String,
        directory: String?,
    ): Boolean = rpc.call(conn, "session.cancel", buildJsonObject { put("sessionId", sessionId) }) { Unit }
        .isSuccess

    override suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> = emptyList()

    /** share 域缺失（shareSupported=false 能力位门控 UI）——V2 no-op 先例。 */
    override suspend fun shareSession(conn: ServerConnection, sessionId: String): Session =
        getSession(conn, sessionId)

    override suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session =
        getSession(conn, sessionId)

    /**
     * 压缩根治（#276 后端接口补全）：/compact 走斜杠命令通道（§1.6：prompt 单
     * 文本块以 / 开头 = 服务端命令注册表执行，mode 无关、不进模型）——复用
     * [promptAsync]（mode=queue）。受理成功即 true；压缩**完成**信号走事件：
     * compaction/end → SseEvent.SessionCompacted（mapper）→ compactedSessions
     * 计数 → ChatViewModel 刷新 + 完成 snackbar。[providerId]/[modelId] 对
     * DSH 无效（命令通道无模型参数，调用方签名兼容保留）。
     */
    override suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean {
        promptAsync(conn, sessionId, listOf(PromptPart(type = "text", text = "/compact")))
        return true
    }

    override suspend fun revertSession(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
    ): Session = unsupported("session.revert")

    override suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session =
        unsupported("session.unrevert")

    override suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String?): Session {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        val value = rpc.call(conn, "session.fork", payload) { it }.getOrElse { e -> throw e }
        return mapSessionEcho(value, fallbackId = sessionId)
    }

    override suspend fun importSession(conn: ServerConnection, shareUrl: String): Session =
        unsupported("session.import")

    /**
     * 泛型斜杠命令执行（V1/V2 门面对接）：DSH 无 V1 /command 端点——组装
     * "/{command} {arguments}" 走 commands/execute 既有通道（2026-08-31 斜杠
     * 命令补全定音：服务端命令选择即执行，参照 /permission 先例）。
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
        parts: List<Map<String, String>>?,
    ): Boolean {
        val name = command.trim().trimStart('/')
        val line = if (arguments.isNotBlank()) "/$name $arguments" else "/$name"
        return executeCommand(conn, sessionId, line)
    }

    /**
     * 通用斜杠命令执行（commands/execute typert 通道，非 52 方法面；
     * docs/research/2026-08-31-dsh-permission-sandbox-approval.md §2）。
     *
     * 传输：POST /api/commands/execute，payload {args:{agentId,line,images:[]}}。
     * DSH 单 agent 每会话——agentId == sessionId（SessionId 即 agentId，dsh-commands
     * typert 的 agent 参数 source=lookup 落到 agentId=SessionId）。响应 value =
     * {commandId,result:{kind,text}}，kind!="success" 视为失败（如未知名 → kind:"error"）。
     */
    suspend fun executeCommand(conn: ServerConnection, sessionId: String, line: String): Boolean {
        val payload = buildJsonObject {
            put("args", buildJsonObject {
                put("agentId", sessionId)
                put("line", line)
                put("images", JsonArray(emptyList()))
            })
        }
        return rpc.call(conn, "commands/execute", payload) { value ->
            value.dshObj("result")?.dshStr("kind") == "success"
        }.getOrDefault(false)
    }

    /** 权限预设切换（/permission <preset> 命令封装）；成功 = kind:"success"。 */
    override suspend fun setPermissionPreset(conn: ServerConnection, sessionId: String, preset: String): Boolean =
        executeCommand(conn, sessionId, "/permission $preset")

    /**
     * agentPreset.list → roster（value.presets[{id,name,description,isDefault}]）。
     * 失败软降级空列表（调用方隐藏预设卡，AppLogger.w）。
     */
    override suspend fun listAgentPresets(conn: ServerConnection): List<AgentPreset> {
        val value = rpc.call(conn, "agentPreset.list", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "agentPreset.list failed: " + e.message)
            return emptyList()
        }
        val presets = value.dshArr("presets") ?: emptyList()
        return presets.mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val id = entry.dshStr("id") ?: return@mapNotNull null
            AgentPreset(
                id = id,
                name = entry.dshStr("name") ?: id,
                description = entry.dshStr("description") ?: "",
                isDefault = entry.dshBool("isDefault") ?: false,
            )
        }
    }

    /**
     * agentPreset.select {sessionId, agentPreset}（活体 ap-5/ap-6）：成功 value={agentPreset}；
     * 非 blank 会话 → agent-preset-locked；未知 id → agent-preset-not-found。
     * 错误分支上抛 [DshApiError]（锁定时 category=Busy）由调用方映射可提示文案。
     */
    override suspend fun selectAgentPreset(conn: ServerConnection, sessionId: String, presetId: String): Boolean {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("agentPreset", presetId)
        }
        rpc.call(conn, "agentPreset.select", payload) { Unit }.getOrElse { e -> throw e }
        return true
    }

    // ============ DSH goal 六 mutation（backlog #286；payload 形状照 dsh-goal schema） ============
    // 读侧面 = 'goal' session 投影（无 goal.get）；回执只带新 CAS ref，状态由
    // goal/change 事件/投影帧整值驱动（mutations never feed client state）。

    override suspend fun goalCreate(
        conn: ServerConnection,
        sessionId: String,
        objective: String,
        maxGoalRounds: Long?,
    ): DshGoalRef? {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("objective", objective)
            maxGoalRounds?.let { put("maxGoalRounds", it) }
        }
        return rpc.call(conn, "goal.create", payload) { mapGoalRef(it) }.getOrElse { e -> throw e }
    }

    override suspend fun goalEdit(
        conn: ServerConnection,
        sessionId: String,
        ref: DshGoalRef,
        objective: String?,
        maxGoalRounds: Long?,
    ): DshGoalRef? {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("ref", goalRefJson(ref))
            objective?.let { put("objective", it) }
            maxGoalRounds?.let { put("maxGoalRounds", it) }
        }
        return rpc.call(conn, "goal.edit", payload) { mapGoalRef(it) }.getOrElse { e -> throw e }
    }

    override suspend fun goalPause(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? =
        refMutation(conn, "goal.pause", sessionId, ref)

    override suspend fun goalResume(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? =
        refMutation(conn, "goal.resume", sessionId, ref)

    override suspend fun goalComplete(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? =
        refMutation(conn, "goal.complete", sessionId, ref)

    override suspend fun goalClear(conn: ServerConnection, sessionId: String, ref: DshGoalRef): Boolean {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("ref", goalRefJson(ref))
        }
        return rpc.call(conn, "goal.clear", payload) { value ->
            value.dshBool("cleared") ?: false
        }.getOrElse { e -> throw e }
    }

    /** goal.pause/resume/complete 共享形态：{sessionId, ref} → 回执 {ref}。 */
    private suspend fun refMutation(
        conn: ServerConnection,
        method: String,
        sessionId: String,
        ref: DshGoalRef,
    ): DshGoalRef? {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("ref", goalRefJson(ref))
        }
        return rpc.call(conn, method, payload) { mapGoalRef(it) }.getOrElse { e -> throw e }
    }

    private fun goalRefJson(ref: DshGoalRef): JsonObject = buildJsonObject {
        put("id", ref.id)
        put("revision", ref.revision)
    }

    /** 回执 value.ref → [DshGoalRef]（畸形/缺席 → null——mutation 回执规模面置信）。 */
    private fun mapGoalRef(value: JsonObject): DshGoalRef? {
        val ref = value.dshObj("ref") ?: return null
        val id = ref.dshStr("id") ?: return null
        return DshGoalRef(id = id, revision = ref.dshLong("revision") ?: 0L)
    }

    /** V2 先例：listSessions 本地过滤 parentSessionId。 */
    override suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> =
        runCatching { listSessions(conn).filter { it.parentId == sessionId } }.getOrElse { emptyList() }

    /**
     * subagent.list（AgentSheet 多级树权威域，2026-09-25 活体实录）：payload
     * {parentSessionId}（也接受裸子会话 id——L2 逐层懒加载）；value =
     * {entries:[SubagentListEntry], parentAvailable}。错误恒 HTTP 200 +
     * result.error 上抛（DshApiError），由调用方软降级本地镜像递归。
     *
     * 容错映射：非对象条目/缺 id 跳过；缺 kind 按 child、缺 hasChildren 按
     * false、activity 保留原串（"running"/"inactive" 由上层判定）。
     */
    override suspend fun listSubagentCatalog(
        conn: ServerConnection,
        parentSessionId: String,
    ): List<SubagentListEntryDto> {
        val value = rpc.call(conn, "subagent.list", buildJsonObject {
            put("parentSessionId", parentSessionId)
        }) { it }.getOrElse { e -> throw e }
        val entries = value.dshArr("entries") ?: emptyList()
        return entries.mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val id = entry.dshStr("id") ?: return@mapNotNull null
            SubagentListEntryDto(
                kind = entry.dshStr("kind") ?: "child",
                id = id,
                mode = entry.dshStr("mode"),
                activity = entry.dshStr("activity"),
                hasChildren = entry.dshBool("hasChildren") ?: false,
                label = entry.dshStr("label"),
                reason = entry.dshStr("reason"),
            )
        }
    }

    override suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> = emptyList()

    override suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean = false

    override suspend fun activeSessions(conn: ServerConnection): Map<String, ActiveSessionInfo> = emptyMap()

    override suspend fun listSessionStatus(conn: ServerConnection, directory: String?): Map<String, SessionStatusInfo> = emptyMap()

    /** 存活探测 = host.describe 成功（§2.5：DSH 无 /health）——空 map 起步（#276 任务契约）。 */
    override suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String?,
    ): Result<Map<String, RestSessionStatusInfo>> =
        rpc.call(conn, "host.describe", buildJsonObject {}) { Unit }
            .map { emptyMap<String, RestSessionStatusInfo>() }

    /**
     * session.create/rename/fork 响应形状待 E2E 回填——按 list 条目形状容忍解析，
     * 解析不出时回退最小 Session（id/title 已知字段保真）。
     *
     * blankByDefault：session.create 回显实证为 {sessionId, agentPreset}——不带
     * blank 字段（2026-08-31 活体探测）。刚创建的会话按定义 blank（事件流无
     * turn/start），缺失时按此补真，否则空白页预设卡门控（sessionIsBlank）在
     * 首次点卡（ensureSession 落地）后即翻转、卡片消失、无法反复换档
     * （真机实证回归）。列表/事件刷新后以服务器显式值为准。
     */
    private fun mapSessionEcho(
        value: JsonObject,
        fallbackId: String = "",
        fallbackTitle: String? = null,
        blankByDefault: Boolean = false,
    ): Session {
        val direct = value.takeIf { it.dshStr("sessionId") != null } ?: value.dshObj("session")
        if (direct?.dshStr("sessionId") != null) {
            val mapped = DshSessionMapper.toSession(direct)
            val withBlank = if (blankByDefault && !direct.containsKey("blank")) mapped.copy(blank = true) else mapped
            // 回显形状可能不带 projections.title——请求参数里的 title 是权威回退
            return if (withBlank.title == null && fallbackTitle != null) withBlank.copy(title = fallbackTitle) else withBlank
        }
        AppLogger.w(TAG, "session echo shape unrecognized, falling back to minimal session: " + value.toString().take(120))
        return Session(
            id = direct?.dshStr("sessionId") ?: fallbackId,
            title = fallbackTitle,
            time = Session.Time(created = 0L, updated = 0L),
            blank = blankByDefault,
        )
    }

    // ============ MessageApi（session.prompt/history + /api/respond 回程） ============

    /**
     * 历史分页：session.history{beforeSeq, maxMessages} → HistoryEntry 行 →
     * DshHistoryFolder.fold → DshMessageAssembler 装配 MessagePage。
     *
     * 游标契约：nextCursor = 本页最小事件 seq（字符串）；下一页以 beforeSeq=该值
     * 向旧翻页（§1.6-5）；hasMore=false 或页尽 → null（读尽）。limit 参数映射
     * maxMessages（页边界按 append-origin 消息对齐，§1.5 结论 4）。
     */
    override suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int?,
        before: String?,
    ): MessagePage {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            before?.toLongOrNull()?.let { put("beforeSeq", it) }
            limit?.let { put("maxMessages", it) }
        }
        val value = rpc.call(conn, "session.history", payload) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "session.history failed for $sessionId: " + e.message)
                return MessagePage(messages = emptyList(), nextCursor = null)
            }
        val entries = historyEntryRows(value)
        val fold = DshHistoryFolder.fold(entries, sessionId)
        if (fold.refusedRebuild) {
            // §5 fold 安全规则：未知事件类型 → 放弃本次重建（展示残缺历史比空更糟）
            AppLogger.w(TAG, "history fold refused rebuild for $sessionId: " + fold.unknownUnignorable)
            return MessagePage(messages = emptyList(), nextCursor = null)
        }
        val messages = DshMessageAssembler.assemble(fold.sseEvents)
        // 翻页游标 = 本页最小 seq（beforeSeq 向旧翻页，§1.6-5）——lastSeq 是最大 seq，
        // 只有页内首行才是下一页的锚点。
        val minSeq = entries.minOfOrNull { row ->
            val entry = row.dshObj("event") ?: row
            entry.dshLong("seq") ?: entry.dshLong("seq0") ?: Long.MAX_VALUE
        } ?: Long.MAX_VALUE
        val hasMore = value.dshBool("hasMore") ?: false
        val nextCursor = if (hasMore && minSeq != Long.MAX_VALUE) minSeq.toString() else null
        return MessagePage(messages = messages, nextCursor = nextCursor)
    }

    override suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        return rpc.call(conn, "session.history", payload) { it.toString() }.getOrElse { e -> throw e }
    }

    /**
     * 导出根治（#276 后端接口补全）：GET {base}/api/session.export?sessionId=
     * 是 4 个非信封入口之一（§5 P-4）——响应体直接是会话日志 ZIP 流（无 RPC
     * 信封、无 JSON）。Ktor GET + bodyAsChannel 逐块读出（readAvailable 循环，
     * UpdateRepository 同款流式模式），copyTo(outputStream) + onProgress(累计
     * 字节)。conn 无 auth 头（DSH 无鉴权）、Host 由 OkHttp 按 URL 自动生成
     * （§1.6 P-1 栅栏只看 Host）。非 200 → IOException（导出失败通知依赖）。
     */
    override suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        val response = rpc.http.get(conn.baseUrl.trimEnd('/') + "/api/session.export") {
            parameter("sessionId", sessionId)
        }
        if (response.status.value != 200) {
            throw java.io.IOException("session.export HTTP " + response.status.value)
        }
        var bytesWritten = 0L
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) continue
            outputStream.write(buffer, 0, read)
            bytesWritten += read
            onProgress(bytesWritten)
        }
        outputStream.flush()
    }

    override suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts =
        listMessages(conn, sessionId).messages.firstOrNull { it.info.id == messageId }
            ?: throw IllegalStateException("DSH message not found: $messageId")

    /**
     * 发送消息：session.prompt（payload content = P-4 PromptContentPart 形态）。
     * text part → {type:text,text}；图片 file part（data URL）→ {type:image,data,mime}。
     * 受理回执 null（V1 先例）——用户消息经 WS session/event 回显（mapper 播种）。
     */
    override suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: dev.leonardo.ocbeacon.data.dto.common.ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?,
    ): PromptAdmission? {
        val content = parts.mapNotNull { part -> promptContentPart(part) }
        if (content.isEmpty()) {
            AppLogger.w(TAG, "session.prompt skipped: no mappable content parts")
            return null
        }
        if (model != null) {
            // V2 先例（promptAsync → switchModel）：session.prompt 无模型参数——
            // 发送前显式切换会话模型。业务拒绝（subagent-origin 会话 agent-busy，
            // 11 号实测证据）不阻断发送，仅告警。
            val selected = selectModel(conn, sessionId, model.providerId, model.modelId, variant)
            if (!selected) {
                AppLogger.w(TAG, "session.selectModel rejected (continuing with prompt): " +
                    model.providerId + "/" + model.modelId)
            }
        }
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("content", JsonArray(content))
            // E2E 实证（2026-08-31）：mode 必填（zod expected queue|steer，缺席整单拒绝）。
            // queue→send（对齐 oc-beacon 既有排队语义）；steer=注入进行中轮次，留给后续 UX。
            put("mode", "queue")
        }
        rpc.call(conn, "session.prompt", payload) { Unit }.getOrElse { e -> throw e }
        return null
    }

    /**
     * session.selectModel（M03 实测证据）：payload {sessionId, provider, model,
     * reasoningEffort?}——ModelSelection 切换当前会话模型；variant 槽位（思考
     * 档位 pill）映射 reasoningEffort，null（默认档）缺席交服务器 defaultEffort。
     * 失败返回 false（业务拒绝/传输失败均容错，调用方不据此阻断发送）。
     */
    private suspend fun selectModel(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String,
        reasoningEffort: String?,
    ): Boolean = rpc.call(conn, "session.selectModel", buildJsonObject {
        put("sessionId", sessionId)
        put("provider", providerId)
        put("model", modelId)
        reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoningEffort", it) }
    }) { Unit }.isSuccess

    private fun promptContentPart(part: PromptPart): JsonObject? = when {
        part.type == "text" && !part.text.isNullOrBlank() -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }
        part.type == "file" && part.url != null -> {
            // data URL → {type:image, data(base64), mime}；远程 URL 不带 data 时透传 url
            val url = part.url!!
            val (mime, data) = if (url.startsWith("data:")) {
                val header = url.substringBefore(",", "")
                (header.removePrefix("data:").substringBefore(";")) to url.substringAfter(",", "")
            } else {
                (part.mime ?: "application/octet-stream") to null
            }
            buildJsonObject {
                put("type", "image")
                data?.let { put("data", it) } ?: put("url", url)
                put("mime", mime)
                part.filename?.let { put("name", it) }
            }
        }
        else -> null
    }

    /**
     * updateQueue（2026-09-01 QueueDock）：对仍待发的排队项施加 edit/remove/steer。
     *
     * payload：{sessionId, itemId, action:{kind: edit|remove|steer}}——edit 带
     * content=[{type:text,text}]（纯文本改写，官方 QueueAction 契约）；steer 仅
     * running + next-turn 有效（否则服务器回 steer-unavailable）；子代理会话
     * 拒绝（agent-busy）。错误码 → [dev.leonardo.ocbeacon.domain.model.QueueMutationResult]。
     */
    override suspend fun updateQueue(
        conn: ServerConnection,
        sessionId: String,
        itemId: String,
        action: dev.leonardo.ocbeacon.domain.model.QueueActionKind,
        editText: String?,
    ): dev.leonardo.ocbeacon.domain.model.QueueMutationResult {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put(
                "action",
                buildJsonObject {
                    when (action) {
                        dev.leonardo.ocbeacon.domain.model.QueueActionKind.EDIT -> {
                            put("kind", "edit")
                            put(
                                "content",
                                kotlinx.serialization.json.JsonArray(
                                    listOf(buildJsonObject {
                                        put("type", "text")
                                        put("text", editText ?: "")
                                    })
                                ),
                            )
                        }
                        dev.leonardo.ocbeacon.domain.model.QueueActionKind.REMOVE -> put("kind", "remove")
                        dev.leonardo.ocbeacon.domain.model.QueueActionKind.STEER -> put("kind", "steer")
                    }
                },
            )
        }
        return rpc.call(conn, "updateQueue", payload) { Unit }.fold(
            onSuccess = { dev.leonardo.ocbeacon.domain.model.QueueMutationResult.Accepted },
            onFailure = { e ->
                val code = (e as? DshApiError)?.code
                when {
                    code == DshRpcErrorCode.SteerUnavailable ->
                        dev.leonardo.ocbeacon.domain.model.QueueMutationResult.SteerUnavailable
                    code == DshRpcErrorCode.QueueItemNotFound ->
                        dev.leonardo.ocbeacon.domain.model.QueueMutationResult.QueueItemNotFound
                    code == DshRpcErrorCode.AgentBusy ->
                        dev.leonardo.ocbeacon.domain.model.QueueMutationResult.Busy
                    else -> dev.leonardo.ocbeacon.domain.model.QueueMutationResult.Failed(
                        (e as? DshApiError)?.message ?: (e.message ?: "updateQueue failed")
                    )
                }
            },
        )
    }

    override suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean = false

    override suspend fun deleteMessagePart(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        partIndex: Int,
    ): Boolean = false

    /**
     * 权限应答（/api/respond 回程，§1.6-2）：outcome 词汇 once→allowed-once /
     * always→allowed-always / reject→rejected（allowed-once 见 P-4 fixture，
     * 其余两词 E2E 定音）。[requestId] 即 PermissionAsked.id——requested 帧 rpcId
     * 透传在 mapper 层（#276 接线注意①）。
     */
    override suspend fun replyToPermission(
        conn: ServerConnection,
        sessionId: String,
        requestId: String,
        reply: String,
        message: String?,
        directory: String?,
    ): Boolean {
        val outcome = when (reply) {
            "once" -> "allowed-once"
            "always" -> "allowed-always"
            "reject" -> "rejected"
            else -> reply
        }
        return rpc.respond(conn, requestId, buildJsonObject { put("outcome", outcome) }).isSuccess
    }

    /** 无待处理权限 REST 端点（开流即重放未决帧，§1.5 结论 5）——空列表。 */
    override suspend fun listPendingPermissions(
        conn: ServerConnection,
        directory: String?,
    ): List<PermissionRequest> = emptyList()

    /**
     * 提问应答（/api/respond）：answers（有序列表，V1 形态）→ {questionId: 选值}
     * 键控 map——键取 [question] 各 item 的 key/id（V2FormMapper 同思路）。
     */
    override suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
        question: SseEvent.QuestionAsked?,
    ): Boolean {
        if (question == null) return false
        val answerObj = buildJsonObject {
            question.questions.forEachIndexed { index, q ->
                val key = q.key ?: q.question
                val selected = answers.getOrNull(index) ?: emptyList()
                put(key, if (selected.size == 1) kotlinx.serialization.json.JsonPrimitive(selected[0]) else JsonArray(selected.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        }
        return rpc.respond(conn, requestId, buildJsonObject { put("answers", answerObj) }).isSuccess
    }

    override suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String?,
        sessionId: String?,
    ): Boolean = rpc.respond(conn, requestId, buildJsonObject { put("outcome", "cancelled") }).isSuccess

    override suspend fun listPendingQuestions(
        conn: ServerConnection,
        directory: String?,
    ): List<QuestionRequest> = emptyList()

    // ============ SystemApi（host.describe + 常量降级） ============

    override suspend fun getHealth(conn: ServerConnection): ServerHealth {
        val value = rpc.call(conn, "host.describe", buildJsonObject {}) { it }.getOrElse { e -> throw e }
        return ServerHealth(healthy = true, version = value.dshStr("version"))
    }

    override suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        val value = rpc.call(conn, "host.describe", buildJsonObject {}) { it }.getOrElse { e -> throw e }
        return ServerPaths(
            home = value.dshStr("home") ?: "",
            directory = value.dshStr("cwd") ?: "",
        )
    }

    /** V2 输入行 agent 循环切换器域（SystemApi）——DSH 无 agent loop，空列表降级；
     *  Agent 预设域另走 [listAgentPresets]/[selectAgentPreset]。 */
    override suspend fun listAgents(conn: ServerConnection): List<AgentInfo> = emptyList()

    override suspend fun listCommands(conn: ServerConnection): List<CommandInfo> = listCommands(conn, null)

    /**
     * commands/list typert 通道（2026-08-31 活体定音：方法存在且可用，早前
     * “command.list/slashCommand.list/commands.list 404” 证据属陈旧部署）。
     *
     * 传输：POST /api/commands/list，payload {args:{agentId}}（agentId == sessionId，
     * DSH 单 agent 每会话）。value = CommandDescriptor[] [{name, description,
     * input?:{hint,images?}}]。会话缺席（null，懒建前）→ 空列表——DSH 命令枚举是
     * agent-scoped 的，无会话无法枚举。
     */
    override suspend fun listCommands(conn: ServerConnection, sessionId: String?): List<CommandInfo> {
        if (sessionId == null) return emptyList()
        val value = rpc.callJson(conn, "commands/list", buildJsonObject {
            put("args", buildJsonObject { put("agentId", sessionId) })
        }) { it }.getOrElse { e ->
            AppLogger.w(TAG, "commands/list failed: " + e.message)
            return emptyList()
        }
        val list = value as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val name = entry.dshStr("name") ?: return@mapNotNull null
            CommandInfo(
                name = name,
                description = entry.dshStr("description"),
                source = "server",
                hints = entry.dshObj("input")?.dshStr("hint")?.let { listOf(it) } ?: emptyList(),
            )
        }
    }

    /** skill.list 需 attached 会话（§5 坑位：冷会话→session-not-found）——空列表降级。 */
    override suspend fun listSkills(conn: ServerConnection, directory: String?): List<SkillInfo> = emptyList()

    override suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> = emptyMap()

    override suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean = false

    override suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean = false

    // ============ FileApi（host.listDirectory/workspace.list；内容读无方法） ============

    override suspend fun findFiles(
        conn: ServerConnection,
        query: String,
        type: String?,
        directory: String?,
        limit: Int?,
        dirs: String?,
    ): List<String> = emptyList()

    /** 文件内容读取无方法（§2.6：host.openPath 是宿主侧打开，特权）——能力位缺口。 */
    override suspend fun readFile(conn: ServerConnection, path: String, directory: String?): FileContentDto =
        unsupported("file.read")

    override suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> = emptyList()

    override suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean =
        rpc.call(conn, "host.listDirectory", buildJsonObject { put("path", directory) }) { Unit }.isSuccess

    /**
     * #276 走查 N2（D1 workspace 空路径）：DSH host.listDirectory 要求
     * fully-qualified path——path=""（OpenCode 语义的工作区根）直传会
     * directory-unreadable。解析序：①调用方 [directory]（会话 cwd 等
     * fully-qualified 值）②workspace.list 首个 workspace path（单一真相源——
     * UI 工作区标签同源；按 baseUrl 缓存，注册表极少变）③host.describe cwd
     * （服务器启动目录兜底）。
     */
    override suspend fun listDirectory(
        conn: ServerConnection,
        path: String,
        directory: String?,
    ): List<FileNodeDto> {
        val effectivePath = if (path.isBlank()) {
            directory?.takeIf { it.isNotBlank() } ?: resolveRootPath(conn)
        } else {
            path
        }
        val value = rpc.call(conn, "host.listDirectory", buildJsonObject { put("path", effectivePath) }) { it }.getOrElse { e -> throw e }
        val entries = value.dshArr("entries") ?: value.dshArr("items") ?: emptyList()
        return entries.mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val name = entry.dshStr("name") ?: return@mapNotNull null
            val rawType = entry.dshStr("type")?.lowercase()
            val type = when (rawType) {
                "directory", "dir" -> "directory"
                "file" -> "file"
                // #276 终验 V4（协议级补偿）：DSH host.listDirectory 条目无 type
                // 判别（活体样本仅 {name,path,hidden}）——缺省按 directory 映射，
                // 全部可展开；非目录路径由 UI 层展开失败（directory-unreadable）
                // 时转标 file 叶（WorkspaceViewModel 失败分支），已解析类型随树缓存。
                else -> "directory"
            }
            val entryPath = entry.dshStr("path") ?: joinPath(path, name)
            FileNodeDto(name = name, path = entryPath, type = type, absolute = entryPath)
        }
    }

    /** 根路径缓存（baseUrl → 解析结果）——workspace 注册表极少变，树根仅为浏览起点。 */
    private val rootPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 空 path 的根解析：workspace.list 首个 path → host.describe cwd；都无则显式失败。 */
    private suspend fun resolveRootPath(conn: ServerConnection): String {
        val base = conn.baseUrl.trimEnd('/')
        rootPathCache[base]?.let { return it }
        val workspaceRoot = runCatching {
            val value = rpc.call(conn, "workspace.list", buildJsonObject {}) { it }.getOrNull()
            val items = value?.dshArr("items") ?: value?.dshArr("workspaces") ?: emptyList()
            items.asSequence()
                .mapNotNull { it as? JsonObject }
                .firstNotNullOfOrNull { it.dshStr("path") ?: it.dshStr("cwd") ?: it.dshStr("directory") }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val root = workspaceRoot ?: runCatching {
            rpc.call(conn, "host.describe", buildJsonObject {}) { it }.getOrNull()?.dshStr("cwd")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: throw DshApiError(null, "cannot resolve root path for empty listDirectory request", null, null)
        rootPathCache[base] = root
        return root
    }

    override suspend fun findSymbols(conn: ServerConnection, query: String, directory: String?): List<SymbolInfo> = emptyList()

    override suspend fun getFileStatus(conn: ServerConnection, directory: String?): List<FileStatusInfo> = emptyList()

    override suspend fun getVcs(conn: ServerConnection, directory: String?): VcsBranchDto = VcsBranchDto(branch = null)

    override suspend fun getVcsStatus(conn: ServerConnection, directory: String?): List<VcsChangeDto> = emptyList()

    override suspend fun getVcsDiff(
        conn: ServerConnection,
        mode: String,
        context: Int,
        directory: String?,
    ): List<FileDiffDto> = emptyList()

    /** workspace.list → Project（时间戳单位双态坑位 §5：workspace 侧 ISO 字符串不进 Project）。 */
    override suspend fun listProjects(conn: ServerConnection): List<Project> {
        val value = rpc.call(conn, "workspace.list", buildJsonObject {}) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "workspace.list failed: " + e.message)
                return emptyList()
            }
        val items = value.dshArr("items") ?: value.dshArr("workspaces") ?: emptyList()
        return items.mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val worktree = entry.dshStr("path") ?: entry.dshStr("cwd") ?: entry.dshStr("directory") ?: return@mapNotNull null
            Project(
                id = entry.dshStr("id") ?: entry.dshStr("workspaceId") ?: worktree,
                worktree = worktree,
                name = entry.dshStr("name"),
            )
        }
    }

    override suspend fun getCurrentProject(conn: ServerConnection): Project =
        listProjects(conn).firstOrNull() ?: Project()

    // ============ TerminalApi（PTY 域整体缺失，§2.6 终局确认） ============

    override suspend fun createPty(
        conn: ServerConnection,
        title: String?,
        cwd: String?,
        directory: String?,
    ): PtyInfo = unsupported("pty.create")

    override suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean = unsupported("pty.remove")

    override suspend fun updatePtySize(
        conn: ServerConnection,
        ptyId: String,
        cols: Int,
        rows: Int,
        directory: String?,
    ): Boolean = unsupported("pty.resize")

    override suspend fun openPtySocket(
        conn: ServerConnection,
        ptyId: String,
        cursor: Int,
        directory: String?,
    ): dev.leonardo.ocbeacon.data.dto.common.PtySocket = unsupported("pty.connect")

    override suspend fun listPtyShells(conn: ServerConnection, directory: String?): List<ShellInfo> =
        unsupported("pty.shells")

    override suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: dev.leonardo.ocbeacon.data.dto.common.ModelSelection?,
        directory: String?,
    ): Boolean = unsupported("session.shell")

    // ============ ShellApi（shell 域缺失；后台任务 = session/jobs 帧 + goal/subagent 域） ============

    override suspend fun listShells(conn: ServerConnection, directory: String?): List<ShellJob> =
        unsupported("shell.list")

    override suspend fun getShell(conn: ServerConnection, shellId: String, directory: String?): ShellJob? =
        unsupported("shell.get")

    override suspend fun getShellOutput(
        conn: ServerConnection,
        shellId: String,
        cursor: Long?,
        limit: Int?,
        directory: String?,
    ): ShellOutput? = unsupported("shell.output")

    override suspend fun removeShell(conn: ServerConnection, shellId: String, directory: String?): Boolean =
        unsupported("shell.remove")

    // ============ ProviderApi（llm 目录读；配置写/特权面不开放） ============

    /**
     * llm.providers + llm.models → ProvidersResponse（#276 模型切换接通；05/06 号
     * 活体证据，V2 getProviders 双端点拼目录先例）。
     *
     * 目录条目 {provider, displayName} → id/name（id/name 旧键防御兼容）；组
     * {id, name, models[{id, name, reasoning{efforts[{id,name}], defaultEffort}}]}：
     * 组内模型挂同名 provider，efforts → variants（variantNames 驱动思考档位
     * pill）+ capabilities.reasoning 槽位。两端各自软降级——目录失败按组序兜底
     * 拼目录；组失败目录仍完整返回（模型空，由上层 applyProviderFilter 过滤）。
     */
    override suspend fun getProviders(conn: ServerConnection): dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse {
        val directory = rpc.call(conn, "llm.providers", buildJsonObject {}) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "llm.providers failed: " + e.message)
                null
            }
        val groups = rpc.call(conn, "llm.models", buildJsonObject {}) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "llm.models failed: " + e.message)
                null
            }

        // 目录序（providers 数组即 directory order；undeclared routes appended）
        val directoryNames = linkedMapOf<String, String>()
        directory?.dshArr("providers")?.filterIsInstance<JsonObject>()?.forEach { entry ->
            val id = entry.dshStr("provider") ?: entry.dshStr("id") ?: return@forEach
            directoryNames[id] = entry.dshStr("displayName") ?: entry.dshStr("name") ?: id
        }

        val groupsById = linkedMapOf<String, ProviderInfo>()
        groups?.dshArr("groups")?.filterIsInstance<JsonObject>()?.forEach { group ->
            val groupId = group.dshStr("id") ?: return@forEach
            val models = (group.dshArr("models") ?: emptyList()).filterIsInstance<JsonObject>().mapNotNull { m ->
                val modelId = m.dshStr("id") ?: return@mapNotNull null
                val efforts = m.dshObj("reasoning")?.dshArr("efforts")
                    ?.filterIsInstance<JsonObject>().orEmpty()
                val variants = efforts.mapNotNull { e -> e.dshStr("id")?.let { it to e } }.toMap()
                    .takeIf { it.isNotEmpty() }
                ProviderModel(
                    id = modelId,
                    providerId = groupId,
                    name = m.dshStr("name") ?: modelId,
                    capabilities = variants?.let { ModelCapabilities(reasoning = true) },
                    variants = variants,
                )
            }
            if (models.isNotEmpty()) {
                groupsById[groupId] = ProviderInfo(
                    id = groupId,
                    name = group.dshStr("name") ?: groupId,
                    source = "dsh",
                    models = models.associateBy { it.id },
                )
            }
        }

        // 合流：目录序优先（目录名优先于组名）；目录未覆盖的组防御性追加
        // （目录整体失败时 groupsById 即全量——组序兜底）。
        val providers = buildList {
            directoryNames.forEach { (id, name) ->
                val fromGroup = groupsById.remove(id)
                add(fromGroup?.copy(name = name) ?: ProviderInfo(id = id, name = name, source = "dsh"))
            }
            addAll(groupsById.values)
        }
        return dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse(providers = providers)
    }

    override suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse =
        ProviderCatalogResponse(all = getProviders(conn).providers)

    override suspend fun getProviderAuthMethods(
        conn: ServerConnection,
    ): Map<String, List<ProviderAuthMethod>> = emptyMap()

    /** OAuth 域缺失（DSH 凭据走 credentials.* 特权面，UI 不在本期）。 */
    override suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
    ): ProviderOauthAuthorization? = null

    override suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = false

    override suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean =
        unsupported("credentials.write")

    override suspend fun removeProviderCredential(conn: ServerConnection, providerId: String): Boolean =
        unsupported("credentials.remove")

    /** settings.* 读最小映射（特权面；configEditable=false 门控 UI）。 */
    override suspend fun getConfig(conn: ServerConnection): ServerConfigResponse = ServerConfigResponse()

    override suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse = ServerConfigResponse()

    /**
     * 读新会话默认权限档（settings.describe ns=permission，特权但 loopback 可读）。
     * value = {writable,hasDocument,namespaces:[{ns,schema,value,revision,...}]}；
     * 取 ns=permission 的 value.defaultPreset + revision。部署未挂 permission 插件 → null。
     */
    suspend fun getPermissionDefault(conn: ServerConnection): dev.leonardo.ocbeacon.domain.model.DshPermissionDefault? {
        val value = rpc.call(conn, "settings.describe", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "settings.describe failed: " + e.message)
            return null
        }
        val permission = (value.dshArr("namespaces") ?: emptyList())
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.dshStr("ns") == "permission" }
            ?: return null
        val currentValue = permission.dshObj("value")?.dshStr("defaultPreset") ?: return null
        return dev.leonardo.ocbeacon.domain.model.DshPermissionDefault(
            currentValue = currentValue,
            revision = permission.dshLong("revision") ?: 0L,
        )
    }

    /**
     * 写新会话默认权限档（settings.mutate ns=permission，path=["defaultPreset"]）。
     * expectedRevision 先经 settings.describe 取当前 revision（乐观并发，陈旧 → settings-conflict）。
     */
    suspend fun setPermissionDefault(conn: ServerConnection, preset: String): Boolean {
        val current = getPermissionDefault(conn) ?: return false
        val payload = buildJsonObject {
            put("ns", "permission")
            put("ops", JsonArray(listOf(buildJsonObject {
                put("op", "set")
                put("path", JsonArray(listOf(JsonPrimitive("defaultPreset"))))
                put("value", JsonPrimitive(preset))
            })))
            put("expectedRevision", current.revision)
        }
        return rpc.call(conn, "settings.mutate", payload) { Unit }.isSuccess
    }

    /**
     * 读新会话默认 Agent 预设（settings.describe ns=agent-presets，§6 官方 Web General 设置行）。
     * value = {writable,hasDocument,namespaces:[{ns,value,revision,...}]}；取 ns=agent-presets
     * 的 value.default + revision。部署未挂 agent-presets 插件 → null。
     */
    suspend fun getDefaultAgentPreset(conn: ServerConnection): DshAgentPresetDefault? {
        val value = rpc.call(conn, "settings.describe", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "settings.describe failed: " + e.message)
            return null
        }
        val ns = (value.dshArr("namespaces") ?: emptyList())
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.dshStr("ns") == "agent-presets" }
            ?: return null
        val currentValue = ns.dshObj("value")?.dshStr("default") ?: return null
        return DshAgentPresetDefault(
            currentValue = currentValue,
            revision = ns.dshLong("revision") ?: 0L,
        )
    }

    /**
     * 写新会话默认 Agent 预设（settings.mutate ns=agent-presets，path=["default"]）。
     * expectedRevision 先经 settings.describe 取当前 revision（乐观并发，陈旧 → settings-conflict）。
     */
    suspend fun setDefaultAgentPreset(conn: ServerConnection, preset: String): Boolean {
        val current = getDefaultAgentPreset(conn) ?: return false
        val payload = buildJsonObject {
            put("ns", "agent-presets")
            put("ops", JsonArray(listOf(buildJsonObject {
                put("op", "set")
                put("path", JsonArray(listOf(JsonPrimitive("default"))))
                put("value", JsonPrimitive(preset))
            })))
            put("expectedRevision", current.revision)
        }
        return rpc.call(conn, "settings.mutate", payload) { Unit }.isSuccess
    }

    override suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        unsupported("settings.write")

    override suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        unsupported("settings.write.global")

    override suspend fun disposeGlobal(conn: ServerConnection): Boolean = false

    override suspend fun disposeInstance(conn: ServerConnection): Boolean = false

    // ============ 共用 ============

    /** session.history 响应行提取：entries/events 数组（HistoryEntry={event,view?} 由 folder 解包）。 */
    internal fun historyEntryRows(value: JsonObject): List<JsonObject> {
        val rows = value.dshArr("entries") ?: value.dshArr("events") ?: return emptyList()
        return rows.mapNotNull { it as? JsonObject }
    }

    private fun joinPath(base: String, name: String): String =
        if (base.isEmpty()) name else base.trimEnd('/') + "/" + name
}