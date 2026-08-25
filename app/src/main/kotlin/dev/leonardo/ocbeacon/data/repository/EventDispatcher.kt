package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

private const val TAG = "EventDispatcher"

/**
 * 事件分发器——替代单体 EventReducer。
 *
 * 将 SSE 事件委托给已注册的 [SseEventHandler] 实例。
 * 暴露从各 handler 聚合的只读 StateFlow。
 * 处理横切关注点（例如 SessionDeleted 级联清理、
 * CommandExecuted 会话状态重置）。
 */
@Singleton
class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val miscHandler: MiscEventHandler,
    private val sessionNextHandler: SessionNextEventHandler,
    private val shellJobsHandler: ShellJobsHandler,
    private val sessionStateRepository: SessionStateService,
    private val settingsDataStore: SettingsDataStore,
    // C5 拆分：未读红点持久化自 SettingsDataStore 迁出（同 DataStore 同键名）
    private val unreadStateStore: UnreadStateStore,
    private val unreadBadgeService: UnreadBadgeService,
    private val ownershipRegistry: StreamingOwnershipRegistry,
    private val sessionRepoProvider: javax.inject.Provider<dev.leonardo.ocbeacon.domain.repository.SessionRepository>,
    // #122（2026-08-18 接线）：PermissionAutoApprover 此前全库零调用——用户在设置页
    // 保存的自动批准规则从未生效（功能失效）。接线进 PermissionAsked 分发路径。
    private val permissionAutoApprover: PermissionAutoApprover,
    private val chatRepoProvider: javax.inject.Provider<dev.leonardo.ocbeacon.domain.repository.ChatRepository>,
    // 堆积消息管线（2026-08-20）：Provider 打破 EventDispatcher→ChatRepository 循环；
    // init 中 eager 构造 + 接线 naturalTurnEndListener
    private val pendingMessagePipelineProvider: javax.inject.Provider<PendingMessagePipeline>,
    private val pendingMessageRepository: dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository,
) {
    /**
     * 一次性 unread v2 迁移 scope：App 启动时清空旧域已读标记（readTimes/allReadAt/
     * 孤儿 lastReplyTime），值域从客户端 now 变为服务器 completed，旧值不可比。幂等
     * （boolean 标记）。独立 scope，不阻塞事件处理（与已删 replyTimePersistScope 同模式）。
     */
    private val unreadMigrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** #122：自动批准协程 scope（IO；失败仅日志，不影响事件分发主路径）。 */
    private val autoApproveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** debug 级分发日志的 delta 节流计数器（仅 DEBUG 构建使用）。
     *  2026-08-14 走查修复：多服务器 SSE 协程并发调用 processEvent →
     *  改原子计数（原 var 非原子，仅日志节流不准，无功能影响）。 */
    private val dispatchCounter = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        // #174：SessionStateService 的 8 个 FSM 回调已收进 SessionStateCollaboratorImpl
        //（构造注入，漏接=编译错误）；本 init 只保留跨 handler 事件桥接（EventDispatcher 本职）。
        // 2026-08-15（research/11 P1）：session.next.moved → 更新会话缓存
        // directory（对齐官方 TUI 增量更新；无 sessionHandler 依赖倒置问题）
        sessionNextHandler.sessionMovedListener = { sessionId, location, subdirectory ->
            sessionHandler.updateSessionDirectory(sessionId, location, subdirectory)
        }
        // 2026-08-15（research/11 P1）：error 产生未读（对齐官方 Web——挂后台
        // 会话失败时列表有感知）
        sessionHandler.onSessionError = { sessionId, _ ->
            // 客户端时刻——事件类型显式承载该例外（research/11 P1）
            unreadBadgeService.onEvent(
                UnreadEvent.SessionErrorOccurred(sessionId, System.currentTimeMillis())
            )
        }
        // #176/#177：堆积消息状态补偿驱动（心跳 + Idle 观察）随首个连接启动
        //（幂等；此前的边沿触发 naturalTurnEndListener 接线不变）
        pendingMessagePipelineProvider.get().start()
    }

    // ============ 事件处理器注册表（开闭原则）============
    // 将每个 SseEvent 子类映射到其唯一负责的 handler。
    // 要支持新的事件域：在下方添加 bind() 调用。processEvent 本身
    // 永不改变——它只是查找此 map。这替代了之前的广播模型，
    // 即每个事件都发送给全部 6 个 handler，每个 handler 再通过自身的
    // `when` 块在内部过滤。
    private val registry: Map<KClass<out SseEvent>, SseEventHandler> = buildRegistry()

    private fun buildRegistry(): Map<KClass<out SseEvent>, SseEventHandler> {
        val map = mutableMapOf<KClass<out SseEvent>, SseEventHandler>()
        fun bind(handler: SseEventHandler, vararg events: KClass<out SseEvent>) {
            for (e in events) map[e] = handler
        }
        // 会话生命周期 + 服务器连接事件 → SessionEventHandler
        bind(
            sessionHandler,
            SseEvent.ServerConnected::class, SseEvent.ServerHeartbeat::class,
            SseEvent.ServerInstanceDisposed::class,
            SseEvent.SessionCreated::class, SseEvent.SessionUpdated::class,
            SseEvent.SessionDeleted::class, SseEvent.SessionStatus::class,
            SseEvent.SessionIdle::class, SseEvent.SessionError::class,
            SseEvent.SessionDiff::class, SseEvent.SessionCompacted::class,
            SseEvent.VcsBranchUpdated::class, SseEvent.ProjectUpdated::class
        )
        // 消息（updated/removed/part×3）→ MessageEventHandler 直接实现 SseEventHandler
        //（#175：原三壳 handler 全指向同一 store 且 serverId 未用，删壳单 bind）
        bind(
            messageHandler,
            SseEvent.MessageUpdated::class, SseEvent.MessageRemoved::class,
            SseEvent.MessagePartUpdated::class, SseEvent.MessagePartDelta::class,
            SseEvent.MessagePartRemoved::class
        )
        // 权限 → PermissionEventHandler
        bind(
            permissionHandler,
            SseEvent.PermissionAsked::class, SseEvent.PermissionReplied::class
        )
        // 问题 → QuestionEventHandler
        bind(
            questionHandler,
            SseEvent.QuestionAsked::class, SseEvent.QuestionReplied::class,
            SseEvent.QuestionRejected::class
        )
        // 杂项（todo、command、pty、workspace、file、vcs、install、lsp）→ MiscEventHandler
        bind(
            miscHandler,
            SseEvent.TodoUpdated::class, SseEvent.CommandExecuted::class,
            SseEvent.PtyCreated::class, SseEvent.PtyUpdated::class, SseEvent.PtyDeleted::class,
            SseEvent.WorkspaceReady::class, SseEvent.WorkspaceFailed::class,
            SseEvent.FileEdited::class, SseEvent.McpToolsChanged::class,
            SseEvent.FileWatcherUpdated::class,
            SseEvent.InstallationUpdated::class, SseEvent.InstallationUpdateAvailable::class,
            SseEvent.WorktreeReady::class, SseEvent.WorktreeFailed::class,
            SseEvent.LspUpdated::class
        )
        // SessionNext → SessionNextEventHandler
        bind(sessionNextHandler, SseEvent.SessionNext::class)
        // V2 后台 shell → ShellJobsHandler
        bind(
            shellJobsHandler,
            SseEvent.ShellJobStarted::class, SseEvent.ShellJobEnded::class
        )
        return map
    }

    // ============ 公共状态（只读）============

    val serverSessions: StateFlow<Map<String, Set<String>>> get() = sessionHandler.serverSessions
    val sessions: StateFlow<List<Session>> get() = sessionHandler.sessions
    /** [SessionStateService.statusFlow] 的门面——会话状态的单一真相源。 */
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> get() = sessionStateRepository.statusFlow

    /** 会话 TODO（2026-08-20 面板数据源）：SSE todo.updated 实时 + REST hydrate。 */
    val sessionTodos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> get() = miscHandler.todos

    /** REST hydrate 委托（SessionRepositoryImpl.getSessionTodos 成功后回填）。 */
    fun hydrateTodos(sessionId: String, todos: List<SseEvent.TodoUpdated.Todo>) {
        miscHandler.setTodos(sessionId, todos)
    }
    val messages: StateFlow<Map<String, List<Message>>> get() = messageHandler.messages
    val parts: StateFlow<Map<String, List<Part>>> get() = messageHandler.parts
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> get() = sessionHandler.sessionDiffs
    val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> get() = permissionHandler.permissions
    val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> get() = questionHandler.questions
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> get() = miscHandler.todos
    val vcsBranch: StateFlow<String?> get() = sessionHandler.vcsBranch
    val projectInfo: StateFlow<Project?> get() = sessionHandler.projectInfo
    val lastUserMessageTime: StateFlow<Map<String, Long>> get() = sessionHandler.lastUserMessageTime

    /** 每会话最后完成 assistant 消息的 completed（**服务器时刻**，实时派生）。
     *  红点判定的唯一时间源——委托 [UnreadBadgeService]（抽出前的 _lastCompletedReplyTime）。 */
    val lastCompletedReplyTime: StateFlow<Map<String, Long>> get() = unreadBadgeService.lastCompletedReplyTime

    init {
        // 持久化 init：迁移必须先于 seed（清空旧客户端 now 域值后再读 seed）。
        // 顺序保证：迁移在先 → seedFromStorage 读到的是服务器域值或空。
        unreadMigrationScope.launch {
            // runCatching 容错：迁移失败（含 mock 环境）不阻塞 init 持久化路径（spec §3.1）
            val migrationRan = runCatching { unreadStateStore.runUnreadStateV2Migration() }.isSuccess
            AppLogger.d("UnreadDiag", "[migration] executed=$migrationRan")
            // #202：collapse_tools→auto_expand_tools 键名搬家迁移（值无取反；unread 同款纪律）
            runCatching { settingsDataStore.runAutoExpandToolsKeyMigration() }
            // seed 合并 + 落盘由 UnreadBadgeService 负责；幂等（max 合并，详见其类注释）。
            // kill 进程后 seed 不丢——落盘由 service 内 persistNow（suspend，本协程内同步完成）。
            runCatching { unreadBadgeService.seedFromStorage() }
                .onFailure { e -> AppLogger.e("UnreadDiag", "[seed] failed", e) }
        }
    }

    // Session Next 状态
    val currentAgent: StateFlow<Map<String, String>> get() = sessionNextHandler.currentAgent
    val currentModel: StateFlow<Map<String, Pair<String, String>>> get() = sessionNextHandler.currentModel
    val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>> get() = sessionNextHandler.activeToolProgress
    val stepProgress: StateFlow<Map<String, StepProgressInfo>> get() = sessionNextHandler.stepProgress
    val compactionState: StateFlow<Map<String, CompactionStateInfo>> get() = sessionNextHandler.compactionState
    /** #219：压缩失败广播——ChatViewModel snackbar 数据源。 */
    val compactionFailures: kotlinx.coroutines.flow.SharedFlow<Pair<String, String>> get() = sessionNextHandler.compactionFailures
    /** 2026-08-15：按 sessionId 的实时 token 用量（V2 session.usage.updated）。 */
    val sessionUsage: StateFlow<Map<String, dev.leonardo.ocbeacon.domain.model.SessionNextEvent.UsageUpdated>> get() = sessionNextHandler.sessionUsage
    /** 2026-08-15：已压缩会话集合（SessionCompacted 事件）——UI 监听刷新消息列表。 */
    val compactedSessions: StateFlow<Map<String, Long>> get() = sessionHandler.compactedSessions
    val shellState: StateFlow<Map<String, ShellStateInfo>> get() = sessionNextHandler.shellState
    val retryState: StateFlow<Map<String, Int>> get() = sessionNextHandler.retryState
    val gapDetected: StateFlow<Set<String>> get() = sessionNextHandler.gapDetected

    // ============ 事件处理 ============

    /**
     * 通过分发给所有 handler 来处理 SSE 事件。
     * 分发后处理横切关注点：
     * - SessionDeleted：对已删除会话级联清理所有 handler
     * - CommandExecuted：将 会话状态重置为 Idle
     *
     * 多服务器去重：若两个服务器配置指向同一后端，
     * 仅首个获得会话所有权的服务器处理其事件。来自不同 serverId
     * 的同一会话的后续事件会被跳过，以防止流式输出翻倍。
     */
    /**
     * 2026-08-16 根治（回复不可见）：SSE 重连成功后的断连窗口内容对账——
     * 委托 [SessionStateService.backfillActiveForServer]（cursor 增量 +
     * SSE_PRIORITY 合并，流式进行中调用安全）。由 SseConnectionManager
     * 在连接恢复处调用。
     */
    fun backfillActiveForServer(serverId: String) {
        sessionStateRepository.backfillActiveForServer(serverId)
    }

    /**
     * #122（2026-08-18）：PermissionAsked 自动批准钩子。
     *
     * 规则匹配（AutoApproveRule.matches：toolName/sessionId/directoryPattern）
     * → 异步 respondPermission("once")。目录取该会话的 Session.directory
     * （sessionHandler 内存态；查不到时传空串，directoryPattern="*" 的规则
     * 仍可匹配）。失败仅 WARN 日志——自动批准是尽力而为的增强，不阻塞
     * 事件主路径（用户仍可手动回复）。
     */
    private fun maybeAutoApprovePermission(event: SseEvent.PermissionAsked, serverId: String) {
        autoApproveScope.launch {
            try {
                val sessionDirectory = sessionHandler.sessions.value
                    .firstOrNull { it.id == event.sessionId }?.directory ?: ""
                if (!permissionAutoApprover.shouldAutoApprove(event, sessionDirectory)) return@launch
                AppLogger.i(TAG, "[auto-approve] rule matched: permission=" + event.permission + " sid=" + event.sessionId.take(12) + " dir=" + sessionDirectory + " — replying once")
                val ok = chatRepoProvider.get()
                    .respondPermission(serverId, event.sessionId, event.id, "once", sessionDirectory.takeIf { it.isNotBlank() })
                    .getOrDefault(false)
                if (!ok) {
                    AppLogger.w(TAG, "[auto-approve] respondPermission returned false (request may have expired): id=" + event.id)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLogger.w(TAG, "[auto-approve] failed: " + t.message)
            }
        }
    }

    fun processEvent(event: SseEvent, serverId: String) {
        // 所有权检查：当两条 SSE 连接投递相同事件
        //（同一后端，不同配置）时，防止重复事件处理。
        val sessionId = extractSessionId(event)
        if (sessionId != null && !ownershipRegistry.claim(sessionId, serverId)) {
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "Skipping duplicate ${event::class.simpleName} for session " +
                    "${sessionId.take(12)} from server=$serverId (owner=${ownershipRegistry.ownerOf(sessionId)})")
            }
            return
        }
        // 注册表分发：将事件路由到其唯一注册的 handler（O(1) 查找）。
        // 替代了之前的广播模型，即每个事件都发送给全部 6 个 handler，
        // 每个 handler 再通过自身的 `when` 块在内部过滤。
        val handler = registry[event::class]
        if (handler != null) {
            if (BuildConfig.DEBUG) {
                // debug 级分发日志：事件类型 + 目标 handler（不干扰正常日志）。
                // delta 高频事件按 100 条节流，避免流式期间刷屏。
                val typeName = event::class.simpleName ?: "?"
                if (event is SseEvent.MessagePartDelta) {
                    val n = dispatchCounter.incrementAndGet()
                    if (n % 100L == 1L) {
                        AppLogger.d(TAG, "[dispatch] ${typeName} -> ${handler::class.simpleName} (delta stream, counter=${n})")
                    }
                } else {
                    AppLogger.d(TAG, "[dispatch] ${typeName} -> ${handler::class.simpleName} sid=${sessionId?.take(12)}")
                }
            }
            handler.handle(event, serverId)
        } else if (BuildConfig.DEBUG) {
            AppLogger.w(TAG, "No handler registered for ${event::class.simpleName}")
        }
        forwardToSessionStateService(event, serverId)

        // #122（2026-08-18 接线）：PermissionAsked 自动批准——匹配用户保存的
        // AutoApproveRule 时异步回复（规则列表为空 = shouldAutoApprove 恒 false，
        // 天然关闭；不阻塞事件分发主路径）。成功后 PermissionReplied 事件回流
        // 自然清卡片（handler 幂等去重已防重复）。
        if (event is SseEvent.PermissionAsked) {
            maybeAutoApprovePermission(event, serverId)
        }

        // 跨 handler：#216——.next 的 tool.progress 携带 subagent 子智能体会话
        // ID（metadata.sessionID）但只喂了进度流（SessionNextEventHandler），SSE
        // 实时链路的 part 事件链（session.tool.called 等）不带 metadata → 主对话
        // 流 TaskToolCard Running 期无跳转箭头。此处把 id 跨写进消息流 Part.Tool
        //（幂等，只补缺失），V1 快照 / V2 REST 快照 / V2 SSE 三路径行为对齐。
        if (event is SseEvent.SessionNext && event.event is SessionNextEvent.ToolProgress) {
            val tp = event.event
            val childSid = tp.metadata?.get("sessionID")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                ?.takeIf { it.isNotBlank() }
            if (childSid != null) {
                messageHandler.patchToolChildSession(tp.sessionId, tp.callId, childSid)
            }
        }

        // 跨 handler：SessionDeleted 级联清理其他 handler
        if (event is SseEvent.SessionDeleted) {
            val deletedSessionId = event.info.id
            ownershipRegistry.release(deletedSessionId)
            messageHandler.clearForSession(deletedSessionId)
            unreadBadgeService.removeSession(deletedSessionId)
            permissionHandler.clearForSession(deletedSessionId)
            questionHandler.clearForSession(deletedSessionId)
            miscHandler.clearForSession(deletedSessionId)
            sessionNextHandler.clearForSession(deletedSessionId)
            sessionStateRepository.clearSession(deletedSessionId)
            // 堆积消息级联删除（2026-08-20）：会话没了，队列无意义
            kotlinx.coroutines.runBlocking {
                runCatching { pendingMessageRepository.deleteForSession(deletedSessionId) }
            }
        }

        // 跨 handler：SessionCompacted（V2 session.compaction.ended 映射 /
        // legacy session.compacted）——服务器压缩真实完成，终结压缩横幅。
        // 用户发起路径的 HTTP 回调注入（SessionNext(CompactionEnded)）已幂等
        // 处理；auto-compaction 只有本事件，此前横幅永久停留。
        if (event is SseEvent.SessionCompacted) {
            sessionNextHandler.endCompaction(event.sessionId)
        }

        // 跨 handler：CommandExecuted——仅将命令所属的消息标记为已完成。
        // command.executed 是消息级事件（properties 含 messageID）；
        // 用 messageId 精确终结该消息，避免误杀同一会话中仍在流式的
        // 其他 assistant 消息（圆形进度条/统计栏提前切换的竞态根因）。
        // 不要强制会话为 Idle：会话实际变为空闲时服务器会发送 session.status 事件。
        // 此处强制 Idle 会在 agent 继续下一个工具调用时导致闪烁。
        if (event is SseEvent.CommandExecuted) {
            // #45：每命令事件触发——release 下跳过字符串拼接与 logcat
            if (BuildConfig.DEBUG) {
                AppLogger.i("UnreadDiag", "[CommandExecuted] session=${event.sessionId.take(12)} msg=${event.messageId.take(12)} name=${event.name}")
            }
            messageHandler.markSessionIdle(event.sessionId, event.messageId)
        }

        // 红点时间源：assistant 消息完成（服务器 completed）→ 增量更新 maxCompleted。
        // 与 markSessionIdle（客户端 now，UI 流式终止）解耦——红点判定只用服务器时刻。
        if (event is SseEvent.MessageUpdated && event.info is Message.Assistant && event.info.time.completed != null) {
            unreadBadgeService.onEvent(
                UnreadEvent.ServerMessageCompleted(event.info.sessionId, event.info.time.completed)
            )
        }

        // 跟踪用户消息时间，用于稳定的会话排序。
        if (event is SseEvent.MessageUpdated && event.info is Message.User) {
            sessionHandler.recordUserMessage(event.info.sessionId, event.info.time.created)
        }
    }

    /**
     * 检查会话是否有仍在流式输出的 assistant 消息（time.completed == null）。
     * 供 REST 同步逻辑和 L5 交叉校验器使用。
     */
    private fun hasIncompleteAssistant(sessionId: String): Boolean {
        return messageHandler.messages.value[sessionId]
            .orEmpty()
            .filterIsInstance<Message.Assistant>()
            .any { it.time.completed == null }
    }

    // ============ FSM 转发 ============

    /**
     * 将 SSE 事件转发给 [SessionStateService]（单一真相源）进行 FSM 处理。
     */
    private fun forwardToSessionStateService(event: SseEvent, serverId: String) {
        val fsmSessionId = extractSessionId(event)
        if (fsmSessionId != null) {
            // #110（D2-12）：serverId 一并传入——SessionStateService 记录
            // session→server 归属，REST 校验打到正确服务器（原全局
            // currentServerId 单值被后连接服务器覆盖 → L3 误判）。
            sessionStateRepository.onSseEvent(event, fsmSessionId, serverId)
        }
    }

    /**
     * 从任意 [SseEvent] 子类中提取 sessionId。
     * 对无关联会话的事件返回 null。
     */
    private fun extractSessionId(event: SseEvent): String? {
        return when (event) {
            // 会话生命周期（与 FSM 状态相关）
            is SseEvent.SessionStatus -> event.sessionId
            is SseEvent.SessionIdle -> event.sessionId
            is SseEvent.SessionError -> event.sessionId
            is SseEvent.SessionNext -> event.event.sessionId
            // 会话生命周期（信息）
            is SseEvent.SessionCreated -> event.info.id
            is SseEvent.SessionUpdated -> event.info.id
            is SseEvent.SessionDeleted -> event.info.id
            is SseEvent.SessionDiff -> event.sessionId
            is SseEvent.SessionCompacted -> event.sessionId
            // 消息
            is SseEvent.MessageUpdated -> event.info.sessionId
            is SseEvent.MessageRemoved -> event.sessionId
            is SseEvent.MessagePartUpdated -> event.part.sessionId
            is SseEvent.MessagePartDelta -> event.sessionId
            is SseEvent.MessagePartRemoved -> event.sessionId
            // 权限 / 问题
            is SseEvent.PermissionAsked -> event.sessionId
            is SseEvent.PermissionReplied -> event.sessionId
            is SseEvent.QuestionAsked -> event.sessionId
            is SseEvent.QuestionReplied -> event.sessionId
            is SseEvent.QuestionRejected -> event.sessionId
            // Todo / 命令
            is SseEvent.TodoUpdated -> event.sessionId
            is SseEvent.CommandExecuted -> event.sessionId
            // 无 sessionId 的事件
            is SseEvent.ServerConnected -> null
            is SseEvent.ServerHeartbeat -> null
            is SseEvent.ServerInstanceDisposed -> null
            is SseEvent.VcsBranchUpdated -> null
            is SseEvent.LspUpdated -> null
            is SseEvent.ProjectUpdated -> null
            is SseEvent.PtyCreated -> null
            is SseEvent.PtyUpdated -> null
            is SseEvent.PtyDeleted -> null
            // V2 后台 shell（按归属会话路由）
            is SseEvent.ShellJobStarted -> event.info.sessionId
            is SseEvent.ShellJobEnded -> event.info.sessionId
            is SseEvent.WorkspaceReady -> null
            is SseEvent.WorkspaceFailed -> null
            is SseEvent.FileEdited -> null
            is SseEvent.McpToolsChanged -> null
            is SseEvent.FileWatcherUpdated -> null
            is SseEvent.InstallationUpdated -> null
            is SseEvent.InstallationUpdateAvailable -> null
            is SseEvent.WorktreeReady -> null
            is SseEvent.WorktreeFailed -> null
        }
    }

    // ============ 委托操作 ============

    fun setSessions(serverId: String, sessions: List<Session>) =
        sessionHandler.setSessions(serverId, sessions)

    fun clearRevert(sessionId: String) {
        // 在清除过滤器之前从缓存中修剪已撤销的消息。
        // 否则过滤器解除后，已撤销的消息会短暂重现，
        // 然后服务器的 message.removed SSE 才追上——可见的闪烁。
        val revert = sessionHandler.sessions.value
            .find { it.id == sessionId }?.revert
        if (revert != null) {
            messageHandler.pruneRevertedMessages(sessionId, revert.messageId)
        }
        sessionHandler.clearRevert(sessionId)
    }

    fun setRevert(sessionId: String, messageId: String) =
        sessionHandler.setRevert(sessionId, messageId)

    /**
     * 服务器载荷 upsert（SSE_PRIORITY/REST_AUTHORITY）：合并进消息缓存 + 红点水位线
     * 从**载荷本身**提取（#171 切断消费侧——不再扫合并缓存，本地终结戳无从混入）。
     * 本地/DB 缓存种子请走 [seedCachedMessages]（不触红点）。
     */
    fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        strategy: MergeStrategy,
    ) {
        // #224：V1 压缩消息归一化（assistant(agent=compaction)→Part.Compaction
        // 分割线形态，与 V2 一致）；V2 载荷不满足条件，零操作直通。
        val normalized = dev.leonardo.ocbeacon.data.mapper.CompactionNormalizer
            .normalizeAll(messages)
        messageHandler.upsertMessages(sessionId, normalized, strategy)
        recomputeMaxCompleted(sessionId, normalized)
    }

    /**
     * 本地缓存种子（Room 回读 → 内存热视图）：纯缓存写，**不喂红点**。
     * DB 中的 completed 可能携带 markSessionIdle 的客户端终结戳（展示域正当，
     * 落盘持久化亦正当）——红点域只消费服务器载荷，DB 回环由此封死（#171）。
     */
    fun seedCachedMessages(sessionId: String, messages: List<MessageWithParts>) {
        messageHandler.upsertMessages(sessionId, messages, MergeStrategy.APPEND_ONLY)
    }


    /**
     * 从**载荷**提取 maxCompleted 喂红点（#171：不扫合并缓存——本地终结戳/DB 回读
     * 的客户端时刻从数据流上无法到达水位线；只增不减语义见 UnreadBadgeService 类注释）。
     */
    private fun recomputeMaxCompleted(sessionId: String, payload: List<MessageWithParts>) {
        val maxTs = payload
            .map { it.info }
            .filterIsInstance<Message.Assistant>()
            .mapNotNull { it.time.completed }
            .maxOrNull() ?: return
        unreadBadgeService.onEvent(UnreadEvent.RestSnapshot(sessionId, maxTs))
    }

    fun removePermission(permissionId: String) =
        permissionHandler.removePermission(permissionId)

    fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) =
        permissionHandler.setPermissions(sessionId, permissions)

    fun removeQuestion(questionId: String) =
        questionHandler.removeQuestion(questionId)

    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) =
        questionHandler.setQuestions(sessionId, questions)

    fun mergeQuestionsFromREST(sessionId: String, questions: List<SseEvent.QuestionAsked>) =
        questionHandler.mergeFromREST(sessionId, questions)

    fun trackSequence(sessionId: String, seq: Long) {
        sessionNextHandler.trackSequence(sessionId, seq)
    }

    fun clearGap(sessionId: String) {
        sessionNextHandler.clearGap(sessionId)
    }

    // ============ 子智能体会话聚合 ============

    /** 聚合某会话及其子智能体会话的权限。 */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>) =
        permissionHandler.getPermissionsWithChildren(sessionId, sessions)

    /** 聚合某会话及其子智能体会话的问题。 */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>) =
        questionHandler.getQuestionsWithChildren(sessionId, sessions)

    fun clearAll() {
        sessionHandler.clearAll()
        messageHandler.clearAll()
        permissionHandler.clearAll()
        questionHandler.clearAll()
        miscHandler.clearAll()
        sessionNextHandler.clearAll()
        sessionStateRepository.clearAll()
        ownershipRegistry.clearAll()
    }

    /**
     * 释放某会话的内存数据（消息/part/权限/问题/状态/通知去重）。
     * 会话退出时调用（ChatViewModel.onCleared）——内存泄漏修复（#89）：
     * 各 handler 是 Singleton 且按 sessionId 持有数据，正常切换会话
     * 不触发 SessionDeleted → 旧会话数据永驻内存。
     */
    fun releaseSessionData(serverId: String, sessionId: String) {
        // 可观测性（#89 验证）：清理入口日志——内存测试确认链路生效
        AppLogger.i(TAG, "releaseSessionData: server=$serverId session=$sessionId")
        sessionHandler.clearForSession(sessionId)
        messageHandler.clearForSession(sessionId)
        // 2026-08-14：不清理 permissionHandler/questionHandler——pending
        // permission/question 是服务器状态，退出会话后应保留（列表 Asking 状态
        // 不闪烁）；回答/拒绝由 SSE 事件清理，SessionDeleted/断连时级联清理。
        // 2026-08-14 再修复：不清理 sessionStateRepository FSM 状态——busy/streaming
        // 同样是服务器状态镜像（execution.started→busy，SSE 事件持续更新）。
        // 退出时清除 → 列表状态先消失再恢复（闪烁）；内存由 24h staleness
        // 自动清扫兜底（STATE_RETENTION_MS，非 Busy 会话超时移除）。
        miscHandler.clearForSession(sessionId)
        sessionNextHandler.clearForSession(sessionId)
        ownershipRegistry.release(sessionId)
        shellJobsHandler.clearForSession(sessionId)
    }

    fun clearForServer(serverId: String) {
        val sessionIds = sessionHandler.serverSessions.value[serverId] ?: emptySet()
        sessionHandler.clearForServer(serverId)
        messageHandler.clearForServer(sessionIds)
        permissionHandler.clearForServer(sessionIds)
        questionHandler.clearForServer(sessionIds)
        miscHandler.clearForServer(sessionIds)
        sessionNextHandler.clearForServer(sessionIds)
        // 释放由此服务器拥有的会话的流式所有权，
        // 允许另一服务器在仍连接时认领它们。
        ownershipRegistry.releaseAllForServer(serverId)
    }
}

