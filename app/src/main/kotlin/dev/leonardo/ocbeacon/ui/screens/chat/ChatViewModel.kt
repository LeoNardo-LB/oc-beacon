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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    private val sessionReadSignal: dev.leonardo.ocbeacon.ui.screens.sessions.SessionReadSignal,
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
    private val messageStore: dev.leonardo.ocbeacon.data.local.MessageStore,
    private val tokenStatsTracker: TokenStatsTracker,
    private val httpClient: HttpClient,
    private val sessionStateService: SessionStateRepository,
    private val sessionFocusHolder: dev.leonardo.ocbeacon.service.SessionFocusHolder,
    private val appNotificationManager: dev.leonardo.ocbeacon.service.AppNotificationManager,
    private val toolSnapshotCache: dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache,
    private val serverRepository: ServerRepository,
    private val shellJobsStore: dev.leonardo.ocbeacon.data.repository.ShellJobsStore,
    private val eventDispatcher: dev.leonardo.ocbeacon.data.repository.EventDispatcher,
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
    private val _serverApiVersion = MutableStateFlow<ApiVersion?>(null)
    val serverApiVersion: StateFlow<ApiVersion?> = _serverApiVersion.asStateFlow()

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

    // ============ 任务聚合（subagent + shell） ============
    private val taskAggregator = TaskAggregator(
        sessionRepository = sessionRepository,
        chatRepository = chatRepository,
        shellJobsStore = shellJobsStore,
        serverId = serverId,
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        scope = viewModelScope,
        // 2026-08-16（R3 僵尸自愈）：active 轮询发现 FSM 与服务器分歧时触发 L3 校验
        sessionStateService = sessionStateService,
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

    fun onSessionFocused(notificationManager: android.app.NotificationManager) {
        appNotificationManager.cancelSessionNotifications(notificationManager, serverId, sessionId)
        sessionFocusHolder.setActiveFocus(serverId, sessionId)
    }

    fun onSessionUnfocused() {
        sessionFocusHolder.setActiveFocus(null, null)
    }

    /** 离开会话时标记已读（清除未读提示）：打开期间到达的消息也算已读。
     *  先更新内存信号（列表立即感知，消除 DataStore 异步写入窗口期的红点闪烁），
     *  再在 [NonCancellable] 下持久化——导航返回时 ViewModel 随返回栈销毁，
     *  viewModelScope 会被 cancel，普通 launch 的 DataStore 写入可能在完成前
     *  被取消（红点不消除的根因，2026-08-07 修复）。 */
    fun markSessionRead() {
        val srv = serverId
        val sid = sessionId
        if (srv.isNotBlank() && sid.isNotBlank()) {
            // 已读位置 = 该会话最后一条完成 assistant 消息的 completed（服务器时刻）。
            // 会话无任何完成消息（如秒退、消息未加载）→ 不更新已读标记（用户未消费内容，之后红点合理）。
            val lastCompleted = messageData.messagesList.value
                .filterIsInstance<dev.leonardo.ocbeacon.domain.model.Message.Assistant>()
                .mapNotNull { it.time.completed }
                .maxOrNull()
            if (lastCompleted == null) {
                AppLogger.d("UnreadDiag", "[markRead] sid=${sid.take(12)} no completed msg, skip")
                return
            }
            AppLogger.d("UnreadDiag", "[markRead] sid=${sid.take(12)} completed=$lastCompleted")
            sessionReadSignal.markRead(sid, lastCompleted)
            viewModelScope.launch {
                withContext(NonCancellable) { settingsRepository.markSessionRead(srv, sid, lastCompleted) }
            }
        }
    }

    init {
        sessionStateService.setServerId(serverId)
        // backlog #38: 异步加载服务器配置（Room 毫秒级，但避免主线程 runBlocking 阻塞）。
        // 加载完成后：更新 serverName StateFlow + 回填终端 workspace 连接。
        viewModelScope.launch {
            val config = withContext(kotlinx.coroutines.Dispatchers.IO) {
                serverRepository.getServer(serverId)
            }
            _serverName.value = config?.displayName ?: ""
            _serverApiVersion.value = config?.apiVersion
            val conn = config?.let {
                ServerConnection.from(it.url, it.username, it.password, it.apiVersion)
            } ?: ServerConnection.from("", "", null)
            terminalRegistry.updateConn(serverId, conn)
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
    fun selectAgent(name: String) = modelConfig.selectAgent(name)
    fun cycleVariant() = modelConfig.cycleVariant()
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

    fun selectModel(providerId: String, modelId: String) = modelConfig.selectModel(providerId, modelId)

    // ============ 消息数据 Delegate ============
    private val messageData: MessageDataDelegate = MessageDataDelegate(
        manageSessionUseCase = manageSessionUseCase,
        managePermissionUseCase = managePermissionUseCase,
        chatRepository = chatRepository,
        messagePaging = messagePaging,
        messageStore = messageStore,
        sessionStateService = sessionStateService,
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
    )
    val terminalTabs: StateFlow<List<TerminalTabUi>> get() = terminalDelegate.terminalTabs
    val activeTerminalTabId: StateFlow<String?> get() = terminalDelegate.activeTerminalTabId
    val terminalVersion: StateFlow<Long> get() = terminalDelegate.terminalVersion
    val terminalState: StateFlow<TerminalTabState> get() = terminalDelegate.terminalState
    val terminalFontSizeSp: StateFlow<Float> get() = terminalDelegate.terminalFontSizeSp
    val terminalEmulator: org.connectbot.terminal.TerminalEmulator get() = terminalDelegate.terminalEmulator
    val terminalCursorKeysAppMode: Boolean get() = terminalDelegate.terminalCursorKeysAppMode

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
        sessionStateService = sessionStateService,
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
        // 2026-08-16（压缩气泡·V2 适配）：压缩状态注入 EventDispatcher ——
        // compactSession 发起前置 CompactionStarted（V2 无服务器 started 事件，
        // 进行中气泡唯一驱动），结束时 CompactionEnded（幂等）。
        compactionNotifier = { sid, started, reason ->
            val next = if (started) {
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionStarted(sid, messageId = "", reason = reason)
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
    val collapseTools get() = settingsState.collapseTools
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
    // 使用 cache window 策略（窗口式预组合）替代默认的单 item 异步预取：
    // 默认 LazyListPrefetchStrategy 每次只预取 1 个 item，fling 快速滚动（向下滑/看更旧）
    // 时预取跟不上 → item 进入视口才现场组合（重型 Markdown 耗时）→ 整个气泡被跳过。
    // v5 迭代（日志证实）：拖拽摩擦时同一 item 反复销毁重建（behind 太小）+ fling
    // 2026-08-13 终极解法：跳转预组合策略——视口外预组合跳转目标（组合+测量
    // 不显示），滚动到视口时即静态显示（零渲染过程）；同时承担滚动方向预测
    // 预组合（替代原 cacheWindow 大窗口——二者构造不可兼得）。
    @OptIn(ExperimentalFoundationApi::class)
    val jumpPrefetch = JumpPrefetchStrategy()

    // 2026-08-13：LazyListState 改用 prefetchStrategy（跳转预组合 + 滚动预测），
    // 原 cacheWindow（ahead/behind 1.5 屏）由 JumpPrefetchStrategy 的滚动方向
    // 预测替代——流式/滚动预组合收益保持。
    @OptIn(ExperimentalFoundationApi::class)
    val listState = androidx.compose.foundation.lazy.LazyListState(
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0,
        prefetchStrategy = jumpPrefetch,
    )

    val restoredDraftState: StateFlow<RevertedDraftPayload?> get() = draftDelegate.restoredDraftState

    // ============ 拆分状态聚合管道（已委托 —— ChatStateAggregator） ============
    // sessionMetaState / tokenStatsState / directoryState / uiState
    // 已迁移到 ChatStateAggregator（6 源 combine 组装管道）。
    private val stateAggregator = ChatStateAggregator(
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        sessionRepository = sessionRepository,
        sessionStateService = sessionStateService,
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

    val uiState: StateFlow<ChatUiState> get() = stateAggregator.uiState

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
                var lastCompacted = eventDispatcher.compactedSessions.value
                eventDispatcher.compactedSessions.collect { compacted ->
                    if (sessionId in compacted && compacted != lastCompacted) {
                        lastCompacted = compacted
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

    fun loadMessages() = messageData.paginationDelegate.loadMessages()
    fun refreshSession() = sessionActions.refreshSession()
    fun refreshIfNeeded() = sessionActions.refreshIfNeeded()
    fun syncSessionStatus() = sessionActions.syncSessionStatus()
    fun loadOlderMessages() = messageData.paginationDelegate.loadOlderMessages()
    /** 自动续载的退避等待毫秒（0 = 无需等待）——UI 触发自动分页前查询。 */
    fun autoLoadWaitMillis(): Long = messageData.paginationDelegate.autoLoadWaitMillis()

    // ============ 快速导航双向加载（门面 —— MessagePaginationDelegate） ============

    /** 快速导航定位加载（前后各 N 条）。suspend —— 调用方在协程中 await。 */
    suspend fun loadAround(targetMessageId: String) =
        messageData.paginationDelegate.loadAround(targetMessageId)

    /** 向更新方向加载（定位到中间后下滑触发）。 */
    fun loadNewerMessages() = messageData.paginationDelegate.loadNewerMessages()

    /** 服务器上是否存在更新方向（newer）的更多消息。 */
    val hasNewerMessages: kotlinx.coroutines.flow.StateFlow<Boolean> =
        messageData.paginationDelegate.hasNewerMessages

    /** "定位加载" 请求是否进行中（jumpToMessage 异步加载指示）。 */
    val isLoadingAround: kotlinx.coroutines.flow.StateFlow<Boolean> =
        messageData.paginationDelegate.isLoadingAround

    /** "加载更新" 请求是否进行中。 */
    val isLoadingNewer: kotlinx.coroutines.flow.StateFlow<Boolean> =
        messageData.paginationDelegate.isLoadingNewer

    /** 快速导航全量列表（Room 热表 role='user'，含 parts）。suspend —— 调用方在协程中 await。 */
    suspend fun loadJumpTargets(): List<MessageWithParts> = messageData.loadJumpTargets()

    // ============ @ 文件提及搜索 + 草稿管理（门面 —— DraftInputDelegate） ============

    val fileSearchResults: StateFlow<List<String>> get() = draftDelegate.fileSearchResults

    fun searchFilesForMention(query: String) = draftDelegate.searchFilesForMention(query)
    fun confirmFilePath(path: String) = draftDelegate.confirmFilePath(path)
    fun removeFilePath(path: String) = draftDelegate.removeFilePath(path)
    fun clearFileSearch() = draftDelegate.clearFileSearch()
    fun clearConfirmedPaths() = draftDelegate.clearConfirmedPaths()

    fun updateDraftText(text: String) = draftDelegate.updateDraftText(text)
    fun addDraftAttachment(uri: String) = draftDelegate.addDraftAttachment(uri)
    fun removeDraftAttachment(index: Int) = draftDelegate.removeDraftAttachment(index)
    fun clearDraft() = draftDelegate.clearDraft()
    fun consumeRestoredDraft() = draftDelegate.consumeRestoredDraft()

    override fun onCleared() {
        messageData.cancelSseJob()
        closeTerminalSession()
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
        sessionStateService = sessionStateService,
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
     * 中止当前会话 —— 协调器。
     * 将 REST abort + markIdle 委托给 [sessionActions]，然后处理
     * SSE job 的取消/重启（B↔C↔G 编排）。
     */
    fun abortSession() {
        // RS-006 修复：在更新 FSM 之前取消 SSE job。
        messageData.cancelSseJob()
        sessionStateService.onClientAbort(sessionId)
        viewModelScope.launch {
            try {
                sessionActions.abortSession()
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Aborted session $sessionId")
                runCatching { messageData.startObservingMessages() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to abort session", e)
            }
        }
    }

    /** 2026-08-18 E2E-C 向量1修复：提问卡答案宿主缓存（question.id → answers）。
     * ViewModel 存活期跨导航条目——BACK pop 销毁 saveable 作用域后重进，
     * QuestionCard 从此恢复；提交/拒绝（答案已消费）时移除。 */
    val questionAnswerCache = androidx.compose.runtime.mutableStateMapOf<String, List<List<String>>>()

    fun replyToQuestion(requestId: String, answers: List<List<String>>) {
        questionAnswerCache.remove(requestId)
        sessionActions.replyToQuestion(requestId, answers)
    }

    fun rejectQuestion(requestId: String) {
        questionAnswerCache.remove(requestId)
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
                val currentStatus = sessionStateService.statusFlow.value[sessionId]
                val wasBusy = currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry

                // RS-008 修复：在取消 SSE job 之前设置 revert 过滤器。
                chatRepository.setRevert(sessionId, messageId)

                if (wasBusy) {
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "Revert：暂停 busy 会话 $sessionId")
                    sessionStateService.onClientAbort(sessionId)
                    messageData.cancelSseJob()
                    runCatching { sessionRepository.abort(serverId, sessionId, sessionLifecycle.sessionDirectory) }
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
        sessionActions.runShellCommand(command, onResult)

    // ============ 终端操作（门面 —— TerminalDelegate） ============

    fun openTerminalSession(onResult: (Boolean) -> Unit = {}) =
        terminalDelegate.openTerminalSession(onResult)

    fun createTerminalTab(onResult: (Boolean) -> Unit = {}) =
        terminalDelegate.createTerminalTab(onResult)

    fun switchTerminalTab(tabId: String) = terminalDelegate.switchTerminalTab(tabId)
    fun closeTerminalTab(tabId: String) = terminalDelegate.closeTerminalTab(tabId)
    fun reconnectTerminalTab(tabId: String, onResult: (Boolean) -> Unit = {}) =
        terminalDelegate.reconnectTerminalTab(tabId, onResult)
    fun setTerminalFontSize(fontSizeSp: Float) =
        terminalDelegate.setTerminalFontSize(fontSizeSp)
    fun sendTerminalInput(input: String) = terminalDelegate.sendTerminalInput(input)
    fun clearTerminalBuffer() = terminalDelegate.clearTerminalBuffer()
    fun resizeTerminal(cols: Int, rows: Int) = terminalDelegate.resizeTerminal(cols, rows)
    fun closeTerminalSession() = terminalDelegate.closeTerminalSession()

    fun getLastAssistantText(): String? = sessionActions.getLastAssistantText()

    companion object
}
