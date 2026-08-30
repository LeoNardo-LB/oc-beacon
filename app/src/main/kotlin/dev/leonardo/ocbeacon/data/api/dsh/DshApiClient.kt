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
import dev.leonardo.ocbeacon.data.dto.response.PermissionRequest
import dev.leonardo.ocbeacon.data.dto.response.ProviderAuthMethod
import dev.leonardo.ocbeacon.data.dto.response.ProviderCatalogResponse
import dev.leonardo.ocbeacon.data.dto.response.ProviderInfo
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
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        return mapSessionEcho(value, fallbackTitle = title)
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

    override suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = false

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
    ): Boolean = false

    /** V2 先例：listSessions 本地过滤 parentSessionId。 */
    override suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session> =
        runCatching { listSessions(conn).filter { it.parentId == sessionId } }.getOrElse { emptyList() }

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
     */
    private fun mapSessionEcho(value: JsonObject, fallbackId: String = "", fallbackTitle: String? = null): Session {
        val direct = value.takeIf { it.dshStr("sessionId") != null } ?: value.dshObj("session")
        if (direct?.dshStr("sessionId") != null) {
            val mapped = DshSessionMapper.toSession(direct)
            // 回显形状可能不带 projections.title——请求参数里的 title 是权威回退
            return if (mapped.title == null && fallbackTitle != null) mapped.copy(title = fallbackTitle) else mapped
        }
        AppLogger.w(TAG, "session echo shape unrecognized, falling back to minimal session: " + value.toString().take(120))
        return Session(
            id = direct?.dshStr("sessionId") ?: fallbackId,
            title = fallbackTitle,
            time = Session.Time(created = 0L, updated = 0L),
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

    override suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit,
    ) = unsupported("session.export")

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

    /** agentPreset 域映射待 UI 卡（模式选择器不在本期）——空列表降级。 */
    override suspend fun listAgents(conn: ServerConnection): List<AgentInfo> = emptyList()

    override suspend fun listCommands(conn: ServerConnection): List<CommandInfo> = emptyList()

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

    override suspend fun listDirectory(
        conn: ServerConnection,
        path: String,
        directory: String?,
    ): List<FileNodeDto> {
        val value = rpc.call(conn, "host.listDirectory", buildJsonObject { put("path", path) }) { it }.getOrElse { e -> throw e }
        val entries = value.dshArr("entries") ?: value.dshArr("items") ?: emptyList()
        return entries.mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val name = entry.dshStr("name") ?: return@mapNotNull null
            val rawType = entry.dshStr("type")?.lowercase()
            val type = when (rawType) {
                "directory", "dir" -> "directory"
                "file" -> "file"
                else -> "file"
            }
            val entryPath = entry.dshStr("path") ?: joinPath(path, name)
            FileNodeDto(name = name, path = entryPath, type = type, absolute = entryPath)
        }
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

    // ============ ProviderApi（llm.providers 读；配置写/特权面不开放） ============

    /** llm.providers → ProvidersResponse（模型目录形状 E2E 回填，最小映射）。 */
    override suspend fun getProviders(conn: ServerConnection): dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse {
        val value = rpc.call(conn, "llm.providers", buildJsonObject {}) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "llm.providers failed: " + e.message)
                return dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse(providers = emptyList())
            }
        val items = value.dshArr("providers") ?: value.dshArr("items") ?: emptyList()
        val providers = items.mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val id = entry.dshStr("id") ?: entry.dshStr("providerId") ?: return@mapNotNull null
            ProviderInfo(id = id, name = entry.dshStr("name") ?: id)
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
