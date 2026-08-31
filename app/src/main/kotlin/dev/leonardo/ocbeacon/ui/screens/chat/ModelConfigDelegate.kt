package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.domain.usecase.ManageAgentUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import dev.leonardo.ocbeacon.domain.usecase.SelectModelUseCase
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ModelConfigDelegate"

/**
 * 管理 provider/agent/model/variant/command 选择及
 * 此前内联在 [ChatViewModel] 中的 [modelConfigState] 解析管道。
 *
 * [modelConfigState] 是一个以 [sessionIdFlow] 为 key 的 12 路 `combine`，执行
 * **自反馈副作用**：从消息历史解析有效模型/agent（当未显式选择时），
 * 并将解析值写回原始 [MutableStateFlow]，使 [ChatViewModel.sendParts] /
 * [runShellCommand] 始终使用显示值。此解析逻辑
 * 保持完整并整体迁移 —— 不可拆分。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的
 * 运行时上下文（ViewModel 的协程作用域、来自
 * [SessionLifecycleDelegate] 的 session-id flow 和服务器 id），Hilt 无法提供这些。
 * ChatViewModel 直接构造它并将每个成员作为门面重新暴露，
 * 因此 UI 文件无需改动。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ModelConfigDelegate(
    private val selectModelUseCase: SelectModelUseCase,
    private val manageAgentUseCase: ManageAgentUseCase,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val messagePaging: MessagePaginationUseCase,
    private val tokenStatsTracker: TokenStatsTracker,
    private val serverId: String,
    private val sessionIdFlow: StateFlow<String>,
    private val scope: CoroutineScope,
) {
    private val _allProviders = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _providers = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _hiddenModels = MutableStateFlow<Set<String>>(emptySet())
    private val _defaultModels = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 2026-08-16（方案 A·默认模型）：服务器级本地默认模型（"pid|mid|variant"）。
     *  解析链优先级：显式选择 > 会话最后模型 > **本地默认** > provider default。 */
    private val _localDefaultModel = MutableStateFlow<String?>(null)
    private val _selectedProviderId = MutableStateFlow<String?>(null)
    private val _selectedModelId = MutableStateFlow<String?>(null)
    // 跟踪模型是否被用户显式选择，以避免用默认值/历史覆盖
    private var isModelExplicitlySelected = false
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    /** Pair(agentName, explicitlySelected) —— 使用单个 flow 避免标志与值之间的竞态 */
    private val _selectedAgent = MutableStateFlow("build" to false)
    private val _selectedVariant = MutableStateFlow<String?>(null)
    private val _commands = MutableStateFlow<List<CommandInfo>>(emptyList())

    /** 当前 agent 选择的快照 —— 供 [DraftInputDelegate] 草稿持久化消费。 */
    val selectedAgentValue: Pair<String, Boolean> get() = _selectedAgent.value

    /** 当前 variant 选择的快照 —— 供 [DraftInputDelegate] 和 [ChatViewModel.sendParts] 消费。 */
    val selectedVariantValue: String? get() = _selectedVariant.value

    // ============ 模型/Agent 配置状态（含解析副作用） ============

    /**
     * 模型和 agent 配置 —— providers、agents、模型/agent 选择、variants。
     * 执行副作用：从消息历史解析模型/agent、模型缓存，
     * 以及回写到原始 StateFlow，使 sendParts()/runShellCommand() 使用一致值。
     */
    val modelConfigState: StateFlow<ModelConfigState> = sessionIdFlow.flatMapLatest { sid ->
        combine(
            _allProviders,
            _providers,
            _defaultModels,
            _selectedProviderId,
            _selectedModelId,
            _agents,
            _selectedAgent,
            _selectedVariant,
            _commands,
            messagePaging.observeMessages(sid),
            sessionRepository.getSessionsFlow(serverId),
            tokenStatsTracker.stats,
            // 2026-08-16（方案 A·默认模型）：作为 combine 源（缺位会重蹈任务面板
            // R1 覆辙——状态在 lambda 内读但非源，变化不触发重算）
            _localDefaultModel,
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val allProviders = args[0] as List<ProviderCatalog>
            @Suppress("UNCHECKED_CAST")
            val providers = args[1] as List<ProviderCatalog>
            @Suppress("UNCHECKED_CAST")
            val defaultModels = args[2] as Map<String, String>
            val selProviderId = args[3] as String?
            val selModelId = args[4] as String?
            @Suppress("UNCHECKED_CAST")
            val agents = args[5] as List<AgentInfo>
            @Suppress("UNCHECKED_CAST")
            val agentSelection = args[6] as Pair<String, Boolean>
            val selectedAgent = agentSelection.first
            val isAgentExplicitlySelected = agentSelection.second
            val selectedVariant = args[7] as String?
            @Suppress("UNCHECKED_CAST")
            val commands = args[8] as List<CommandInfo>
            @Suppress("UNCHECKED_CAST")
            val sessionMessages = args[9] as List<Message>
            @Suppress("UNCHECKED_CAST")
            val allSessions = args[10] as List<Session>
            val tokenStats = args[11] as TokenStatsTracker.TokenStats

            // 解析模型：显式选择 > 最后一条用户消息的模型 > provider 默认
            var effectiveProviderId = selProviderId
            var effectiveModelId = selModelId

            if (!isModelExplicitlySelected) {
                val lastUserWithModel = sessionMessages
                    .filterIsInstance<Message.User>()
                    .lastOrNull { it.model != null }
                if (lastUserWithModel?.model != null) {
                    effectiveProviderId = lastUserWithModel.model.providerId
                    effectiveModelId = lastUserWithModel.model.modelId
                } else if (effectiveModelId == null) {
                    // 2026-08-16（方案 A·默认模型）：本地默认优先于 provider
                    // default——新会话（无历史消息）即用默认模型。
                    val localDefault = (args[12] as String?)?.split("|")
                    if (localDefault != null && localDefault.size >= 2 &&
                        localDefault[0].isNotBlank() && localDefault[1].isNotBlank()
                    ) {
                        effectiveProviderId = localDefault[0]
                        effectiveModelId = localDefault[1]
                    } else if (defaultModels.isNotEmpty()) {
                        val entry = defaultModels.entries.first()
                        effectiveProviderId = entry.key
                        effectiveModelId = entry.value
                    }
                }
            }

            // 如果未显式更改，从最后一条用户消息解析 agent
            // 2026-08-16 修复（对齐官方 TUI prompt/index.tsx:323-326）：只回填
            // **primary agent**——官方明确防御"Only set agent if it's a primary
            // agent (not a subagent)"。原实现 lastOrNull{agent!=null} 会取到
            // subagent 上下文注入的消息（agent=deep-explore 等）→ 进会话时
            // agent 选择器被置成 subagent 且需手动切回（用户实测根因）。
            val effectiveAgent = if (!isAgentExplicitlySelected) {
                val agentList = args[5] as List<AgentInfo>
                val primaryAgentNames = agentList
                    .filter { it.mode != "subagent" && !it.hidden }
                    .map { it.name }
                    .toSet()
                val lastUserAgent = sessionMessages
                    .filterIsInstance<Message.User>()
                    .lastOrNull { !it.agent.isNullOrBlank() && it.agent in primaryAgentNames }
                    ?.agent
                lastUserAgent ?: selectedAgent
            } else {
                selectedAgent
            }

            // 保持原始状态同步，使 sendParts()/runShellCommand() 始终使用显示值
            if (effectiveAgent != selectedAgent && !isAgentExplicitlySelected) {
                _selectedAgent.value = effectiveAgent to false
            }

            // 保持模型 StateFlow 与有效模型同步
            if ((effectiveProviderId != selProviderId || effectiveModelId != selModelId) && !isModelExplicitlySelected) {
                _selectedProviderId.value = effectiveProviderId
                _selectedModelId.value = effectiveModelId
            }

            // 解析当前选择模型的可用 variants。
            var currentModel = if (effectiveProviderId != null && effectiveModelId != null) {
                providers.find { it.id == effectiveProviderId }
                    ?.models?.get(effectiveModelId)
            } else null
            if (currentModel == null) {
                val firstProvider = providers.firstOrNull()
                val firstModel = firstProvider?.models?.values?.firstOrNull()
                if (firstProvider != null && firstModel != null) {
                    effectiveProviderId = firstProvider.id
                    effectiveModelId = firstModel.id
                    currentModel = firstModel
                }
            }
            val availableVariants = currentModel?.variantNames?.sorted() ?: emptyList()

            // 将解析的模型持久化到内存缓存
            if (effectiveProviderId != null && effectiveModelId != null) {
                sessionModelCache[sid] = effectiveProviderId to effectiveModelId
            }

            // 解析上下文窗口，降级取 provider 模型信息
            val session = allSessions.find { it.id == sid }
            // 2026-08-17 上下文占用口径修正（ACP：input+cache.read）：删除
            // `?: currentModel?.contextWindow` 兜底——session 模型在 catalog
            // 查不到时 currentModel 是「第一个 provider 第一个模型」的降级值，
            // 其 limit.context 可能远小于实际窗口（分母错小数倍 → 显示超
            // 100%）。查不到时置 0——UI（ChatTopBar/ContextDetailDialog）对
            // contextWindow<=0 的处理是隐藏指示器，不崩溃。
            // #209（2026-08-24）：删除 tokenStats.contextWindow 优先分支——
            // 生产代码从不写该字段（全库 2 处 update 均不含它），恒 0 的死分支；
            // 唯一写点是插桩测试注入捷径，已改为走真实 catalog 路径。
            val contextWindow = session?.model?.let { sm ->
                providers.find { it.id == sm.providerId }?.models?.get(sm.id)?.contextWindow
            } ?: 0

            ModelConfigState(
                providers = providers,
                hasServerModelCatalog = allProviders.any { it.models.isNotEmpty() },
                defaultModels = defaultModels,
                selectedProviderId = effectiveProviderId,
                selectedModelId = effectiveModelId,
                agents = agents.filter { it.mode != "subagent" && !it.hidden },
                selectedAgent = effectiveAgent,
                variantNames = availableVariants,
                selectedVariant = if (selectedVariant != null && selectedVariant in availableVariants) selectedVariant else null,
                commands = commands,
                contextWindow = contextWindow,
            )
        }
    }.stateIn(
        scope,
        WhileSubscribed5s,
        ModelConfigState()
    )

    // ============ 初始化时加载（从 ChatViewModel.init 调用） ============

    fun loadProviders() {
        scope.launch {
            try {
                val response = selectModelUseCase.loadProviders(serverId)
                _allProviders.value = response.providers
                applyProviderFilter()
                _defaultModels.value = response.default
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Loaded ${response.providers.size} providers, defaults: ${response.default}")
                // 无需在此设置默认值，combine 块处理降级
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load providers", e)
            }
        }
    }

    private fun applyProviderFilter() {
        val hidden = _hiddenModels.value
        val filtered = _allProviders.value
            .map { provider ->
                provider.copy(
                    models = provider.models.filterKeys { modelId ->
                        "${provider.id}:$modelId" !in hidden
                    }
                )
            }
            .filter { it.models.isNotEmpty() }
        _providers.value = filtered
    }

    fun loadAgents() {
        scope.launch {
            try {
                val agents = manageAgentUseCase.loadAgents(serverId)
                _agents.value = agents
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Loaded ${agents.size} agents: ${agents.map { it.name }}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load agents", e)
            }
        }
    }

    /** [sessionId] 只对 DSH 有效（commands/list 是 agent-scoped 的；null = 懒建前 → 空列表）。 */
    fun loadCommands(sessionId: String? = null) {
        scope.launch {
            try {
                val commands = manageAgentUseCase.loadCommands(serverId, sessionId)
                _commands.value = commands
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Loaded " + commands.size + " commands: " + commands.map { it.name })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load commands", e)
            }
        }
    }

    /** 观察隐藏模型设置并在变更时重新过滤 providers。 */
    fun observeHiddenModels() {
        scope.launch {
            settingsRepository.hiddenModels(serverId).collect { hidden ->
                _hiddenModels.value = hidden
                applyProviderFilter()
            }
        }
    }

    /** 2026-08-16（方案 A·默认模型）：观察本地默认模型变化（combine 已含
     *  _localDefaultModel 时自动重算——见下方 combine 源补位说明）。 */
    fun observeLocalDefaultModel() {
        scope.launch {
            settingsRepository.defaultModel(serverId).collect { value ->
                _localDefaultModel.value = value
            }
        }
    }

    /** 当前本地默认模型（"pid|mid|variant" 或 null）。 */
    val localDefaultModel: String? get() = _localDefaultModel.value

    /** #188：响应式默认模型（DataStore 写入后 UI 即时回显——修复快照断裂）。 */
    val localDefaultModelFlow: kotlinx.coroutines.flow.StateFlow<String?> = _localDefaultModel

    // ============ 选择（UI 门面） ============

    fun selectAgent(name: String) {
        _selectedAgent.value = name to true
    }

    fun selectModel(providerId: String, modelId: String, variant: String? = null) {
        // 必须在修改 StateFlow 之前设置标志 —— 在 Main.immediate 调度器上，
        // 设置 StateFlow 值会同步触发 combine 重计算，
        // 如果标志尚未设置，会覆盖我们的值。
        isModelExplicitlySelected = true
        _selectedProviderId.value = providerId
        _selectedModelId.value = modelId
        // #187：二级面板 variant pill 选择——同步思考档位（null=默认档）
        _selectedVariant.value = variant
        // 记住本会话的选择（内存中，应用重启时清除）
        sessionModelCache[sessionIdFlow.value] = providerId to modelId
    }

    // ============ 恢复（从 ChatViewModel.init 调用） ============

    /** 应用从持久化草稿恢复的 agent/variant。 */
    fun applyDraftRestore(agent: String?, variant: String?) {
        if (!agent.isNullOrBlank()) {
            _selectedAgent.value = agent to true
        }
        if (!variant.isNullOrBlank()) {
            _selectedVariant.value = variant
        }
    }

    /** 从内存缓存恢复模型选择（会话切换时存活）。 */
    fun restoreModelFromCache() {
        val sid = sessionIdFlow.value
        if (sid.isEmpty()) return
        sessionModelCache[sid]?.let { (providerId, modelId) ->
            _selectedProviderId.value = providerId
            _selectedModelId.value = modelId
            isModelExplicitlySelected = true
        }
    }

    companion object {
        /**
         * 内存缓存，映射 sessionId → (providerId, modelId)。
         * 会话切换时存活（ViewModel 重建），但应用重启（进程死亡）时清除。
         */
        private val sessionModelCache = mutableMapOf<String, Pair<String, String>>()
    }
}