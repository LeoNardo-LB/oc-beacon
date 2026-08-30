package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolProgressOutputInjector
import dev.leonardo.ocbeacon.ui.screens.chat.util.suppressRepeatedPatchHashes
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
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
 * 发送状态由 [sendStateStore] 承担（仅“发送中”标志——乐观消息体系已整体
 * 移除，见 [SendStateStore]），自身保留 SSE 观察、
 * 消息/内容块状态、工具展开、pending 问题/权限加载与共享加载/错误状态。
 *
 * [messageListState] 和 [interactionState] 是此 delegate 拥有的两个大型
 * `combine` 管道，以 [sessionIdFlow] 为 key。它们从 ChatViewModel 整体
 * 迁移而来，不可拆分。
 *
 * **SSE 观察器管理**通过 [cancelSseJob] / [startObservingMessages] 暴露，
 * 因为 [ChatViewModel.interruptSession] / [revertMessage] 需要暂停和重启
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
    private val messageStore: MessageCacheRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val serverId: String,
    private val sessionIdFlow: StateFlow<String>,
    private val sessionDirectoryProvider: () -> String?,
    private val scope: CoroutineScope,
) {
    init {
        // #263 round3：轮次完结后的服务器权威时长对账。V2 生命周期事件
        //（started/ended）不带服务器时间戳——流式期思考计时为本地钟代理值；
        // content 的权威 {created, completed} 只在 REST 快照里。观测到
        // Busy/Retry → Idle 的自然轮次结束转换后，延迟一次 REST_AUTHORITY
        // 对账，完结显示收敛到服务器时长（REST 拉取经既有 #266 竞态守卫）。
        scope.launch {
            var lastSid: String? = null
            var lastSeenStatus: SessionStatus? = null
            var reconcileJob: Job? = null
            combine(sessionIdFlow, sessionStateRepository.statusFlow) { sid, statuses ->
                sid to statuses[sid]
            }.collect { (sid, status) ->
                val sidChanged = sid != lastSid
                val prev = if (sidChanged) null else lastSeenStatus
                lastSid = sid
                lastSeenStatus = status
                val naturalTurnEnd = !sidChanged &&
                    (prev is SessionStatus.Busy || prev is SessionStatus.Retry) &&
                    status is SessionStatus.Idle
                if (naturalTurnEnd && reconcileJob?.isActive != true) {
                    reconcileJob = scope.launch {
                        // 延迟窗口：让 48ms 批缓冲 flush 与 UI 稳定，再拉权威值
                        delay(1_500L)
                        runCatching { reconcileFromRest(sid) }
                            .onFailure {
                                if (BuildConfig.DEBUG) {
                                    AppLogger.d(TAG, "reconcileFromRest failed: " + it.message)
                                }
                            }
                    }
                }
            }
        }
    }

    // ============ 加载与错误状态（共享 —— 分页/乐观通过 sink 回写） ============
    private val _isLoading = MutableStateFlow(true)
    private val _isRefreshing = MutableStateFlow(false)  // 后台刷新 —— 无 UI 清空
    private val _error = MutableStateFlow<String?>(null)

    // ============ V1 消息状态 ============
    /** 过滤后消息列表的快照 —— 供 [ChatViewModel] 的 init 块消费（TokenStatsTracker）。 */
    private val _messagesList = MutableStateFlow<List<Message>>(emptyList())
    /** 原始（未过滤）消息 —— 用于 hasIncompleteMessage 检查，
     *  避免新 assistant 消息尚无 parts 时的窗口期。 */
    private val _rawMessagesList = MutableStateFlow<List<Message>>(emptyList())
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
        messagePaging = messagePaging,
        messageStore = messageStore,
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
     *（token 聚合是 token 状态簇的关注点，因此 tracker 未注入此处）。
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
            paginationDelegate.autoLoadPaused,
            _toolExpandedStates,
            sessionStateRepository.statusFlow,
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
            val autoLoadPaused = args[6] as Boolean
            @Suppress("UNCHECKED_CAST")
            val toolExpandedStates = args[7] as Map<String, Boolean>
            @Suppress("UNCHECKED_CAST")
            val statuses = args[8] as Map<String, SessionStatus>

            // 工具进度输出注入：将 tool.progress 内容累积到
            // Running 工具的 output 字段。callId 全局唯一，因此单个
            // progressOutputs map 对本会话所有消息安全。
            //
            // 注意 combine 参数顺序：args[8] 是 statusFlow（上方），
            // args[9] 是 getActiveToolProgressForSession（combine 第 10 个源）。
            @Suppress("UNCHECKED_CAST")
            val progressList = args[9] as? List<ToolProgressInfo>
            val progressOutputs = progressList.orEmpty().associate { it.callId to it.output }
            // #180：Running 期子智能体会话 id（tool.progress metadata.sessionID）
            val childSessionIds = progressList.orEmpty().mapNotNull { p ->
                p.childSessionId?.let { p.callId to it }
            }.toMap()


            val session = allSessions.find { it.id == sid }
            val revertState = session?.revert

            val visible: List<Message> = if (loading && sessionMessages.isEmpty()) {
                emptyList()
            } else {
                // 消息已在 MessageEventHandler 的写入路径按 time.created 排序
                //（handleMessageUpdated upsert 后 sortBy；setMessages/upsertMessages merge 后 sortedBy），
                // 此处无需重复排序——移除每次 combine 的 O(n log n) 排序（SSE 活跃时每秒 ~20 次，
                // 1896 条消息的排序是真机 slowUI 的根因之一，2026-08-10 定位）。
                if (revertState != null) {
                    // OpenCode 模式：通过消息 ID 字符串比较过滤。
                    // 消息 ID 是 ULID（单调递增），因此
                    // id < revertId 保留 revert 点之前的所有消息（不含 revert 点本身）。
                    sessionMessages.filter { it.id < revertState.messageId }
                } else {
                    sessionMessages
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
                val injected = ToolProgressOutputInjector.inject(rawParts, progressOutputs, childSessionIds)
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
                autoLoadPaused = autoLoadPaused,
                toolExpandedStates = toolExpandedStates,
                queuedMessageIds = queuedMessageIds,
                // #44：原始消息与 parts 映射由唯一 combine 管道统一提供，
                // sseJob 投影（messagesList/rawMessagesList）不再独立观察数据源。
                rawMessages = sessionMessages,
                partsByMessageId = allParts,
            )
            // DIAG 已移除（2026-08-10）：combine 每 48ms 触发的 MsgDiag 日志（每秒 ~80 条 logcat 写入）
            // 是真机掉帧的根因之一——debug 版 BuildConfig.DEBUG=true 时门控无效，必须彻底删除。
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
        if (BuildConfig.DEBUG) {
            val nv = !( _toolExpandedStates.value[toolId] ?: defaultExpanded)
            AppLogger.w(
                "RB-EXP",
                "[DEBUG-rbexp] toggle id=" + toolId.takeLast(8) + " -> " + nv +
                    " from=" + Thread.currentThread().stackTrace
                        .drop(1).take(6).joinToString("<-") { it.methodName }
            )
        }
        _toolExpandedStates.update { it + (toolId to !(it[toolId] ?: defaultExpanded)) }
    }

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean {
        return _toolExpandedStates.value[toolId] ?: autoExpand
    }

    // ============ SSE 观察（由 ChatViewModel + SessionLifecycleDelegate 调用） ============

    /**
     * 观察消息快照 —— 由 [messageListState] 投影（#44：消除独立的
     * `getMessagesFlow + getParts` 双订阅 combine，每个 SSE 事件只扫描一次）。
     *
     * 生命周期（cancel/restart）保留：interruptSession / revertMessage 需要暂停
     * 快照更新（RS-006/RS-008 历史竞态修复），与 UI 主列表（messageListState）
     * 的持续更新解耦。
     */
    fun startObservingMessages() {
        sseJob?.cancel()
        sseJob = scope.launch {
            messageListState.collect { state ->
                _rawMessagesList.value = state.rawMessages
                // 过滤掉还没有 parts 的 assistant 消息：
                // MessageUpdated 可能先于 MessagePartUpdated 到达，此时 assistant 消息存在但没有内容，
                // 导致 UI 上看起来回复没出现。等第一个 part 到达后自然会显示。
                _messagesList.value = state.rawMessages.filter { msg ->
                    msg is Message.User ||
                        (msg is Message.Assistant && state.partsByMessageId[msg.id]?.isNotEmpty() == true)
                }
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
            val messages = manageSessionUseCase.listMessages(serverId, sid, limit = paginationDelegate.currentLimitValue)
            chatRepository.upsertMessages(sid, messages, MergeStrategy.SSE_PRIORITY)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // #242 同源修复：错误转交互层 error（消息区空时 ChatErrorState 兜底），
            // 不再纯日志吞掉
            AppLogger.e(TAG, "Failed to refresh messages", e)
            reportError(e.message ?: "Failed to refresh messages")
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * 快速导航全量列表：从 Room 热表加载 role='user' 的最近 [MessageCacheRepository.SESSION_MESSAGE_LIMIT]
     * 条消息（含 parts）。覆盖内存窗口外的更早历史——内存热视图（rawMessages）仅含
     * 已加载窗口（~30 条），Room 热表含 ≤1000 条全量 user 消息。
     *
     * 2026-08-12 修复（用户反馈"Q1 之上还有内容"）：Room 只保留初始加载的消息
     *（分页加载的窗口外消息不落库——防 prune 循环设计）→ 快速导航列表不全
     *（实测 12/34）。服务器翻页全量拉取补充：落库（persistOldBeyondWindow=true，
     * <1000 条无 prune 风险）+ 会话内标记，下次打开直接 Room。
     *
     * IO 线程查询（[MessageStore] 内 withContext(Dispatchers.IO)）；调用方在协程中 await。
     */
    suspend fun loadJumpTargets(): List<MessageWithParts> {
        val sid = sessionIdFlow.value
        // 2026-08-16（缺 Q 根治·第 1 层：数据源合并）：导航列表 = Room 全量 ∪
        // 内存热视图（按 id 去重、按 created 排序）。根治"主对话流有、导航没有"：
        // 向上翻页加载的更早消息进内存但不落 Room（persistOldBeyondWindow=false
        // 防裁剪的旧设计）→ 只查 Room 的导航永远缺失这部分。合并后不依赖任何
        // 时序（预取完成与否、翻页落库策略），结构性保证 导航 ⊇ 主对话流所见。
        // 内存 user 消息的 parts 从 _parts 取（与主对话流同源）。
        val roomMsgs = messageStore.userMessages(sid, MessageCacheRepository.SESSION_MESSAGE_LIMIT)
        val memUsers = _rawMessagesList.value
            .filterIsInstance<Message.User>()
            .filter { it.role != "synthetic" }
        if (memUsers.isEmpty()) return roomMsgs
        val roomIds = roomMsgs.map { it.info.id }.toHashSet()
        val memOnly = memUsers.filter { it.id !in roomIds }
        if (memOnly.isEmpty()) return roomMsgs
        val memWithParts = memOnly.map { u ->
            MessageWithParts(
                info = u,
                parts = messagePartsProvider(u.id).orEmpty().map { it },
            )
        }
        return (roomMsgs + memWithParts).sortedBy { it.info.time.created }
    }

    /** 内存 parts 快照读取（懒加载注入，避免构造环）。复用 messageListState
     *  的 partsByMessageId（combine 内部已有，与主对话流完全同源）。 */
    private var messagePartsProvider: (String) -> List<Part>? = { null }

    init {
        // 启动一个轻量镜像：combine 产出 partsByMessageId 时同步缓存最近快照
        //（仅在 parts 变化时赋值，读取方 loadJumpTargets 同步取用）。
        scope.launch {
            try {
                chatRepository.getAllPartsMap().collect { map ->
                    messagePartsProvider = { id -> map[id] }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    /**
     * 2026-08-15（research/01）：进会话后**后台预取**全量消息落库（官方 TUI
     * index.tsx:314 模式：进入会话 sync，Timeline 打开零 IO）。由
     * loadMessagesForSession 完成后触发；失败静默（下次打开抽屉兜底）。
     * 完成后 jumpTargetsServerSync 置位（首次打开兜底路径用）。
     */
    fun prefetchJumpTargets(scope: kotlinx.coroutines.CoroutineScope) {
        val sid = sessionIdFlow.value
        if (jumpTargetsServerSync) return
        scope.launch {
            try {
                val all = fetchAllMessages(sid)
                if (all.isNotEmpty()) {
                    messageStore.upsertMessages(sid, all, persistOldBeyondWindow = true)
                    // 2026-08-16（快速定位缺失根治·对账）：以服务器全量为权威——
                    // 压缩（compaction 裁剪历史）/删除后服务器消息集变小，Room
                    // 中多出的幽灵消息（upsert 不删缺席项）会让主对话流/快速
                    // 导航显示服务器已不存在的消息（用户反馈"Q1 之上还有我发
                    // 的消息"的数据根源——历史压缩残留）。clearSession + 重写
                    // 服务器全集，语义等同官方 TUI reconcile 全量替换。
                    val serverIds = all.map { it.info.id }.toSet()
                    messageStore.replaceSessionMessages(sid, all)
                    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                        AppLogger.d(TAG, "[jump] prefetch complete: ${all.size} msgs (reconciled, server-authoritative) -> Room")
                    }
                }
                jumpTargetsServerSync = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.w(TAG, "[jump] prefetch failed (will fallback on drawer open): ${e.message}")
            }
        }
    }

    /**
     * 合并连续无回复的 user：升序遍历，user 的下一条（时间上）仍是 user（无
     * assistant 间隔）→ 该 user 无独立回复——归入当前组；下一条是 assistant 或
     * 结束 → 组末保留为导航项（preview 用组末文本）。synthetic 已排除。
     */
    private fun mergeUnrepliedUsers(all: List<MessageWithParts>): List<MessageWithParts> {
        val sorted = all.sortedBy { it.info.time.created }
        val users = sorted.filter { it.info is Message.User && it.info.role != "synthetic" }
        if (users.size < 2) return users
        val result = mutableListOf<MessageWithParts>()
        for ((i, u) in users.withIndex()) {
            val nextUser = users.getOrNull(i + 1)
            // 该 user 与下一条 user 之间是否有 assistant（非 user 消息）
            val hasAssistantBetween = nextUser != null && sorted.any {
                it.info.time.created > u.info.time.created &&
                    it.info.time.created < nextUser.info.time.created &&
                    it.info !is Message.User
            }
            // 独立项：无下一条 user，或与下一条之间有 assistant（有回复）
            // 连续无回复的组：当前跳过，组末由下一条的判定保留（组末 nextUser==null → 保留）
            if (nextUser == null || hasAssistantBetween) {
                result += u
            }
        }
        return result
    }

    /** 会话内标记：服务器全量消息已同步（避免每次打开快速导航重复翻页）。 */
    private var jumpTargetsServerSync = false

    /** 服务器翻页全量消息（cursor.next 直到读尽；防呆 100 页上限——5000 条）。
     *  2026-08-13 修复：20 页（1000 条）对长会话（测试会话实测含大量初始化
     *  + 多轮测试消息）会截断——截断导致部分 assistant 缺失 → mergeUnrepliedUsers
     *  误判"无回复"合并 → 快速导航漏 Q（用户反馈"漏很多之前的消息"）。 */
    private suspend fun fetchAllMessages(sid: String): List<MessageWithParts> {
        val all = mutableListOf<MessageWithParts>()
        var cursor: String? = null
        var guard = 0
        while (guard++ < 100) {
            val page = sessionRepository.listMessages(serverId, sid, 50, cursor).getOrThrow()
            all += page.messages
            cursor = page.nextCursor ?: break
            if (page.messages.isEmpty()) break
        }
        return all.distinctBy { it.info.id }
    }

    /**
     * #263 round3：REST_AUTHORITY 对账——以服务器 content 的 {created, completed}
     * 覆盖本地合成时间，思考完结时长（及一切时元数据）收敛到服务器权威值。
     * 合并优先级由 mergePart 决定：incoming 服务器 start>0 直接胜出。
     */
    internal suspend fun reconcileFromRest(sid: String) {
        val all = fetchAllMessages(sid)
        chatRepository.upsertMessages(sid, all, MergeStrategy.REST_AUTHORITY)
    }

    /**
     * 当服务器确认会话空闲时修复 time.completed == null 的消息。
     * 处理服务器重启场景：重启后，所有会话在内存中都是空闲的，
     * 但数据库保留了 finished_at = NULL 的中断消息。
     * 不得在轮询期间调用 —— 仅在显式用户操作
     *（进入会话、中断）时调用，以避免破坏过早空闲保护。
     *
     * 2026-08-11 修复：改为触发 [SessionStateRepository.requestValidation]
     *（REST 校验）而非直接 onRestValidation(Idle)——FSM 的 restValidation
     * 无条件接受 Idle 并 forceComplete，直接传 Idle 会把真正流式（busy）的
     * 会话误标记完成。REST 校验由服务器返回真实状态：idle → forceComplete
     * → markSessionIdle（含落盘）；busy → 不标记，安全。
     *
     * 通过 [SessionStateService.onRestValidation] 路由 —— FSM 的 forceComplete
     * 机制通过在 [EventDispatcher] 的 init 块中连接的回调触发 [MessageEventHandler.markSessionIdle]。
     */
    fun fixIncompleteMessagesIfIdle(sid: String) {
        val messages = _rawMessagesList.value
        val hasIncomplete = messages.any { it is Message.Assistant && it.time.completed == null }
        if (hasIncomplete) {
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Fixing incomplete messages for session $sid (REST validation)")
            sessionStateRepository.requestValidation(sid)
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

            // 包含子智能体会话的问题
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
                        // key 合成 fallback（2026-08-18 E2E-B 真根因修复）：REST
                        // 恢复的卡 key=null → reply keyedAnswers 全跳过 → answer={}
                        // → 服务器"未作答"。与 V2FormMapper 同规则合成 q$index；
                        // option value 同步补传（V2 提交用）。
                        questions = req.questions.mapIndexed { index, q ->
                            SseEvent.QuestionAsked.Question(
                                header = q.header,
                                question = q.question,
                                multiple = q.multiple,
                                custom = q.custom,
                                options = q.options.map { o ->
                                    SseEvent.QuestionAsked.Option(
                                        label = o.label,
                                        description = o.description,
                                        value = o.value
                                    )
                                },
                                key = q.key ?: "q$index"
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
            if (e is CancellationException) throw e
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

            // 包含子智能体会话的权限
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
                // SSE 将子智能体会话权限存储在 childSessionId 下，REST 应做同样处理
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
            if (e is CancellationException) throw e
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
