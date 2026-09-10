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
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDocument
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetRoster
import dev.leonardo.ocbeacon.domain.model.DshConfigurableProvider
import dev.leonardo.ocbeacon.domain.model.DshCredentialStatus
import dev.leonardo.ocbeacon.domain.model.DshCustomProviderDraft
import dev.leonardo.ocbeacon.domain.model.DshCustomProviders
import dev.leonardo.ocbeacon.domain.model.DshDiscoveredModel
import dev.leonardo.ocbeacon.domain.model.DshGoalRef
import dev.leonardo.ocbeacon.domain.model.DshModelDiscoveryRequest
import dev.leonardo.ocbeacon.domain.model.DshPluginEnabled
import dev.leonardo.ocbeacon.domain.model.DshPluginInventory
import dev.leonardo.ocbeacon.domain.model.DshPluginInventoryEntry
import dev.leonardo.ocbeacon.domain.model.DshPluginInventoryPreset
import dev.leonardo.ocbeacon.domain.model.DshPluginInventoryPresetRow
import dev.leonardo.ocbeacon.domain.model.DshSettingsOp
import dev.leonardo.ocbeacon.domain.model.DshSettingsSnapshot
import dev.leonardo.ocbeacon.domain.model.DshSkillInfo
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionPage
import dev.leonardo.ocbeacon.domain.model.SessionSearchHit
import dev.leonardo.ocbeacon.domain.model.SessionSearchResult
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ShellOutput
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.Workspace
import dev.leonardo.ocbeacon.domain.repository.DshSettingsForbiddenException
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.PathUtils
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DshApi"

/** #322：session/search 契约 snippet 上限（Unicode 码点；服务器侧同值保证）。 */
private const val SNIPPET_MAX_CODE_POINTS = 240

/**
 * DSH 七域 API 实现（backlog #276 步骤③；设计 §2.6 方法面 → 域接口映射表）。
 *
 * 与 V1ApiClient/V2ApiClient 并列实现同一批域接口；#391 切片4 后由适配器注册表按连接解析端口。
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
    private val protocolSource: DshProtocolSource,
) : SessionApi, MessageApi, SystemApi, FileApi, ProviderApi, TerminalApi, ShellApi {

    /**
     * 测试便利构造（未探测线面——保守 V011；生产走 [DshProtocolSourceAdapter]）。
     */
    internal constructor(rpc: DshRpcClient) : this(rpc, DshUnprobedProtocolSource)

    /** 线面版本判定（未探测保守 V011——与 DshRpcClient 翻译同源语义）。 */
    private fun protocolOf(conn: ServerConnection): DshWireProtocol =
        protocolSource.protocolOf(conn.baseUrl) ?: DshWireProtocol.V011

    /**
     * #331：create/fork/rename 回执时钟（测试注入；生产 System.currentTimeMillis）。
     * 0.1.2 schema 的 session.create/session.fork 回显只有 {sessionId,(agentPreset?)}
     * **无 updatedAt**（typert.host.js result schema 实证）——回执行若带 epoch0
     * updated 入库，按 time.updated 倒序沉列表底部（观测「新会话行约 3 分钟才入
     * 列表顶位」的回执腿）。以本地时钟补排序位；服务器真值由后续 session.list
     * 基线 / added 帧 updatedAt 覆盖。
     */
    internal var echoClock: () -> Long = { System.currentTimeMillis() }

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

    /**
     * session.list 全量映射（含 blank 空壳）——#311 Task3 连接复用判定候选源。
     * 与 [listSessions] 差异：不经 [DshSessionMapper.filterByDirectory]（blank
     * 滤除面）——web connectWorkspace 复用判定在含 blank 的会话集上进行
     * （mod29:46-58）。
     */
    suspend fun listSessionsIncludingBlank(conn: ServerConnection): List<Session> {
        val value = rpc.call(conn, "session.list", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "session.list failed: " + e.message)
            throw e
        }
        return (value.dshArr("items") ?: emptyList())
            .filterIsInstance<JsonObject>()
            .map { DshSessionMapper.toSession(it) }
    }

    /** 无 session.get——session.list 全量取回后本地查找（52 方法面终局）。 */
    override suspend fun getSession(conn: ServerConnection, sessionId: String): Session =
        listSessions(conn).firstOrNull { it.id == sessionId }
            ?: throw IllegalStateException("DSH session not found: $sessionId")

    /**
     * #322：session/search 服务端内容搜索（按名字+内容搜全部历史会话，上限 20）。
     *
     * wire（0.1.2）：{args:{request:{query}}}（typert parameter wire="request"）→
     * 回 {items:[{sessionId,snippet}],hasMore}；snippet ≤240 码点（服务器
     * SESSION_SEARCH_SNIPPET_MAX_CODE_POINTS 保证——客户端防御性同限截断，不劈代理对）。
     * 畸形行容错：非对象行 / sessionId 缺席或空白 / snippet 缺席——逐行跳过不整批失败；
     * hasMore 缺席按 false。命中无消息级锚点（wire 无 messageId）——UI 只做会话级跳转。
     * 保守 V011：session.search 不在 0.1.1 方法面（#322 裁决）——unsupported。
     */
    override suspend fun searchSessions(conn: ServerConnection, query: String): SessionSearchResult {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("session.search")
        val value = rpc.call(conn, "session.search", buildJsonObject {
            put("query", query)
        }) { it }.getOrElse { e -> throw e }
        val items = (value.dshArr("items") ?: emptyList()).mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val sessionId = row.dshStr("sessionId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val snippet = row.dshStr("snippet") ?: return@mapNotNull null
            dev.leonardo.ocbeacon.domain.model.SessionSearchHit(
                sessionId = sessionId,
                snippet = truncateSnippetCodePoints(snippet),
            )
        }
        return dev.leonardo.ocbeacon.domain.model.SessionSearchResult(
            items = items,
            hasMore = value.dshBool("hasMore") ?: false,
        )
    }

    /** snippet 防御性截断（≤240 码点；offsetByCodePoints 保证不劈代理对）。 */
    private fun truncateSnippetCodePoints(snippet: String): String {
        if (snippet.codePointCount(0, snippet.length) <= SNIPPET_MAX_CODE_POINTS) return snippet
        return snippet.substring(0, snippet.offsetByCodePoints(0, SNIPPET_MAX_CODE_POINTS))
    }

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
        workspaceId: String?,
        agentPreset: String?,
    ): Session {
        if (protocolOf(conn) == DshWireProtocol.V012) {
            // 0.1.2 schema {workspaceId?,cwd?,sessionId?,agentPreset?}——无 title/
            // parentSessionId（journal §2.3）；改名走 create 成功后的 session.rename
            // 追加（失败仅告警，本地 fallbackTitle 保真展示）。workspaceId（#311 ①-d
            // SessionCreateRequest）指定入组 workspace；缺席走服务器 cwd 归属。
            val payload = buildJsonObject {
                directory?.let { put("cwd", it) }
                // #311 ①-d：SessionCreateRequest.workspaceId（入组 workspace）——与 cwd
                // 可并存（都给时服务器按 workspaceId 归属），缺席走 cwd 归属。
                workspaceId?.let { put("workspaceId", it) }
                // #354：agentPreset 创建即带（SessionCreateRequest.agentPreset）——
                // 回显 agentPreset 经 mapSessionEcho 入槽，根治 create-then-select
                // 竞态（session.list 基线实测不回带该字段）。
                agentPreset?.let { put("agentPreset", it) }
            }
            val value = rpc.call(conn, "session.create", payload) { it }.getOrElse { e -> throw e }
            val session = mapSessionEcho(value, fallbackTitle = title, blankByDefault = true)
            if (!title.isNullOrBlank()) {
                rpc.call(conn, "session.rename", buildJsonObject {
                    put("sessionId", session.id)
                    put("title", title)
                }) { Unit }.onFailure { e ->
                    AppLogger.w(TAG, "session.rename after create failed for " + session.id + ": " + e.message)
                }
            }
            return session
        }
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
     * 压缩根治（#276 后端接口补全；#297 通道勘误）：/compact 走 commands/execute
     * 命令通道——部署版（0.1.1-rc.2）session.prompt 处理器**无斜杠命令派发**
     * （apiproxy prompt 直接 agent.followup：leading-/ 文本块变 user/message 进
     * 模型，2026-08-31 perm-7b 活体实证 + 2026-09-02 部署产物复核；新版源码契约
     * 虽有派发描述但未部署）。官方客户端先例 client-runtime command() 同走
     * commands/execute；/compact 是注册命令（dsh-command-compact 包）。
     *
     * kind:"success" → true；拒绝（活跃压缩中/agent 非 idle → kind:"error"）→
     * 抛 [DshApiError]（command-error）——repository 收编 Result.failure、
     * SessionActionsDelegate catch → onResult(false) → 失败 snackbar（静默
     * 失败不可接受，2026-08-26 用户裁决）。压缩**完成**信号走事件：
     * compaction/end → SseEvent.SessionCompacted（mapper）→ compactedSessions
     * 计数 → ChatViewModel 刷新。[providerId]/[modelId] 对 DSH 无效（命令通道
     * 无模型参数，调用方签名兼容保留）。
     */
    override suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean {
        val payload = buildJsonObject {
            put("args", buildJsonObject {
                put("agentId", sessionId)
                put("line", "/compact")
                put("images", JsonArray(emptyList()))
            })
        }
        val value = rpc.call(conn, "commands/execute", payload) { it }.getOrElse { e -> throw e }
        val result = value?.dshObj("result")
        val kind = result?.dshStr("kind")
        if (kind != "success") {
            throw DshApiError(
                code = DshRpcErrorCode.CommandError,
                message = result?.dshStr("text") ?: "compact rejected: kind=" + kind,
                details = null,
                httpStatus = null,
            )
        }
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
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            // #312⑤ 轮尾锚点：DSH 消息 id = "seq-{seq}"（DshEventMapper
            // messageId 契约）→ 服务器 atSeq 字段（SessionForkRequest
            // {sessionId, atSeq?}——锚到包含该事件的已完成轮次边界；非 seq 形态
            // （V2 msg_*/流式宿主）安全降级为无锚点（既有行为）。
            DshEventMapper.seqOf(messageId)?.let { put("atSeq", it) }
        }
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
        directory: String?
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
        // #358（2026-09-08 走查⑦取证定音）：typert 网关 args 语义 =
        // payload.args.{agentId,line,images}（RPC 直探双证：args 内再包 args →
        // gateway/arguments-invalid「missing agentId…unexpected args」；正确包裹
        // → ok 受理）——SELF_METHODS 直传原样包裹。
        val payload = buildJsonObject {
            put("args", buildJsonObject {
                put("agentId", sessionId)
                put("line", line)
                put("images", JsonArray(emptyList()))
            })
        }
        // #358 终版：CommandExecution|undefined（dsh-commands typert）三分派——
        // ① value **缺席** = 受理-异步（生命周期经 command/run|done 事件卡呈现，
        //    #323）→ true（真机 11:02 取证：HTTP 200 无 value，旧「value 必为对象」
        //    前置误判失败）；
        // ② value 在场无 result.kind（{} 退化形态）→ false（V1 常量先例测试钉死）；
        // ③ result.kind 判定（同步型命令即时成败）。
        return rpc.callOptional(conn, "commands/execute", payload).map { value ->
            val obj = value as? JsonObject
            when {
                obj == null -> true
                else -> obj.dshObj("result")?.dshStr("kind") == "success"
            }
        }.getOrDefault(false)
    }

    /** 权限预设切换（/permission <preset> 命令封装）；成功 = kind:"success"。 */
    override suspend fun setPermissionPreset(conn: ServerConnection, sessionId: String, preset: String): Boolean =
        executeCommand(conn, sessionId, "/permission $preset")

    /**
     * agentPreset.list → roster（value.presets[{id,name,description,isDefault,trust,broken?}]
     * + authorable；#324② 扩信任教仓/损坏标注/可创作位）。
     * 失败软降级空 roster（调用方隐藏预设卡，AppLogger.w）。
     */
    suspend fun agentPresetRoster(conn: ServerConnection): DshAgentPresetRoster {
        val value = rpc.call(conn, "agentPreset.list", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "agentPreset.list failed: " + e.message)
            return DshAgentPresetRoster()
        }
        val presets = value.dshArr("presets") ?: emptyList()
        return DshAgentPresetRoster(
            presets = presets.mapNotNull { el ->
                val entry = el as? JsonObject ?: return@mapNotNull null
                val id = entry.dshStr("id") ?: return@mapNotNull null
                AgentPreset(
                    id = id,
                    name = entry.dshStr("name") ?: id,
                    description = entry.dshStr("description") ?: "",
                    isDefault = entry.dshBool("isDefault") ?: false,
                    trust = entry.dshStr("trust") ?: "system",
                    broken = entry.dshStr("broken"),
                )
            },
            authorable = value.dshBool("authorable") ?: false,
        )
    }

    override suspend fun listAgentPresets(conn: ServerConnection): List<AgentPreset> =
        agentPresetRoster(conn).presets

    // ============ #324②：agentPresets 管理三方法（read/copy/deletePreset） ============

    /**
     * agentPresets/read(agentPreset) → 只读组成文档（content 为服务端
     * 解析后原文）。未知 id（agent-preset-not-found）→ null；
     * 其余失败上抛由调用方提示。
     */
    suspend fun readAgentPreset(conn: ServerConnection, agentPreset: String): DshAgentPresetDocument? {
        val value = rpc.call(conn, "agentPreset.read", buildJsonObject { put("agentPreset", agentPreset) }) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "agentPresets/read failed for " + agentPreset + ": " + e.message)
                return null
            }
        return DshAgentPresetDocument(
            agentPreset = value.dshStr("agentPreset") ?: agentPreset,
            trust = value.dshStr("trust") ?: "system",
            content = value.dshStr("content") ?: "",
            name = value.dshStr("name"),
            description = value.dshStr("description"),
        )
    }

    /**
     * agentPresets/copy(from, id, name?) → 复制为 user 预设（void 回程走
     * [DshRpcClient.callVoid]）。name 空→载荷不放键（服务端按 id 派生）。
     */
    suspend fun copyAgentPreset(conn: ServerConnection, from: String, id: String, name: String?): Boolean {
        val payload = buildJsonObject {
            put("from", from)
            put("id", id)
            if (!name.isNullOrBlank()) put("name", name)
        }
        return rpc.callVoid(conn, "agentPreset.copy", payload).isSuccess
    }

    /** agentPresets/deletePreset(id)（user 预设可删；system 拒绝；void 回程）。 */
    suspend fun deleteAgentPreset(conn: ServerConnection, id: String): Boolean =
        rpc.callVoid(conn, "agentPreset.deletePreset", buildJsonObject { put("id", id) }).isSuccess

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
        // 0.1.2 回程 value 是字符串（"standard"）非对象——call 的对象前置会以
        // non-object value 失败；callJson 无该前置。载荷由 adapter FLAT 自动
        // 改名包装（sessionId→agentId），调用方无需手改。
        if (protocolOf(conn) == DshWireProtocol.V012) {
            rpc.callJson(conn, "agentPreset.select", payload) { _ -> Unit }.getOrElse { e -> throw e }
        } else {
            rpc.call(conn, "agentPreset.select", payload) { Unit }.getOrElse { e -> throw e }
        }
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
        // 0.1.2 goals/create = {args:{agentId, request:{objective,maxGoalRounds?}}}
        //（SELF——调用点全量构造）；0.1.1 裸 {sessionId,objective,…}。方法名恒传
        // 0.1.1 规范名，adapter 只做翻译（goal.create→goals/create）。
        val payload = if (protocolOf(conn) == DshWireProtocol.V012) {
            buildJsonObject {
                put("args", buildJsonObject {
                    put("agentId", sessionId)
                    put("request", buildJsonObject {
                        put("objective", objective)
                        maxGoalRounds?.let { put("maxGoalRounds", it) }
                    })
                })
            }
        } else {
            buildJsonObject {
                put("sessionId", sessionId)
                put("objective", objective)
                maxGoalRounds?.let { put("maxGoalRounds", it) }
            }
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
        // 0.1.2 goals/edit = {args:{agentId, ref:{id,revision}, request:{objective?,maxGoalRounds?}}}；
        // 0.1.1 裸 {sessionId,ref,objective?,…}。
        val payload = if (protocolOf(conn) == DshWireProtocol.V012) {
            buildJsonObject {
                put("args", buildJsonObject {
                    put("agentId", sessionId)
                    put("ref", goalRefJson(ref))
                    put("request", buildJsonObject {
                        objective?.let { put("objective", it) }
                        maxGoalRounds?.let { put("maxGoalRounds", it) }
                    })
                })
            }
        } else {
            buildJsonObject {
                put("sessionId", sessionId)
                put("ref", goalRefJson(ref))
                objective?.let { put("objective", it) }
                maxGoalRounds?.let { put("maxGoalRounds", it) }
            }
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
        val payload = goalRefMutationPayload(conn, sessionId, ref)
        return rpc.call(conn, "goal.clear", payload) { value ->
            if (protocolOf(conn) == DshWireProtocol.V012) {
                // 0.1.2 回执是 GoalRef 本身（顶层 {id,revision}）——成功即 true
                true
            } else {
                value.dshBool("cleared") ?: false
            }
        }.getOrElse { e -> throw e }
    }

    /** goal.pause/resume/complete 共享形态：{sessionId, ref} → 回执 {ref}。 */
    private suspend fun refMutation(
        conn: ServerConnection,
        method: String,
        sessionId: String,
        ref: DshGoalRef,
    ): DshGoalRef? {
        val payload = goalRefMutationPayload(conn, sessionId, ref)
        return rpc.call(conn, method, payload) { mapGoalRef(it) }.getOrElse { e -> throw e }
    }

    /**
     * ref 形 mutation 载荷双版本构造（goalPause/Resume/Complete/Clear 共形）：
     * 0.1.2 = {args:{agentId, ref}}（SELF——全量构造）；0.1.1 = 裸 {sessionId, ref}。
     */
    private fun goalRefMutationPayload(conn: ServerConnection, sessionId: String, ref: DshGoalRef): JsonObject =
        if (protocolOf(conn) == DshWireProtocol.V012) {
            buildJsonObject {
                put("args", buildJsonObject {
                    put("agentId", sessionId)
                    put("ref", goalRefJson(ref))
                })
            }
        } else {
            buildJsonObject {
                put("sessionId", sessionId)
                put("ref", goalRefJson(ref))
            }
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
        val value = subagentListValue(conn, parentSessionId)
        return subagentEntryDtos(value)
    }

    /**
     * #310①（wire 契约钉死 2026-09-05 §①）：subagents/list 整帧域投影——
     * entries（复用 [subagentEntryDtos] 容错映射）+ parentAvailable（父 Agent
     * 不在线时 subagents/prompt 将拒 subagent/parent-unavailable——UI 禁发依据；
     * AgentSheet 刷新迭代接线）。保守 V011：subagents 域不在 0.1.1 方法面。
     */
    suspend fun subagentCatalog(
        conn: ServerConnection,
        parentSessionId: String,
    ): dev.leonardo.ocbeacon.domain.model.SubagentCatalog {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("subagent.list")
        val value = subagentListValue(conn, parentSessionId)
        return dev.leonardo.ocbeacon.domain.model.SubagentCatalog(
            entries = subagentEntryDtos(value).map { dto ->
                dev.leonardo.ocbeacon.domain.model.SubagentCatalogEntry(
                    kind = dto.kind,
                    id = dto.id,
                    activity = dto.activity,
                    hasChildren = dto.hasChildren,
                    mode = dto.mode,
                    label = dto.label,
                    reason = dto.reason,
                )
            },
            parentAvailable = value.dshBool("parentAvailable") ?: false,
        )
    }

    private suspend fun subagentListValue(conn: ServerConnection, parentSessionId: String): JsonObject {
        return rpc.call(conn, "subagent.list", buildJsonObject {
            put("parentSessionId", parentSessionId)
        }) { it }.getOrElse { e -> throw e }
    }

    private fun subagentEntryDtos(value: JsonObject): List<SubagentListEntryDto> {
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

    /**
     * #310① subagents/prompt（子智能体续聊）：SubagentPromptRequest =
     * {requestId(randomUUID), parentSessionId, childSessionId, mode:"continuable"
     * 固定, content:PromptContentPart[], clientTimeZone?}（zod literal
     * "continuable"——无 queue/steer 档位、无模型参数，与主会话 session/prompt
     * 的差异即双会话地址 + 恒定 mode）。回执 {messageId}；用户消息经 WS
     * session/event 回显（V1 先例——受理回执只作日志锚点，不本地播种）。
     *
     * 保守 V011：subagents 域不在 0.1.1 方法面（#310 审计裁决）——unsupported。
     */
    suspend fun subagentPrompt(
        conn: ServerConnection,
        parentSessionId: String,
        childSessionId: String,
        parts: List<PromptPart>,
        clientTimeZone: String? = null,
    ): String? {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("subagent.prompt")
        val content = parts.mapNotNull { part -> promptContentPart(part, v012 = true) }
        if (content.isEmpty()) {
            AppLogger.w(TAG, "subagents/prompt skipped: no mappable content parts for $childSessionId")
            return null
        }
        val payload = buildJsonObject {
            put("requestId", java.util.UUID.randomUUID().toString())
            put("parentSessionId", parentSessionId)
            put("childSessionId", childSessionId)
            put("mode", "continuable")
            put("content", JsonArray(content))
            // 可选（zod optional）：UTC 或 IANA Area/Location——缺失交服务器本地时区
            clientTimeZone?.takeIf { it.isNotBlank() }?.let { put("clientTimeZone", it) }
        }
        val value = rpc.call(conn, "subagent.prompt", payload) { it }.getOrElse { e -> throw e }
        return value.dshStr("messageId")
    }

    /**
     * #310① subagents/interruptByParent（子会话停止）：durable 父址中断——父
     * Agent 不在线也能中断（与 session.cancel 会话自址的差异）。载荷三平铺参
     * {childSessionId, parentSessionId, mode:"continuable"}；回执 {accepted:true}。
     * 保守 V011 unsupported（同 [subagentPrompt]）。
     */
    suspend fun subagentInterrupt(
        conn: ServerConnection,
        parentSessionId: String,
        childSessionId: String,
    ): Boolean {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("subagent.interruptByParent")
        val payload = buildJsonObject {
            put("childSessionId", childSessionId)
            put("parentSessionId", parentSessionId)
            put("mode", "continuable")
        }
        return rpc.call(conn, "subagent.interruptByParent", payload) { Unit }.isSuccess
    }

    // ============ #310② 消息反馈（messageFeedback/put·delete·list） ============

    /**
     * #310② messageFeedback/put（wire 契约钉死 2026-09-05 §②）：载荷
     * {sessionId,messageId,rating,note?,ifVersion:version|null}（未评断言 ifVersion=null，
     * 与键缺席不同）。**业务结果驱 RPC value**：成功 value={ok:true,
     * value:整项}，拒绝 value={ok:false,error:{code,…}}（web mod37 put:188 先例——
     * 不走信封 error）。version-conflict 携带服务器权威 current（整项或
     * null）供重同步；信封级/传输失败收编进 Failure（DshAuthRequired
     * 与取消例外上抛——连接层 TokenNeeded 语义不可被吞）。
     * 保守 V011：messageFeedback 域不在 0.1.1 方法面（#310 审计裁决）。
     */
    suspend fun messageFeedbackPut(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        rating: dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating,
        note: String? = null,
        ifVersion: String?,
    ): dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("messageFeedback.put")
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("messageId", messageId)
            put("rating", rating.wire)
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            put("ifVersion", ifVersion?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        return rpc.call(conn, "messageFeedback.put", payload) { value ->
            when (value.dshBool("ok")) {
                true -> {
                    val item = value.dshObj("value")?.let(::messageFeedbackItem)
                        ?: return@call dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Failure(
                            null, "messageFeedback.put ok without item",
                        )
                    dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Success(item)
                }
                false -> {
                    val error = value.dshObj("error")
                    val code = error?.dshStr("code")
                    if (code == "version-conflict") {
                        dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.VersionConflict(
                            error?.dshObj("current")?.let(::messageFeedbackItem),
                        )
                    } else {
                        dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Failure(
                            code, error?.dshStr("message") ?: "messageFeedback.put rejected",
                        )
                    }
                }
                null -> dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Failure(
                    null, "malformed messageFeedback.put value",
                )
            }
        }.getOrElse { e ->
            if (e is DshAuthRequiredException || e is kotlin.coroutines.cancellation.CancellationException) throw e
            val dsh = e as? DshApiError
            dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Failure(
                dsh?.code?.wire, dsh?.message ?: (e.message ?: "messageFeedback.put failed"),
            )
        }
    }

    /**
     * #310② messageFeedback/delete：{sessionId,messageId,ifVersion} → {absent:true}
     * 幂等（项不存在时恒成功；版本不匹配时拒绝带 current）。
     * 业务结果驱 value 同 [messageFeedbackPut]；保守 V011 unsupported。
     */
    suspend fun messageFeedbackDelete(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        ifVersion: String,
    ): dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("messageFeedback.delete")
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("messageId", messageId)
            put("ifVersion", ifVersion)
        }
        return rpc.call(conn, "messageFeedback.delete", payload) { value ->
            when (value.dshBool("ok")) {
                true -> dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult.Absent
                false -> {
                    val error = value.dshObj("error")
                    val code = error?.dshStr("code")
                    if (code == "version-conflict") {
                        dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult.VersionConflict(
                            error?.dshObj("current")?.let(::messageFeedbackItem),
                        )
                    } else {
                        dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult.Failure(
                            code, error?.dshStr("message") ?: "messageFeedback.delete rejected",
                        )
                    }
                }
                null -> dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult.Failure(
                    null, "malformed messageFeedback.delete value",
                )
            }
        }.getOrElse { e ->
            if (e is DshAuthRequiredException || e is kotlin.coroutines.cancellation.CancellationException) throw e
            val dsh = e as? DshApiError
            dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult.Failure(
                dsh?.code?.wire, dsh?.message ?: (e.message ?: "messageFeedback.delete failed"),
            )
        }
    }

    /**
     * #310② messageFeedback/list：{sessionId} → {items:[整项]}（会话进入时拉种子）。
     * 业务/信封/传输失败一律上抛 DshApiError（repository Result 收编）。
     */
    suspend fun messageFeedbackList(
        conn: ServerConnection,
        sessionId: String,
    ): List<dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem> {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("messageFeedback.list")
        val payload = buildJsonObject { put("sessionId", sessionId) }
        return rpc.call(conn, "messageFeedback.list", payload) { value ->
            if (value.dshBool("ok") != true) {
                val error = value.dshObj("error")
                throw DshApiError(
                    DshRpcErrorCode(error?.dshStr("code") ?: "internal"),
                    error?.dshStr("message") ?: "messageFeedback.list rejected",
                    null, 200,
                )
            }
            (value.dshObj("value")?.dshArr("items") ?: emptyList()).mapNotNull { el ->
                (el as? JsonObject)?.let(::messageFeedbackItem)
            }
        }.getOrElse { e -> throw e }
    }

    /** 整项容错映射：缺征意义字段（messageId/rating/version）丢弃该行。 */
    private fun messageFeedbackItem(obj: JsonObject): dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem? {
        val id = obj.dshStr("messageId") ?: return null
        val rating = dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.fromWire(obj.dshStr("rating")) ?: return null
        val version = obj.dshStr("version") ?: return null
        return dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem(
            messageId = id,
            rating = rating,
            note = obj.dshStr("note"),
            version = version,
            createdAt = obj.dshLong("createdAt") ?: 0L,
            updatedAt = obj.dshLong("updatedAt") ?: 0L,
        )
    }

    // ============ #310⑤/#321 @ 引用候选（fileReferences/list · sessionReferenceResolver/candidates） ============

    /**
     * #321 fileReferences/list（wire 契约钉死 2026-09-05 §⑤）：两平铺参
     * {args:{agentId,query}}（typert 参数名即 wire 键，FLAT——无 request 包装）；
     * value = FileReferenceCandidate[] [{path,kind:'file'|'directory'}] 直返数组
     * （callJson 语义，commands/list 先例）；agentId == sessionId（DSH 单 agent
     * 每会话，listCommands 先例）。kind 现阶段丢弃（UI 以尾 / 约定区分目录，
     * FileMentionSuggestions 现状）；缺 path 行丢弃（容错先例）。
     * 业务/信封/传输失败一律上抛 DshApiError（repository Result 收编）。
     * 保守 V011：fileReferences 域不在 0.1.1 方法面（#310 审计裁决）。
     */
    suspend fun fileReferencesList(
        conn: ServerConnection,
        agentId: String,
        query: String,
    ): List<String> {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("fileReferences.list")
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("query", query)
        }
        return rpc.callJson(conn, "fileReferences/list", payload) { value ->
            (value as? JsonArray ?: emptyList()).mapNotNull { el ->
                (el as? JsonObject)?.dshStr("path")
            }
        }.getOrElse { e -> throw e }
    }

    /**
     * #310⑤ sessionReferenceResolver/candidates：同两平铺参 {args:{agentId,query}}
     * （FLAT）；value = SessionReferenceMentionCandidate[] 直返数组
     * [{sessionId,label,cwd?,sameWorkspace,createdAt,mention}]。mention 为服务器
     * 权威规范串 @[label](dsh-session:…)（客户端不重组）；createdAt 现阶段丢弃
     * （合并排序按 sameWorkspace，见 [dev.leonardo.ocbeacon.domain.model.mergeMentionCandidates]）。
     * 业务/信封/传输失败一律上抛（同 [fileReferencesList]）；保守 V011 unsupported。
     */
    suspend fun sessionReferenceCandidates(
        conn: ServerConnection,
        agentId: String,
        query: String,
    ): List<dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention> {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("sessionReferenceResolver.candidates")
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("query", query)
        }
        return rpc.callJson(conn, "sessionReferenceResolver/candidates", payload) { value ->
            (value as? JsonArray ?: emptyList()).mapNotNull { el ->
                (el as? JsonObject)?.let(::sessionMentionOf)
            }
        }.getOrElse { e -> throw e }
    }

    /** 会话候选容错映射：缺征意义字段（sessionId/label/mention）丢弃该行。 */
    private fun sessionMentionOf(obj: JsonObject): dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention? {
        val sessionId = obj.dshStr("sessionId") ?: return null
        val label = obj.dshStr("label") ?: return null
        val mention = obj.dshStr("mention") ?: return null
        return dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention(
            sessionId = sessionId,
            label = label,
            cwd = obj.dshStr("cwd"),
            sameWorkspace = obj.dshBool("sameWorkspace") == true,
            mention = mention,
        )
    }

    override suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem> = emptyList()

    override suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean = false

    override suspend fun activeSessions(conn: ServerConnection): Map<String, ActiveSessionInfo> = emptyMap()

    override suspend fun listSessionStatus(conn: ServerConnection, directory: String?): Map<String, SessionStatusInfo> = emptyMap()

    /** 存活探测 = host.describe 成功（§2.5：DSH 无 /health）——空 map 起步（#276 任务契约）。 */
    /**
     * #278（僵尸 Busy L3 兜底）：DSH 无 host.describe 状态端点——改由 session.list
     * 的 running 字段播种（true=busy / false=idle，服务器权威）。原恒空 map 使
     * syncFromRest 对 DSH 会话零信息——无 turn/end 终态的异常会话永远停在 Busy。
     * directory 参数不参与（DSH list 全局；聚合层幂等合并）。
     */
    override suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String?,
    ): Result<Map<String, RestSessionStatusInfo>> =
        rpc.call(conn, "session.list", buildJsonObject {}) { value ->
            (value.dshArr("items") ?: emptyList()).mapNotNull { el ->
                (el as? JsonObject)?.let { item ->
                    val sid = item.dshStr("sessionId") ?: return@let null
                    sid to RestSessionStatusInfo(type = if (item.dshBool("running") == true) "busy" else "idle")
                }
            }.toMap()
        }

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
            val withTitle = if (withBlank.title == null && fallbackTitle != null) withBlank.copy(title = fallbackTitle) else withBlank
            // #331：回显无 updatedAt（0.1.2 schema）→ echoClock 补排序位（防 epoch0 沉底）
            return stampEchoUpdated(withTitle)
        }
        AppLogger.w(TAG, "session echo shape unrecognized, falling back to minimal session: " + value.toString().take(120))
        return stampEchoUpdated(
            Session(
                id = direct?.dshStr("sessionId") ?: fallbackId,
                title = fallbackTitle,
                time = Session.Time(created = 0L, updated = 0L),
                blank = blankByDefault,
            )
        )
    }

    /** #331：updated==0（wire 缺席哨兵）→ 本地时钟；真值在场原样保留。 */
    private fun stampEchoUpdated(session: Session): Session =
        if (session.time.updated == 0L) {
            session.copy(time = session.time.copy(updated = echoClock()))
        } else session

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
        val payload = historyPayload(conn, sessionId, limit, before)
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
        // #378：同窗卡族事件随页携带——历史加载方（SessionRepositoryImpl）dispatch
        // 后命令/压缩卡按 commandId/compactionId 幂等重建（与重连回填等价）。
        return MessagePage(
            messages = messages,
            nextCursor = nextCursor,
            transcriptEvents = DshTranscriptEvents.cardFamily(fold.sseEvents),
        )
    }

    override suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        val payload = historyPayload(conn, sessionId, limit = null, before = null)
        return rpc.call(conn, "session.history", payload) { it.toString() }.getOrElse { e -> throw e }
    }

    /**
     * session.history 载荷双版本构造：V011 裸 {sessionId,beforeSeq?,maxMessages}；
     * V012 语义替换 session/page——{address,throughSeq,beforeSeq?,maxMessages?}
     * （方法名仍传 session.history，adapter RENAMES 翻成 session/page + request
     * 包装；throughSeq 先行 session.list 读该会话 projections.asOfSeq）。
     *
     * #310① A8 缺陷A修复：地址不再恒 {kind:session}——origin=subagent 会话拒收
     * 该形态（"subagent Sessions require their durable parent address"，A8 logcat
     * 5454 实测转录恒空的 history 腿），按 [DshSessionAddress.fromListItem] 从
     * session.list 行装配 durable subagent 地址；投影缺席（无 mode）回退
     * session 形态（服务器侧明确报错，不劣于修复前）。
     */
    private suspend fun historyPayload(
        conn: ServerConnection,
        sessionId: String,
        limit: Int?,
        before: String?,
    ): JsonObject {
        if (protocolOf(conn) != DshWireProtocol.V012) {
            return buildJsonObject {
                put("sessionId", sessionId)
                before?.toLongOrNull()?.let { put("beforeSeq", it) }
                limit?.let { put("maxMessages", it) }
            }
        }
        val item = sessionListItemOf(conn, sessionId)
        val throughSeq = item.dshObj("projections")?.dshLong("asOfSeq")
            ?: throw IllegalStateException("DSH session $sessionId has no projections.asOfSeq")
        return buildJsonObject {
            put("address", DshSessionAddress.fromListItem(item) ?: buildJsonObject {
                put("kind", "session")
                put("sessionId", sessionId)
            })
            put("throughSeq", throughSeq)
            before?.toLongOrNull()?.let { put("beforeSeq", it) }
            limit?.let { put("maxMessages", it) }
        }
    }

    /**
     * V012 session.list 行查找（page 读上界 + 地址装配共源）：0.1.2 无
     * session.get，list 是唯一权威源。会话缺席 → IllegalStateException。
     */
    private suspend fun sessionListItemOf(conn: ServerConnection, sessionId: String): JsonObject {
        val value = rpc.call(conn, "session.list", buildJsonObject {}) { it }.getOrElse { e -> throw e }
        return (value.dshArr("items") ?: emptyList())
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.dshStr("sessionId") == sessionId }
            ?: throw IllegalStateException("DSH session not found: $sessionId")
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
            // 0.1.2 鉴权栅栏：非信封入口同样需要 Cookie（rpc.cookieFor 复用注册表）
            rpc.cookieFor(conn)?.let { header("Cookie", it) }
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
        steer: Boolean,
    ): PromptAdmission? {
        val v012 = protocolOf(conn) == DshWireProtocol.V012
        val content = parts.mapNotNull { part -> promptContentPart(part, v012) }
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
        // #356：requestId 提升为变量——受理后构造本地 echo 播种回执（V012）。
        val requestId = if (v012) java.util.UUID.randomUUID().toString() else null
        val payload = buildJsonObject {
            // 0.1.2 prompt 必填 requestId（journal §2.3）；0.1.1 无此键。
            if (requestId != null) put("requestId", requestId)
            put("sessionId", sessionId)
            put("content", JsonArray(content))
            // E2E 实证（2026-08-31）：mode 必填（zod expected queue|steer，缺席整单拒绝）。
            // queue→轮末排队派发（idle 时即普通发送）；steer=注入进行中轮次
            //（#356 反转：busy 菜单「立即发送」= steer、「消息排队」= queue——
            // 对齐 DSH web 提交策略与用户「上屏+徽标」语义）。
            put("mode", if (steer) "steer" else "queue")
        }
        rpc.call(conn, "session.prompt", payload) { Unit }.getOrElse { e -> throw e }
        // #356 echo 播种：受理即返回 admission → ChatRepositoryImpl 现有本地播种链
        // 上屏（web PendingSubmissionBubble 对位）；id=pending-<requestId>，
        // 持久 user/message（source=user-rpc.rpcId）到达时 mapper 补发
        // MessageRemoved 原子换装（幂等）。V011 无 requestId → 维持 null 无 echo。
        return requestId?.let { rid ->
            dev.leonardo.ocbeacon.data.api.message.PromptAdmission(
                id = "pending-$rid",
                sessionId = sessionId,
                text = parts.firstOrNull { it.type == "text" }?.text,
            )
        }
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

    private fun promptContentPart(part: PromptPart, v012: Boolean = false): JsonObject? = when {
        part.type == "text" && !part.text.isNullOrBlank() -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }
        part.type == "file" && part.url != null -> {
            val url = part.url!!
            if (!url.startsWith("data:")) {
                // #358（2026-09-08 走查⑦发送失败取证）：DSH PromptContentPart 契约
                // 仅 text | image(base64 data 必填)——服务器 types.d.ts 实证，无 url
                // 字段、无文件块。@file 提及（file:// 路径引用，无字节）旧实现发
                // {type:image,url:file://…} → 整单被 gateway boundary validation
                // 拒收（真机 logcat: session/prompt wire field "request" failed）。
                // 降级文本保真：路径以 @path 文本入 prompt，服务端 agent 以自身
                // 文件工具解读（read/bash 均可达）。
                return buildJsonObject {
                    put("type", "text")
                    put("text", "@" + (part.path ?: part.filename ?: url.removePrefix("file:///")))
                }
            }
            // data URL → {type:image, data(base64), mime}
            val header = url.substringBefore(",", "")
            val mime = header.removePrefix("data:").substringBefore(";")
            val data = url.substringAfter(",", "")
            buildJsonObject {
                put("type", "image")
                put("data", data)
                // 0.1.2 图片 part 字段是 mediaType（0.1.1 是 mime）
                put(if (v012) "mediaType" else "mime", mime)
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
        // 2026-09-01（steer 实测发现）：服务器方法面是 session.<域>.<动作>——
        // session.updateQueue。原 "updateQueue" 直发 /api/updateQueue HTTP 404，
        // QueueDock edit/remove/steer 全部静默失效（走查期「remove 可用」实为
        // 步边界消费误判，真机 logcat Ktor REQUEST 404 + 服务器方法探测定音）。
        return rpc.call(conn, "session.updateQueue", payload) { Unit }.fold(
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

    /**
     * workspace/archiveSession（#311 Task1；契约 ①-a）：payload {sessionId}——V012
     * 经 WRAPPED 翻译为 {args:{request:{sessionId}}}（typert 参数 wire:'request'）。
     * 回执 {archivedSessionIds} 是**完整新集合**（集合替换式——回执即新集合，
     * workspace-controller types.d.ts WorkspaceArchiveValue），直接返回。
     * V011 线面无此动词 → [UnsupportedServerCapability]。
     */
    suspend fun archiveSession(conn: ServerConnection, sessionId: String): List<String> {
        if (protocolOf(conn) != DshWireProtocol.V012) unsupported("workspace.archiveSession")
        val value = rpc.call(conn, "workspace/archiveSession", buildJsonObject {
            put("sessionId", sessionId)
        }) { it }.getOrElse { e -> throw e }
        return (value.dshArr("archivedSessionIds") ?: emptyList())
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    override suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean = false

    override suspend fun deleteMessagePart(
        conn: ServerConnection,
        sessionId: String,
        messageId: String,
        partIndex: Int,
    ): Boolean = false

    /**
     * 权限应答（/api/respond 回程，§1.6-2）。#308（2026-09-03 服务端源码+官方
     * 客户端四重定音）：载荷三键必填 {sessionId, approvalId, outcome}，outcome
     * 枚举仅 allowed-once|rejected（dsh 0.1.1-rc.2 全树无 allowed-always）——
     * - once 与 always → allowed-once：「始终允许」是客户端本地规则模拟（UI 层
     *   savePermissionRule 已存规则，后续同类 ask 由 PermissionAutoApprover 自动
     *   以 once 重答），非服务器能力；
     * - 信封 rpcId = requested 帧稳定 id（[metadata]「rpcId」，#276 接线注意①），
     *   与 payload approvalId 是两个不同 id（服务端 pendingApprovals 按帧 rpcId
     *   路由 + 三键比对）；内存 pending 丢失（重启后）时回退 [requestId] 尽力而为。
     */
    override suspend fun replyToPermission(
        conn: ServerConnection,
        sessionId: String,
        requestId: String,
        reply: String,
        message: String?,
        directory: String?,
        metadata: Map<String, String>?,
    ): Boolean {
        val outcome = when (reply) {
            "once", "always" -> "allowed-once"
            "reject" -> "rejected"
            else -> reply
        }
        // #308 回修（2026-09-04 真机+服务端源码定音）：V012 权限 waterfall 应答的
        // value 必须是**裸字符串**（dsh-user-approval decide() L179 以
        // OUTCOMES.includes(outcome) 归一化——对象形态恒判 unavailable=fail-closed，
        // 代理侧表现「no approval channel available」；网关对 eventsResult 恒回
        // ok:true，旧 {outcome:...} 对象形态因此在 App 侧恒假成功）。
        if (protocolOf(conn) == DshWireProtocol.V012) {
            val wireOutcome = buildJsonObject {
                put("kind", "result")
                put("value", kotlinx.serialization.json.JsonPrimitive(outcome))
            }
            return rpc.eventsResult(conn, metadata?.get("rpcId") ?: requestId, wireOutcome).isSuccess
        }
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("approvalId", requestId)
            put("outcome", outcome)
        }
        return rpc.respond(conn, metadata?.get("rpcId") ?: requestId, payload).isSuccess
    }

    /**
     * 无待处理权限 REST 端点——**null = 端点缺席**（#314：emptyList 曾被上游当
     * 「服务器权威回答无待答」清空 SSE 存储导致 pre-existing 卡不渲染）。恢复面=
     * 冷启订阅重放未决帧；移除面=approval/resolved 帧。
     */
    override suspend fun listPendingPermissions(
        conn: ServerConnection,
        directory: String?,
    ): List<PermissionRequest>? = null

    /**
     * 提问应答（/api/respond）。#308：载荷 {sessionId, answer:{answers:[{id,
     * selected, custom?}]}}——服务端 matchesQuestions 硬校验：answer.id === 题
     * wire id（[SseEvent.QuestionAsked.Question.key] 即 mapper 存的 item.id）、
     * selected ⊆ 选项 label 且无重复、custom 省略或非空、**单选题带 custom 时
     * selected 必空**。非 label 的答案串视为自由文本进 custom。信封 rpcId =
     * [requestId]（question/requested 帧 id 即提问标识，payload 无资源 id）。
     */
    override suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
        question: SseEvent.QuestionAsked?,
    ): Boolean {
        if (question == null) return false
        val answerObjects = mutableListOf<JsonObject>()
        question.questions.forEachIndexed { index, q ->
            val id = q.key ?: return false
            val labels = q.options.map { it.label }.toSet()
            val chosen = answers.getOrNull(index) ?: emptyList()
            val custom = chosen.firstOrNull { it !in labels }?.trim()?.takeIf { it.isNotEmpty() }
            // 单选 + custom 契约：selected 必空（matchesQuestions multiSelect!==true 分支）
            val selected = if (!q.multiple && custom != null) emptyList() else chosen.filter { it in labels }
            answerObjects += buildJsonObject {
                put("id", id)
                put("selected", JsonArray(selected.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                custom?.let { put("custom", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        }
        // #318 V012：waterfall 应答走 $events/result（journal §2.5 实测闭环）——
        // requestId = waterfall eventId（合成帧 rpcId 槽），answers 构造两版同源。
        if (protocolOf(conn) == DshWireProtocol.V012) {
            val outcome = buildJsonObject {
                put("kind", "result")
                put("value", buildJsonObject { put("answers", JsonArray(answerObjects)) })
            }
            return rpc.eventsResult(conn, requestId, outcome).isSuccess
        }
        val payload = buildJsonObject {
            put("sessionId", question.sessionId)
            put("answer", buildJsonObject { put("answers", JsonArray(answerObjects)) })
        }
        return rpc.respond(conn, requestId, payload).isSuccess
    }

    /**
     * 提问取消（/api/respond）。#308：服务端唯一接受的取消形态 = Err 信封
     * （result.ok=false + error.code="cancelled" → claimQuestion + accepted:true）；
     * 旧 Ok 载荷 {outcome:"cancelled"} 恒 bad-response。[sessionId]/[directory]
     * 仅满足域接口签名（DSH 路由键就是帧 rpcId=[requestId]），忽略。
     */
    override suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String?,
        sessionId: String?,
    ): Boolean {
        // #328（2026-09-05 网关源码定音）：rejected 的 error 必须含 name（非空）+
        // message,可选 code/details——parseRemoteEventRejection(gateway index.js
        // L158-159)对缺 name 形态抛 invalid Remote event result → rpcFailure →
        // ok:false → waterfall 永不解除（#327 验收真机实测代理冻结根因）。
        // 正字法=web 端 questionError:name=UserQuestionError + code=ASK_CANCELLED
        // （服务器 restoreUserQuestionError 按 name 复原类型,工具层收规范错误）。
        if (protocolOf(conn) == DshWireProtocol.V012) {
            val outcome = buildJsonObject {
                put("kind", "rejected")
                put("error", buildJsonObject {
                    put("name", "UserQuestionError")
                    put("message", "the user cancelled ask_user_question")
                    put("code", "ASK_CANCELLED")
                })
            }
            return rpc.eventsResult(conn, requestId, outcome).isSuccess
        }
        return rpc.respondError(conn, requestId, DshRpcErrorCode.Cancelled, "user cancelled ask_user_question").isSuccess
    }

    override suspend fun listPendingQuestions(
        conn: ServerConnection,
        directory: String?,
    ): List<QuestionRequest>? = null

    // ============ SystemApi（host.describe + 常量降级） ============

    override suspend fun getHealth(conn: ServerConnection): ServerHealth {
        // 0.1.2 已删 host.describe（journal §2.3）——健康探活由双形态探测/连接层
        // 承担（探测通过才可能判定 V012），这里直接报告在线。
        if (protocolOf(conn) == DshWireProtocol.V012) {
            return ServerHealth(healthy = true, version = "0.1.2")
        }
        val value = rpc.call(conn, "host.describe", buildJsonObject {}) { it }.getOrElse { e -> throw e }
        return ServerHealth(healthy = true, version = value.dshStr("version"))
    }

    override suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        // 0.1.2 host.describe 已删——session/list 首条 cwd 兜底（home 无来源留空）。
        if (protocolOf(conn) == DshWireProtocol.V012) {
            return ServerPaths(home = "", directory = firstSessionCwd(conn) ?: "")
        }
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

    /**
     * #324④：skills/list {sessionId}（WRAPPED request）→ 触发组
     * {skills:[{name,description,whenToUse?,modelInvocable}]}。失败/冷会话 →
     * 空列表（面板组隐藏）。
     */
    override suspend fun listSessionSkills(conn: ServerConnection, sessionId: String): List<DshSkillInfo> {
        val value = rpc.call(conn, "skills.list", buildJsonObject { put("sessionId", sessionId) }) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "skills/list failed for session: " + e.message)
                return emptyList()
            }
        return (value.dshArr("skills") ?: emptyList()).mapNotNull { el ->
            val entry = el as? JsonObject ?: return@mapNotNull null
            val name = entry.dshStr("name") ?: return@mapNotNull null
            DshSkillInfo(
                name = name,
                description = entry.dshStr("description") ?: "",
                whenToUse = entry.dshStr("whenToUse"),
                modelInvocable = entry.dshBool("modelInvocable") ?: false,
            )
        }
    }

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

    /** 文件内容搜索无对应方法（find 域缺失）——空表降级。
     *  #322 裁决：session/search 是**会话**内容搜索（SessionSearchHit），语义与
     *  本方法（文件 path/line 匹配，SearchMatchDto）不同域——不共用通道，见 [searchSessions]。 */
    override suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> = emptyList()

    override suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean =
        rpc.call(conn, "host.listDirectory", buildJsonObject { put("path", directory) }) { Unit }.isSuccess

    /**
     * W4/D8(2026-09-06 全量 E2E)：DSH 原生建目录——directoryPicker/createDirectory
     * {path,name}(browse 能力;name 单段,返回绝对路径字符串)。取代 mkdir 临时会话
     * shell 通道:DSH 命令注册表无 shell/exec → runShellCommand/executeCommand 双
     * 回退必败(Failed to create directory),且 finally deleteSession 无能力位 →
     * 临时会话泄漏为正式列表行(实测「mkdir」行,SessionListViewModel 链在案)。
     */
    override suspend fun createDirectory(conn: ServerConnection, parentDirectory: String, folderName: String): String {
        // callJson 语义:结果是非对象 value(字符串路径)——call 的对象前置会拒。
        val value = rpc.callJson(conn, "host.createDirectory", buildJsonObject {
            put("path", parentDirectory)
            put("name", folderName)
        }) { it }.getOrElse { e -> throw e }
        return (value as? JsonPrimitive)?.content
            ?: throw DshApiError(
                code = null,
                message = "createDirectory: unexpected non-string result",
                details = null,
                httpStatus = null,
            )
    }

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

    /**
     * 空 path 的根解析：V012 直接 session.list 首条 cwd（workspace.list 已删——
     * 404，不再打无谓请求）；V011 保持 workspace.list 首个 path → host.describe
     * cwd；都无则显式失败。
     */
    private suspend fun resolveRootPath(conn: ServerConnection): String {
        val base = conn.baseUrl.trimEnd('/')
        rootPathCache[base]?.let { return it }
        val root = when (protocolOf(conn)) {
            DshWireProtocol.V012 -> firstSessionCwd(conn)
            DshWireProtocol.V011 -> {
                // #311 Task1：workspace.list 消费升级为完整 List<Workspace>——根路径
                // 只取首个 path（调用方兼容语义不变：首个含 path 条目）。
                val workspaceRoot = runCatching { listWorkspaces(conn).firstOrNull()?.path }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                workspaceRoot ?: runCatching {
                    rpc.call(conn, "host.describe", buildJsonObject {}) { it }.getOrNull()?.dshStr("cwd")
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }?.takeIf { it.isNotBlank() }
            ?: throw DshApiError(null, "cannot resolve root path for empty listDirectory request", null, null)
        rootPathCache[base] = root
        return root
    }

    /** session.list 首条 cwd（0.1.2 根路径/服务器路径兜底源；失败/空 → null）。 */
    private suspend fun firstSessionCwd(conn: ServerConnection): String? = runCatching {
        rpc.call(conn, "session.list", buildJsonObject {}) { it }.getOrNull()
            ?.dshArr("items")
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull()
            ?.dshStr("cwd")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

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

    /**
     * workspace.list → 完整 [Workspace] 注册表（#311 Task1；契约 ①-d WorkspaceView）。
     *
     * 名字键 = title（缺席回退旧线面 name 键，再回退 basename(path)——服务器 create
     * 默认语义）；workspaceId 缺席回退 id 键；sessionIds 显式数组（旧线面缺席 → 空）。
     * 时间戳 ISO 字符串不进域模型（§5 双态坑位）。失败/空 → 空列表（调用方兜底）。
     * V012 线面该方法已删（journal §2.3——注册表数据源改 workspace/follow baseline），
     * 本方法承载旧线面（V011）消费点升级后的完整映射。
     */
    suspend fun listWorkspaces(conn: ServerConnection): List<Workspace> {
        val value = rpc.call(conn, "workspace.list", buildJsonObject {}) { it }
            .getOrElse { e ->
                AppLogger.w(TAG, "workspace.list failed: " + e.message)
                return emptyList()
            }
        val items = value.dshArr("items") ?: value.dshArr("workspaces") ?: emptyList()
        return items.mapNotNull { el -> (el as? JsonObject)?.let(::mapWorkspaceEntry) }
    }

    /** workspace.list 条目 → [Workspace]（新旧线面键兼容：workspaceId←id、title←name、path←cwd/directory）。 */
    private fun mapWorkspaceEntry(entry: JsonObject): Workspace? {
        val path = entry.dshStr("path") ?: entry.dshStr("cwd") ?: entry.dshStr("directory") ?: return null
        val title = entry.dshStr("title")
            ?: entry.dshStr("name")
            ?: PathUtils.fileName(path).takeIf { it.isNotEmpty() }
            ?: path
        return Workspace(
            workspaceId = entry.dshStr("workspaceId") ?: entry.dshStr("id") ?: path,
            path = path,
            title = title,
            sessionIds = (entry.dshArr("sessionIds") ?: emptyList())
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
        )
    }

    /** workspace.list → Project（时间戳单位双态坑位 §5：workspace 侧 ISO 字符串不进 Project）。 */
    override suspend fun listProjects(conn: ServerConnection): List<Project> {
        // 0.1.2 workspace.list 无对应（journal §2.3）——从 session.list 聚合
        // distinct cwd 构造 Project（id=cwd, worktree=cwd, name=最后一段路径）；
        // 失败/空回退现状返回（emptyList）。
        if (protocolOf(conn) == DshWireProtocol.V012) {
            val cwds = runCatching {
                rpc.call(conn, "session.list", buildJsonObject {}) { it }.getOrNull()
            }.getOrNull()
                ?.dshArr("items")
                ?.filterIsInstance<JsonObject>()
                ?.mapNotNull { it.dshStr("cwd")?.takeIf { c -> c.isNotBlank() } }
                ?.distinct()
                .orEmpty()
            if (cwds.isEmpty()) return emptyList()
            return cwds.map { cwd ->
                Project(
                    id = cwd,
                    worktree = cwd,
                    name = PathUtils.fileName(cwd).takeIf { it.isNotEmpty() } ?: cwd,
                )
            }
        }
        // #311 Task1：完整 List<Workspace> 映射后投影 Project——名字键升级 title
        //（mapper 内 name 键回退 + basename 兜底，Project.name 由可空变为有名——
        // 服务器 create 默认 basename 语义对齐，UI 工作区标签展示面零回归）。
        return listWorkspaces(conn).map { workspace ->
            Project(
                id = workspace.workspaceId,
                worktree = workspace.path,
                name = workspace.title,
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
        if (protocolOf(conn) == DshWireProtocol.V012) return providersV012(conn)
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
                mapCatalogModel(groupId, m)
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
        return dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse(
            providers = mergeProviders(directoryNames, groupsById),
        )
    }

    /**
     * 0.1.2 目录（#318）：llm.providers → llm/listProviders 回**数组**
     * [{id,name}]（callJson 面，call 的对象前置不适用）；llm.models →
     * session/modelCatalog——防御式解析：遍历 value 顶层键（跳过 default），
     * 值为对象且含 models 数组则视为组 {id=键, models=[{id,…}]}；解析不出组则
     * 退化为只有 providers 目录。合流语义与 V011 同构（目录序优先）。
     */
    private suspend fun providersV012(conn: ServerConnection): dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse {
        val directoryNames = linkedMapOf<String, String>()
        rpc.callJson(conn, "llm.providers", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "llm.providers failed: " + e.message)
            null
        }?.let { value ->
            (value as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { entry ->
                val id = entry.dshStr("id") ?: return@forEach
                directoryNames[id] = entry.dshStr("name") ?: id
            }
        }

        val groupsById = linkedMapOf<String, ProviderInfo>()
        val catalog = rpc.call(conn, "llm.models", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "llm.models failed: " + e.message)
            null
        }
        // 生产 0.1.2-rc.1 实测形态（2026-09-04，用户反馈智谱模型缺席定因）：value
        // = {default, routableProviders, groups:[{id,name,models[]}], failures}——
        // **组在 groups 数组**（条目含 id/name/models），非顶层键=组 id。原顶层
        // 遍历全部落空（routableProviders/groups 无 models 键被跳过）→ 目录只剩
        // provider 名、零模型 → 切换模型无智谱（Web 端读 groups 正常）。
        val groupsArr = catalog?.get("groups") as? JsonArray
        if (groupsArr != null) {
            groupsArr.filterIsInstance<JsonObject>().forEach { group ->
                val groupId = group.dshStr("id") ?: return@forEach
                val models = (group.dshArr("models") ?: emptyList()).filterIsInstance<JsonObject>()
                    .mapNotNull { m -> mapCatalogModel(groupId, m) }
                if (models.isNotEmpty()) {
                    groupsById[groupId] = ProviderInfo(
                        id = groupId,
                        name = group.dshStr("name") ?: groupId,
                        source = "dsh",
                        models = models.associateBy { it.id },
                    )
                }
            }
        } else catalog?.forEach { (key, groupEl) ->
            // 防御 fallback：顶层键=组 id 形态（alpha.5 未复核；与 groups 数组互斥）
            if (key == "default" || key == "routableProviders" || key == "failures") return@forEach
            val group = groupEl as? JsonObject ?: return@forEach
            val modelsArr = group["models"] as? JsonArray ?: return@forEach
            val models = modelsArr.filterIsInstance<JsonObject>().mapNotNull { m -> mapCatalogModel(key, m) }
            if (models.isNotEmpty()) {
                groupsById[key] = ProviderInfo(
                    id = key,
                    name = key,
                    source = "dsh",
                    models = models.associateBy { it.id },
                )
            }
        }

        return dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse(
            providers = mergeProviders(directoryNames, groupsById),
        )
    }

    /** 目录模型条目映射（V011/V012 共形）：reasoning.efforts → variants + capabilities。 */
    private fun mapCatalogModel(groupId: String, m: JsonObject): ProviderModel? {
        val modelId = m.dshStr("id") ?: return null
        val efforts = m.dshObj("reasoning")?.dshArr("efforts")
            ?.filterIsInstance<JsonObject>().orEmpty()
        val variants = efforts.mapNotNull { e -> e.dshStr("id")?.let { it to e } }.toMap()
            .takeIf { it.isNotEmpty() }
        return ProviderModel(
            id = modelId,
            providerId = groupId,
            name = m.dshStr("name") ?: modelId,
            capabilities = variants?.let { ModelCapabilities(reasoning = true) },
            variants = variants,
        )
    }

    /** 合流：目录序优先（目录名优先于组名）；目录未覆盖的组防御性追加。 */
    private fun mergeProviders(
        directoryNames: LinkedHashMap<String, String>,
        groupsById: LinkedHashMap<String, ProviderInfo>,
    ): List<ProviderInfo> = buildList {
        directoryNames.forEach { (id, name) ->
            val fromGroup = groupsById.remove(id)
            add(fromGroup?.copy(name = name) ?: ProviderInfo(id = id, name = name, source = "dsh"))
        }
        addAll(groupsById.values)
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
        val permission = settingsNamespace(conn, "permission") ?: return null
        val currentValue = permission.dshObj("value")?.dshStr("defaultPreset") ?: return null
        // #283：schema enum 动态档集（部署权威）。schema 形态防御：对象直取 /
        // JSON 字符串解析 / 缺席 → 空（UI 回退已知三档）。
        // #283：活体 schema 是 ref 解析形态（{uid, refs:{id:{type:const,value}},
        // {type:union,list:[ids]}}）而非朴素 enum——按 union→refs→const 提取档集；
        // 退化兼容朴素 {enum:[...]} 与缺席（空）。
        val options: List<String> = runCatching { parseSchemaEnumOptions(permission["schema"]) }
            .getOrDefault(emptyList())
        return dev.leonardo.ocbeacon.domain.model.DshPermissionDefault(
            currentValue = currentValue,
            revision = permission.dshLong("revision") ?: 0L,
            options = options,
        )
    }

    /**
     * 写新会话默认权限档（settings.mutate ns=permission，path=["defaultPreset"]）。
     * expectedRevision 先经 settings.describe 取当前 revision（乐观并发，陈旧 → settings-conflict）。
     */
    suspend fun setPermissionDefault(conn: ServerConnection, preset: String): Boolean {
        // #282-a：同形 payload 收口到 settingsMutateSet（先行读复用 getter 的 revision）
        val current = getPermissionDefault(conn) ?: return false
        return settingsMutateSet(conn, "permission", "defaultPreset", preset, current.revision)
    }

    /**
     * 读新会话默认 Agent 预设（settings.describe ns=agent-presets，§6 官方 Web General 设置行）。
     * value = {writable,hasDocument,namespaces:[{ns,value,revision,...}]}；取 ns=agent-presets
     * 的 value.default + revision。部署未挂 agent-presets 插件 → null。
     */
    suspend fun getDefaultAgentPreset(conn: ServerConnection): DshAgentPresetDefault? {
        // #282-a：ns 提取收口到 settingsNamespace
        val ns = settingsNamespace(conn, "agent-presets") ?: return null
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
        // #282-a：同形 payload 收口到 settingsMutateSet
        val current = getDefaultAgentPreset(conn) ?: return false
        return settingsMutateSet(conn, "agent-presets", "default", preset, current.revision)
    }

    override suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        unsupported("settings.write")

    override suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        unsupported("settings.write.global")

    override suspend fun disposeGlobal(conn: ServerConnection): Boolean = false

    override suspend fun disposeInstance(conn: ServerConnection): Boolean = false

    // ============ #324①：provider/模型目录（llm 目录探查 + credentials + 自定义增删） ============

    /**
     * llm/listConfigurableProviders → 可配置 provider 目录（数组直返，callJson 面）。
     * 0.1.1 无该端点 → 恒空列表（UI 目录合流退化为已注册行）。失败软降级空列表。
     */
    suspend fun listConfigurableProviders(conn: ServerConnection): List<DshConfigurableProvider> {
        if (protocolOf(conn) == DshWireProtocol.V011) return emptyList()
        val value = rpc.callJson(conn, "llm.listConfigurableProviders", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "llm/listConfigurableProviders failed: " + e.message)
            return emptyList()
        }
        return (value as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { entry ->
            val provider = entry.dshStr("provider") ?: return@mapNotNull null
            DshConfigurableProvider(
                provider = provider,
                displayName = entry.dshStr("displayName") ?: provider,
                settingsNs = entry.dshStr("settingsNs") ?: "",
                settingsPath = entry.dshArr("settingsPath")
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                    .orEmpty(),
                declared = entry.dshBool("declared"),
            )
        }
    }

    /**
     * llm/discoverModels(settingsNs, request) → 端点模型清单（数组直返）。
     * request {provider?, baseURL?, api?, apiKey?} 全可选（探测未落盘配置）。
     */
    suspend fun discoverModels(conn: ServerConnection, request: DshModelDiscoveryRequest): List<DshDiscoveredModel> {
        val payload = buildJsonObject {
            put("settingsNs", request.settingsNs)
            put("request", buildJsonObject {
                request.provider?.let { put("provider", it) }
                request.baseURL?.let { put("baseURL", it) }
                request.api?.let { put("api", it) }
                request.apiKey?.let { put("apiKey", it) }
            })
        }
        val value = rpc.callJson(conn, "llm.discoverModels", payload) { it }.getOrElse { e ->
            AppLogger.w(TAG, "llm/discoverModels failed: " + e.message)
            throw e
        }
        return (value as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { entry ->
            val id = entry.dshStr("id") ?: return@mapNotNull null
            DshDiscoveredModel(
                id = id,
                name = entry.dshStr("name"),
                contextWindow = entry.dshLong("contextWindow"),
                maxTokens = entry.dshLong("maxTokens"),
            )
        }
    }

    /**
     * credentials/describe(keys) → 逐 ref 状态 {configured, source?, writable}。
     * **永不携带明文**——UI 只显已配置态，写走 [setCredential]。
     */
    suspend fun describeCredentials(conn: ServerConnection, refs: List<String>): Map<String, DshCredentialStatus> {
        if (refs.isEmpty()) return emptyMap()
        val payload = buildJsonObject {
            put("keys", JsonArray(refs.map { JsonPrimitive(it) }))
        }
        val value = rpc.call(conn, "credentials.describe", payload) { it }.getOrElse { e ->
            AppLogger.w(TAG, "credentials/describe failed: " + e.message)
            return emptyMap()
        }
        val out = linkedMapOf<String, DshCredentialStatus>()
        value.forEach { (ref, entry) ->
            val obj = entry as? JsonObject ?: return@forEach
            out[ref] = DshCredentialStatus(
                ref = ref,
                configured = obj.dshBool("configured") ?: false,
                writable = obj.dshBool("writable") ?: false,
                source = obj.dshStr("source"),
            )
        }
        return out
    }

    /** credentials/set(key, value) → 写凭据（void 回程走 [DshRpcClient.callVoid]）。 */
    suspend fun setCredential(conn: ServerConnection, ref: String, value: String): Boolean =
        rpc.callVoid(conn, "credentials.set", buildJsonObject {
            put("key", ref)
            put("value", value)
        }).isSuccess

    /** credentials/unset(key) → 删凭据（void 回程）。 */
    suspend fun unsetCredential(conn: ServerConnection, ref: String): Boolean =
        rpc.callVoid(conn, "credentials.unset", buildJsonObject { put("key", ref) }).isSuccess

    /**
     * 新建自定义 provider（契约锚点 web CustomProviderCard）：
     * ① settings/mutate(ns=llm-pi-ai, set ["providers",route]=profile, expectedRevision?)；
     * ② apiKey 非空 → credentials/set(deriveCredentialRef(route), key)。
     * profile 落盘成功但凭据失败 → 返回 false（profile 已在，重试路径=凭据单写）。
     */
    suspend fun createCustomProvider(conn: ServerConnection, draft: DshCustomProviderDraft, expectedRevision: Long?): Boolean {
        val payload = buildJsonObject {
            put("ns", DshCustomProviders.SETTINGS_NS)
            put("ops", JsonArray(listOf(buildJsonObject {
                put("op", "set")
                put("path", JsonArray(DshCustomProviders.providerPath(draft.route).map { JsonPrimitive(it) }))
                put("value", DshCustomProviders.profileJson(draft))
            })))
            expectedRevision?.let { put("expectedRevision", it) }
        }
        val outcome = rpc.call(conn, "settings.mutate", payload) { Unit }
        if (outcome.isFailure) {
            val e = outcome.exceptionOrNull()
            if (e is DshApiError && e.httpStatus == 403) {
                AppLogger.w(TAG, "settings.mutate forbidden for provider create (403, loopback-only)")
                throw DshSettingsForbiddenException()
            }
            AppLogger.w(TAG, "createCustomProvider mutate failed: " + e?.message)
            return false
        }
        if (draft.apiKey.isNotBlank() && !setCredential(conn, DshCustomProviders.deriveCredentialRef(draft.route), draft.apiKey)) {
            AppLogger.w(TAG, "createCustomProvider credential store failed for route=" + draft.route)
            return false
        }
        return true
    }

    /**
     * 删除自定义 provider（对齐 web removeProviderProfile）：
     * ① settings/mutate(ns, unset path)（无 expectedRevision——整段删除不冲突）；
     * ② credentialRef 非空 → credentials/unset（托管凭据一并清除）。
     */
    suspend fun deleteCustomProvider(conn: ServerConnection, settingsNs: String, settingsPath: List<String>, credentialRef: String?): Boolean {
        val payload = buildJsonObject {
            put("ns", settingsNs)
            put("ops", JsonArray(listOf(buildJsonObject {
                put("op", "unset")
                put("path", JsonArray(settingsPath.map { JsonPrimitive(it) }))
            })))
        }
        val outcome = rpc.call(conn, "settings.mutate", payload) { Unit }
        if (outcome.isFailure) {
            val e = outcome.exceptionOrNull()
            if (e is DshApiError && e.httpStatus == 403) {
                AppLogger.w(TAG, "settings.mutate forbidden for provider delete (403, loopback-only)")
                throw DshSettingsForbiddenException()
            }
            AppLogger.w(TAG, "deleteCustomProvider mutate failed: " + e?.message)
            return false
        }
        if (credentialRef != null && !unsetCredential(conn, credentialRef)) {
            AppLogger.w(TAG, "deleteCustomProvider credential unset failed for ref=" + credentialRef)
            return false
        }
        return true
    }

    // ============ #324③：settings 全量快照 / 泛化 mutate / pluginInventory ============

    /**
     * settings/describe → 全量快照（writable/hasDocument/namespaces 原始 JSON——
     * 表单投影由 [DshSettingsFormMapper] 承担）。403 → [DshSettingsForbiddenException]
     *（#298 特权面栅栏同 settingsNamespace）。
     */
    suspend fun describeSettings(conn: ServerConnection): DshSettingsSnapshot? {
        val value = rpc.call(conn, "settings.describe", buildJsonObject {}) { it }.getOrElse { e ->
            if (e is DshApiError && e.httpStatus == 403) {
                AppLogger.w(TAG, "settings.describe forbidden (403, loopback-only)")
                throw DshSettingsForbiddenException()
            }
            AppLogger.w(TAG, "settings.describe failed: " + e.message)
            return null
        }
        return DshSettingsSnapshot(
            writable = value.dshBool("writable") ?: false,
            hasDocument = value.dshBool("hasDocument") ?: false,
            namespaces = (value.dshArr("namespaces") ?: emptyList()).filterIsInstance<JsonObject>(),
        )
    }

    /**
     * settings/mutate 泛化 ops（顶层键 Set/Unset；expectedRevision 乐观并发）。
     * 403 → [DshSettingsForbiddenException]；其余失败 false（UI 内联提示）。
     */
    suspend fun mutateSettings(
        conn: ServerConnection,
        ns: String,
        ops: List<DshSettingsOp>,
        expectedRevision: Long,
    ): Boolean {
        val opsArray = JsonArray(ops.map { op ->
            when (op) {
                is DshSettingsOp.Set -> buildJsonObject {
                    put("op", "set")
                    put("path", JsonArray(listOf(JsonPrimitive(op.key))))
                    put("value", op.value)
                }
                is DshSettingsOp.Unset -> buildJsonObject {
                    put("op", "unset")
                    put("path", JsonArray(listOf(JsonPrimitive(op.key))))
                }
            }
        })
        val payload = buildJsonObject {
            put("ns", ns)
            put("ops", opsArray)
            put("expectedRevision", expectedRevision)
        }
        val outcome = rpc.call(conn, "settings.mutate", payload) { Unit }
        if (outcome.isFailure) {
            val e = outcome.exceptionOrNull()
            if (e is DshApiError && e.httpStatus == 403) {
                AppLogger.w(TAG, "settings.mutate forbidden for ns=" + ns + " (403, loopback-only)")
                throw DshSettingsForbiddenException()
            }
            AppLogger.w(TAG, "settings.mutate failed for ns=" + ns + ": " + e?.message)
            return false
        }
        return true
    }

    /**
     * pluginInventory/list → 清单快照（只读：entries + per-preset 行三态）。
     * 失败 → null（UI 区块隐藏）。
     */
    suspend fun listPluginInventory(conn: ServerConnection): DshPluginInventory? {
        val value = rpc.call(conn, "pluginInventory.list", buildJsonObject {}) { it }.getOrElse { e ->
            AppLogger.w(TAG, "pluginInventory/list failed: " + e.message)
            return null
        }
        val entries = (value.dshArr("entries") ?: emptyList()).filterIsInstance<JsonObject>().mapNotNull { entry ->
            val entryId = entry.dshStr("entryId") ?: return@mapNotNull null
            val moduleName = entry.dshStr("moduleName") ?: return@mapNotNull null
            DshPluginInventoryEntry(
                entryId = entryId,
                moduleName = moduleName,
                enabled = entry.dshBool("enabled") ?: false,
                fiberPhase = entry.dshStr("fiberPhase"),
            )
        }
        val presets = (value.dshArr("agentPresets") ?: emptyList()).filterIsInstance<JsonObject>().mapNotNull { preset ->
            val id = preset.dshStr("id") ?: return@mapNotNull null
            DshPluginInventoryPreset(
                id = id,
                trust = preset.dshStr("trust") ?: "system",
                name = preset.dshStr("name") ?: id,
                isDefault = preset.dshBool("isDefault") ?: false,
                broken = preset.dshStr("broken"),
                rows = (preset.dshArr("rows") ?: emptyList()).filterIsInstance<JsonObject>().mapNotNull { row ->
                    val moduleName = row.dshStr("moduleName") ?: return@mapNotNull null
                    val enabled = DshPluginEnabled.fromWire(
                        (row["enabled"] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }
                    ) ?: return@mapNotNull null
                    DshPluginInventoryPresetRow(
                        entryId = row.dshStr("entryId"),
                        moduleName = moduleName,
                        enabled = enabled,
                        condition = row.dshStr("condition"),
                        fiberPhase = row.dshStr("fiberPhase"),
                    )
                },
            )
        }
        return DshPluginInventory(entries = entries, presets = presets)
    }

    // ============ #282-a：settings 域同形收口 ============

    /** settings.describe → 指定 ns 条目（缺席/失败 → null；日志带 ns 便于分诊）。 */
    private suspend fun settingsNamespace(conn: ServerConnection, ns: String): JsonObject? {
        val value = rpc.call(conn, "settings.describe", buildJsonObject {}) { it }.getOrElse { e ->
            // #298：Host 栅栏 403（非 loopback 连接对特权面恒 403，body 恒 "forbidden"）
            // ——上抛让 UI 显式标注「需 loopback 连接」，不当普通失败静默降级
            if (e is DshApiError && e.httpStatus == 403) {
                AppLogger.w(TAG, "settings.describe forbidden for ns=$ns (403, loopback-only)")
                throw DshSettingsForbiddenException()
            }
            AppLogger.w(TAG, "settings.describe failed for ns=$ns: " + e.message)
            return null
        }
        return (value.dshArr("namespaces") ?: emptyList())
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.dshStr("ns") == ns }
    }

    /**
     * #324：settings.describe → 指定 ns 快照（public 仓库面；[settingsNamespace] 的
     * 开放包装——provider 目录/自定义增删读 revision 与 providers 值）。
     * 403 上抛 [DshSettingsForbiddenException]（同 [settingsNamespace] 栅栏）。
     */
    suspend fun settingsNamespaceSnapshot(conn: ServerConnection, ns: String): JsonObject? =
        settingsNamespace(conn, ns)

    /** settings.mutate → 单键 set（乐观并发：expectedRevision 由调用方先行读取）。 */
    private suspend fun settingsMutateSet(conn: ServerConnection, ns: String, key: String, value: String, expectedRevision: Long): Boolean {
        // #318 双保险：expectedRevision 是非空 Long → 恒 JsonPrimitive（非 JsonNull）；
        // 0.1.2 adapter FLAT 对 settings/mutate 亦丢 JsonNull 值。
        val payload = buildJsonObject {
            put("ns", ns)
            put("ops", JsonArray(listOf(buildJsonObject {
                put("op", "set")
                put("path", JsonArray(listOf(JsonPrimitive(key))))
                put("value", JsonPrimitive(value))
            })))
            put("expectedRevision", JsonPrimitive(expectedRevision))
        }
        val outcome = rpc.call(conn, "settings.mutate", payload) { Unit }
        if (outcome.isFailure) {
            val e = outcome.exceptionOrNull()
            // #298：与 settingsNamespace 同栅栏（settings.mutate 同属特权面）
            if (e is DshApiError && e.httpStatus == 403) {
                AppLogger.w(TAG, "settings.mutate forbidden for ns=$ns (403, loopback-only)")
                throw DshSettingsForbiddenException()
            }
            return false
        }
        return true
    }

    // ============ 共用 ============

    /**
     * session.history 响应行提取：entries/events（0.1.1）/ records（0.1.2 page）
     * 数组（HistoryEntry={event,view?} 由 folder 解包）。防御：跳过 0.1.2 的
     * chunk 压缩行（{type:chunks}）——fold 不认识该行型会 refusedRebuild 放弃
     * 整页重建（journal §2.4），AppLogger.i 计数。
     */
    internal fun historyEntryRows(value: JsonObject): List<JsonObject> {
        val rows = value.dshArr("entries") ?: value.dshArr("events") ?: value.dshArr("records") ?: return emptyList()
        val all = rows.mapNotNull { it as? JsonObject }
        val chunks = all.count { it.dshStr("type") == "chunks" }
        if (chunks > 0) {
            AppLogger.i(TAG, "history rows: skipped " + chunks + " chunk-compressed row(s) (dsh 0.1.2)")
        }
        return all.filterNot { it.dshStr("type") == "chunks" }
    }

    private fun joinPath(base: String, name: String): String =
        if (base.isEmpty()) name else base.trimEnd('/') + "/" + name

    /**
     * #287：附件字节拉取（session.attachment）。活体形态（2026-09-01 实测）：
     * value = {attachment:{attachmentId, mediaType, bytes, width, height, name},
     * data:"<base64>"}（无 data: 前缀）。返回可直接拼 data URL 的对。
     */
    suspend fun readAttachment(
        conn: ServerConnection,
        sessionId: String,
        attachmentId: String,
    ): Pair<String, String>? {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("attachmentId", attachmentId)
        }
        val value = rpc.call(conn, "session.attachment", payload) { it }.getOrElse { e ->
            AppLogger.w(TAG, "session.attachment failed for $attachmentId: " + e.message)
            return null
        }
        val mediaType = value.dshObj("attachment")?.dshStr("mediaType") ?: "application/octet-stream"
        val data = value.dshStr("data") ?: return null
        return mediaType to data
    }

    private fun parseSchemaEnumOptions(schemaEl: kotlinx.serialization.json.JsonElement?): List<String> {
        val schema = schemaEl as? JsonObject ?: return emptyList()
        // refs 是 id→节点 的对象表（活体实测："848":{type:const,...}）
        val refs = (schema["refs"] as? JsonObject)
            ?.mapValues { it.value as? JsonObject }
            ?.filterValues { it != null }
            ?.mapValues { it.value!! }
            ?: return emptyList()
        fun constValue(node: JsonObject): String? =
            if (node.dshStr("type") == "const") {
                (node["value"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    ?.content
            } else null
        for (node in refs.values) {
            if (node.dshStr("type") == "union") {
                val ids = (node["list"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    .orEmpty()
                val values = ids.mapNotNull { id -> refs[id]?.let { n -> constValue(n) } }
                if (values.isNotEmpty()) return values
            }
        }
        return (schema["enum"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    ?.content
            }
            .orEmpty()
    }
}