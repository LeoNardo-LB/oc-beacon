package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.PendingPromptRecord
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.OptimisticMessage
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.model.UserMsgStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "MessageDataDelegate"

/**
 * 管理消息 SSE 观察、加载、分页、发送状态和工具展开
 * 状态（此前内联在 [ChatViewModel] 中）。
 *
 * 在 Phase 3 Task 5（B 集群）中提取。
 *
 * [messageListState] 和 [interactionState] 是此 delegate 拥有的两个大型 `combine` 管道，
 * 以 [sessionIdFlow] 为 key。它们从 ChatViewModel 整体迁移而来，不可拆分。
 *
 * **发送生命周期**以 intent 方法暴露（[onSendStarted] / [onSendSuccess]
 * / [onSendError]），因为 [ChatViewModel.sendParts] 是留在
 * ViewModel 中的协调器 —— 它不得直接写入此 delegate 的私有状态。
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
    // ============ 加载与错误状态 ============
    private val _isLoading = MutableStateFlow(true)
    private val _isRefreshing = MutableStateFlow(false)  // 后台刷新 —— 无 UI 清空
    private val _error = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)
    /** 同步读取 [_isSending]，用于竞态条件保护（RS-007）。 */
    internal val isSendingValue: Boolean get() = _isSending.value

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

    // ============ 乐观发送 ============
    /** 本地生成的乐观消息 ID。用于与服务器确认的消息区分。 */
    private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    /** 等待服务器通过 SSE 确认的乐观消息。 */
    private val _pendingMessages = MutableStateFlow<List<OptimisticMessage>>(emptyList())

    // ============ 工具展开状态 ============
    private val _toolExpandedStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolExpandedStates: StateFlow<Map<String, Boolean>> = _toolExpandedStates

    // ============ 分页 ============
    /**
     * 每页加载的消息数。每次"加载更早"点击时翻倍。
     * 在 [loadMessagesForSession] 开始时从用户的 initialMessageCount 设置刷新。
     */
    private var currentMessageLimit = 30
    /** 服务器上是否存在超出当前限制的更多消息。 */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** "加载更早" 请求是否进行中。 */
    private val _isLoadingOlder = MutableStateFlow(false)

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
            _hasOlderMessages,
            _isLoadingOlder,
            _toolExpandedStates,
            _pendingMessageIds,
            sessionStateService.statusFlow,
            chatRepository.getActiveToolProgressForSession(sid),
            _pendingMessages,
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
            val pendingMessageIds = args[7] as Set<String>
            @Suppress("UNCHECKED_CAST")
            val statuses = args[8] as Map<String, SessionStatus>

            // 工具进度输出注入：将 tool.progress 内容累积到
            // Running 工具的 output 字段。callId 全局唯一，因此单个
            // progressOutputs map 对本会话所有消息安全。
            @Suppress("UNCHECKED_CAST")
            val progressList = args[9] as? List<ToolProgressInfo>
            val progressOutputs = progressList.orEmpty().associate { it.callId to it.output }

            @Suppress("UNCHECKED_CAST")
            val pendingMessages = args[10] as List<OptimisticMessage>


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

            // 追加尚未被服务器确认的乐观消息。
            // 当服务器投递任何消息（user 或 assistant）且时间戳
            // 大于等于 pending 发送时间时，pending 消息即为"已确认"。
            // 乐观消息绝不注入共享的 _messages/_parts
            // 缓存 —— 它们仅存在于 [_pendingMessages] 中并在此处合并。
            // 仅在其 ID 仍在 [_pendingMessageIds] 中时显示 pending —— 即
            // POST 进行中时。一旦 onSendSuccess 移除 ID，
            // 服务器消息（已通过 SSE 存在于 _messages 中）无缝接管。
            val activePending = pendingMessages.filter { it.pendingId in pendingMessageIds }
            val mergedChatMessages = if (activePending.isEmpty()) {
                chatMessages
            } else {
                chatMessages + activePending.map { ChatMessage(it.message, it.parts) }
            }

            // 折叠连续重复 hash 的 patch 卡片——服务器对未变更 session diff 可能
            // 重复推送相同 hash，导致每个 assistant 消息都显示重复补丁卡片。
            val visibleMessages = suppressRepeatedPatchHashes(mergedChatMessages)

            val state = MessageListState(
                messages = visibleMessages,
                messageCount = visibleMessages.size,
                hasOlderMessages = hasOlderMessages,
                isLoadingOlder = isLoadingOlder,
                toolExpandedStates = toolExpandedStates,
                queuedMessageIds = queuedMessageIds,
                pendingMessageIds = pendingMessageIds,
                pendingMessages = pendingMessages,
            )
            // 诊断：记录 combine 输出以检测陈旧发射
            val lastMsgId = mergedChatMessages.lastOrNull()?.message?.id?.take(12) ?: "none"
            Log.d("MsgDiag", "[combine] msgs=${sessionMessages.size} visible=${visible.size} " +
                "merged=${mergedChatMessages.size} revert=${revertState != null} " +
                "lastMsg=$lastMsgId pending=${pendingMessages.size}")
            // 诊断：记录最后 3 条消息的 parts 详情
            mergedChatMessages.takeLast(3).forEach { cm ->
                val textLen = cm.parts.filterIsInstance<Part.Text>().sumOf { it.text.length }
                val role = if (cm.message is Message.User) "U" else "A"
                Log.d("MsgDiag", "  [$role] id=${cm.message.id.take(12)} parts=${cm.parts.size} textLen=$textLen")
            }
            state
         } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("MessageDataDelegate", "messageListState combine error", e)
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
        _isSending,
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

    // ============ 加载与观察（从 ChatViewModel + SessionLifecycleDelegate 调用） ============

    /**
     * 通过 V1 API 为当前会话加载消息。
     * 从 [SessionLifecycleDelegate.loadSession] 回调（跨集群
     * 回调），使 C 集群 delegate 拥有完整的加载编排，
     * 而此处保留 MessageData 集群关注点（分页限制 +
     * list/set）。
     */
    suspend fun loadMessagesForSession() {
        // 应用用户配置的初始消息数量作为分页起点
        currentMessageLimit = settingsRepository.getSettingsFlow().first().initialMessageCount
        val sid = sessionIdFlow.value
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
            chatRepository.setMessages(sid, messages)
            _hasOlderMessages.value = messages.size >= currentMessageLimit
            if (BuildConfig.DEBUG) Log.d(TAG, "V1 loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages", e)
        }
    }

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
                Log.d(TAG, "[sseJob] msgs=${messages.size} visible=${visibleMessages.size} parts=${parts.size} active=${sseJob?.isActive} filtered=$missingParts")
                _rawMessagesList.value = messages
                _messagesList.value = visibleMessages
                _partsList.value = parts
            }.collect { }
        }
    }

    /**
     * 通过 V1 API 加载消息以解析 modelConfigState（从历史中解析模型/agent）。
     * 不修改分页状态（_hasOlderMessages）—— 该状态由
     * loadMessagesForSession（会话进入）和 loadOlderMessages（分页）管理。
     */
    fun loadMessages() {
        val sid = sessionIdFlow.value
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                chatRepository.setMessages(sid, messages)

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit)")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load messages", e)
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    Log.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                        chatRepository.mergeMessages(sid, messages)
                        if (BuildConfig.DEBUG) Log.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                    } catch (retryEx: Throwable) {
                        Log.e(TAG, "Retry also failed", retryEx)
                        _error.value = retryEx.message ?: "Failed to load messages"
                    }
                } else {
                    _error.value = e.message ?: "Failed to load messages"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 通过 V1 API 刷新消息。
     */
    suspend fun refreshMessages() {
        val sid = sessionIdFlow.value
        _isRefreshing.value = true
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
            chatRepository.setMessages(sid, messages)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh messages", e)
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Fixing incomplete messages for session $sid (server confirmed idle)")
            sessionStateService.onRestValidation(sid, SessionStatus.Idle)
        }
    }

    fun loadOlderMessages() {
        val sid = sessionIdFlow.value
        scope.launch {
            _isLoadingOlder.value = true
            currentMessageLimit = currentMessageLimit * 2
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                chatRepository.mergeMessages(sid, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded older: ${messages.size} messages (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load older messages", e)
                currentMessageLimit = currentMessageLimit / 2
            } finally {
                _isLoadingOlder.value = false
            }
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
            if (BuildConfig.DEBUG) Log.d(TAG, "loadPendingQuestions: ${allQuestions.size} total pending (directory=$directory), filtering for session $sid")

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
                // 合并 SSE 已有的问题 + REST 恢复的问题（去重），防止覆盖 SSE 新推送的问题
                val existingSseQs = chatRepository.getQuestionsSnapshot()[sid] ?: emptyList()
                val existingIds = existingSseQs.map { it.id }.toSet()
                val newQs = sessionQuestions.filter { it.id !in existingIds }
                if (newQs.isNotEmpty()) {
                    chatRepository.setQuestions(sid, existingSseQs + newQs)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Merged ${newQs.size} new + ${existingSseQs.size} existing questions for session $sid")
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "All ${sessionQuestions.size} REST questions already present via SSE for session $sid")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending questions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** 从服务器 REST API 加载待处理权限（会话打开时的 REST 恢复）。 */
    suspend fun loadPendingPermissions() {
        val sid = sessionIdFlow.value
        val directory = sessionDirectoryProvider()
        try {
            val allPermissions = managePermissionUseCase.listPendingPermissions(serverId, directory = directory)
            if (BuildConfig.DEBUG) Log.d(TAG, "loadPendingPermissions: ${allPermissions.size} total pending (directory=$directory), filtering for session $sid")

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
                        if (BuildConfig.DEBUG) Log.d(TAG, "Merged ${newPerms.size} new + ${existingSsePerms.size} existing permissions for session $targetSessionId")
                    } else {
                        if (BuildConfig.DEBUG) Log.d(TAG, "All ${perms.size} REST permissions already present via SSE for session $targetSessionId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending permissions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // ============ 发送生命周期（ChatViewModel.sendParts 的 intent 方法） ============

    /**
     * 标记乐观发送的开始：翻转 [_isSending]，注册
     * [pendingId]，并存储乐观消息以立即显示。
     */
    fun onSendStarted(pendingId: String, optimisticMsg: Message.User, optimisticParts: List<Part>) {
        _isSending.value = true
        _pendingMessageIds.update { it + pendingId }
        _pendingMessages.update { it + OptimisticMessage(pendingId, optimisticMsg, optimisticParts, UserMsgStatus.Sending) }
        // 注意：乐观消息不注入共享的 _messages/_parts
        // 缓存。它们在 [messageListState] 的 combine
        // 体内合并（参见下方的 `activePending`），并在服务器投递任何
        // 时间戳大于等于 pending 发送时间的消息后移除。
    }

    /** 标记发送成功：将状态翻转为 Sent。乐观消息以稳定 key 保留在缓存中
     *  —— 仅状态（以及指示器）变化。 */
    fun onSendSuccess(pendingId: String) {
        _isSending.value = false
        _pendingMessageIds.update { it - pendingId }
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Sent) else it }
        }
        // 无定时器清理 —— 乐观消息以稳定 key 保留直到
        // 会话切换（自然缓存清除 + 用真实 ID 的 REST 重载）。
    }

    /**
     * 标记发送失败：清除 [_isSending]，设置 [_error]，将消息标记为 Failed。
     */
    fun onSendError(message: String, pendingId: String) {
        _isSending.value = false
        _pendingMessageIds.update { it - pendingId }
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Failed) else it }
        }
        _error.value = message
    }

    /** 标记重试进行中：将 pending 消息翻转回 Sending。 */
    fun onRetryStarted(pendingId: String) {
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Sending) else it }
        }
        _pendingMessageIds.update { it + pendingId }
        _isSending.value = true
    }

    /** 通过 ID 获取 pending 乐观消息（用于重试内容提取）。 */
    fun getPendingMessage(pendingId: String): OptimisticMessage? {
        return _pendingMessages.value.find { it.pendingId == pendingId }
    }

    /** 移除 pending 消息（在重试提取内容并重新发送后使用）。 */
    fun removePendingMessage(pendingId: String) {
        _pendingMessages.update { it.filter { p -> p.pendingId != pendingId } }
    }

    // ============ Pending Prompt 持久化与对账 ============

    /**
     * 应用重启后恢复已持久化的 pending prompt。
     *
     * 每条记录重新物化为 [OptimisticMessage]，状态为
     * [UserMsgStatus.Sending]。对账（将丢失的发送标记为
     * [UserMsgStatus.Failed]）在服务器权威消息列表加载后
     * 进行 —— 由 [ChatViewModel] 通过
     * [pendingOptimisticSnapshot] + [markPendingAsFailed] 驱动。
     */
    internal fun restorePendingPrompts(records: List<PendingPromptRecord>) {
        if (records.isEmpty()) return
        _pendingMessageIds.update { ids -> ids + records.map { it.messageId }.toSet() }
        _pendingMessages.update { existing ->
            // distinctBy 保留已有条目；避免重复恢复时的重复。
            (existing + records.map { it.toOptimisticMessage() }).distinctBy { it.pendingId }
        }
    }

    /**
     * 当前 pending 乐观消息的快照 —— 供 [ChatViewModel] 中的对账
     * 循环使用，用于检测重启中丢失的发送。
     */
    internal fun pendingOptimisticSnapshot(): List<OptimisticMessage> = _pendingMessages.value

    /**
     * 将 pending prompt 标记为失败并从活跃 pending 集合中移除。
     * 当发送被判定为丢失（覆盖 + 过期）时由对账使用。
     */
    internal fun markPendingAsFailed(pendingId: String) {
        _pendingMessageIds.update { it - pendingId }
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Failed) else it }
        }
    }

    /** 从持久化记录重建 [OptimisticMessage]（sendParts 的逆操作）。 */
    private fun PendingPromptRecord.toOptimisticMessage(): OptimisticMessage {
        val optimisticParts = parts.mapIndexed { index, pp ->
            Part.Text(
                id = "${messageId}-part-$index",
                sessionId = sessionId,
                messageId = messageId,
                text = pp.text ?: "",
            )
        }
        return OptimisticMessage(
            pendingId = messageId,
            message = Message.User(
                id = messageId,
                sessionId = sessionId,
                time = TimeInfo(created = createdAt),
            ),
            parts = optimisticParts,
            status = UserMsgStatus.Sending,
        )
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
}

