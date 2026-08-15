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
    private val messagePartHandler: MessagePartHandler,
    private val messageUpdatedHandler: MessageUpdatedHandler,
    private val messageRemovedHandler: MessageRemovedHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val miscHandler: MiscEventHandler,
    private val sessionNextHandler: SessionNextEventHandler,
    private val shellJobsHandler: ShellJobsHandler,
    private val sessionStateService: SessionStateService,
    private val settingsDataStore: SettingsDataStore,
    private val unreadBadgeService: UnreadBadgeService,
    private val ownershipRegistry: StreamingOwnershipRegistry,
) {
    /**
     * 一次性 unread v2 迁移 scope：App 启动时清空旧域已读标记（readTimes/allReadAt/
     * 孤儿 lastReplyTime），值域从客户端 now 变为服务器 completed，旧值不可比。幂等
     * （boolean 标记）。独立 scope，不阻塞事件处理（与已删 replyTimePersistScope 同模式）。
     */
    private val unreadMigrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** debug 级分发日志的 delta 节流计数器（仅 DEBUG 构建使用）。
     *  2026-08-14 走查修复：多服务器 SSE 协程并发调用 processEvent →
     *  改原子计数（原 var 非原子，仅日志节流不准，无功能影响）。 */
    private val dispatchCounter = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        // SessionStateService 回调——在此接线以打破循环依赖
        //（EventDispatcher ← SessionStateService 经由 Provider，但回调
        // 需要 messageHandler，它位于 EventDispatcher 的作用域内）。
        sessionStateService.incompleteChecker = IncompleteAssistantChecker { sessionId ->
            hasIncompleteAssistant(sessionId)
        }
        sessionStateService.directoryResolver = DirectoryResolver { sessionId ->
            sessionHandler.sessions.value.find { it.id == sessionId }?.directory
        }
        sessionStateService.messageForceCompleter = MessageForceCompleter { sessionId ->
            // markSessionIdle 用客户端 now 标记 UI 流式终止，但不写入红点时间源
            //（红点判定只用服务器时刻 event.time.completed，见 processEvent 增量块）。
            messageHandler.markSessionIdle(sessionId)
            // 落盘兜底：idle 到达时，前序 MessageUpdated(completed) 已更新内存红点时间源，
            // 此刻触发落盘确保杀进程不丢。旧为 runBlocking 同步写，现委托 UnreadBadgeService 异步写
            //（seed 恢复兜底，有界丢失窗口：毫秒级）。
            unreadBadgeService.persistAsync()
        }
        sessionStateService.messageRefresher = MessageRefresher { sessionId, messages ->
            messageHandler.upsertMessages(sessionId, messages, MergeStrategy.REST_AUTHORITY)
        }
        // #55：L3 校验增量补漏的游标锚点——本地最新消息 id（V2 NEWER 方向增量拉取）
        sessionStateService.latestMessageIdProvider = { sessionId ->
            messageHandler.messages.value[sessionId]?.maxByOrNull { it.time.created }?.id
        }
        // 2026-08-14 走查修复（僵尸误杀防护）：该会话有等待用户输入的
        // pending question/permission 时，服务器合法运行中（等待用户回答），
        // 僵尸判定不得 interrupt（否则 >3 分钟未回答即被误杀）。
        sessionStateService.pendingUserInputChecker = { sessionId ->
            questionHandler.questions.value[sessionId]?.isNotEmpty() == true ||
                permissionHandler.permissions.value[sessionId]?.isNotEmpty() == true
        }
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
        // 消息 → 按子事件的 handler。它们共享 MessageEventHandler
        // 的状态存储（注入），但独立注册，使每种消息事件类型
        // 都路由到其专注的 handler。
        bind(
            messageUpdatedHandler,
            SseEvent.MessageUpdated::class
        )
        bind(
            messageRemovedHandler,
            SseEvent.MessageRemoved::class
        )
        bind(
            messagePartHandler,
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
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> get() = sessionStateService.statusFlow
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
            val migrationRan = runCatching { settingsDataStore.runUnreadStateV2Migration() }.isSuccess
            AppLogger.d("UnreadDiag", "[migration] executed=$migrationRan")
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
    /** 2026-08-15：按 sessionId 的实时 token 用量（V2 session.usage.updated）。 */
    val sessionUsage: StateFlow<Map<String, dev.leonardo.ocbeacon.domain.model.SessionNextEvent.UsageUpdated>> get() = sessionNextHandler.sessionUsage
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
            sessionStateService.clearSession(deletedSessionId)
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
            unreadBadgeService.onMessageCompleted(event.info.sessionId, event.info.time.completed)
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
            sessionStateService.onSseEvent(event, fsmSessionId, serverId)
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
        // 在清除过滤器之前从缓存中修剪已回退的消息。
        // 否则过滤器解除后，已回退的消息会短暂重现，
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

    fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        strategy: MergeStrategy,
    ) {
        messageHandler.upsertMessages(sessionId, messages, strategy)
        recomputeMaxCompleted(sessionId)
    }

    @Deprecated("Use upsertMessages", ReplaceWith("upsertMessages(sessionId, messages, strategy)"))
    fun setMessages(sessionId: String, messages: List<MessageWithParts>) {
        messageHandler.setMessages(sessionId, messages)
        recomputeMaxCompleted(sessionId)
    }

    @Deprecated("Use upsertMessages", ReplaceWith("upsertMessages(sessionId, messages, strategy)"))
    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        messageHandler.mergeMessages(sessionId, messages)
        recomputeMaxCompleted(sessionId)
    }

    @Deprecated("Use upsertMessages", ReplaceWith("upsertMessages(sessionId, messages, strategy)"))
    fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) {
        messageHandler.replaceMessages(sessionId, messages)
        recomputeMaxCompleted(sessionId)
    }

    /**
     * 重算某会话的 maxCompleted（REST 整批替换后调用）——委托 [UnreadBadgeService]。
     * **只增不减**：REST 快照滞后（会话流式中 completed=null）时不移除已记录的
     * maxCompleted（详见 UnreadBadgeService 类注释）。
     */
    private fun recomputeMaxCompleted(sessionId: String) {
        unreadBadgeService.recomputeMaxCompleted(
            sessionId,
            messageHandler.messages.value[sessionId].orEmpty()
        )
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

    // ============ 子会话聚合 ============

    /** 聚合某会话及其子会话的权限。 */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>) =
        permissionHandler.getPermissionsWithChildren(sessionId, sessions)

    /** 聚合某会话及其子会话的问题。 */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>) =
        questionHandler.getQuestionsWithChildren(sessionId, sessions)

    fun clearAll() {
        sessionHandler.clearAll()
        messageHandler.clearAll()
        permissionHandler.clearAll()
        questionHandler.clearAll()
        miscHandler.clearAll()
        sessionNextHandler.clearAll()
        sessionStateService.clearAll()
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
        // 2026-08-14 再修复：不清理 sessionStateService FSM 状态——busy/streaming
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

