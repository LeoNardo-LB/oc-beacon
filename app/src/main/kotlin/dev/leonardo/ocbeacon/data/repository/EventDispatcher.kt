package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.FileDiff
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val sessionStateService: SessionStateService,
    private val settingsDataStore: SettingsDataStore,
) {
    /**
     * 一次性 unread v2 迁移 scope：App 启动时清空旧域已读标记（readTimes/allReadAt/
     * 孤儿 lastReplyTime），值域从客户端 now 变为服务器 completed，旧值不可比。幂等
     * （boolean 标记）。独立 scope，不阻塞事件处理（与已删 replyTimePersistScope 同模式）。
     */
    private val unreadMigrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            // 同步落盘：idle 兜底到达时，前序 MessageUpdated(completed) 已更新内存红点时间源，
            // 此刻同步写盘确保杀进程不丢（消灭旧的异步 collect 调度窗口）
            persistLastCompletedReplyTime()
        }
        sessionStateService.messageRefresher = MessageRefresher { sessionId, messages ->
            messageHandler.replaceMessages(sessionId, messages)
        }
    }

    /**
     * 跟踪每个会话由哪个 serverId"拥有"，用于 SSE 事件处理。
     *
     * 当两个服务器配置指向同一后端（同一 OpenCode serve 实例）时，
     * 两条 SSE 连接都会投递相同的全局事件。若无所有权跟踪，
     * 像 [SseEvent.MessagePartDelta] 这样的追加式事件会被应用两次，
     * 使流式文本输出翻倍。
     *
     * 首个为某会话投递事件的服务器获得所有权。
     * 来自任何其他 serverId 的该会话事件会被跳过。
     * 所有权在 [clearForServer]、[clearAll] 或 [SseEvent.SessionDeleted] 时释放。
     */
    private val streamingSessionOwners = java.util.concurrent.ConcurrentHashMap<String, String>()

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
     *  红点判定的唯一时间源——替换旧 _turnEndTime（曾混入 markSessionIdle 的客户端 now）。
     *  增量维护：MessageUpdated(completed!=null) 或 REST 整批替换时更新。 */
    private val _lastCompletedReplyTime = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastCompletedReplyTime: StateFlow<Map<String, Long>> = _lastCompletedReplyTime

    init {
        // 持久化 init：必须在 _lastCompletedReplyTime 声明之后（Kotlin 按文本顺序初始化，
        // 否则 launch 协程在 IO 线程读到未初始化的 null 属性）。
        unreadMigrationScope.launch {
            // runCatching 容错：迁移失败（含 mock 环境）不阻塞 init 持久化路径（spec §3.1）
            val migrationRan = runCatching { settingsDataStore.runUnreadStateV2Migration() }.isSuccess
            AppLogger.d("UnreadDiag", "[migration] executed=$migrationRan")
            // 迁移完成后再读 seed：确保旧客户端 now 域值已清空，读到的是服务器域值或空。
            // update 合并取 max 保证不覆盖 SSE 并发写入的更新值（seed 可能过时——断线期服务器新回复缺失，
            // 为已知限制，非本任务引入，与旧 lastReplyTime 机制相同）。
            // runCatching 容错：DataStore 异常（含 mock 环境）返回空 seed，不阻塞 init
            val seed = runCatching { settingsDataStore.lastCompletedReplyTimes().first() }.getOrDefault(emptyMap())
            AppLogger.d("UnreadDiag", "[seed] loaded ${seed.size} entries: ${seed.entries.take(3)}")
            _lastCompletedReplyTime.update { current ->
                val merged = current.toMutableMap()
                for ((sid, ts) in seed) {
                    if (ts > (merged[sid] ?: 0L)) merged[sid] = ts
                }
                merged
            }
            // 同步落盘合并结果（本块已在 suspend 协程内，直接调 saveLastCompletedReplyTimes；
            // kill 进程后 seed 不丢——与各 SSE/REST 更新点共用同一同步落盘策略）
            runCatching { settingsDataStore.saveLastCompletedReplyTimes(_lastCompletedReplyTime.value) }
        }
    }

    // Session Next 状态
    val currentAgent: StateFlow<Map<String, String>> get() = sessionNextHandler.currentAgent
    val currentModel: StateFlow<Map<String, Pair<String, String>>> get() = sessionNextHandler.currentModel
    val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>> get() = sessionNextHandler.activeToolProgress
    val stepProgress: StateFlow<Map<String, StepProgressInfo>> get() = sessionNextHandler.stepProgress
    val compactionState: StateFlow<Map<String, CompactionStateInfo>> get() = sessionNextHandler.compactionState
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
        if (sessionId != null) {
            val owner = streamingSessionOwners.putIfAbsent(sessionId, serverId)
            if (owner != null && owner != serverId) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "Skipping duplicate ${event::class.simpleName} for session " +
                        "${sessionId.take(12)} from server=$serverId (owner=$owner)")
                }
                return
            }
        }

        // 注册表分发：将事件路由到其唯一注册的 handler（O(1) 查找）。
        // 替代了之前的广播模型，即每个事件都发送给全部 6 个 handler，
        // 每个 handler 再通过自身的 `when` 块在内部过滤。
        val handler = registry[event::class]
        if (handler != null) {
            handler.handle(event, serverId)
        } else if (BuildConfig.DEBUG) {
            AppLogger.w(TAG, "No handler registered for ${event::class.simpleName}")
        }
        forwardToSessionStateService(event)

        // 跨 handler：SessionDeleted 级联清理其他 handler
        if (event is SseEvent.SessionDeleted) {
            val deletedSessionId = event.info.id
            streamingSessionOwners.remove(deletedSessionId)
            messageHandler.clearForSession(deletedSessionId)
            _lastCompletedReplyTime.update { it - deletedSessionId }
            // 同步落盘：删除会话的红点条目立即清出持久层，避免重启后复活
            persistLastCompletedReplyTime()
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
            AppLogger.i("UnreadDiag", "[CommandExecuted] session=${event.sessionId.take(12)} msg=${event.messageId.take(12)} name=${event.name}")
            messageHandler.markSessionIdle(event.sessionId, event.messageId)
        }

        // 红点时间源：assistant 消息完成（服务器 completed）→ 增量更新 maxCompleted。
        // 与 markSessionIdle（客户端 now，UI 流式终止）解耦——红点判定只用服务器时刻。
        if (event is SseEvent.MessageUpdated && event.info is Message.Assistant && event.info.time.completed != null) {
            val sessionId = event.info.sessionId
            val completed = event.info.time.completed
            _lastCompletedReplyTime.update { map ->
                if (completed > (map[sessionId] ?: 0L)) map + (sessionId to completed) else map
            }
            // 同步落盘：红点出现时数据已持久化，杀进程不丢（消灭旧 collect 调度窗口）
            persistLastCompletedReplyTime()
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
    private fun forwardToSessionStateService(event: SseEvent) {
        val fsmSessionId = extractSessionId(event)
        if (fsmSessionId != null) {
            sessionStateService.onSseEvent(event, fsmSessionId)
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

    fun setMessages(sessionId: String, messages: List<MessageWithParts>) {
        messageHandler.setMessages(sessionId, messages)
        recomputeMaxCompleted(sessionId)
    }

    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        messageHandler.mergeMessages(sessionId, messages)
        recomputeMaxCompleted(sessionId)
    }

    fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) {
        messageHandler.replaceMessages(sessionId, messages)
        recomputeMaxCompleted(sessionId)
    }

    /**
     * 重算某会话的 maxCompleted（REST 整批替换后调用）。
     * **只增不减**：REST 快照滞后（会话流式中 completed=null）时不移除已记录的
     * maxCompleted——暂时的 null 快照不能抹掉已知完成时刻（红点消失根因，2026-08-07 日志实证）。
     * 只有 SessionDeleted（会话真删）才移除；clearForServer/clearAll（连接状态清理）不清红点数据。
     */
    private fun recomputeMaxCompleted(sessionId: String) {
        val maxTs = messageHandler.messages.value[sessionId]
            ?.filterIsInstance<Message.Assistant>()
            ?.mapNotNull { it.time.completed }
            ?.maxOrNull()
        _lastCompletedReplyTime.update { map ->
            if (maxTs == null) map
            else if (maxTs > (map[sessionId] ?: 0L)) map + (sessionId to maxTs)
            else map
        }
        persistLastCompletedReplyTime()
    }

    /**
     * 同步落盘当前 maxCompleted（量小频低；调用点均在 SSE/IO 协程，非主线程）。
     * DataStore edit suspend 返回即写入文件——红点出现（idle 兜底）时数据已持久化，杀进程不丢。
     * runCatching + withTimeout 防御：DataStore 异常/极端卡顿不阻塞事件处理（best-effort，可由 SSE/REST 重建）。
     */
    private fun persistLastCompletedReplyTime() {
        runBlocking {
            runCatching {
                withTimeout(500) {
                    settingsDataStore.saveLastCompletedReplyTimes(_lastCompletedReplyTime.value)
                }
            }
        }
    }

    fun addOptimisticMessage(sessionId: String, message: Message.User, parts: List<Part>) =
        messageHandler.addOptimisticMessage(sessionId, message, parts)

    fun removePermission(permissionId: String) =
        permissionHandler.removePermission(permissionId)

    fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) =
        permissionHandler.setPermissions(sessionId, permissions)

    fun removeQuestion(questionId: String) =
        questionHandler.removeQuestion(questionId)

    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) =
        questionHandler.setQuestions(sessionId, questions)

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
        streamingSessionOwners.clear()
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
        streamingSessionOwners.entries.removeAll { it.value == serverId }
    }
}

