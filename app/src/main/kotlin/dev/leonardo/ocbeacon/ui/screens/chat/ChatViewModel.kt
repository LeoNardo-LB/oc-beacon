package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.PendingPromptRecord
import dev.leonardo.ocbeacon.data.repository.PendingPromptRepository
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.repository.missingPendingPromptIds
import dev.leonardo.ocbeacon.data.terminal.TerminalTabState
import dev.leonardo.ocbeacon.data.terminal.TerminalTabUi
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextDetailState
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "ChatViewModel"

// ============ UI State 数据类 ============
// MessageListState / SessionMetaState / InteractionState / TokenStatsState /
// ModelConfigState / ChatUiState / RevertedDraftPayload / ChatMessage /
// ConnectionParams / PENDING_RECONCILE_MIN_AGE_MS
// 已迁移到 ChatUiState.kt（纯数据类，无依赖）。

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
    private val httpClient: HttpClient,
    private val sessionStateService: SessionStateRepository,
    private val sessionFocusHolder: dev.leonardo.ocbeacon.service.SessionFocusHolder,
    private val appNotificationManager: dev.leonardo.ocbeacon.service.AppNotificationManager,
    private val toolSnapshotCache: dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache,
    private val pendingPromptRepository: PendingPromptRepository,
) : ViewModel() {

    // ============ 工具快照缓存（已提取到 ToolCacheDelegate） ============

    private val toolCacheDelegate = ToolCacheDelegate(toolSnapshotCache)

    fun cacheToolPart(part: dev.leonardo.ocbeacon.domain.model.Part.Tool) =
        toolCacheDelegate.cacheToolPart(part)

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

    fun onSessionFocused(notificationManager: android.app.NotificationManager) {
        appNotificationManager.cancelSessionNotifications(notificationManager, serverId, sessionId)
        sessionFocusHolder.setActiveFocus(serverId, sessionId)
    }

    fun onSessionUnfocused() {
        sessionFocusHolder.setActiveFocus(null, null)
    }

    init {
        sessionStateService.setServerId(serverId)
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
    fun selectModel(providerId: String, modelId: String) = modelConfig.selectModel(providerId, modelId)

    // ============ 消息数据 Delegate ============
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
    val messageListState: StateFlow<MessageListState> get() = messageData.messageListState
    val interactionState: StateFlow<InteractionState> get() = messageData.interactionState

    // ============ 终端 Delegate ============
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
    val terminalVersion: StateFlow<Long> get() = terminalDelegate.terminalVersion
    val terminalState: StateFlow<TerminalTabState> get() = terminalDelegate.terminalState
    val terminalFontSizeSp: StateFlow<Float> get() = terminalDelegate.terminalFontSizeSp
    val terminalEmulator: org.connectbot.terminal.TerminalEmulator get() = terminalDelegate.terminalEmulator
    val terminalCursorKeysAppMode: Boolean get() = terminalDelegate.terminalCursorKeysAppMode

    // ============ 草稿输入 Delegate ============
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
    val listState = androidx.compose.foundation.lazy.LazyListState()

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

        // 恢复已持久化的 pending prompt（在发送中途存活应用重启）。
        val restoredPending = pendingPromptRepository.getForSession(sessionId)
        if (restoredPending.isNotEmpty()) {
            messageData.restorePendingPrompts(restoredPending)
        }

        // 观察消息并更新 token 统计跟踪器。
        viewModelScope.launch {
            messageData.messagesList.collect { messages ->
                val assistantMessages = messages.filterIsInstance<dev.leonardo.ocbeacon.domain.model.Message.Assistant>()

                val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }

                val lastWithTokens = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
                val lastTokens = lastWithTokens?.tokens
                val totalInputTokens = lastTokens?.input ?: 0
                val totalOutputTokens = lastTokens?.output ?: 0
                val totalReasoningTokens = lastTokens?.reasoning ?: 0
                val totalCacheReadTokens = lastTokens?.cache?.read ?: 0
                val totalCacheWriteTokens = lastTokens?.cache?.write ?: 0

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
                val pendingSnapshot = messageData.pendingOptimisticSnapshot()
                if (pendingSnapshot.isNotEmpty()) {
                    val pendingRecords = pendingSnapshot.map { om ->
                        PendingPromptRecord(
                            messageId = om.pendingId,
                            sessionId = sessionId,
                            parts = emptyList(),
                            createdAt = om.message.time.created,
                        )
                    }
                    val missing = missingPendingPromptIds(
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

        // 从磁盘恢复草稿
        if (!isNewSession) {
            val draft = draftDelegate.restorePersistedDraft()
            if (draft != null) {
                modelConfig.applyDraftRestore(draft.selectedAgent, draft.selectedVariant)
            }
        }

        // 从内存缓存恢复模型选择
        if (!isNewSession) {
            modelConfig.restoreModelFromCache()
        }

        modelConfig.observeHiddenModels()

        // 加载数据
        if (!isNewSession) {
            viewModelScope.launch {
                try { sessionLifecycle.loadSession() } catch (e: Exception) { Log.e(TAG, "loadSession failed", e) }
                try { messageData.loadMessages() } catch (e: Exception) { Log.e(TAG, "loadMessages failed", e) }
                try { messageData.loadPendingQuestions() } catch (e: Exception) { Log.e(TAG, "loadPendingQuestions failed", e) }
                try { messageData.loadPendingPermissions() } catch (e: Exception) { Log.e(TAG, "loadPendingPermissions failed", e) }
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

    private suspend fun loadMessagesForSession() = messageData.loadMessagesForSession()
    private fun startObservingMessages() = messageData.startObservingMessages()

    fun loadMessages() = messageData.loadMessages()
    fun refreshSession() = sessionActions.refreshSession()
    fun refreshIfNeeded() = sessionActions.refreshIfNeeded()
    fun syncSessionStatus() = sessionActions.syncSessionStatus()
    fun loadOlderMessages() = messageData.loadOlderMessages()

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
        draftDelegate.saveDraft()
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
        pendingPromptRepository = pendingPromptRepository,
        scope = viewModelScope,
        serverId = serverId,
        sessionIdProvider = { sessionLifecycle.sessionId },
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        ensureSession = { sessionLifecycle.ensureSession() },
        modelConfigProvider = { modelConfigState.value },
        selectedVariantProvider = { modelConfig.selectedVariantValue },
        messageData = messageData,
        draftDelegate = draftDelegate,
    )

    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList()) =
        sendDelegate.sendMessage(text, attachments)

    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>) =
        sendDelegate.sendMessage(promptParts, attachments)

    fun retrySendMessage(pendingId: String) = sendDelegate.retrySendMessage(pendingId)

    // ============ 权限/问题回复（门面 —— SessionActionsDelegate） ============

    fun replyToPermission(requestId: String, reply: String) =
        sessionActions.replyToPermission(requestId, reply)

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
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                runCatching { messageData.startObservingMessages() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
            }
        }
    }

    fun replyToQuestion(requestId: String, answers: List<List<String>>) =
        sessionActions.replyToQuestion(requestId, answers)

    fun rejectQuestion(requestId: String) =
        sessionActions.rejectQuestion(requestId)

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
                    if (BuildConfig.DEBUG) Log.d(TAG, "Revert：暂停 busy 会话 $sessionId")
                    sessionStateService.onClientAbort(sessionId)
                    messageData.cancelSseJob()
                    runCatching { sessionRepository.abort(serverId, sessionId, sessionLifecycle.sessionDirectory) }
                }

                undoRedoUseCase.revertSession(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")

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

    /** 导航到其他会话的连接参数。 */
    fun getConnectionParams(): ConnectionParams = ConnectionParams(
        serverUrl = serverUrl,
        username = username,
        password = password,
        serverName = serverName,
        serverId = serverId
    )

    fun getLastAssistantText(): String? = sessionActions.getLastAssistantText()

    companion object
}
