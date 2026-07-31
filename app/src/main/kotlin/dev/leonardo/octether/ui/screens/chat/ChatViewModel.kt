package dev.leonardo.octether.ui.screens.chat

import android.util.Log
import androidx.compose.runtime.Immutable
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.ui.WhileSubscribed5s
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.octether.domain.model.AgentInfo
import dev.leonardo.octether.domain.model.CommandInfo
import dev.leonardo.octether.domain.model.ModelSelection
import dev.leonardo.octether.domain.model.PromptPart
import dev.leonardo.octether.domain.model.ProviderCatalog
import dev.leonardo.octether.data.repository.ServerTerminalRegistry
import dev.leonardo.octether.data.repository.SessionStateService
import dev.leonardo.octether.ui.screens.chat.tools.ToolCardResolver
import dev.leonardo.octether.ui.screens.chat.terminal.TerminalTabState
import dev.leonardo.octether.ui.screens.chat.util.ContextBreakdown
import dev.leonardo.octether.ui.screens.chat.util.ContextDetailState
import dev.leonardo.octether.ui.screens.chat.util.MessageCount
import dev.leonardo.octether.ui.screens.chat.util.ProviderModel
import dev.leonardo.octether.ui.screens.chat.util.SessionTimestamps
import dev.leonardo.octether.ui.screens.chat.util.cacheHitRate
import dev.leonardo.octether.ui.screens.chat.util.countMessages
import dev.leonardo.octether.ui.screens.chat.util.estimateContextBreakdown
import dev.leonardo.octether.domain.model.*
import dev.leonardo.octether.domain.repository.ChatRepository
import dev.leonardo.octether.domain.repository.SessionRepository
import dev.leonardo.octether.domain.repository.SettingsRepository
import dev.leonardo.octether.domain.tracker.TokenStatsTracker
import dev.leonardo.octether.domain.usecase.*
import dev.leonardo.octether.data.api.SseClient
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
 * Split state: message list and pagination data.
 * Changes on every new message/part update — highest frequency.
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
 * Split state: session metadata.
 * Changes when session info is updated (title, status, agent).
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
    /** True when FSM activity for this session is Streaming (gates reasoning timer + streamingMsgId). */
    val isStreaming: Boolean = false,
)

/**
 * Split state: user interaction state.
 * Changes on loading/sending/error and pending permissions/questions.
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
 * Split state: token usage statistics.
 * Changes on every streaming token update — high frequency during generation.
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
 * Split state: model/agent configuration and resolved selections.
 * Changes on provider loads, user selection, or message history updates (for auto-resolution).
 * Contains side-effect logic for model/agent resolution from message history.
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
    /** Context window size — resolved from token stats with provider fallback. */
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
    /** Session totals computed from all loaded assistant messages (not session.tokens which may be per-call). */
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
    /** True when there are older messages on the server that haven't been loaded yet. */
    val hasOlderMessages: Boolean = false,
    /** True while a "load older" request is in flight. */
    val isLoadingOlder: Boolean = false,
    /** Share URL if session is shared, null otherwise. */
    val shareUrl: String? = null,
    /** Context window size of the current model (0 if unknown). */
    val contextWindow: Int = 0,
    /** Total tokens from the last assistant message with output > 0 (current context usage). */
    val lastContextTokens: Int = 0,
    /** IDs of user messages that are queued (sent while assistant is still generating). */
    val queuedMessageIds: Set<String> = emptySet(),
    /** Parent session ID — non-null when this session is a child/sub-agent session. */
    val sessionParentId: String? = null,
    /** Agent name for this session (e.g. "explore", "general"). Populated for sub-agent sessions. */
    val sessionAgent: String? = null,
    /** Persisted expand/collapse state for tool cards, keyed by Part.Tool.id or Part.Patch.id. */
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val currentAgentName: String? = null,
    val currentModelId: String? = null,
    /** User messages optimistically inserted before API confirmation, keyed by messageId. */
    val pendingMessageIds: Set<String> = emptySet(),
    /** Draft restored after a failed send. Non-null only once until consumed. */
    val restoredDraft: RevertedDraftPayload? = null,
)

data class RevertedDraftPayload(
    val text: String,
    val attachmentUris: List<String> = emptyList(),
)

/**
 * A flattened chat message for the UI.
 * Combines Message info with its parts.
 */
data class ChatMessage(
    val message: Message,
    val parts: List<Part>
) {
    val isUser: Boolean get() = message is Message.User
    val isAssistant: Boolean get() = message is Message.Assistant
}

/** Grace period (ms) before a covered pending prompt is declared lost during reconciliation. */
private const val PENDING_RECONCILE_MIN_AGE_MS = 60_000L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scrollSignal: dev.leonardo.octether.ui.screens.sessions.SessionScrollSignal,
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
    private val sessionFocusHolder: dev.leonardo.octether.service.SessionFocusHolder,
    private val appNotificationManager: dev.leonardo.octether.service.AppNotificationManager,
    private val toolSnapshotCache: dev.leonardo.octether.domain.repository.ToolSnapshotCache,
    private val pendingPromptRepository: dev.leonardo.octether.data.repository.PendingPromptRepository,
) : ViewModel() {

    // ============ Tool snapshot cache (extracted to ToolCacheDelegate) ============

    private val toolCacheDelegate = ToolCacheDelegate(toolSnapshotCache)

    fun cacheToolPart(part: dev.leonardo.octether.domain.model.Part.Tool) =
        toolCacheDelegate.cacheToolPart(part)

    /** Snapshot of message IDs at the time of revert. Used to distinguish
     *  old messages (should be hidden) from new messages (should be shown). */
    // Removed: using m.id <= revertState.messageId instead (OpenCode pattern)

    // (lastRefreshTimeMs migrated to SessionActionsDelegate — Phase 3 Task 6 — G cluster.)
    // (_isLoading / _isRefreshing / _error / _isSending / _messagesList / _rawMessagesList /
    //  _partsList / sseJob) migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    /** Expose chatRepository for ChatMessageList composable (tool progress, step progress, compaction state). */
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
    // ============ Session Lifecycle Delegate (Phase 3 Task 3 — C cluster) ============
    // Owns session identity/directory/lazy-creation — the spine of the delegate layer.
    // sessionIdFlow feeds 6 combine pipelines; sessionDirectory/sessionLoaded feed
    // TerminalDelegate & DraftInputDelegate via their constructor providers.
    private val sessionLifecycle = SessionLifecycleDelegate(
        manageSessionUseCase = manageSessionUseCase,
        sessionRepository = sessionRepository,
        serverId = serverId,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope,
        // Forwarding via VM methods (not direct messageData refs) avoids a
        // property-init circular dependency: messageData needs sessionLifecycle
        // .sessionIdFlow while sessionLifecycle's lambdas would reference messageData.
        // Method calls resolve lazily at invocation time (well after both are init).
        onMessagesNeedLoading = { loadMessagesForSession() },
        onStartObservingMessages = { startObservingMessages() },
    )
    /** Current session id — facade over [sessionLifecycle]. */
    val sessionId: String get() = sessionLifecycle.sessionId

    /**
     * Called when ChatScreen enters composition.
     * Cancels existing notifications for this session and registers active focus
     * so future TaskComplete notifications are suppressed.
     */
    fun onSessionFocused(notificationManager: android.app.NotificationManager) {
        appNotificationManager.cancelSessionNotifications(notificationManager, serverId, sessionId)
        sessionFocusHolder.setActiveFocus(serverId, sessionId)
    }

    /**
     * Called when ChatScreen leaves composition.
     * Clears active focus so notifications resume.
     */
    fun onSessionUnfocused() {
        sessionFocusHolder.setActiveFocus(null, null)
    }

    init {
        sessionStateService.setServerId(serverId)
    }

    // ============ Model Config Delegate (Phase 3 Task 4 — A cluster) ============
    // Owns provider/agent/model/variant/command selection and the modelConfigState
    // resolution pipeline (with self-feedback side effects). Consumes sessionIdFlow
    // from sessionLifecycle; exposes selectedAgentValue/selectedVariantValue for
    // DraftInputDelegate draft persistence and sendParts().
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

    // ============ Message Data Delegate (Phase 3 Task 5 — B cluster) ============
    // Owns message SSE observation, loading, pagination, send-state, tool-expand,
    // and the messageListState/interactionState combine pipelines. Consumes
    // sessionIdFlow from sessionLifecycle; exposes intent methods (onSendStarted/
    // onSendSuccess/onSendError) and sseJob management (cancelSseJob/
    // startObservingMessages) so sendParts/abort/revert coordinators stay in the
    // ViewModel but never touch this delegate's private state directly.
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
    /** Message list state — facade over [messageData]. */
    val messageListState: StateFlow<MessageListState> get() = messageData.messageListState
    /** Interaction state — facade over [messageData]. */
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
    /** Incremented on active terminal tab updates — observe to trigger recomposition. */
    val terminalVersion: StateFlow<Long> get() = terminalDelegate.terminalVersion
    val terminalState: StateFlow<TerminalTabState> get() = terminalDelegate.terminalState
    val terminalFontSizeSp: StateFlow<Float> get() = terminalDelegate.terminalFontSizeSp
    val terminalEmulator: org.connectbot.terminal.TerminalEmulator get() = terminalDelegate.terminalEmulator
    val terminalCursorKeysAppMode: Boolean get() = terminalDelegate.terminalCursorKeysAppMode

    // ============ Draft Input Delegate (Phase 3 Task 2 — D cluster) ============
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

    // ============ Session Actions Delegate (Phase 3 Task 6 — G cluster) ============
    // Owns 24 stateless REST operations — no private StateFlow. Reads other delegates'
    // state via providers and delegates to UseCases/Repositories. Cross-delegate
    // coordinators (sendParts, revertMessage, abortSession) stay in this ViewModel.
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

    // ============ Settings (exposed for ChatScreen) ============
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
    // ============ Tool Expand / Pagination (delegated — Phase 3 Task 5) ============
    val toolExpandedStates: StateFlow<Map<String, Boolean>> get() = messageData.toolExpandedStates

    fun toggleToolExpanded(toolId: String, defaultExpanded: Boolean = false) =
        messageData.toggleToolExpanded(toolId, defaultExpanded)

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean =
        messageData.isToolExpanded(toolId, autoExpand)

    // ============ Scroll State ============
    // LazyListState lives in the ViewModel so it is retained across configuration
    // changes and recomposition (key-based position tracking for conditional items).
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
    /** Expose restored draft as StateFlow for ChatScreen consumption. */
    val restoredDraftState: StateFlow<RevertedDraftPayload?> get() = draftDelegate.restoredDraftState

    // NOTE: Legacy uiState is declared after the 5 split StateFlows below (needs forward references).

    // ============ Split State Flows (independent combines for fine-grained recomposition) ============

    // messageListState — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    /**
     * Session metadata — changes when session info is updated (title, status, agent).
     * Includes [SessionLifecycleDelegate.sessionIdFlow] as a source so lazy-session creation triggers immediate recomputation.
     * Session status is sourced from [SessionStateService.statusFlow] (FSM-driven),
     * the single source of truth for busy/idle/activity state.
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

    // interactionState — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    /**
     * Token usage statistics — changes on every streaming token update.
     * Directly mapped from [TokenStatsTracker.stats].
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
     * Session directory — current chat's working directory, used for the top bar subtitle.
     * Empty when session is not yet resolved or has no directory.
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
     * Aggregated context detail — extracted to ContextDetailDelegate.
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
     * Legacy uiState for backward compatibility (tests).
     * Lightweight assembly from the 5 split StateFlows — no business logic.
     */
    // TODO: Replace positional args[] with a data class or structured combine sources for type safety
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

        // Reset token stats from previous session (TokenStatsTracker is @Singleton, shared across sessions)
        tokenStatsTracker.reset()

        // Restore persisted pending prompts (survive app restart mid-send).
        // Reconciliation against loaded messages happens in the collect loop below.
        val restoredPending = pendingPromptRepository.getForSession(sessionId)
        if (restoredPending.isNotEmpty()) {
            messageData.restorePendingPrompts(restoredPending)
        }

        // Observe messages and update token stats tracker.
        // Token values use the LAST assistant message's tokens (represents current context size).
        // Cost is cumulative across all API calls.
        viewModelScope.launch {
            messageData.messagesList.collect { messages ->
                val assistantMessages = messages.filterIsInstance<Message.Assistant>()

                // Cost is cumulative across all API calls
                val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }

                // Token usage = last call's values (not cumulative, matches OpenCode behavior)
                val lastWithTokens = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
                val lastTokens = lastWithTokens?.tokens
                val totalInputTokens = lastTokens?.input ?: 0
                val totalOutputTokens = lastTokens?.output ?: 0
                val totalReasoningTokens = lastTokens?.reasoning ?: 0
                val totalCacheReadTokens = lastTokens?.cache?.read ?: 0
                val totalCacheWriteTokens = lastTokens?.cache?.write ?: 0

                // Context tokens for the circular progress indicator
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

                // Reconcile pending prompts against the authoritative message list.
                // Detects sends the server never echoed (e.g. lost across a restart)
                // once they are both older than [PENDING_RECONCILE_MIN_AGE_MS] and
                // "covered" (the server delivered a message past their send time).
                val pendingSnapshot = messageData.pendingOptimisticSnapshot()
                if (pendingSnapshot.isNotEmpty()) {
                    val pendingRecords = pendingSnapshot.map { om ->
                        dev.leonardo.octether.data.repository.PendingPromptRecord(
                            messageId = om.pendingId,
                            sessionId = sessionId,
                            parts = emptyList(),
                            createdAt = om.message.time.created,
                        )
                    }
                    val missing = dev.leonardo.octether.data.repository.missingPendingPromptIds(
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

        // Restore draft from disk — apply restored agent/variant to model config
        if (!isNewSession) {
            val draft = draftDelegate.restorePersistedDraft()
            if (draft != null) {
                modelConfig.applyDraftRestore(draft.selectedAgent, draft.selectedVariant)
            }
        }

        // Restore model selection from in-memory cache (survives session switching, cleared on app restart)
        if (!isNewSession) {
            modelConfig.restoreModelFromCache()
        }

        modelConfig.observeHiddenModels()

        // Load initial message count from settings, then load data
        if (!isNewSession) {
            viewModelScope.launch {
                try { sessionLifecycle.loadSession() } catch (e: Exception) { Log.e(TAG, "loadSession failed", e) }
                try { messageData.loadMessages() } catch (e: Exception) { Log.e(TAG, "loadMessages failed", e) }
                try { messageData.loadPendingQuestions() } catch (e: Exception) { Log.e(TAG, "loadPendingQuestions failed", e) }
                try { messageData.loadPendingPermissions() } catch (e: Exception) { Log.e(TAG, "loadPendingPermissions failed", e) }
            }
        } else {
            // New session: set directory from route param, skip loading
            sessionLifecycle.initForNewSession()
            // New session has nothing to load — mark loading complete immediately
            messageData.markLoaded()
        }
        modelConfig.loadProviders()
        modelConfig.loadAgents()
        modelConfig.loadCommands()
    }

    // loadMessagesForSession / startObservingMessages — migrated to MessageDataDelegate
    // (Phase 3 Task 5 — B cluster). These thin forwarders are invoked via the
    // sessionLifecycle callbacks; they exist as VM methods (rather than inlining
    // messageData refs into the lambdas) to avoid a property-init circular dep.
    private suspend fun loadMessagesForSession() = messageData.loadMessagesForSession()
    private fun startObservingMessages() = messageData.startObservingMessages()

    /**
     * Load messages via V1 API for modelConfigState resolution (model/agent from history).
     * Facade over [messageData].
     */
    fun loadMessages() = messageData.loadMessages()

    /**
     * Refresh session data — facade over [sessionActions].
     */
    fun refreshSession() = sessionActions.refreshSession()

    /**
     * Refresh session only if enough time has passed since last refresh.
     * Facade over [sessionActions].
     */
    fun refreshIfNeeded() = sessionActions.refreshIfNeeded()

    // refreshMessages — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    /**
     * Query the OpenCode server for actual session statuses and correct
     * any UI state drift caused by missed SSE events. Facade over [sessionActions].
     */
    fun syncSessionStatus() = sessionActions.syncSessionStatus()

    // refreshAndSync — migrated to SessionActionsDelegate (Phase 3 Task 6 — G cluster).

    /** Load older messages — facade over [messageData]. */
    fun loadOlderMessages() = messageData.loadOlderMessages()

    // loadPendingQuestions — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    // loadPendingPermissions — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    // Model/agent/provider/variant/command selection + modelConfigState resolution
    // are delegated to ModelConfigDelegate (Phase 3 Task 4 — A cluster).

    // ============ @ File Mention Search (delegated — Phase 3 Task 2) ============
    val fileSearchResults: StateFlow<List<String>> get() = draftDelegate.fileSearchResults

    fun searchFilesForMention(query: String) = draftDelegate.searchFilesForMention(query)
    fun confirmFilePath(path: String) = draftDelegate.confirmFilePath(path)
    fun removeFilePath(path: String) = draftDelegate.removeFilePath(path)
    fun clearFileSearch() = draftDelegate.clearFileSearch()
    fun clearConfirmedPaths() = draftDelegate.clearConfirmedPaths()

    // ============ Draft Management (delegated) ============

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

    /** Get the session directory for building file:// URLs */
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

    /** Send pre-built prompt parts (used when @-file mentions need structured parts). */
    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>) {
        val parts = promptParts + attachments
        if (parts.isEmpty()) return
        sendParts(parts)
    }

    /**
     * Schedule a delayed REST refresh to fetch the updated session title.
     * Only refreshes if the current title looks like a default placeholder
     * (null, empty, or matches "New session - ..." pattern).
     */
    private fun refreshSessionTitleDelayed(sid: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(8_000) // Wait for server async title generation
            try {
                val refreshed = manageSessionUseCase.getSession(serverId, sid)
                val currentSession = chatRepository.getSessionsSnapshot().find { it.id == sid }
                val currentTitle = currentSession?.title
                // Only update if the title actually changed (skip if SSE already delivered it)
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
        // RS-007 fix: guard against rapid double-tap. _isSending is set synchronously
        // by onSendStarted, but Compose recomposition (which disables the button)
        // has a 1-frame delay. This check closes the race window.
        if (messageData.isSendingValue) {
            if (BuildConfig.DEBUG) Log.d(TAG, "sendParts: already sending, ignoring duplicate")
            return
        }
        scrollSignal.requestScrollToTop()
        val pendingId = "pending-${java.util.UUID.randomUUID()}"

        // Create optimistic message for immediate display
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
        // Persist the optimistic send so it survives an app kill mid-flight.
        // Reconciliation on next launch detects sends the server never echoed.
        pendingPromptRepository.save(
            dev.leonardo.octether.data.repository.PendingPromptRecord(
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
                // P5-5: read from modelConfigState (resolved effective value) instead of
                // raw _selectedProviderId which may be null on new session's first send.
                val modelCfg = modelConfigState.value
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId,
                        modelId = modelCfg.selectedModelId
                    )
                } else null

                // Clear revert before sending — message.removed SSE events have
                // already cleaned old messages from cache, so no flash.
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
                // Restore draft from the failed send
                val failedText = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                if (failedText.isNotBlank()) {
                    draftDelegate.setRestoredDraft(RevertedDraftPayload(text = failedText))
                }
                messageData.onSendError(e.message ?: "Failed to send message", pendingId)
                pendingPromptRepository.remove(pendingId)
            }
        }
    }

    /** Retry sending a failed optimistic message by its pending ID. */
    fun retrySendMessage(pendingId: String) {
        val pending = messageData.getPendingMessage(pendingId) ?: return
        val parts = pending.parts.mapNotNull { part ->
            (part as? Part.Text)?.let { PromptPart(type = "text", text = it.text) }
        }
        messageData.removePendingMessage(pendingId)
        sendParts(parts)
    }

    /**
     * Reply to a permission request. Facade over [sessionActions].
     * @param requestId The permission request ID
     * @param reply One of: "once", "always", "reject"
     */
    fun replyToPermission(requestId: String, reply: String) =
        sessionActions.replyToPermission(requestId, reply)

    /** Save a permission auto-approve rule. Facade over [sessionActions]. */
    fun savePermissionRule(event: dev.leonardo.octether.domain.model.SseEvent.PermissionAsked, directory: String) =
        sessionActions.savePermissionRule(event, directory)

    /**
     * Abort the current session — coordinator.
     * Delegates REST abort + markIdle to [sessionActions], then handles
     * SSE job cancel/restart (B↔C↔G orchestration).
     */
    fun abortSession() {
        // RS-006 fix: cancel SSE job BEFORE updating FSM. The old order
        // (FSM first, then cancel) left a window where in-flight SSE events
        // arrived in Idle state, triggering isSuspicious transitions and
        // unnecessary REST validation calls.
        messageData.cancelSseJob()
        sessionStateService.onClientAbort(sessionId)
        viewModelScope.launch {
            try {
                sessionActions.abortSession()
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                // P5-2: restart sseJob to avoid _rawMessagesList freeze.
                runCatching { messageData.startObservingMessages() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
            }
        }
    }

    /**
     * Reply to a question request. Facade over [sessionActions].
     * @param requestId The question request ID
     * @param answers Answers for each question (list of selected labels per question)
     */
    fun replyToQuestion(requestId: String, answers: List<List<String>>) =
        sessionActions.replyToQuestion(requestId, answers)

    /**
     * Reject a question request. Facade over [sessionActions].
     */
    fun rejectQuestion(requestId: String) =
        sessionActions.rejectQuestion(requestId)

    // ============ Slash Command Actions (delegated — Phase 3 Task 6) ============

    /** Share the current session. Facade over [sessionActions]. */
    fun shareSession(onResult: (String?) -> Unit) =
        sessionActions.shareSession(onResult)

    /** Unshare the current session. Facade over [sessionActions]. */
    fun unshareSession(onResult: (Boolean) -> Unit) =
        sessionActions.unshareSession(onResult)

    /** Compact (summarize) the current session. Facade over [sessionActions]. */
    fun compactSession(onResult: (Boolean) -> Unit) =
        sessionActions.compactSession(onResult)

    /**
     * Export the session as JSON to a file URI. Facade over [sessionActions].
     */
    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) =
        sessionActions.exportSession(context, uri, onResult)

    /** Undo the last user message in the session, restoring its text to the input field. Facade over [sessionActions]. */
    fun undoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.undoMessage(onResult)

    /** Revert to a specific user message by ID, optionally restoring its text to the input field.
     *  Coordinator (B↔D↔G orchestration): halts busy session, reverts via undoRedoUseCase,
     *  reconnects SSE, restores draft. */
    fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // Halt: if session is busy (AI generating), abort first before reverting.
                // Same pattern as OpenCode WebUI: halt(sessionID).then(() => revert(input))
                val currentStatus = sessionStateService.statusFlow.value[sessionId]
                val wasBusy = currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry

                // RS-008 fix: set revert filter BEFORE cancelling SSE job.
                // The old order (cancel → revert REST → setRevert) left a window
                // where buffered SSE events drained into the message store without
                // the revert filter active, causing post-revert message flicker.
                chatRepository.setRevert(sessionId, messageId)

                if (wasBusy) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Revert: halting busy session $sessionId")
                    sessionStateService.onClientAbort(sessionId)
                    messageData.cancelSseJob()
                    runCatching { sessionRepository.abort(serverId, sessionId, sessionLifecycle.sessionDirectory) }
                }

                undoRedoUseCase.revertSession(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")

                // Reconnect SSE now — old messages will be filtered by revert state.
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

    // extractRevertedDraft — migrated to SessionActionsDelegate (Phase 3 Task 6 — G cluster).
    // restoreRevertedDraft — inlined to draftDelegate.restoreRevertedDraft() (Phase 3 Task 6).

    /** Redo the last undone message. Facade over [sessionActions]. */
    fun redoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.redoMessage(onResult)

    /** Delete a message from the current session. Facade over [sessionActions]. */
    fun deleteMessage(messageId: String, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessage(messageId, onResult)

    /** Delete a specific part from a message by index. Facade over [sessionActions]. */
    fun deleteMessagePart(messageId: String, partIndex: Int, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessagePart(messageId, partIndex, onResult)

    /**
     * Called when a SessionUpdated SSE event is received. Facade over [sessionActions].
     */
    fun onSessionUpdated(session: Session) =
        sessionActions.onSessionUpdated(session)

    /** Fork the current session. Facade over [sessionActions]. */
    fun forkSession(onResult: (Session?) -> Unit) =
        sessionActions.forkSession(onResult)

    /** Rename the current session. Facade over [sessionActions]. */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) =
        sessionActions.renameSession(title, onResult)

    /** Execute a server-side command. Facade over [sessionActions]. */
    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) =
        sessionActions.executeCommand(command, arguments, onResult)

    /** Execute shell command in current session. Facade over [sessionActions]. */
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

    /** Connection parameters for navigation to other sessions. */
    fun getConnectionParams(): ConnectionParams = ConnectionParams(
        serverUrl = serverUrl,
        username = username,
        password = password,
        serverName = serverName,
        serverId = serverId
    )

    /** Get the last assistant message text for copying. Facade over [sessionActions]. */
    fun getLastAssistantText(): String? = sessionActions.getLastAssistantText()

    /** Append a diagnostic log line for permission/question debugging. */
    private fun appendDiagnosticLog(message: String) {
        // Log to logcat only; file writing requires MediaStore on Android 11+
        Log.i(TAG, message)
    }

    companion object
}

/** Holds server connection info for navigation purposes. */
data class ConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
)

