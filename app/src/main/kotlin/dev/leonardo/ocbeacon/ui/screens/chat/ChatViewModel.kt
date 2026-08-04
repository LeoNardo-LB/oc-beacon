package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import androidx.compose.runtime.Immutable
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCardResolver
import dev.leonardo.ocbeacon.data.terminal.ServerTerminalWorkspace
import dev.leonardo.ocbeacon.data.terminal.TerminalTabUi
import dev.leonardo.ocbeacon.data.terminal.TerminalTabState
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextBreakdown
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocbeacon.ui.screens.chat.util.MessageCount
import dev.leonardo.ocbeacon.ui.screens.chat.util.ProviderModel
import dev.leonardo.ocbeacon.ui.screens.chat.util.SessionTimestamps
import dev.leonardo.ocbeacon.ui.screens.chat.util.cacheHitRate
import dev.leonardo.ocbeacon.ui.screens.chat.util.countMessages
import dev.leonardo.ocbeacon.ui.screens.chat.util.estimateContextBreakdown
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.data.api.SseClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "ChatViewModel"

/**
 * 拆分状态：消息列表与分页数据。
 * 每次新增消息/part 更新时变化 —— 频率最高。
 */
@Immutable
data class MessageListState(
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val queuedMessageIds: Set<String> = emptySet(),
    val pendingMessageIds: Set<String> = emptySet(),
    val pendingMessages: List<OptimisticMessage> = emptyList(),
)

/**
 * 拆分状态：会话元数据。
 * 会话信息更新时变化（标题、状态、agent）。
 */
@Immutable
data class SessionMetaState(
    val sessionTitle: String = "",
    val serverName: String = "",
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val revert: Session.Revert? = null,
    val sessionParentId: String? = null,
    val sessionAgent: String? = null,
    val currentAgentName: String? = null,
    val currentModelId: String? = null,
    val shareUrl: String? = null,
    /** 当本会话的 FSM activity 为 Streaming 时为 true（控制 reasoning 计时器 + streamingMsgId）。 */
    val isStreaming: Boolean = false,
)

/**
 * 拆分状态：用户交互状态。
 * 在加载/发送/出错及待处理权限/问题时变化。
 */
@Immutable
data class InteractionState(
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val error: String? = null,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
)

/**
 * 拆分状态：token 使用统计。
 * 每次流式 token 更新时变化 —— 生成期间频率高。
 */
@Immutable
data class TokenStatsState(
    val totalCost: Double = 0.0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalReasoningTokens: Int = 0,
    val totalCacheReadTokens: Int = 0,
    val totalCacheWriteTokens: Int = 0,
    val contextWindow: Int = 0,
    val lastContextTokens: Int = 0,
)

/**
 * 拆分状态：模型/agent 配置与已解析的选择项。
 * 在 provider 加载、用户选择或消息历史更新（用于自动解析）时变化。
 * 包含从消息历史解析模型/agent 的副作用逻辑。
 */
@Immutable
data class ModelConfigState(
    val providers: List<ProviderCatalog> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** 上下文窗口大小 —— 从 token 统计解析，带 provider 回退。 */
    val contextWindow: Int = 0,
)

data class ChatUiState(
    val sessionTitle: String = "",
    val serverName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val revert: Session.Revert? = null,
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val providers: List<ProviderCatalog> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val totalCost: Double = 0.0,
    /** 会话总量，从所有已加载的 assistant 消息计算（非 session.tokens，后者可能是单次调用的值）。 */
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalReasoningTokens: Int = 0,
    val totalCacheReadTokens: Int = 0,
    val totalCacheWriteTokens: Int = 0,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** 服务器上存在尚未加载的更早消息时为 true。 */
    val hasOlderMessages: Boolean = false,
    /** "加载更早消息" 请求进行中时为 true。 */
    val isLoadingOlder: Boolean = false,
    /** 会话已分享时的分享 URL，否则为 null。 */
    val shareUrl: String? = null,
    /** 当前模型的上下文窗口大小（未知时为 0）。 */
    val contextWindow: Int = 0,
    /** 最后一条 output > 0 的 assistant 消息的 token 总量（当前上下文使用量）。 */
    val lastContextTokens: Int = 0,
    /** 已排队（在 assistant 仍在生成时发送）的用户消息 ID 集合。 */
    val queuedMessageIds: Set<String> = emptySet(),
    /** 父会话 ID —— 当本会话是子会话/sub-agent 会话时非空。 */
    val sessionParentId: String? = null,
    /** 本会话的 agent 名称（如 "explore"、"general"）。子 agent 会话时填充。 */
    val sessionAgent: String? = null,
    /** 已持久化的工具卡片展开/折叠状态，以 Part.Tool.id 或 Part.Patch.id 为键。 */
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val currentAgentName: String? = null,
    val currentModelId: String? = null,
    /** API 确认前乐观插入的用户消息 ID 集合，以 messageId 为键。 */
    val pendingMessageIds: Set<String> = emptySet(),
    /** 发送失败后恢复的草稿。仅在消费前非空一次。 */
    val restoredDraft: RevertedDraftPayload? = null,
)

data class RevertedDraftPayload(
    val text: String,
    val attachmentUris: List<String> = emptyList(),
)

/**
 * UI 用的扁平化聊天消息。
 * 将 Message 信息与其 parts 组合在一起。
 */
data class ChatMessage(
    val message: Message,
    val parts: List<Part>
) {
    val isUser: Boolean get() = message is Message.User
    val isAssistant: Boolean get() = message is Message.Assistant
}

/** 宽限期（ms），超过此时间的覆盖型 pending prompt 在对账时判定为丢失。 */
private const val PENDING_RECONCILE_MIN_AGE_MS = 60_000L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scrollSignal: dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal,
    private val sendMessageUseCase: SendMessageUseCase,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val selectModelUseCase: SelectModelUseCase,
    private val manageAgentUseCase: ManageAgentUseCase,
    private val manageTerminalUseCase: ManageTerminalUseCase,
    private val draftUseCase: DraftUseCase,
    private val shareExportUseCase: ShareExportUseCase,
    private val undoRedoUseCase: UndoRedoUseCase,
    private val settingsRepository: SettingsRepository,
    private val terminalRegistry: ServerTerminalRegistry,
    val toolCardResolver: ToolCardResolver,
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val messagePaging: MessagePaginationUseCase,
    private val tokenStatsTracker: TokenStatsTracker,
    private val httpClient: io.ktor.client.HttpClient,
    private val sseClient: SseClient,
    private val sessionStateService: SessionStateService,
    private val sessionFocusHolder: dev.leonardo.ocbeacon.service.SessionFocusHolder,
    private val appNotificationManager: dev.leonardo.ocbeacon.service.AppNotificationManager,
    private val toolSnapshotCache: dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache,
    private val pendingPromptRepository: dev.leonardo.ocbeacon.data.repository.PendingPromptRepository,
) : ViewModel() {

    // ============ 工具快照缓存（已提取到 ToolCacheDelegate） ============

    private val toolCacheDelegate = ToolCacheDelegate(toolSnapshotCache)

    fun cacheToolPart(part: dev.leonardo.ocbeacon.domain.model.Part.Tool) =
        toolCacheDelegate.cacheToolPart(part)

    /** revert 时的消息 ID 快照。用于区分
     *  旧消息（应隐藏）和新消息（应显示）。 */
    // 已移除：改用 m.id <= revertState.messageId（OpenCode 模式）

    // (lastRefreshTimeMs 已迁移到 SessionActionsDelegate — Phase 3 Task 6 — G 集群。)
    // (_isLoading / _isRefreshing / _error / _isSending / _messagesList / _rawMessagesList /
    //  _partsList / sseJob) 已迁移到 MessageDataDelegate（Phase 3 Task 5 — B 集群）。

    /** 为 ChatMessageList composable 暴露 chatRepository（工具进度、步骤进度、压缩状态）。 */
    val chatRepositoryExposed: ChatRepository get() = chatRepository

    private val serverUrl: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverUrl") ?: "", "UTF-8"
    )
    private val username: String = URLDecoder.decode(
        savedStateHandle.get<String>("username") ?: "", "UTF-8"
    )
    private val password: String = URLDecoder.decode(
        savedStateHandle.get<String>("password") ?: "", "UTF-8"
    )
    val serverName: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverName") ?: "", "UTF-8"
    )
    val serverId: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverId") ?: "", "UTF-8"
    )
    // ============ 会话生命周期 Delegate（Phase 3 Task 3 — C 集群） ============
    // 管理会话身份/目录/延迟创建 —— delegate 层的核心骨架。
    // sessionIdFlow 为 6 个 combine 管道提供数据；sessionDirectory/sessionLoaded
    // 通过其构造器 provider 为 TerminalDelegate 和 DraftInputDelegate 提供数据。
    private val sessionLifecycle = SessionLifecycleDelegate(
        manageSessionUseCase = manageSessionUseCase,
        sessionRepository = sessionRepository,
        serverId = serverId,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope,
        // 通过 VM 方法转发（而非直接引用 messageData）可避免
        // 属性初始化循环依赖：messageData 需要 sessionLifecycle
        // .sessionIdFlow，而 sessionLifecycle 的 lambda 会引用 messageData。
        // 方法调用在调用时延迟解析（两者都已完成初始化之后）。
        onMessagesNeedLoading = { loadMessagesForSession() },
        onStartObservingMessages = { startObservingMessages() },
    )
    /** 当前会话 ID —— [sessionLifecycle] 的门面。 */
    val sessionId: String get() = sessionLifecycle.sessionId

    /**
     * 当 ChatScreen 进入组合时调用。
     * 取消本会话的现有通知并注册活跃焦点，
     * 使后续 TaskComplete 通知被抑制。
     */
    fun onSessionFocused(notificationManager: android.app.NotificationManager) {
        appNotificationManager.cancelSessionNotifications(notificationManager, serverId, sessionId)
        sessionFocusHolder.setActiveFocus(serverId, sessionId)
    }

    /**
     * 当 ChatScreen 离开组合时调用。
     * 清除活跃焦点以恢复通知。
     */
    fun onSessionUnfocused() {
        sessionFocusHolder.setActiveFocus(null, null)
    }

    init {
        sessionStateService.setServerId(serverId)
    }

    // ============ 模型配置 Delegate（Phase 3 Task 4 — A 集群） ============
    // 管理 provider/agent/model/variant/command 选择及 modelConfigState
    // 解析管道（含自反馈副作用）。消费 sessionLifecycle 的 sessionIdFlow；
    // 为 DraftInputDelegate 草稿持久化和 sendParts() 暴露 selectedAgentValue/selectedVariantValue。
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
    fun selectModel(providerId: String, modelId: String) = modelConfig.selectModel(providerId, modelId)

    // ============ 消息数据 Delegate（Phase 3 Task 5 — B 集群） ============
    // 管理消息 SSE 观察、加载、分页、发送状态、工具展开，
    // 以及 messageListState/interactionState combine 管道。消费
    // sessionLifecycle 的 sessionIdFlow；暴露 intent 方法（onSendStarted/
    // onSendSuccess/onSendError）和 sseJob 管理（cancelSseJob/
    // startObservingMessages），使 sendParts/abort/revert 协调器留在
    // ViewModel 中但从不直接触碰此 delegate 的私有状态。
    private val messageData: MessageDataDelegate = MessageDataDelegate(
        manageSessionUseCase = manageSessionUseCase,
        managePermissionUseCase = managePermissionUseCase,
        chatRepository = chatRepository,
        messagePaging = messagePaging,
        sessionStateService = sessionStateService,
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        serverId = serverId,
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        scope = viewModelScope,
    )
    /** 消息列表状态 —— [messageData] 的门面。 */
    val messageListState: StateFlow<MessageListState> get() = messageData.messageListState
    /** 交互状态 —— [messageData] 的门面。 */
    val interactionState: StateFlow<InteractionState> get() = messageData.interactionState

    private val terminalDelegate = TerminalDelegate(
        terminalRegistry = terminalRegistry,
        settingsRepository = settingsRepository,
        serverId = serverId,
        serverUrl = serverUrl,
        username = username,
        password = password.ifEmpty { null },
        scope = viewModelScope,
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        sessionLoaded = sessionLifecycle.sessionLoaded,
    )
    val terminalTabs: StateFlow<List<TerminalTabUi>> get() = terminalDelegate.terminalTabs
    val activeTerminalTabId: StateFlow<String?> get() = terminalDelegate.activeTerminalTabId
    /** 活跃终端标签更新时递增 —— 观察它以触发重组。 */
    val terminalVersion: StateFlow<Long> get() = terminalDelegate.terminalVersion
    val terminalState: StateFlow<TerminalTabState> get() = terminalDelegate.terminalState
    val terminalFontSizeSp: StateFlow<Float> get() = terminalDelegate.terminalFontSizeSp
    val terminalEmulator: org.connectbot.terminal.TerminalEmulator get() = terminalDelegate.terminalEmulator
    val terminalCursorKeysAppMode: Boolean get() = terminalDelegate.terminalCursorKeysAppMode

    // ============ 草稿输入 Delegate（Phase 3 Task 2 — D 集群） ============
    private val draftDelegate = DraftInputDelegate(
        draftUseCase = draftUseCase,
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
    val draftAttachmentUris: StateFlow<List<String>> get() = draftDelegate.draftAttachmentUris
    val confirmedFilePaths: StateFlow<Set<String>> get() = draftDelegate.confirmedFilePaths

    // ============ 会话操作 Delegate（Phase 3 Task 6 — G 集群） ============
    // 管理 24 个无状态 REST 操作 —— 无私有 StateFlow。通过 provider 读取其他
    // delegate 的状态并委托给 UseCase/Repository。跨 delegate 协调器
    //（sendParts、revertMessage、abortSession）留在本 ViewModel 中。
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
    )

    // ============ 设置（暴露给 ChatScreen） ============
    val chatFontSize = settingsRepository.getSettingsFlow().map { it.chatFontSize }.stateIn(
        viewModelScope, WhileSubscribed5s, "medium"
    )
    val chatDensity = settingsRepository.getSettingsFlow().map { it.chatDensity }.stateIn(
        viewModelScope, WhileSubscribed5s, "normal"
    )
    val codeWordWrap = settingsRepository.getSettingsFlow().map { it.codeWordWrap }.stateIn(
        viewModelScope, WhileSubscribed5s, false
    )
    val confirmBeforeSend = settingsRepository.getSettingsFlow().map { it.confirmBeforeSend }.stateIn(
        viewModelScope, WhileSubscribed5s, false
    )
    val compactMessages = settingsRepository.getSettingsFlow().map { it.compactMessages }.stateIn(
        viewModelScope, WhileSubscribed5s, false
    )
    val collapseTools = settingsRepository.getSettingsFlow().map { it.collapseTools }.stateIn(
        viewModelScope, WhileSubscribed5s, false
    )
    // ============ 工具展开 / 分页（已委托 —— Phase 3 Task 5） ============
    val toolExpandedStates: StateFlow<Map<String, Boolean>> get() = messageData.toolExpandedStates

    fun toggleToolExpanded(toolId: String, defaultExpanded: Boolean = false) =
        messageData.toggleToolExpanded(toolId, defaultExpanded)

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean =
        messageData.isToolExpanded(toolId, autoExpand)

    // ============ 滚动状态 ============
    // LazyListState 存放在 ViewModel 中，以在配置变更和重组时保留
    //（基于 key 的位置跟踪，用于条件项）。
    val listState = androidx.compose.foundation.lazy.LazyListState()

    val expandReasoning = settingsRepository.getSettingsFlow().map { it.expandReasoning }.stateIn(
        viewModelScope, WhileSubscribed5s, false
    )
    val showTurnDividers = settingsRepository.getSettingsFlow().map { it.showTurnDividers }.stateIn(
        viewModelScope, WhileSubscribed5s, true
    )
    val hapticFeedback = settingsRepository.getSettingsFlow().map { it.hapticFeedback }.stateIn(
        viewModelScope, WhileSubscribed5s, true
    )
    val keepScreenOn = settingsRepository.getSettingsFlow().map { it.keepScreenOn }.stateIn(
        viewModelScope, WhileSubscribed5s, false
    )
    val compressImageAttachments = settingsRepository.getSettingsFlow().map { it.compressImageAttachments }.stateIn(
        viewModelScope, WhileSubscribed5s, true
    )
    val imageAttachmentMaxLongSide = settingsRepository.getSettingsFlow().map { it.imageAttachmentMaxLongSide }.stateIn(
        viewModelScope, WhileSubscribed5s, 1440
    )
    val imageAttachmentWebpQuality = settingsRepository.getSettingsFlow().map { it.imageAttachmentWebpQuality }.stateIn(
        viewModelScope, WhileSubscribed5s, 60
    )
    /** 以 StateFlow 暴露恢复的草稿，供 ChatScreen 消费。 */
    val restoredDraftState: StateFlow<RevertedDraftPayload?> get() = draftDelegate.restoredDraftState

    // 注意：Legacy uiState 声明在以下 5 个拆分 StateFlow 之后（需要前向引用）。

    // ============ 拆分状态 Flow（独立 combine，用于细粒度重组） ============

    // messageListState —— 已迁移到 MessageDataDelegate（Phase 3 Task 5 — B 集群）。

    /**
     * 会话元数据 —— 会话信息更新时变化（标题、状态、agent）。
     * 包含 [SessionLifecycleDelegate.sessionIdFlow] 作为数据源，使延迟会话创建触发立即重计算。
     * 会话状态来自 [SessionStateService.statusFlow]（FSM 驱动），
     * 是 busy/idle/activity 状态的单一真相源。
     */
    val sessionMetaState: StateFlow<SessionMetaState> = combine(
        sessionLifecycle.sessionIdFlow,
        sessionRepository.getSessionsFlow(serverId),
        sessionStateService.statusFlow,
        sessionRepository.getCurrentAgentFlow(serverId),
        sessionRepository.getCurrentModelFlow(serverId),
        sessionStateService.activityFlow,
    ) { args ->
        val sid = args[0] as String
        @Suppress("UNCHECKED_CAST")
        val allSessions = args[1] as List<Session>
        @Suppress("UNCHECKED_CAST")
        val statuses = args[2] as Map<String, SessionStatus>
        @Suppress("UNCHECKED_CAST")
        val currentAgentMap = args[3] as Map<String, String>
        @Suppress("UNCHECKED_CAST")
        val currentModelMap = args[4] as Map<String, Pair<String, String>>
        @Suppress("UNCHECKED_CAST")
        val activities = args[5] as Map<String, SessionActivity?>

        val session = allSessions.find { it.id == sid }
        val sessionStatus = statuses[sid] ?: SessionStatus.Idle
        val isStreaming = activities[sid] is SessionActivity.Streaming

        SessionMetaState(
            sessionTitle = session?.title ?: "",
            serverName = serverName,
            sessionStatus = sessionStatus,
            revert = session?.revert,
            sessionParentId = session?.parentId,
            sessionAgent = session?.agent,
            currentAgentName = currentAgentMap[sid],
            currentModelId = currentModelMap[sid]?.second,
            shareUrl = session?.share?.url,
            isStreaming = isStreaming,
        )
    }.stateIn(
        viewModelScope,
        WhileSubscribed5s,
        SessionMetaState()
    )

    // interactionState —— 已迁移到 MessageDataDelegate（Phase 3 Task 5 — B 集群）。

    /**
     * Token 使用统计 —— 每次流式 token 更新时变化。
     * 直接映射自 [TokenStatsTracker.stats]。
     */
    val tokenStatsState: StateFlow<TokenStatsState> = tokenStatsTracker.stats.map { stats ->
        TokenStatsState(
            totalCost = stats.totalCost,
            totalInputTokens = stats.totalInputTokens,
            totalOutputTokens = stats.totalOutputTokens,
            totalReasoningTokens = stats.totalReasoningTokens,
            totalCacheReadTokens = stats.totalCacheReadTokens,
            totalCacheWriteTokens = stats.totalCacheWriteTokens,
            contextWindow = stats.contextWindow,
            lastContextTokens = stats.lastContextTokens,
        )
    }.stateIn(
        viewModelScope,
        WhileSubscribed5s,
        TokenStatsState()
    )

    /**
     * 会话目录 —— 当前聊天的工作目录，用于顶栏副标题。
     * 会话尚未解析或无目录时为空。
     */
    val directoryState: StateFlow<String> = sessionLifecycle.sessionIdFlow.flatMapLatest { sid ->
        sessionRepository.getSessionsFlow(serverId).map { sessions ->
            sessions.find { it.id == sid }?.directory.orEmpty()
        }
    }.stateIn(
        viewModelScope,
        WhileSubscribed5s,
        ""
    )

    /**
     * 聚合上下文详情 —— 已提取到 ContextDetailDelegate。
     */
    private val contextDetailDelegate = ContextDetailDelegate(
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        messageListState = messageListState,
        tokenStatsState = tokenStatsState,
        sessionsFlow = sessionRepository.getSessionsFlow(serverId),
        modelConfigContextWindow = modelConfigState.map { it.contextWindow },
        scope = viewModelScope,
    )
    val contextDetailState: StateFlow<ContextDetailState> get() = contextDetailDelegate.state

    /**
     * Legacy uiState，用于向后兼容（测试）。
     * 从 5 个拆分 StateFlow 轻量组装 —— 无业务逻辑。
     */
    // TODO：将位置参数 args[] 替换为 data class 或结构化 combine 源以提升类型安全
    val uiState: StateFlow<ChatUiState> = combine(
        messageListState,
        sessionMetaState,
        interactionState,
        tokenStatsState,
        modelConfigState,
        draftDelegate.restoredDraftState,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val msgList = args[0] as MessageListState
        @Suppress("UNCHECKED_CAST")
        val sessMeta = args[1] as SessionMetaState
        @Suppress("UNCHECKED_CAST")
        val inter = args[2] as InteractionState
        @Suppress("UNCHECKED_CAST")
        val tokens = args[3] as TokenStatsState
        @Suppress("UNCHECKED_CAST")
        val modelCfg = args[4] as ModelConfigState
        val restoredDraft = args[5] as RevertedDraftPayload?
        ChatUiState(
            sessionTitle = sessMeta.sessionTitle,
            serverName = sessMeta.serverName,
            messages = msgList.messages,
            messageCount = msgList.messageCount,
            revert = sessMeta.revert,
            sessionStatus = sessMeta.sessionStatus,
            pendingPermissions = inter.pendingPermissions,
            pendingQuestions = inter.pendingQuestions,
            isLoading = inter.isLoading,
            error = inter.error,
            providers = modelCfg.providers,
            hasServerModelCatalog = modelCfg.hasServerModelCatalog,
            defaultModels = modelCfg.defaultModels,
            selectedProviderId = modelCfg.selectedProviderId,
            selectedModelId = modelCfg.selectedModelId,
            totalCost = tokens.totalCost,
            totalInputTokens = tokens.totalInputTokens,
            totalOutputTokens = tokens.totalOutputTokens,
            totalReasoningTokens = tokens.totalReasoningTokens,
            totalCacheReadTokens = tokens.totalCacheReadTokens,
            totalCacheWriteTokens = tokens.totalCacheWriteTokens,
            agents = modelCfg.agents,
            selectedAgent = modelCfg.selectedAgent,
            variantNames = modelCfg.variantNames,
            selectedVariant = modelCfg.selectedVariant,
            commands = modelCfg.commands,
            hasOlderMessages = msgList.hasOlderMessages,
            isLoadingOlder = msgList.isLoadingOlder,
            shareUrl = sessMeta.shareUrl,
            contextWindow = modelCfg.contextWindow,
            lastContextTokens = tokens.lastContextTokens,
            queuedMessageIds = msgList.queuedMessageIds,
            sessionParentId = sessMeta.sessionParentId,
            sessionAgent = sessMeta.sessionAgent,
            currentAgentName = sessMeta.currentAgentName,
            currentModelId = sessMeta.currentModelId,
            toolExpandedStates = msgList.toolExpandedStates,
            pendingMessageIds = msgList.pendingMessageIds,
            restoredDraft = restoredDraft,
        )
    }.stateIn(
        viewModelScope,
        WhileSubscribed5s,
        ChatUiState()
    )

    init {
        val isNewSession = sessionId.isEmpty()

        // 重置上一会话的 token 统计（TokenStatsTracker 是 @Singleton，跨会话共享）
        tokenStatsTracker.reset()

        // 恢复已持久化的 pending prompt（在发送中途存活应用重启）。
        // 与已加载消息的对账在下面的 collect 循环中进行。
        val restoredPending = pendingPromptRepository.getForSession(sessionId)
        if (restoredPending.isNotEmpty()) {
            messageData.restorePendingPrompts(restoredPending)
        }

        // 观察消息并更新 token 统计跟踪器。
        // Token 值使用最后一条 assistant 消息的 token（代表当前上下文大小）。
        // Cost 在所有 API 调用中累积。
        viewModelScope.launch {
            messageData.messagesList.collect { messages ->
                val assistantMessages = messages.filterIsInstance<Message.Assistant>()

                // Cost 在所有 API 调用中累积
                val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }

                // Token 使用 = 最后一次调用的值（非累积，与 OpenCode 行为一致）
                val lastWithTokens = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
                val lastTokens = lastWithTokens?.tokens
                val totalInputTokens = lastTokens?.input ?: 0
                val totalOutputTokens = lastTokens?.output ?: 0
                val totalReasoningTokens = lastTokens?.reasoning ?: 0
                val totalCacheReadTokens = lastTokens?.cache?.read ?: 0
                val totalCacheWriteTokens = lastTokens?.cache?.write ?: 0

                // 圆形进度条的上下文 token
                val lastContextTokens = lastTokens?.let { t ->
                    t.input + t.output + t.reasoning + t.cache.read + t.cache.write
                } ?: 0

                tokenStatsTracker.update {
                    copy(
                        totalCost = totalCost,
                        totalInputTokens = totalInputTokens,
                        totalOutputTokens = totalOutputTokens,
                        totalReasoningTokens = totalReasoningTokens,
                        totalCacheReadTokens = totalCacheReadTokens,
                        totalCacheWriteTokens = totalCacheWriteTokens,
                        lastContextTokens = lastContextTokens,
                    )
                }

                // 将 pending prompt 与权威消息列表对账。
                // 检测服务器从未回显的发送（如重启中丢失），
                // 条件是它们既超过 [PENDING_RECONCILE_MIN_AGE_MS] 的年龄，
                // 又被"覆盖"（服务器已投递了发送时间之后的消息）。
                val pendingSnapshot = messageData.pendingOptimisticSnapshot()
                if (pendingSnapshot.isNotEmpty()) {
                    val pendingRecords = pendingSnapshot.map { om ->
                        dev.leonardo.ocbeacon.data.repository.PendingPromptRecord(
                            messageId = om.pendingId,
                            sessionId = sessionId,
                            parts = emptyList(),
                            createdAt = om.message.time.created,
                        )
                    }
                    val missing = dev.leonardo.ocbeacon.data.repository.missingPendingPromptIds(
                        pending = pendingRecords,
                        authoritative = messages,
                        now = System.currentTimeMillis(),
                        minimumAgeMs = PENDING_RECONCILE_MIN_AGE_MS,
                    )
                    missing.forEach { id ->
                        messageData.markPendingAsFailed(id)
                        pendingPromptRepository.remove(id)
                    }
                }
            }
        }

        // 从磁盘恢复草稿 —— 将恢复的 agent/variant 应用到模型配置
        if (!isNewSession) {
            val draft = draftDelegate.restorePersistedDraft()
            if (draft != null) {
                modelConfig.applyDraftRestore(draft.selectedAgent, draft.selectedVariant)
            }
        }

        // 从内存缓存恢复模型选择（会话切换时存活，应用重启时清除）
        if (!isNewSession) {
            modelConfig.restoreModelFromCache()
        }

        modelConfig.observeHiddenModels()

        // 从设置加载初始消息数量，然后加载数据
        if (!isNewSession) {
            viewModelScope.launch {
                try { sessionLifecycle.loadSession() } catch (e: Exception) { Log.e(TAG, "loadSession failed", e) }
                try { messageData.loadMessages() } catch (e: Exception) { Log.e(TAG, "loadMessages failed", e) }
                try { messageData.loadPendingQuestions() } catch (e: Exception) { Log.e(TAG, "loadPendingQuestions failed", e) }
                try { messageData.loadPendingPermissions() } catch (e: Exception) { Log.e(TAG, "loadPendingPermissions failed", e) }
            }
        } else {
            // 新会话：从路由参数设置目录，跳过加载
            sessionLifecycle.initForNewSession()
            // 新会话无需加载 —— 立即标记加载完成
            messageData.markLoaded()
        }
        modelConfig.loadProviders()
        modelConfig.loadAgents()
        modelConfig.loadCommands()
    }

    // loadMessagesForSession / startObservingMessages —— 已迁移到 MessageDataDelegate
    //（Phase 3 Task 5 — B 集群）。这些轻量转发器通过 sessionLifecycle 回调调用；
    // 它们作为 VM 方法存在（而非将 messageData 引用内联到 lambda 中）是为了
    // 避免属性初始化循环依赖。
    private suspend fun loadMessagesForSession() = messageData.loadMessagesForSession()
    private fun startObservingMessages() = messageData.startObservingMessages()

    /**
     * 通过 V1 API 加载消息以解析 modelConfigState（从历史中解析模型/agent）。
     * [messageData] 的门面。
     */
    fun loadMessages() = messageData.loadMessages()

    /**
     * 刷新会话数据 —— [sessionActions] 的门面。
     */
    fun refreshSession() = sessionActions.refreshSession()

    /**
     * 仅在距上次刷新足够时间后才刷新会话。
     * [sessionActions] 的门面。
     */
    fun refreshIfNeeded() = sessionActions.refreshIfNeeded()

    // refreshMessages —— 已迁移到 MessageDataDelegate（Phase 3 Task 5 — B 集群）。

    /**
     * 查询 OpenCode 服务器的实际会话状态，纠正
     * 因丢失 SSE 事件导致的 UI 状态偏移。[sessionActions] 的门面。
     */
    fun syncSessionStatus() = sessionActions.syncSessionStatus()

    // refreshAndSync —— 已迁移到 SessionActionsDelegate（Phase 3 Task 6 — G 集群）。

    /** 加载更早的消息 —— [messageData] 的门面。 */
    fun loadOlderMessages() = messageData.loadOlderMessages()

    // loadPendingQuestions —— 已迁移到 MessageDataDelegate（Phase 3 Task 5 — B 集群）。

    // loadPendingPermissions —— 已迁移到 MessageDataDelegate（Phase 3 Task 5 — B 集群）。

    // 模型/agent/provider/variant/command 选择 + modelConfigState 解析
    // 已委托给 ModelConfigDelegate（Phase 3 Task 4 — A 集群）。

    // ============ @ 文件提及搜索（已委托 —— Phase 3 Task 2） ============
    val fileSearchResults: StateFlow<List<String>> get() = draftDelegate.fileSearchResults

    fun searchFilesForMention(query: String) = draftDelegate.searchFilesForMention(query)
    fun confirmFilePath(path: String) = draftDelegate.confirmFilePath(path)
    fun removeFilePath(path: String) = draftDelegate.removeFilePath(path)
    fun clearFileSearch() = draftDelegate.clearFileSearch()
    fun clearConfirmedPaths() = draftDelegate.clearConfirmedPaths()

    // ============ 草稿管理（已委托） ============

    fun updateDraftText(text: String) = draftDelegate.updateDraftText(text)
    fun addDraftAttachment(uri: String) = draftDelegate.addDraftAttachment(uri)
    fun removeDraftAttachment(index: Int) = draftDelegate.removeDraftAttachment(index)
    fun clearDraft() = draftDelegate.clearDraft()
    fun consumeRestoredDraft() = draftDelegate.consumeRestoredDraft()

    override fun onCleared() {
        messageData.cancelSseJob()
        closeTerminalSession()
        super.onCleared()
        draftDelegate.saveDraft()
    }

    /** 获取会话目录用于构建 file:// URL */
    fun getSessionDirectory(): String? = sessionLifecycle.sessionDirectory

    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        val parts = mutableListOf<PromptPart>()
        if (text.isNotBlank()) {
            parts.add(PromptPart(type = "text", text = text))
        }
        parts.addAll(attachments)
        sendParts(parts)
    }

    /** 发送预构建的 prompt parts（当 @ 文件提及需要结构化 parts 时使用）。 */
    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>) {
        val parts = promptParts + attachments
        if (parts.isEmpty()) return
        sendParts(parts)
    }

    /**
     * 安排延迟的 REST 刷新以获取更新后的会话标题。
     * 仅当当前标题看起来像默认占位符时才刷新
     *（null、空或匹配 "New session - ..." 模式）。
     */
    private fun refreshSessionTitleDelayed(sid: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(8_000) // 等待服务器异步标题生成
            try {
                val refreshed = manageSessionUseCase.getSession(serverId, sid)
                val currentSession = chatRepository.getSessionsSnapshot().find { it.id == sid }
                val currentTitle = currentSession?.title
                // 仅在标题实际变化时更新（如果 SSE 已投递则跳过）
                if (refreshed.title != currentTitle) {
                    val msg = "[Title] REST fallback: title updated from '$currentTitle' to '${refreshed.title}'"
                    Log.i(TAG, msg)
                    appendDiagnosticLog(msg)
                    sessionRepository.setSessions(serverId, listOf(refreshed))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh session title for $sid: ${e.message}")
            }
        }
    }

    private fun sendParts(parts: List<PromptPart>) {
        // RS-007 修复：防止快速双击。_isSending 由 onSendStarted 同步设置，
        // 但 Compose 重组（禁用按钮）有 1 帧延迟。此检查消除了竞态窗口。
        if (messageData.isSendingValue) {
            if (BuildConfig.DEBUG) Log.d(TAG, "sendParts: already sending, ignoring duplicate")
            return
        }
        scrollSignal.requestScrollToTop()
        val pendingId = "pending-${java.util.UUID.randomUUID()}"

        // 创建乐观消息以立即显示
        val now = System.currentTimeMillis()
        val currentSid = sessionLifecycle.sessionId
        val optimisticMsg = Message.User(
            id = pendingId,
            sessionId = currentSid,
            time = TimeInfo(created = now),
        )
        val optimisticParts = parts.mapIndexed { index, pp ->
            Part.Text(
                id = "${pendingId}-part-$index",
                sessionId = currentSid,
                messageId = pendingId,
                text = pp.text ?: "",
            )
        }
        messageData.onSendStarted(pendingId, optimisticMsg, optimisticParts)
        // 持久化乐观发送，使其在发送中途应用被杀时存活。
        // 下次启动时的对账会检测服务器从未回显的发送。
        pendingPromptRepository.save(
            dev.leonardo.ocbeacon.data.repository.PendingPromptRecord(
                messageId = pendingId,
                sessionId = currentSid,
                parts = parts,
                createdAt = now,
            )
        )
        viewModelScope.launch {
            try {
                val currentSessionId = sessionLifecycle.ensureSession()
                sessionStateService.onClientSendParts(currentSessionId)
                // P5-5：从 modelConfigState（已解析的有效值）读取，而非
                // 原始 _selectedProviderId（在新会话首次发送时可能为 null）。
                val modelCfg = modelConfigState.value
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId,
                        modelId = modelCfg.selectedModelId
                    )
                } else null

                // 发送前清除 revert —— message.removed SSE 事件已从缓存中
                // 清理旧消息，因此不会闪烁。
                chatRepository.clearRevert(currentSessionId)

                sendMessageUseCase.sendPrompt(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    parts = parts,
                    model = model,
                    agent = modelConfigState.value.selectedAgent,
                    variant = modelConfig.selectedVariantValue,
                    directory = sessionLifecycle.sessionDirectory
                )
                messageData.onSendSuccess(pendingId)
                pendingPromptRepository.remove(pendingId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $currentSessionId (${parts.size} parts)")
                refreshSessionTitleDelayed(currentSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                // 从失败的发送恢复草稿
                val failedText = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                if (failedText.isNotBlank()) {
                    draftDelegate.setRestoredDraft(RevertedDraftPayload(text = failedText))
                }
                messageData.onSendError(e.message ?: "Failed to send message", pendingId)
                pendingPromptRepository.remove(pendingId)
            }
        }
    }

    /** 通过 pending ID 重试发送失败的乐观消息。 */
    fun retrySendMessage(pendingId: String) {
        val pending = messageData.getPendingMessage(pendingId) ?: return
        val parts = pending.parts.mapNotNull { part ->
            (part as? Part.Text)?.let { PromptPart(type = "text", text = it.text) }
        }
        messageData.removePendingMessage(pendingId)
        sendParts(parts)
    }

    /**
     * 回复权限请求。[sessionActions] 的门面。
     * @param requestId 权限请求 ID
     * @param reply 取值之一："once"、"always"、"reject"
     */
    fun replyToPermission(requestId: String, reply: String) =
        sessionActions.replyToPermission(requestId, reply)

    /** 保存权限自动批准规则。[sessionActions] 的门面。 */
    fun savePermissionRule(event: dev.leonardo.ocbeacon.domain.model.SseEvent.PermissionAsked, directory: String) =
        sessionActions.savePermissionRule(event, directory)

    /**
     * 中止当前会话 —— 协调器。
     * 将 REST abort + markIdle 委托给 [sessionActions]，然后处理
     * SSE job 的取消/重启（B↔C↔G 编排）。
     */
    fun abortSession() {
        // RS-006 修复：在更新 FSM 之前取消 SSE job。旧顺序
        //（先 FSM 后取消）留了一个窗口，使飞行中的 SSE 事件
        // 在 Idle 状态下到达，触发 isSuspicious 状态转移和
        // 不必要的 REST 验证调用。
        messageData.cancelSseJob()
        sessionStateService.onClientAbort(sessionId)
        viewModelScope.launch {
            try {
                sessionActions.abortSession()
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                // P5-2：重启 sseJob 以避免 _rawMessagesList 冻结。
                runCatching { messageData.startObservingMessages() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
            }
        }
    }

    /**
     * 回复问题请求。[sessionActions] 的门面。
     * @param requestId 问题请求 ID
     * @param answers 每个问题的答案（每个问题的已选标签列表）
     */
    fun replyToQuestion(requestId: String, answers: List<List<String>>) =
        sessionActions.replyToQuestion(requestId, answers)

    /**
     * 拒绝问题请求。[sessionActions] 的门面。
     */
    fun rejectQuestion(requestId: String) =
        sessionActions.rejectQuestion(requestId)

    // ============ 斜杠命令操作（已委托 —— Phase 3 Task 6） ============

    /** 分享当前会话。[sessionActions] 的门面。 */
    fun shareSession(onResult: (String?) -> Unit) =
        sessionActions.shareSession(onResult)

    /** 取消分享当前会话。[sessionActions] 的门面。 */
    fun unshareSession(onResult: (Boolean) -> Unit) =
        sessionActions.unshareSession(onResult)

    /** 压缩（摘要）当前会话。[sessionActions] 的门面。 */
    fun compactSession(onResult: (Boolean) -> Unit) =
        sessionActions.compactSession(onResult)

    /**
     * 将会话导出为 JSON 到文件 URI。[sessionActions] 的门面。
     */
    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) =
        sessionActions.exportSession(context, uri, onResult)

    /** 撤销会话中最后一条用户消息，将其文本恢复到输入框。[sessionActions] 的门面。 */
    fun undoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.undoMessage(onResult)

    /** 通过 ID revert 到特定用户消息，可选地将其文本恢复到输入框。
     *  协调器（B↔D↔G 编排）：暂停 busy 会话，通过 undoRedoUseCase revert，
     *  重连 SSE，恢复草稿。 */
    fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // 暂停：如果会话处于 busy 状态（AI 正在生成），先 abort 再 revert。
                // 与 OpenCode WebUI 相同的模式：halt(sessionID).then(() => revert(input))
                val currentStatus = sessionStateService.statusFlow.value[sessionId]
                val wasBusy = currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry

                // RS-008 修复：在取消 SSE job 之前设置 revert 过滤器。
                // 旧顺序（cancel → revert REST → setRevert）留了一个窗口，
                // 使缓冲的 SSE 事件在 revert 过滤器未激活时排入消息存储，
                // 导致 revert 后消息闪烁。
                chatRepository.setRevert(sessionId, messageId)

                if (wasBusy) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Revert：暂停 busy 会话 $sessionId")
                    sessionStateService.onClientAbort(sessionId)
                    messageData.cancelSseJob()
                    runCatching { sessionRepository.abort(serverId, sessionId, sessionLifecycle.sessionDirectory) }
                }

                undoRedoUseCase.revertSession(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")

                // 现在重连 SSE —— 旧消息将被 revert 状态过滤。
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
                Log.e(TAG, "Failed to revert to message $messageId", e)
                onResult(false)
            }
        }
    }

    // extractRevertedDraft —— 已迁移到 SessionActionsDelegate（Phase 3 Task 6 — G 集群）。
    // restoreRevertedDraft —— 已内联到 draftDelegate.restoreRevertedDraft()（Phase 3 Task 6）。

    /** Redo 最后一次撤销的消息。[sessionActions] 的门面。 */
    fun redoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.redoMessage(onResult)

    /** 从当前会话删除一条消息。[sessionActions] 的门面。 */
    fun deleteMessage(messageId: String, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessage(messageId, onResult)

    /** 通过索引从消息中删除特定 part。[sessionActions] 的门面。 */
    fun deleteMessagePart(messageId: String, partIndex: Int, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessagePart(messageId, partIndex, onResult)

    /**
     * 当收到 SessionUpdated SSE 事件时调用。[sessionActions] 的门面。
     */
    fun onSessionUpdated(session: Session) =
        sessionActions.onSessionUpdated(session)

    /** Fork 当前会话。[sessionActions] 的门面。 */
    fun forkSession(onResult: (Session?) -> Unit) =
        sessionActions.forkSession(onResult)

    /** 重命名当前会话。[sessionActions] 的门面。 */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) =
        sessionActions.renameSession(title, onResult)

    /** 执行服务端命令。[sessionActions] 的门面。 */
    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) =
        sessionActions.executeCommand(command, arguments, onResult)

    /** 在当前会话中执行 shell 命令。[sessionActions] 的门面。 */
    fun runShellCommand(command: String, onResult: (Boolean) -> Unit) =
        sessionActions.runShellCommand(command, onResult)

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

    /** 导航到其他会话的连接参数。 */
    fun getConnectionParams(): ConnectionParams = ConnectionParams(
        serverUrl = serverUrl,
        username = username,
        password = password,
        serverName = serverName,
        serverId = serverId
    )

    /** 获取最后一条 assistant 消息文本以供复制。[sessionActions] 的门面。 */
    fun getLastAssistantText(): String? = sessionActions.getLastAssistantText()

    /** 追加诊断日志行，用于权限/问题调试。 */
    private fun appendDiagnosticLog(message: String) {
        // 仅输出到 logcat；文件写入在 Android 11+ 上需要 MediaStore
        Log.i(TAG, message)
    }

    companion object
}

/** 保存服务器连接信息，用于导航。 */
data class ConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
)

