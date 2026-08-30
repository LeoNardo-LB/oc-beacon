package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.terminal.TerminalTabState
import dev.leonardo.ocbeacon.data.terminal.TerminalTabUi
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.ui.navigation.routes.safeDecodeParam
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskOutputFetch
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextDetailState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "ChatViewModel"

// ============ UI State 数据类 ============
// MessageListState / SessionMetaState / InteractionState / TokenStatsState /
// ModelConfigState / ChatUiState / RevertedDraftPayload / ChatMessage
// 已迁移到 ChatUiState.kt（纯数据类，无依赖）。

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scrollSignal: dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal,
    private val unreadBadgeService: dev.leonardo.ocbeacon.data.repository.UnreadBadgeService,
    private val sendMessageUseCase: SendMessageUseCase,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val selectModelUseCase: SelectModelUseCase,
    private val manageAgentUseCase: ManageAgentUseCase,
    private val manageTerminalUseCase: ManageTerminalUseCase,
    private val draftRepository: DraftRepository,
    private val shareExportUseCase: ShareExportUseCase,
    private val undoRedoUseCase: UndoRedoUseCase,
    private val settingsRepository: SettingsRepository,
    private val terminalRegistry: ServerTerminalRegistry,
    val toolCardResolver: ToolCardResolver,
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val messagePaging: MessagePaginationUseCase,
    private val messageStore: dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository,
    private val tokenStatsTracker: TokenStatsTracker,
    private val httpClient: HttpClient,
    private val sessionStateRepository: SessionStateRepository,
    private val sessionFocusHolder: dev.leonardo.ocbeacon.service.SessionFocusHolder,
    private val appNotificationManager: dev.leonardo.ocbeacon.service.AppNotificationManager,
    private val toolSnapshotCache: dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache,
    private val serverRepository: ServerRepository,
    private val shellJobsStore: dev.leonardo.ocbeacon.data.repository.ShellJobsStore,
    private val eventDispatcher: dev.leonardo.ocbeacon.data.repository.EventDispatcher,
    // 堆积消息（2026-08-20 设计定稿）：本地暂存队列 + 推进管线
    private val pendingMessageRepository: dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository,
    private val pendingMessagePipeline: dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline,
    // #271：首开自动 drain 全量历史（HistorySyncManager 唯一所有者；已 synced / 进行中时 no-op）
    private val historySyncManager: dev.leonardo.ocbeacon.data.repository.HistorySyncManager,
) : ViewModel() {

    // ============ 工具快照缓存（已提取到 ToolCacheDelegate） ============

    private val toolCacheDelegate = ToolCacheDelegate(toolSnapshotCache)

    fun cacheToolPart(part: dev.leonardo.ocbeacon.domain.model.Part.Tool) =
        toolCacheDelegate.cacheToolPart(part)

    /** 为 ChatMessageList composable 暴露 chatRepository（工具进度、步骤进度、压缩状态）。 */
    val chatRepositoryExposed: ChatRepository get() = chatRepository

    private val serverId: String = safeDecodeParam(savedStateHandle.get<String>("serverId") ?: "")

    // ============ 服务器配置异步加载（backlog #38：消除构造期主线程 runBlocking） ============
    // 构造期不再 runBlocking 等待 Room 读取；改为 init 中 viewModelScope.launch 异步加载，
    // 结果通过 StateFlow 暴露给依赖方（ChatStateAggregator 的 serverName、
    // TerminalDelegate 的连接）。加载完成前用占位空值，就绪后自动更新。
    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    // 服务器 API 版本（backlog #78：V2 服务器当前无 share 端点 → UI 隐藏
    // Share/Unshare 菜单项；V1 保留。加载完成前为 null（本地 Room 毫秒级）。
    // #172：UI 门控只读能力位（null 版本 = 全开放，与原 permissive 比较语义一致）
    private val _serverCapabilities = MutableStateFlow(dev.leonardo.ocbeacon.domain.model.ServerCapabilities.of(null))
    val serverCapabilities: StateFlow<dev.leonardo.ocbeacon.domain.model.ServerCapabilities> = _serverCapabilities.asStateFlow()

    // ============ 发送成功/失败信号（2026-08-11 用户要求） ============
    // 悲观发送：输入框在发送期间保留内容，成功才清空（sendSuccessTick 驱动）；
    // 失败 → 消息保留在输入框 + AlertDialog（sendFailure）。
    private val _sendSuccessTick = MutableStateFlow(0L)
    /** 发送成功递增信号 —— ChatScreen LaunchedEffect 监听后清空输入框。 */
    val sendSuccessTick: StateFlow<Long> = _sendSuccessTick.asStateFlow()
    /** E8-1：最近一次成功发送的纯文本快照（输入框清空前比对，防误清新输入）。 */
    private var lastSentTextSnapshot: String = ""
    val lastSentTextSnapshotForClear: String get() = lastSentTextSnapshot
    private val _sendFailure = MutableStateFlow<String?>(null)
    /** 发送失败消息（非空时 ChatScreen 弹 AlertDialog）。 */
    val sendFailure: StateFlow<String?> = _sendFailure.asStateFlow()

    /** 消费发送失败弹窗（AlertDialog 确认后清除）。 */
    fun consumeSendFailure() {
        _sendFailure.value = null
    }

    // ============ 会话生命周期 Delegate ============
    private val sessionLifecycle = SessionLifecycleDelegate(
        manageSessionUseCase = manageSessionUseCase,
        sessionRepository = sessionRepository,
        serverId = serverId,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope,
        onMessagesNeedLoading = { loadMessagesForSession() },
        onStartObservingMessages = { startObservingMessages() },
    )
    val sessionId: String get() = sessionLifecycle.sessionId

    // ============ 堆积消息（turn 结束后待发送，2026-08-20 设计定稿） ============
    /** 当前会话的堆积队列（面板列表 + 角标计数数据源）。 */
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    val pendingQueue: kotlinx.coroutines.flow.StateFlow<List<dev.leonardo.ocbeacon.domain.model.PendingMessage>> =
        sessionLifecycle.sessionIdFlow
            .flatMapLatest { sid -> pendingMessageRepository.observeQueue(sid) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 推送中会话集合（UI 标记「发送中」并锁定编辑/删除）。 */
    val pendingDraining: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        pendingMessagePipeline.drainingSessions

    /** busy 气泡菜单「堆积消息」入口：入队当前输入文本。 */
    fun enqueuePendingMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val sid = sessionLifecycle.sessionId
        viewModelScope.launch {
            pendingMessageRepository.enqueue(sid, trimmed)
            dev.leonardo.ocbeacon.logging.AppLogger.i("ChatViewModel", "pending message enqueued: " + sid)
            // #176：入队即时补偿——若 FSM 已 Idle（turn 在入队前结束的 TOCTOU 窗口）
            // 立即 drain，不等边沿/心跳
            pendingMessagePipeline.onEnqueued(sid)
        }
    }

    fun editPendingMessage(id: Long, text: String) {
        viewModelScope.launch { pendingMessageRepository.updateText(id, text.trim()) }
    }

    fun deletePendingMessage(id: Long) {
        viewModelScope.launch { pendingMessageRepository.delete(id) }
    }

    fun clearPendingMessages() {
        viewModelScope.launch { pendingMessageRepository.clear(sessionLifecycle.sessionId) }
    }

    fun reorderPendingMessages(orderedIds: List<Long>) {
        viewModelScope.launch { pendingMessageRepository.reorder(sessionLifecycle.sessionId, orderedIds) }
    }

    /** 面板「继续」：空闲会话手动放行队首 1 条。 */
    fun continuePendingQueue() {
        pendingMessagePipeline.continueNow(sessionLifecycle.sessionId, serverId)
    }

    /** 面板单条「发送」：插队立即发送指定条目。 */
    fun sendPendingNow(id: Long, text: String) {
        pendingMessagePipeline.sendOneNow(sessionLifecycle.sessionId, serverId, id, text)
    }

    // ============ TODO（面板数据源 + 服务器能力探测，2026-08-20） ============
    /** 当前会话 TODO（SSE 实时 + REST hydrate 同源）。 */
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    val sessionTodos: StateFlow<List<dev.leonardo.ocbeacon.domain.model.SseEvent.TodoUpdated.Todo>> =
        sessionLifecycle.sessionIdFlow
            .flatMapLatest { sid ->
                eventDispatcher.sessionTodos.map { it[sid].orEmpty() }.distinctUntilChanged()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _todoCapable = MutableStateFlow(false)
    /** 服务器是否支持 TODO（V2 beta 无此端点 → 隐藏 TODO tab；V1 恒支持）。 */
    val todoCapable: StateFlow<Boolean> = _todoCapable.asStateFlow()

    private var todoProbeStarted = false

    /** 探测 TODO 能力并 hydrate 首屏（幂等：VM 生命周期内一次）。 */
    fun probeTodoCapability() {
        if (todoProbeStarted) return
        todoProbeStarted = true
        viewModelScope.launch {
            val sid = sessionLifecycle.sessionId
            val result = runCatching { sessionRepository.getSessionTodos(serverId, sid).getOrThrow() }
            _todoCapable.value = result.isSuccess
            if (result.isFailure) {
                dev.leonardo.ocbeacon.logging.AppLogger.i(
                    "ChatViewModel",
                    "todo capability probe failed (hiding TODO tab): " + serverId,
                )
            }
        }
    }

    /**
     * #182（2026-08-21）：TaskToolCard 展开时拉取全量输出。
     * part 优先（父会话按 cursor 翻页找 part id——老卡片可落在最新窗口外，
     * 走查修复：50 条窗口只覆盖最新会话，翻页上限 10 页）→ 降级子智能体会话
     * transcript，取长者。失败返回 null（卡片降级本地预览，不阻塞展开）。
     */
    suspend fun fetchFullTaskOutput(partId: String, subSessionId: String?): String? {
        val sid = sessionLifecycle.sessionId
        return runCatching {
            var partOut: String? = null
            var cursor: String? = null
            var pages = 0
            while (partOut == null && pages < TASK_FETCH_MAX_PAGES) {
                val page = sessionRepository.listMessages(serverId, sid, TASK_FETCH_PAGE_LIMIT, cursor).getOrNull() ?: break
                partOut = TaskOutputFetch.findToolOutputById(page.messages, partId)
                cursor = page.nextCursor ?: break
                pages++
            }
            val childOut = subSessionId?.takeIf { it.isNotBlank() }?.let { child ->
                sessionRepository.listMessages(serverId, child, TASK_FETCH_PAGE_LIMIT, null).getOrNull()
                    ?.messages
                    ?.let { TaskOutputFetch.buildChildTranscript(it) }
            }
            TaskOutputFetch.pickLonger(partOut, childOut)
        }.getOrNull()
    }


    // ============ 任务聚合（subagent + shell） ============
    private val taskAggregator = TaskAggregator(
        sessionRepository = sessionRepository,
        chatRepository = chatRepository,
        shellJobsStore = shellJobsStore,
        serverId = serverId,
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        scope = viewModelScope,
        // 2026-08-16（R3 僵尸自愈）：active 轮询发现 FSM 与服务器分歧时触发 L3 校验
        sessionStateRepository = sessionStateRepository,
    )

    /** 启动任务轮询（ChatScreen 组合时调用；幂等）。 */
    fun startTaskPolling() = taskAggregator.startPolling(viewModelScope)

    /** 单次刷新任务状态。 */
    fun refreshTaskNow() = viewModelScope.launch {
        taskAggregator.refreshActiveSessions()
    }

    /** 任务聚合状态（角标计数 / 任务工具栏 / 面板数据）。 */
    val taskUiState: StateFlow<TaskUiState> get() = taskAggregator.uiState

    /**
     * 将当前会话所有前台 subagent 转为后台（对应 TUI ctrl+b）。
     */
    fun backgroundSession() {
        val sid = sessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.backgroundSession(serverId, sid)
        }
    }

    /**
     * 终止并删除后台 shell。
     */
    fun removeShell(shellId: String) {
        viewModelScope.launch {
            chatRepository.removeShell(serverId, shellId)
        }
    }

    /**
     * 读取后台 shell 输出（分页游标模式）。
     */
    fun fetchShellOutput(
        shellId: String,
        cursor: Long? = null,
        limit: Int? = null,
        onResult: (dev.leonardo.ocbeacon.domain.model.ShellOutput?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = chatRepository.getShellOutput(serverId, shellId, cursor, limit)
            onResult(result.getOrNull())
        }
    }

    fun onSessionFocused() {
        // C9-B：NotificationManager 所有权收归 AppNotificationManager 构造
        appNotificationManager.cancelSessionNotifications(serverId, sessionId)
        sessionFocusHolder.setActiveFocus(serverId, sessionId)
    }

    fun onSessionUnfocused() {
        sessionFocusHolder.setActiveFocus(null, null)
    }

    /**
     * 离开会话时标记已读（清除未读提示）：已读位置 = 红点模块自身水位线（服务器域），
     * **不再扫描消息缓存**（#171——原实现从合并缓存取 max，markSessionIdle 的客户端
     * 终结戳可能混入已读标记）。无水位线记录（秒退/消息未加载）模块内跳过。
     * 内存信号先行 + ApplicationScope 持久化（比 VM 活得久，导航返回不丢写入）。
     */
    fun markSessionRead() {
        val srv = serverId
        val sid = sessionId
        if (srv.isNotBlank() && sid.isNotBlank()) {
            unreadBadgeService.markSessionRead(srv, sid)
        }
    }

    init {
        sessionStateRepository.setServerId(serverId)
        // backlog #38: 异步加载服务器配置（Room 毫秒级，但避免主线程 runBlocking 阻塞）。
        // 加载完成后：更新 serverName StateFlow + 回填终端 workspace 连接。
        viewModelScope.launch {
            val config = withContext(kotlinx.coroutines.Dispatchers.IO) {
                serverRepository.getServer(serverId)
            }
            _serverName.value = config?.displayName ?: ""
            _serverCapabilities.value = dev.leonardo.ocbeacon.domain.model.ServerCapabilities.of(config?.apiVersion)
            val conn = config?.let {
                ServerConnection.from(it.url, it.username, it.password, it.apiVersion)
            } ?: ServerConnection.from("", "", null)
            terminalRegistry.updateConn(serverId, conn)
        }
        // #252 时间线化：shell 任务创建/结束（任意来源——客户端 UI 发送、服务器端
        // 直发、TUI）→ SSE 更新 ShellJobsStore。shell 的消息条目（type='shell'，
        // 含 command/status/exit/output 载荷）在服务器消息历史中，客户端需重拉
        // 消息列表才能拿到 → 观察 store 变化去抖刷新（当前会话的 job 集合签名
        // 变化才触发；首次发射跳过）。
        viewModelScope.launch {
            var lastSig: String? = null
            var refreshJob: kotlinx.coroutines.Job? = null
            shellJobsStore.jobsBySession.collect { all ->
                val jobs = all[sessionId].orEmpty()
                val sig = jobs.joinToString("|") { it.id + ":" + it.status }
                if (lastSig != null && sig != lastSig) {
                    refreshJob?.cancel()
                    refreshJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(800)  // 等服务器 appendMessage/updateShell 落库
                        try {
                            messageData.paginationDelegate.loadMessages()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            AppLogger.w("ChatShell", "shell-driven message refresh failed: " + e.message)
                        }
                    }
                }
                lastSig = sig
            }
        }
    }

    // ============ 模型配置 Delegate ============
    private val modelConfig = ModelConfigDelegate(
        selectModelUseCase = selectModelUseCase,
        manageAgentUseCase = manageAgentUseCase,
        settingsRepository = settingsRepository,
        sessionRepository = sessionRepository,
        messagePaging = messagePaging,
        tokenStatsTracker = tokenStatsTracker,
        serverId = serverId,
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        scope = viewModelScope,
    )
    val modelConfigState: StateFlow<ModelConfigState> get() = modelConfig.modelConfigState
            /** 2026-08-16（方案 A·默认模型）：星标切换——设为默认/再点取消。
     *  存 "providerId|modelId"（variants 不参与默认——保持简单）。 */
    fun toggleDefaultModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            val current = modelConfig.localDefaultModel
            val newValue = if (current == "$providerId|$modelId") null else "$providerId|$modelId"
            settingsRepository.setDefaultModel(serverId, newValue)
        }
    }

    /** 2026-08-16（方案 A·默认模型）：本地默认模型（"pid|mid"），供 UI 星标态 */
    val localDefaultModel: String? get() = modelConfig.localDefaultModel

    
    // ============ 消息数据 Delegate ============
    private val messageData: MessageDataDelegate = MessageDataDelegate(
        manageSessionUseCase = manageSessionUseCase,
        managePermissionUseCase = managePermissionUseCase,
        chatRepository = chatRepository,
        messagePaging = messagePaging,
        messageStore = messageStore,
        sessionStateRepository = sessionStateRepository,
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        serverId = serverId,
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        scope = viewModelScope,
    )
    val messageListState: StateFlow<MessageListState> get() = messageData.messageListState
    val interactionState: StateFlow<InteractionState> get() = messageData.interactionState

    // ============ 终端 Delegate ============
    private val terminalDelegate = TerminalDelegate(
        terminalRegistry = terminalRegistry,
        settingsRepository = settingsRepository,
        serverId = serverId,
        conn = ServerConnection.from("", "", null),
        scope = viewModelScope,
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        sessionLoaded = sessionLifecycle.sessionLoaded,
        // 离线兜底：重拉会话信息并同步 sessionDirectory（loadSession 失败场景）
        reloadDirectory = {
            manageSessionUseCase.getSession(serverId, sessionLifecycle.sessionId)
                .directory
                .ifBlank { null }
                ?.also { sessionLifecycle.fillDirectoryFromRetry(it) }
        },
    )
    // #173 段 0：终端簇整体迁出 VM 门面——ChatTerminalView 直接收 delegate，
    // VM 不再逐成员转发（原 7 getter + 10 方法收缩为本单成员）。
    val terminal: TerminalDelegate get() = terminalDelegate

    // ============ 草稿输入 Delegate ============
    private val draftDelegate = DraftInputDelegate(
        draftRepository = draftRepository,
        manageAgentUseCase = manageAgentUseCase,
        scope = viewModelScope,
        serverId = serverId,
        sessionIdProvider = { sessionLifecycle.sessionId },
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        selectedAgentProvider = { modelConfig.selectedAgentValue },
        selectedVariantProvider = { modelConfig.selectedVariantValue },
    )
    val draftText: StateFlow<String> get() = draftDelegate.draftText
    val revertedDraftEvent: SharedFlow<RevertedDraftPayload> get() = draftDelegate.revertedDraftEvent

    /** 2026-08-16（压缩完成后才通知）：SSE session.compacted 事件（压缩完毕
     *  确切时刻）触发的完成通知——ChatScreen collect 显示 snackbar。 */
    private val _compactionDoneEvent = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val compactionDoneEvent: SharedFlow<Boolean> = _compactionDoneEvent

    /** #219（2026-08-25）：压缩失败事件（当前会话）——SSE compaction.failed 驱动，
     * V2 HTTP 秒回受理后失败只从 SSE 到达，此前静默（用户只见分割线闪一下）。 */
    val compactionFailedEvent: kotlinx.coroutines.flow.Flow<String> =
        eventDispatcher.compactionFailures.mapNotNull { (sid, err) ->
            if (sid == sessionId) err else null
        }

    init {
        // #219：失败即时刷新——失败压缩消息（failed 分割线）立即入列，
        // 与成功路径一致（此前失败零刷新，失败记录要重进会话才出现）。
        viewModelScope.launch {
            compactionFailedEvent.collect { messageData.refreshMessages() }
        }
    }

    val draftAttachmentUris: StateFlow<List<String>> get() = draftDelegate.draftAttachmentUris
    val confirmedFilePaths: StateFlow<Set<String>> get() = draftDelegate.confirmedFilePaths

    // ============ 会话操作 Delegate ============
    private val sessionActions = SessionActionsDelegate(
        shareExportUseCase = shareExportUseCase,
        undoRedoUseCase = undoRedoUseCase,
        manageSessionUseCase = manageSessionUseCase,
        managePermissionUseCase = managePermissionUseCase,
        manageTerminalUseCase = manageTerminalUseCase,
        sessionRepository = sessionRepository,
        chatRepository = chatRepository,
        sessionStateRepository = sessionStateRepository,
        serverId = serverId,
        scope = viewModelScope,
        sessionIdProvider = { sessionLifecycle.sessionId },
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        modelConfigProvider = { modelConfigState.value },
        messageListProvider = { messageListState.value.messages },
        ensureSession = { sessionLifecycle.ensureSession() },
        loadSessionInfo = { sessionLifecycle.loadSession() },
        awaitSessionLoaded = { sessionLifecycle.sessionLoaded.await() },
        refreshMessages = { messageData.refreshMessages() },
        loadPendingQuestions = { messageData.loadPendingQuestions() },
        loadPendingPermissions = { messageData.loadPendingPermissions() },
        restoreRevertedDraft = { draftDelegate.restoreRevertedDraft(it) },
        // 2026-08-24（#217 分割线包揽）：压缩能力位 + V1 本地态注入。
        // V2 事件驱动（started/delta/ended），HTTP 返回不杀进行中分割线；
        // V1 HTTP 挂起期间本地置态驱动同一分割线（单一数据源 compactionState）。
        compactionAsyncProvider = {
            _serverCapabilities.value.compactionAsync
        },
        compactionLocalState = { sid, started ->
            val next = if (started) {
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionStarted(sid, messageId = "", reason = "")
            } else {
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionEnded(sid, messageId = "")
            }
            eventDispatcher.processEvent(
                dev.leonardo.ocbeacon.domain.model.SseEvent.SessionNext(next),
                serverId,
            )
        },
    )

    // ============ 设置 StateFlow Delegate ============
    // 12 个 UI 设置 StateFlow（chatFontSize/chatDensity 等）
    // 已迁移到 SettingsStateDelegate。
    private val settingsState = SettingsStateDelegate(settingsRepository, viewModelScope)

    val chatFontSize get() = settingsState.chatFontSize
    val chatDensity get() = settingsState.chatDensity
    val confirmBeforeSend get() = settingsState.confirmBeforeSend
    val compactMessages get() = settingsState.compactMessages
    val autoExpandTools get() = settingsState.autoExpandTools
    val expandReasoning get() = settingsState.expandReasoning
    val showTurnDividers get() = settingsState.showTurnDividers
    val hapticFeedback get() = settingsState.hapticFeedback
    val keepScreenOn get() = settingsState.keepScreenOn
    val compressImageAttachments get() = settingsState.compressImageAttachments
    val imageAttachmentMaxLongSide get() = settingsState.imageAttachmentMaxLongSide
    val imageAttachmentWebpQuality get() = settingsState.imageAttachmentWebpQuality

    // ============ 工具展开 / 分页（已委托 —— MessageDataDelegate） ============
    val toolExpandedStates: StateFlow<Map<String, Boolean>> get() = messageData.toolExpandedStates

    fun toggleToolExpanded(toolId: String, defaultExpanded: Boolean = false) =
        messageData.toggleToolExpanded(toolId, defaultExpanded)

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean =
        messageData.isToolExpanded(toolId, autoExpand)

    // ============ 滚动状态 ============
    // 历史：默认 LazyListPrefetchStrategy 每次只预取 1 个 item，fling 快速滚动
    // （向下滑/看更旧）时预取跟不上 → item 进入视口才现场组合（重型 Markdown
    // 耗时）→ 整个气泡被跳过；v5 迭代（日志证实）：拖拽摩擦时同一 item 反复
    // 销毁重建（behind 太小）+ fling。
    // 2026-08-13 曾以 cacheWindow 窗口式预组合 + 跳转目标预组合（视口外组合+测量、
    // 滚动到视口即静态显示）解决；2026-08-21 跳转目标预组合已移除（预测量尺寸
    // 污染 item 布局，由跳转状态机 + 透明门控取代，见 [ScrollSpeedPrefetchStrategy]）。
    // 现仅保留滚动方向预测预组合（速度自适应窗口）。
    @OptIn(ExperimentalFoundationApi::class)
    val scrollSpeedPrefetch = ScrollSpeedPrefetchStrategy()

    // 2026-08-13：LazyListState 改用 prefetchStrategy（现为滚动方向预测预组合），
    // 原 cacheWindow（ahead/behind 1.5 屏）由 ScrollSpeedPrefetchStrategy 的滚动方向
    // 预测替代——流式/滚动预组合收益保持。
    @OptIn(ExperimentalFoundationApi::class)
    val listState = androidx.compose.foundation.lazy.LazyListState(
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0,
        prefetchStrategy = scrollSpeedPrefetch,
    )

    val restoredDraftState: StateFlow<RevertedDraftPayload?> get() = draftDelegate.restoredDraftState

    // ============ 拆分状态聚合管道（已委托 —— ChatStateAggregator） ============
    // sessionMetaState / tokenStatsState / directoryState / uiState
    // 已迁移到 ChatStateAggregator（6 源 combine 组装管道）。
    private val stateAggregator = ChatStateAggregator(
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        tokenStatsTracker = tokenStatsTracker,
        messageListState = messageListState,
        interactionState = interactionState,
        modelConfigState = modelConfigState,
        restoredDraftState = draftDelegate.restoredDraftState,
        serverId = serverId,
        serverName = serverName,
        scope = viewModelScope,
    )

    val sessionMetaState: StateFlow<SessionMetaState> get() = stateAggregator.sessionMetaState
    val tokenStatsState: StateFlow<TokenStatsState> get() = stateAggregator.tokenStatsState
    val directoryState: StateFlow<String> get() = stateAggregator.directoryState

    // ============ 聚合上下文详情（已委托 —— ContextDetailDelegate） ============
    private val contextDetailDelegate = ContextDetailDelegate(
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        messageListState = messageListState,
        tokenStatsState = stateAggregator.tokenStatsState,
        sessionsFlow = sessionRepository.getSessionsFlow(serverId),
        modelConfigContextWindow = modelConfigState.map { it.contextWindow },
        scope = viewModelScope,
    )
    val contextDetailState: StateFlow<ContextDetailState> get() = contextDetailDelegate.state

    // ============ 状态簇门面（#173 段 1：UI 消费面按簇收缩的中转站） ============
    // 段 2 将把 UI 逐子组件迁移到这些簇成员；段 3 收敛后散成员门面按消费残留逐个退役。
    // 形态：簇 = 职责内聚的 delegate 直引用（内部成型已由既有 delegate 边界保证）。

    /** ①会话上下文簇：身份/目录/懒创建 + 会话元数据聚合。 */
    internal val sessionContext: SessionLifecycleDelegate get() = sessionLifecycle

    /** ②会话数据簇：消息列表/交互状态/分页跳转 + SSE 生命周期入口。 */
    internal val conversation: MessageDataDelegate get() = messageData

    /** ③输入簇：草稿/附件/提及搜索 + 发送信号。 */
    internal val composer: DraftInputDelegate get() = draftDelegate

    /** ④模型配置簇：provider/agent/model/variant 选择状态。 */
    internal val modelSelection: ModelConfigDelegate get() = modelConfig

    /** ⑤会话操作簇：REST 刷新/状态同步/中断/权限应答。 */
    internal val sessionOps: SessionActionsDelegate get() = sessionActions

    init {
        val isNewSession = sessionId.isEmpty()

        // 重置上一会话的 token 统计（TokenStatsTracker 是 @Singleton，跨会话共享）
        tokenStatsTracker.reset()

        // 2026-08-15：session 级权威 tokens 兜底——对齐官方语义（调研 03 文档）：
        // 官方 TUI 的 context% 只用消息级快照（findLast output>0），session 级
        // tokens 是 SQL 累计值（压缩不下降）**不代表当前上下文**。此 bootstrap
        // 仅用于 totalCost（官方同源——prompt/index.tsx:277）。
        // 2026-08-17 上下文占用口径修正（ACP：input+cache.read）：删除
        // lastContextTokens 兜底写入——累计值远超实际窗口占用（每轮累加、
        // 压缩不下降）→ 显示超 100%。lastContextTokens 唯一写入源 =
        // 消息级快照（见下方 collect）；冷启动期间无数据保持 0（指示器
        // 隐藏，可接受）。
        if (!isNewSession) {
            viewModelScope.launch {
                runCatching {
                    val session = manageSessionUseCase.getSession(serverId, sessionId)
                    // cost：session 级累计（官方同源——prompt/index.tsx:277）
                    if (session.cost != null && session.cost!! > 0) {
                        tokenStatsTracker.update { copy(totalCost = session.cost!!) }
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                        AppLogger.d(TAG, "session tokens bootstrap skipped: ${e.message}")
                    }
                }
            }
        }

        // 2026-08-17 上下文占用口径修正（ACP：input+cache.read）：删除
        // session.usage.updated 对 lastContextTokens 的兜底写入——该值是
        // session 累计口径（每轮累加、压缩不下降），写入会让指示器远超实际
        // 占用（显示超 100%）。lastContextTokens 唯一写入源 = 消息级快照
        //（见下方 collect）。V1/V2 均不再消费此事件更新 token 统计。

        // 2026-08-15：压缩完成事件驱动刷新——SessionCompacted 到达时（本会话）
        // 立即拉最新消息（含 compaction 消息→分割线卡片），无需用户重进会话。
        //（此前仅记日志——用户点击压缩后界面无任何反馈的成因之二）
        viewModelScope.launch {
            try {
                // 2026-08-24（#217 R3 修复）：原累积 Set 判变（compacted != last）
                // 在同会话第二次压缩时集合不变 → collect 不发射 → 刷新/通知
                // 双双跳过 → 全程零 UI（真机 round 3 实证）。源头已改
                // per-session 压缩计数 Map（SessionEventHandler），这里按计数判变。
                var seenCount = eventDispatcher.compactedSessions.value[sessionId] ?: 0L
                eventDispatcher.compactedSessions.collect { counts ->
                    val count = counts[sessionId] ?: 0L
                    if (count > seenCount) {
                        seenCount = count
                        messageData.refreshMessages()
                        // 2026-08-16（压缩完成后才通知·用户需求）：SSE
                        // session.compacted 事件 = 压缩**完毕**的确切时刻——
                        // 成功通知从 HTTP 回调挪到这里（HTTP 返回≠压缩完成的
                        // 语义歧义消除；失败通知仍走 HTTP 回调——SSE 无事件）。
                        _compactionDoneEvent.tryEmit(true)
                        // 2026-08-17 上下文占用口径修正（ACP：input+cache.read）：
                        // 删除压缩后 maxOf(lastContextTokens, session 累计 total)
                        // 兜底——session 级 tokens 是 SQL 累计（只增不减），
                        // 写入导致压缩后占用永不回落。压缩后 refreshMessages
                        // 拉到新消息，下次消息级快照（input+cache.read）自然回落。
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // 测试环境 relaxed mock 可能无法构造该 flow——静默跳过
            }
        }

        // 2026-08-15：消息级统计——对齐官方语义（tui prompt/index.tsx:264-282）：
        // context% 唯一权威源 = 最后一条 output>0 的 assistant 消息的单次调用
        // tokens 快照，**直接覆盖**（官方 step.ended → tokens 覆盖，tui
        // data.tsx:224-234）。原 maxOf 合并是为防消息级归零，但导致压缩后
        // 永不回落（session 累计值单向增大）。消息级归零场景已由
        // lastOrNull{output>0} + 空/0 跳过覆盖。
        // 2026-08-17 上下文占用口径修正（ACP：input+cache.read）：占用 =
        // input + cache.read（sst/opencode acp/usage.ts:207；二者不重叠，
        // cache.read 是已扣除了缓存命中部分的 input 计费口径）。原先五项
        // 相加（含 output/reasoning/cache.write）多算了不占 input window
        // 的项 → 显示超 100%（如 104%）。output/reasoning 等仍写入
        // total* 消耗统计字段（下方），不受影响。
        viewModelScope.launch {
            messageData.messagesList
                .map { messages ->
                    val assistantMessages = messages.filterIsInstance<dev.leonardo.ocbeacon.domain.model.Message.Assistant>()
                    val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }
                    val lastWithTokens = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
                    val lastTokens = lastWithTokens?.tokens
                    TokenStatsTracker.TokenStats(
                        totalCost = totalCost,
                        totalInputTokens = lastTokens?.input ?: 0,
                        totalOutputTokens = lastTokens?.output ?: 0,
                        totalReasoningTokens = lastTokens?.reasoning ?: 0,
                        totalCacheReadTokens = lastTokens?.cache?.read ?: 0,
                        totalCacheWriteTokens = lastTokens?.cache?.write ?: 0,
                        lastContextTokens = lastTokens?.let { t ->
                            t.input + t.cache.read
                        } ?: 0,
                    )
                }
                .distinctUntilChanged()
                .flowOn(kotlinx.coroutines.Dispatchers.Default)
                .collect { stats ->
                    tokenStatsTracker.update {
                        // 直接覆盖（官方语义）；lastContextTokens=0（无任何 output>0
                        // 消息的瞬时态）时保留现值防闪烁
                        copy(
                            totalCost = stats.totalCost,
                            totalInputTokens = stats.totalInputTokens,
                            totalOutputTokens = stats.totalOutputTokens,
                            totalReasoningTokens = stats.totalReasoningTokens,
                            totalCacheReadTokens = stats.totalCacheReadTokens,
                            totalCacheWriteTokens = stats.totalCacheWriteTokens,
                            lastContextTokens = if (stats.lastContextTokens > 0) stats.lastContextTokens else lastContextTokens,
                        )
                    }
                }
        }

        // 从磁盘恢复草稿（异步：DataStore IO 不阻塞主线程，backlog #38 根因修复）
        if (!isNewSession) {
            viewModelScope.launch {
                try {
                    val draft = draftDelegate.restorePersistedDraft()
                    if (draft != null) {
                        modelConfig.applyDraftRestore(draft.selectedAgent, draft.selectedVariant)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLogger.e(TAG, "restorePersistedDraft failed", e)
                }
            }
        }

        // 从内存缓存恢复模型选择
        if (!isNewSession) {
            modelConfig.restoreModelFromCache()
        }

        modelConfig.observeHiddenModels()
        // 2026-08-16（方案 A·默认模型）
        modelConfig.observeLocalDefaultModel()

        // 加载数据
        if (!isNewSession) {
            viewModelScope.launch {
                try { sessionLifecycle.loadSession() } catch (e: Exception) { if (e is CancellationException) throw e; AppLogger.e(TAG, "loadSession failed", e) }
                // #271：loadSession 完成后首开自动 drain 全量历史（后台静默分页拉取，
                // 状态机 none/syncing/synced/failed 由 HistorySyncManager 持有；
                // 失败不阻塞聊天——drain 为后台增强）。
                try { historySyncManager.requestSync(serverId, sessionId) } catch (e: Exception) { if (e is CancellationException) throw e; AppLogger.e(TAG, "requestSync failed", e) }
                try { messageData.paginationDelegate.loadMessages() } catch (e: Exception) { if (e is CancellationException) throw e; AppLogger.e(TAG, "loadMessages failed", e) }
                // 修复历史 completed==null（SSE 完成事件丢失/服务器重启场景）：
                // 触发 REST 校验 → 服务器确认 idle 时 markSessionIdle（内存+落盘），
                // 否则 "Thinking…" 计时器对已结束消息一直涨（2026-08-11 用户报告）。
                // 服务器 busy（真实流式）时 REST 校验返回 busy，不误标记。
                try { messageData.fixIncompleteMessagesIfIdle(sessionId) } catch (e: Exception) { if (e is CancellationException) throw e; AppLogger.e(TAG, "fixIncompleteMessagesIfIdle failed", e) }
                try { messageData.loadPendingQuestions() } catch (e: Exception) { if (e is CancellationException) throw e; AppLogger.e(TAG, "loadPendingQuestions failed", e) }
                try { messageData.loadPendingPermissions() } catch (e: Exception) { if (e is CancellationException) throw e; AppLogger.e(TAG, "loadPendingPermissions failed", e) }
            }
        } else {
            sessionLifecycle.initForNewSession()
            messageData.markLoaded()
        }
        modelConfig.loadProviders()
        modelConfig.loadAgents()
        modelConfig.loadCommands()
    }

    // ============ 消息加载/刷新（门面 —— MessageDataDelegate / SessionActionsDelegate） ============

    private suspend fun loadMessagesForSession() = messageData.paginationDelegate.loadMessagesForSession().also {
        // 2026-08-15（research/01）：进会话后台预取全量消息（快速定位数据源）——
        // 官方 TUI 模式（index.tsx:314：进会话 sync，Timeline 打开零 IO）
        messageData.prefetchJumpTargets(viewModelScope)
    }
    private fun startObservingMessages() = messageData.startObservingMessages()

            fun refreshIfNeeded() = sessionActions.refreshIfNeeded()
    fun syncSessionStatus() = sessionActions.syncSessionStatus()
    
    // ============ 快速导航双向加载（门面 —— MessagePaginationDelegate） ============


    // ============ @ 文件提及搜索 + 草稿管理（门面 —— DraftInputDelegate） ============

    
                    
                    
    override fun onCleared() {
        messageData.cancelSseJob()
        terminalDelegate.closeTerminalSession()
        super.onCleared()
        // 内存泄漏修复（#89）：退出会话时释放该会话在 Singleton handler 中的
        // 消息/part/权限/问题/通知去重数据——各 handler 按 sessionId 持有，
        // 正常切换会话不触发 SessionDeleted → 旧会话数据永驻内存
        runCatching { eventDispatcher.releaseSessionData(serverId, sessionId) }
            .onFailure { dev.leonardo.ocbeacon.logging.AppLogger.w("ChatVM", "releaseSessionData failed: ${it.message}") }
        runCatching { appNotificationManager.clearForSession(serverId, sessionId) }
            .onFailure { dev.leonardo.ocbeacon.logging.AppLogger.w("ChatVM", "clearForSession failed: ${it.message}") }
        // 草稿持久化：异步执行（NonCancellable 保证 DataStore 写入在 scope 取消后仍完成）。
        // 同步 saveDraft() 内部 runBlocking 会阻塞主线程 → 退出会话 ANR（真机实证 2026-08-09）。
        viewModelScope.launch {
            withContext(NonCancellable) { draftDelegate.saveDraft() }
        }
    }

    fun getSessionDirectory(): String? = sessionLifecycle.sessionDirectory

    // ============ 消息发送/重试（已委托 —— ChatSendDelegate） ============

    private val sendDelegate = ChatSendDelegate(
        scrollSignal = scrollSignal,
        sendMessageUseCase = sendMessageUseCase,
        manageSessionUseCase = manageSessionUseCase,
        chatRepository = chatRepository,
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        sendStateStore = messageData.sendStateStore,
        scope = viewModelScope,
        serverId = serverId,
        sessionIdProvider = { sessionLifecycle.sessionId },
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        ensureSession = { sessionLifecycle.ensureSession() },
        modelConfigProvider = { modelConfigState.value },
        selectedVariantProvider = { modelConfig.selectedVariantValue },
        errorSink = { messageData.reportError(it) },
        sendFailureSink = { _sendFailure.value = it },
        onSendSuccess = { sentText ->
            // E8-1：记录已发送文本快照——UI 清空输入框前比对，用户发送期间
            // 输入的新内容（被防重复拦截）不会被误清。
            lastSentTextSnapshot = sentText
            _sendSuccessTick.value++
        },
        draftDelegate = draftDelegate,
    )

    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList()) =
        sendDelegate.sendMessage(text, attachments)

    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>, rawText: String) =
        sendDelegate.sendMessage(promptParts, attachments, rawText)

    // ============ 权限/问题回复（门面 —— SessionActionsDelegate） ============

    fun replyToPermission(requestId: String, reply: String, sessionId: String? = null) =
        sessionActions.replyToPermission(requestId, reply, sessionId)

    fun savePermissionRule(event: dev.leonardo.ocbeacon.domain.model.SseEvent.PermissionAsked, directory: String) =
        sessionActions.savePermissionRule(event, directory)

    /**
     * 中断当前会话 —— 协调器。
     * 将 REST abort + markIdle 委托给 [sessionActions]，然后处理
     * SSE job 的取消/重启（B↔C↔G 编排）。
     */
    fun interruptSession() {
        // RS-006 修复：在更新 FSM 之前取消 SSE job。
        messageData.cancelSseJob()
        sessionStateRepository.onClientAbort(sessionId)
        viewModelScope.launch {
            try {
                sessionActions.interruptSession()
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Aborted session $sessionId")
                runCatching { messageData.startObservingMessages() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to abort session", e)
            }
        }
    }

    /** 2026-08-18 E2E-C 终版：应用级答案存储（单例）——VM 级缓存被终验证伪
     * （pop 销毁 entry/recreate 重建 VM），store 跨一切存活；消费时清理。 */
    @javax.inject.Inject
    lateinit var questionAnswerStore: QuestionAnswerStore

    fun replyToQuestion(requestId: String, answers: List<List<String>>) {
        questionAnswerStore.consume(requestId)
        sessionActions.replyToQuestion(requestId, answers)
    }

    fun rejectQuestion(requestId: String) {
        questionAnswerStore.consume(requestId)
        sessionActions.rejectQuestion(requestId)
    }

    // ============ 斜杠命令/分享/导出操作（门面 —— SessionActionsDelegate） ============

    fun shareSession(onResult: (String?) -> Unit) =
        sessionActions.shareSession(onResult)

    fun unshareSession(onResult: (Boolean) -> Unit) =
        sessionActions.unshareSession(onResult)

    fun compactSession(onResult: (Boolean) -> Unit) =
        sessionActions.compactSession(onResult)

    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) =
        sessionActions.exportSession(context, uri, onResult)

    fun undoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.undoMessage(onResult)

    /**
     * 通过 ID revert 到特定用户消息，可选地将其文本恢复到输入框。
     * 协调器（B↔D↔G 编排）：暂停 busy 会话，通过 undoRedoUseCase revert，
     * 重连 SSE，恢复草稿。
     */
    fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val currentStatus = sessionStateRepository.statusFlow.value[sessionId]
                val wasBusy = currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry

                // RS-008 修复：在取消 SSE job 之前设置 revert 过滤器。
                chatRepository.setRevert(sessionId, messageId)

                if (wasBusy) {
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "Revert：暂停 busy 会话 $sessionId")
                    sessionStateRepository.onClientAbort(sessionId)
                    messageData.cancelSseJob()
                    runCatching { sessionRepository.interrupt(serverId, sessionId, sessionLifecycle.sessionDirectory) }
                }

                undoRedoUseCase.revertSession(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Reverted session $sessionId to message $messageId")

                if (wasBusy) {
                    runCatching { messageData.startObservingMessages() }
                }

                val targetMessage = messageListState.value.messages
                    .firstOrNull { it.message.id == messageId && it.isUser }
                val fallbackPayload = RevertedDraftPayload(text = revertedText.orEmpty())
                draftDelegate.restoreRevertedDraft(
                    targetMessage?.let { sessionActions.extractRevertedDraft(it) } ?: fallbackPayload
                )
                onResult(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to revert to message $messageId", e)
                onResult(false)
            }
        }
    }

    fun redoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.redoMessage(onResult)

    fun deleteMessage(messageId: String, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessage(messageId, onResult)

    fun deleteMessagePart(messageId: String, partIndex: Int, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessagePart(messageId, partIndex, onResult)

    fun onSessionUpdated(session: Session) =
        sessionActions.onSessionUpdated(session)

    fun forkSession(onResult: (Session?) -> Unit) =
        sessionActions.forkSession(onResult)

    fun renameSession(title: String, onResult: (Boolean) -> Unit) =
        sessionActions.renameSession(title, onResult)

    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) =
        sessionActions.executeCommand(command, arguments, onResult)

    fun runShellCommand(command: String, onResult: (Boolean) -> Unit) =
        sessionActions.runShellCommand(command) { ok ->
            // #252 时间线化：POST 成功后服务器已创建带完整载荷（command/status/
            // exit/output）的 type='shell' 消息条目——延迟片刻重拉当前窗口让信封
            //（V2Mappers 映射为 Part.Shell）进入消息流，通知卡在其时间线位置出现。
            // ended 的输出更新走渲染层 ShellJobsStore 兜底（shellOutputProvider）。
            if (ok) {
                viewModelScope.launch {
                    kotlinx.coroutines.delay(800)
                    try {
                        messageData.paginationDelegate.loadMessages()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        AppLogger.w("ChatShell", "post-shell message refresh failed: " + e.message)
                    }
                }
            }
            onResult(ok)
        }


    companion object {
        /** #182：Task 卡片全量输出翻页拉取——单页条数与页数上限（老卡片防漏）。 */
        private const val TASK_FETCH_PAGE_LIMIT = 50
        private const val TASK_FETCH_MAX_PAGES = 10
    }
}
