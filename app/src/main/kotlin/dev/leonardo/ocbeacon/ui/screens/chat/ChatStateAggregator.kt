package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.domain.model.SessionActivity
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 管理此前内联在 [ChatViewModel] 中的 4 个聚合状态管道。
 *
 * 负责：
 * - [sessionMetaState]：会话元数据（标题/状态/agent/streaming 标志），7 源 combine
 * - [tokenStatsState]：token 使用统计的轻量映射
 * - [directoryState]：当前会话工作目录
 * - [uiState]：Legacy 全量状态，从 5 个拆分 StateFlow 组装（向后兼容测试）
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的
 * [CoroutineScope]（viewModelScope），Hilt 无法提供。ChatViewModel 直接
 * 构造它并将每个成员作为门面重新暴露，因此 UI 文件无需改动。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ChatStateAggregator(
    sessionIdFlow: StateFlow<String>,
    private val sessionRepository: SessionRepository,
    private val sessionStateService: SessionStateRepository,
    tokenStatsTracker: TokenStatsTracker,
    messageListState: StateFlow<MessageListState>,
    interactionState: StateFlow<InteractionState>,
    modelConfigState: StateFlow<ModelConfigState>,
    restoredDraftState: StateFlow<RevertedDraftPayload?>,
    private val serverId: String,
    private val serverName: StateFlow<String>,
    private val scope: CoroutineScope,
) {
    /** debug 级 UI 状态变化日志的节流缓存（仅 DEBUG 构建使用）。 */
    private var lastLoggedStatusName: String? = null
    private var lastLoggedStreaming: Boolean? = null
    /**
     * 会话元数据 —— 会话信息更新时变化（标题、状态、agent）。
     * 包含 [sessionIdFlow] 作为数据源，使延迟会话创建触发立即重计算。
     * 会话状态来自 [SessionStateRepository.statusFlow]（FSM 驱动），
     * 是 busy/idle/activity 状态的单一真相源。
     */
    val sessionMetaState: StateFlow<SessionMetaState> = combine(
        sessionIdFlow,
        sessionRepository.getSessionsFlow(serverId),
        sessionStateService.statusFlow,
        sessionRepository.getCurrentAgentFlow(serverId),
        sessionRepository.getCurrentModelFlow(serverId),
        sessionStateService.activityFlow,
        serverName,
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
        val serverNameValue = args[6] as String

        val session = allSessions.find { it.id == sid }
        val sessionStatus = statuses[sid] ?: SessionStatus.Idle
        val isStreaming = activities[sid] is SessionActivity.Streaming

        if (BuildConfig.DEBUG) {
            // debug 级 UI 状态变化日志（不干扰正常日志）：仅在 status/isStreaming
            // 实际变化时打印——这是输入区 showBusy 转圈的驱动源，用于定位
            // "发送后一直转圈"是 FSM 状态卡住还是 UI 聚合问题。
            val statusName = sessionStatus::class.simpleName ?: "?"
            if (statusName != lastLoggedStatusName || isStreaming != lastLoggedStreaming) {
                lastLoggedStatusName = statusName
                lastLoggedStreaming = isStreaming
                AppLogger.d("ChatStateAggregator", "[meta] sid=${sid.take(12)} status=$statusName streaming=$isStreaming")
            }
        }

        SessionMetaState(
            sessionTitle = session?.title ?: "",
            serverName = serverNameValue,
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
        scope,
        WhileSubscribed5s,
        SessionMetaState()
    )

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
        scope,
        WhileSubscribed5s,
        TokenStatsState()
    )

    /**
     * 会话目录 —— 当前聊天的工作目录，用于顶栏副标题。
     * 会话尚未解析或无目录时为空。
     */
    val directoryState: StateFlow<String> = sessionIdFlow.flatMapLatest { sid ->
        sessionRepository.getSessionsFlow(serverId).map { sessions ->
            sessions.find { it.id == sid }?.directory.orEmpty()
        }
    }.stateIn(
        scope,
        WhileSubscribed5s,
        ""
    )

    /**
     * Legacy uiState，用于向后兼容（测试）。
     * 从 5 个拆分 StateFlow 轻量组装 —— 无业务逻辑。
     */
    val uiState: StateFlow<ChatUiState> = combine(
        messageListState,
        sessionMetaState,
        interactionState,
        tokenStatsState,
        modelConfigState,
        restoredDraftState,
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
            restoredDraft = restoredDraft,
        )
    }.stateIn(
        scope,
        WhileSubscribed5s,
        ChatUiState()
    )
}
