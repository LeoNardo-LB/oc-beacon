package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolProgressOutputInjector
import dev.leonardo.ocbeacon.ui.screens.chat.util.suppressRepeatedPatchHashes
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "MessageDataDelegate"

/**
 * REST GET /question 是全量权威源：以其返回集合为准替换该会话的待处理问题。
 * 不做与内存快照的合并——服务器上已消失的问题（他端已回答）必须被清除。
 */
internal fun resolvePendingQuestionReplacement(
    restQuestions: List<SseEvent.QuestionAsked>
): List<SseEvent.QuestionAsked> = restQuestions

/**
 * 管理消息 SSE 观察、加载、分页、发送状态和工具展开
 * 状态（此前内联在 [ChatViewModel] 中）。
 *
 * 此 delegate 现为 **组合器**：分页职责委托给 [paginationDelegate]，
 * 乐观消息职责委托给 [optimisticStore]，自身保留 SSE 观察、
 * 消息/零件状态、工具展开、pending 问题/权限加载与共享加载/错误状态。
 *
 * [messageListState] 和 [interactionState] 是此 delegate 拥有的两个大型
 * `combine` 管道，以 [sessionIdFlow] 为 key。它们从 ChatViewModel 整体
 * 迁移而来，不可拆分。
 *
 * **SSE 观察器管理**通过 [cancelSseJob] / [startObservingMessages] 暴露，
 * 因为 [ChatViewModel.abortSession] / [revertMessage] 需要暂停和重启
 * SSE 观察器，同时将其余协调逻辑保留在 ViewModel 中。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的运行时
 * 上下文（ViewModel 的协程作用域、来自
 * [SessionLifecycleDelegate] 的 session-id flow 和 session-directory provider），
 * Hilt 无法提供这些。ChatViewModel 直接构造它并将每个成员作为门面重新暴露，
 * 因此 UI 文件无需改动。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class MessageDataDelegate(
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val chatRepository: ChatRepository,
    private val messagePaging: MessagePaginationUseCase,
    private val sessionStateService: SessionStateRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val serverId: String,
    private val sessionIdFlow: StateFlow<String>,
    private val sessionDirectoryProvider: () -> String?,
    private val scope: CoroutineScope,
) {
    // ============ 加载与错误状态（共享 —— 分页/乐观通过 sink 回写） ============
    private val _isLoading = MutableStateFlow(true)
    private val _isRefreshing = MutableStateFlow(false)  // 后台刷新 —— 无 UI 清空
    private val _error = MutableStateFlow<String?>(null)

    // ============ V1 消息状态 ============
    private val _messagesList = MutableStateFlow<List<Message>>(emptyList())
    /** 原始（未过滤）消息 —— 用于 hasIncompleteMessage 检查，
     *  避免新 assistant 消息尚无 parts 时的窗口期。 */
    private val _rawMessagesList = MutableStateFlow<List<Message>>(emptyList())
    private val _partsList = MutableStateFlow<List<Part>>(emptyList())
    private var sseJob: Job? = null

    // ============ ChatMessage 实例缓存 ============
    /**
     * combine 管道的 ChatMessage 实例缓存：消息未变（parts 与 message 引用均稳定）时
     * 复用上一轮实例，消除流式/工具运行期间每 ~48ms 全量重建全部消息对象（~2000 条）
     * 的分配压力 —— GC 频繁触发导致"用一会儿后滑动卡顿"的根因。
     * 引用稳定性前提：EventDispatcher 的 parts/messages 更新只替换变化消息的
     * List/元素（setMessages/mergeMessages/replaceMessages 均复用 existing 实例），
     * ToolProgressOutputInjector.inject 无匹配时返回原引用。
     */
    private var lastCombineSessionId: String? = null
    private val chatMessageCache = HashMap<String, ChatMessage>()

    // ============ 工具展开状态 ============
    private val _toolExpandedStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolExpandedStates: StateFlow<Map<String, Boolean>> = _toolExpandedStates

    // ============ 拆分出的职责 delegate（先于 combine 管道构造，sink 引用上方字段） ============
    internal val paginationDelegate = MessagePaginationDelegate(
        manageSessionUseCase = manageSessionUseCase,
        chatRepository = chatRepository,
        settingsRepository = settingsRepository,
        serverId = serverId,
        scope = scope,
        sessionIdProvider = { sessionIdFlow.value },
        loadingSink = { _isLoading.value = it },
        errorSink = { _error.value = it },
    )

    internal val sendStateStore = SendStateStore()

    /**
     * 过滤后消息列表的快照 —— 供 [ChatViewModel] 的 init 块消费，
     * 以馈送 [dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker]
     *（token 聚合是 token 集群的关注点，因此 tracker 未注入此处）。
     */
    val messagesList: StateFlow<List<Message>> = _messagesList

    // ============ 拆分状态 Flow ============

    /**
     * 消息列表状态 —— 从 V1 chatRepository flow 派生。
     * 组合消息、parts 和工具展开状态。以 [sessionIdFlow] 为 key。
     */
    val messageListState: StateFlow<MessageListState> = sessionIdFlow.flatMapLatest { sid ->
        combine(
            sessionRepository.getSessionsFlow(serverId),
            messagePaging.observeMessages(sid),
            chatRepository.getAllPartsMap(),
            _isLoading,
            paginationDelegate.hasOlderMessages,
            paginationDelegate.isLoadingOlder,
            _toolExpandedStates,
            sessionStateService.statusFlow,
            chatRepository.getActiveToolProgressForSession(sid),
        ) { args ->
         try {
            @Suppress("UNCHECKED_CAST")
            val allSessions = args[0] as List<Session>
            @Suppress("UNCHECKED_CAST")
            val sessionMessages = args[1] as List<Message>
            @Suppress("UNCHECKED_CAST")
            val allParts = args[2] as Map<String, List<Part>>
            val loading = args[3] as Boolean
            val hasOlderMessages = args[4] as Boolean
            val isLoadingOlder = args[5] as Boolean
            @Suppress("UNCHECKED_CAST")
            val toolExpandedStates = args[6] as Map<String, Boolean>
            @Suppress("UNCHECKED_CAST")
            val statuses = args[7] as Map<String, SessionStatus>

            // 工具进度输出注入：将 tool.progress 内容累积到
            // Running 工具的 output 字段。callId 全局唯一，因此单个
            // progressOutputs map 对本会话所有消息安全。
            @Suppress("UNCHECKED_CAST")
            val progressList = args[8] as? List<ToolProgressInfo>
            val progressOutputs = progressList.orEmpty().associate { it.callId to it.output }


            val session = allSessions.find { it.id == sid }
            val revertState = session?.revert

            val visible: List<Message> = if (loading && sessionMessages.isEmpty()) {
                emptyList()
            } else {
                val sorted = sessionMessages.sortedBy { it.time.created }
                if (revertState != null) {
                    // OpenCode 模式：通过消息 ID 字符串比较过滤。
                    // 消息 ID 是 ULID（单调递增），因此
                    // id <= revertId 正确地包含 revert 点及之前的所有消息。
                    sorted.filter { it.id < revertState.messageId }
                } else {
                    sorted
                }
            }

            // P5-1：queuedMessageIds 从 FSM 状态派生 —— Idle 强制清空。
            // 在完整可见列表（P5-3 过滤之前）上计算，因此 pending
            // assistant 检测不受空 parts 过滤影响。
            val fsmStatus = statuses[sid] ?: SessionStatus.Idle
            val queuedMessageIds: Set<String> = if (fsmStatus is SessionStatus.Idle) {
                emptySet()
            } else {
                val pendingAssistantIndex = visible.indexOfLast {
                    it is Message.Assistant && it.time.completed == null
                }
                if (pendingAssistantIndex >= 0) {
                    visible.drop(pendingAssistantIndex + 1)
                        .filterIsInstance<Message.User>()
                        .map { it.id }
                        .toSet()
                } else {
                    emptySet()
                }
            }

            // Assistant 消息始终可见 —— 不要过滤掉
            // 没有 parts 的消息。旧的 P5-3 过滤器（allParts[msg.id]?.isNotEmpty()）
            // 在 SSE part 事件延迟或丢失时会导致消息永久隐藏。
            // 短暂的空气泡可以接受；消息不可见则不行。
            if (sid != lastCombineSessionId) {
                chatMessageCache.clear()
                lastCombineSessionId = sid
            }
            val chatMessages = visible.map { msg ->
                val rawParts = allParts[msg.id] ?: emptyList()
                val injected = ToolProgressOutputInjector.inject(rawParts, progressOutputs)
                val cached = chatMessageCache[msg.id]
                if (cached != null && cached.parts === injected && cached.message === msg) {
                    cached
                } else {
                    ChatMessage(message = msg, parts = injected)
                        .also { chatMessageCache[msg.id] = it }
                }
            }

            // 悲观消息模式：发送后不显示乐观占位，等待服务器 SSE 回显
            // MessageUpdated 时消息自然出现在 visible 列表中。无乐观合并逻辑。

            // 折叠连续重复 hash 的 patch 卡片——服务器对未变更 session diff 可能
            // 重复推送相同 hash，导致每个 assistant 消息都显示重复补丁卡片。
            val visibleMessages = suppressRepeatedPatchHashes(chatMessages)

            val state = MessageListState(
                messages = visibleMessages,
                messageCount = visibleMessages.size,
                hasOlderMessages = hasOlderMessages,
                isLoadingOlder = isLoadingOlder,
                toolExpandedStates = toolExpandedStates,
                queuedMessageIds = queuedMessageIds,
            )
            // 诊断：记录 combine 输出以检测陈旧发射
            val lastMsgId = chatMessages.lastOrNull()?.message?.id?.take(12) ?: "none"
            AppLogger.d("MsgDiag", "[combine] msgs=${sessionMessages.size} visible=${visible.size} " +
                "merged=${chatMessages.size} revert=${revertState != null} " +
                "lastMsg=$lastMsgId")
            // 诊断：记录最后 3 条消息的 parts 详情
            chatMessages.takeLast(3).forEach { cm ->
                val textLen = cm.parts.filterIsInstance<Part.Text>().sumOf { it.text.length }
                val role = if (cm.message is Message.User) "U" else "A"
                AppLogger.d("MsgDiag", "  [$role] id=${cm.message.id.take(12)} parts=${cm.parts.size} textLen=$textLen")
            }
            state
         } catch (e: Exception) {
            if (BuildConfig.DEBUG) AppLogger.e("MessageDataDelegate", "messageListState combine error", e)
            MessageListState()
         }
        }
    }.stateIn(
        scope,
        WhileSubscribed5s,
        MessageListState()
    )

    /**
     * 交互状态 —— 从 V1 源派生的加载、发送、错误，
     * 以及来自 V1 chatRepository 的待处理权限/问题卡片。
     */
    val interactionState: StateFlow<InteractionState> = combine(
        sessionIdFlow,
        _isLoading,
        _error,
        sendStateStore.isSending,
        sessionRepository.getSessionsFlow(serverId),
        chatRepository.getAllQuestionsFlow(),
        chatRepository.getAllPermissionsFlow(),
    ) { args ->
        val sid = args[0] as String
        val loading = args[1] as Boolean
        val error = args[2] as String?
        val sending = args[3] as Boolean
        @Suppress("UNCHECKED_CAST")
        val allSessions = args[4] as List<Session>

        InteractionState(
            isLoading = loading,
            isSending = sending,
            error = error,
            pendingPermissions = chatRepository.getPermissionsWithChildren(sid, allSessions),
            pendingQuestions = chatRepository.getQuestionsWithChildren(sid, allSessions),
        )
    }.stateIn(
        scope,
        WhileSubscribed5s,
        InteractionState()
    )

    // ============ 工具展开 ============

    fun toggleToolExpanded(toolId: String, defaultExpanded: Boolean = false) {
        _toolExpandedStates.update { it + (toolId to !(it[toolId] ?: defaultExpanded)) }
    }

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean {
        return _toolExpandedStates.value[toolId] ?: autoExpand
    }

    // ============ SSE 观察（由 ChatViewModel + SessionLifecycleDelegate 调用） ============

    /**
     * 观察 V1 chatRepository flow（由 SSE EventDispatcher 驱动）。
     * 消息和 parts 在 SSE 事件到达时自动更新。
     */
    fun startObservingMessages() {
        sseJob?.cancel()
        val sid = sessionIdFlow.value
        sseJob = scope.launch {
            combine(
                chatRepository.getMessagesFlow(sid),
                chatRepository.getParts(sid),
            ) { messages, parts ->
                val grouped = parts.groupBy { it.messageId }
                // 过滤掉还没有 parts 的 assistant 消息：
                // MessageUpdated 可能先于 MessagePartUpdated 到达，此时 assistant 消息存在但没有内容，
                // 导致 UI 上看起来回复没出现。等第一个 part 到达后自然会显示。
                val visibleMessages = messages.filter { msg ->
                    msg is Message.User || (msg is Message.Assistant && grouped[msg.id]?.isNotEmpty() == true)
                }
                val missingParts = messages.size - visibleMessages.size
                AppLogger.d(TAG, "[sseJob] msgs=${messages.size} visible=${visibleMessages.size} parts=${parts.size} active=${sseJob?.isActive} filtered=$missingParts")
                _rawMessagesList.value = messages
                _messagesList.value = visibleMessages
                _partsList.value = parts
            }.collect { }
        }
    }

    /**
     * 通过 V1 API 刷新消息。
     */
    suspend fun refreshMessages() {
        val sid = sessionIdFlow.value
        _isRefreshing.value = true
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sid, limit = paginationDelegate.currentLimitValue)
            chatRepository.setMessages(sid, messages)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to refresh messages", e)
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * 当服务器确认会话空闲时修复 time.completed == null 的消息。
     * 处理服务器重启场景：重启后，所有会话在内存中都是空闲的，
     * 但数据库保留了 finished_at = NULL 的中断消息。
     * 不得在轮询期间调用 —— 仅在显式用户操作
     *（进入会话、中止）时调用，以避免破坏过早空闲保护。
     *
     * 通过 [SessionStateService.onRestValidation] 路由 —— FSM 的 forceComplete
     * 机制通过在 [EventDispatcher] 的 init 块中连接的回调触发 [MessageEventHandler.markSessionIdle]。
     */
    fun fixIncompleteMessagesIfIdle(sid: String) {
        val messages = _rawMessagesList.value
        val hasIncomplete = messages.any { it is Message.Assistant && it.time.completed == null }
        if (hasIncomplete) {
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Fixing incomplete messages for session $sid (server confirmed idle)")
            sessionStateService.onRestValidation(sid, SessionStatus.Idle)
        }
    }

    /**
     * 从服务器 REST API 加载待处理问题。
     * 将 QuestionRequest DTO 转换为 SseEvent.QuestionAsked 领域对象。
     * 必须在 loadSession() 之后调用，以确保 sessionDirectory 已设置。
     */
    suspend fun loadPendingQuestions() {
        val sid = sessionIdFlow.value
        val directory = sessionDirectoryProvider()
        try {
            val allQuestions = managePermissionUseCase.listPendingQuestions(serverId, directory = directory)
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "loadPendingQuestions: ${allQuestions.size} total pending (directory=$directory), filtering for session $sid")

            // 包含子会话的问题
            val childSessionIds = chatRepository.getSessionsSnapshot()
                .filter { it.parentId == sid }
                .map { it.id }
                .toSet()

            val sessionQuestions = allQuestions
                .filter { it.sessionId == sid || it.sessionId in childSessionIds }
                .map { req ->
                    val isChild = req.sessionId != sid
                    SseEvent.QuestionAsked(
                        id = req.id,
                        sessionId = req.sessionId,
                        questions = req.questions.map { q ->
                            SseEvent.QuestionAsked.Question(
                                header = q.header,
                                question = q.question,
                                multiple = q.multiple,
                                custom = q.custom,
                                options = q.options.map { o ->
                                    SseEvent.QuestionAsked.Option(
                                        label = o.label,
                                        description = o.description
                                    )
                                }
                            )
                        },
                        tool = req.tool,
                        sourceSessionTitle = if (isChild) {
                            chatRepository.getSessionsSnapshot().find { it.id == req.sessionId }?.title
                        } else null
                    )
                }
            if (sessionQuestions.isNotEmpty()) {
                chatRepository.setQuestions(sid, resolvePendingQuestionReplacement(sessionQuestions))
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Replaced ${sessionQuestions.size} questions for session $sid (REST authoritative)")
            } else {
                // 服务器无 pending 问题——清空（含他端已回答的情况）
                chatRepository.setQuestions(sid, emptyList())
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "No pending questions for session $sid, cleared")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load pending questions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** 从服务器 REST API 加载待处理权限（会话打开时的 REST 恢复）。 */
    suspend fun loadPendingPermissions() {
        val sid = sessionIdFlow.value
        val directory = sessionDirectoryProvider()
        try {
            val allPermissions = managePermissionUseCase.listPendingPermissions(serverId, directory = directory)
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "loadPendingPermissions: ${allPermissions.size} total pending (directory=$directory), filtering for session $sid")

            // 包含子会话的权限
            val childSessionIds = chatRepository.getSessionsSnapshot()
                .filter { it.parentId == sid }
                .map { it.id }
                .toSet()

            val sessionPermissions = allPermissions
                .filter { it.sessionId == sid || it.sessionId in childSessionIds }
                .map { req ->
                    val isChild = req.sessionId != sid
                    SseEvent.PermissionAsked(
                        id = req.id,
                        sessionId = req.sessionId,
                        permission = req.permission,
                        patterns = req.patterns,
                        metadata = req.metadata,
                        always = req.always,
                        tool = req.tool,
                        sourceSessionTitle = if (isChild) {
                            chatRepository.getSessionsSnapshot().find { it.id == req.sessionId }?.title
                        } else null
                    )
                }
            if (sessionPermissions.isNotEmpty()) {
                // 按目标 sessionId 分组权限以匹配 SSE 存储模式
                // SSE 将子会话权限存储在 childSessionId 下，REST 应做同样处理
                val permissionsByTarget = sessionPermissions.groupBy { it.sessionId }
                for ((targetSessionId, perms) in permissionsByTarget) {
                    val existingSsePerms = chatRepository.getPermissionsSnapshot()[targetSessionId] ?: emptyList()
                    val existingIds = existingSsePerms.map { it.id }.toSet()
                    val newPerms = perms.filter { it.id !in existingIds }
                    if (newPerms.isNotEmpty()) {
                        chatRepository.setPermissions(targetSessionId, existingSsePerms + newPerms)
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Merged ${newPerms.size} new + ${existingSsePerms.size} existing permissions for session $targetSessionId")
                    } else {
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "All ${perms.size} REST permissions already present via SSE for session $targetSessionId")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load pending permissions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // ============ SSE 观察器管理（供 ChatViewModel.abort/revert 使用） ============

    /** 取消飞行中的 SSE 观察器 job（如有）。 */
    fun cancelSseJob() {
        sseJob?.cancel()
        sseJob = null
    }

    // ============ 新会话加载标记 ============

    /**
     * 为没有消息可加载的全新会话标记加载完成。
     * 从 [ChatViewModel] 的 init 块中的新会话分支调用。
     */
    fun markLoaded() {
        _isLoading.value = false
    }

    /** 发送失败等外部错误入口 —— 经 interactionState.error 供 snackbar/空态展示。 */
    internal fun reportError(msg: String?) {
        _error.value = msg
    }
}
