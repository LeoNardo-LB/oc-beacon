package dev.leonardo.octether.ui.screens.chat

import android.util.Log
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.domain.model.AgentInfo
import dev.leonardo.octether.domain.model.CommandInfo
import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.ProviderCatalog
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.repository.SessionRepository
import dev.leonardo.octether.domain.repository.SettingsRepository
import dev.leonardo.octether.domain.tracker.TokenStatsTracker
import dev.leonardo.octether.domain.usecase.ManageAgentUseCase
import dev.leonardo.octether.domain.usecase.MessagePaginationUseCase
import dev.leonardo.octether.domain.usecase.SelectModelUseCase
import dev.leonardo.octether.ui.WhileSubscribed5s
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
 * Owns provider/agent/model/variant/command selection and the
 * [modelConfigState] resolution pipeline previously inlined in [ChatViewModel].
 *
 * Extracted in Phase 3 Task 4 (A cluster).
 *
 * [modelConfigState] is a 12-way `combine` keyed by [sessionIdFlow] that performs
 * **self-feedback side effects**: it resolves the effective model/agent from the
 * message history (when not explicitly selected) and writes the resolved values
 * back into the raw [MutableStateFlow]s so that [ChatViewModel.sendParts] /
 * [runShellCommand] always use the displayed value. This resolution logic is
 * kept intact and migrated wholesale — it must NOT be split apart.
 *
 * NOTE: Intentionally NOT `@Singleton`/`@Inject`. It holds per-ChatViewModel
 * runtime context (the ViewModel's coroutine scope, the session-id flow from
 * [SessionLifecycleDelegate], and the server id) that Hilt cannot supply.
 * ChatViewModel constructs it directly and re-exposes every member as a facade,
 * so UI files are unchanged.
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
    private val _selectedProviderId = MutableStateFlow<String?>(null)
    private val _selectedModelId = MutableStateFlow<String?>(null)
    // Track if the model was explicitly selected by the user to avoid overwriting it with defaults/history
    private var isModelExplicitlySelected = false
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    /** Pair(agentName, explicitlySelected) — using a single flow avoids race between flag and value */
    private val _selectedAgent = MutableStateFlow("build" to false)
    private val _selectedVariant = MutableStateFlow<String?>(null)
    private val _commands = MutableStateFlow<List<CommandInfo>>(emptyList())

    /** Snapshot of the current agent selection — consumed by [DraftInputDelegate] for draft persistence. */
    val selectedAgentValue: Pair<String, Boolean> get() = _selectedAgent.value

    /** Snapshot of the current variant selection — consumed by [DraftInputDelegate] and [ChatViewModel.sendParts]. */
    val selectedVariantValue: String? get() = _selectedVariant.value

    // ============ Model/Agent Config State (with resolution side effects) ============

    /**
     * Model and agent configuration — providers, agents, model/agent selection, variants.
     * Performs side effects: model/agent resolution from message history, model caching,
     * and sync-back to raw StateFlows so sendParts()/runShellCommand() use consistent values.
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

            // Resolve model: explicit selection > last user message's model > provider default
            var effectiveProviderId = selProviderId
            var effectiveModelId = selModelId

            if (!isModelExplicitlySelected) {
                val lastUserWithModel = sessionMessages
                    .filterIsInstance<Message.User>()
                    .lastOrNull { it.model != null }
                if (lastUserWithModel?.model != null) {
                    effectiveProviderId = lastUserWithModel.model.providerId
                    effectiveModelId = lastUserWithModel.model.modelId
                } else if (effectiveModelId == null && defaultModels.isNotEmpty()) {
                    val entry = defaultModels.entries.first()
                    effectiveProviderId = entry.key
                    effectiveModelId = entry.value
                }
            }

            // Resolve agent from last user message if not explicitly changed
            val effectiveAgent = if (!isAgentExplicitlySelected) {
                val lastUserAgent = sessionMessages
                    .filterIsInstance<Message.User>()
                    .lastOrNull { it.agent != null }
                    ?.agent
                lastUserAgent ?: selectedAgent
            } else {
                selectedAgent
            }

            // Keep raw state in sync so sendParts()/runShellCommand() always use the displayed value
            if (effectiveAgent != selectedAgent && !isAgentExplicitlySelected) {
                _selectedAgent.value = effectiveAgent to false
            }

            // Keep model StateFlows in sync with the effective model
            if ((effectiveProviderId != selProviderId || effectiveModelId != selModelId) && !isModelExplicitlySelected) {
                _selectedProviderId.value = effectiveProviderId
                _selectedModelId.value = effectiveModelId
            }

            // Resolve available variants for the currently selected model.
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

            // Persist the resolved model to the in-memory cache
            if (effectiveProviderId != null && effectiveModelId != null) {
                sessionModelCache[sid] = effectiveProviderId to effectiveModelId
            }

            // Resolve context window with fallback to provider model info
            val session = allSessions.find { it.id == sid }
            val contextWindow = tokenStats.contextWindow.let { cw ->
                if (cw > 0) cw else session?.model?.let { sm ->
                    providers.find { it.id == sm.providerId }?.models?.get(sm.id)?.contextWindow
                } ?: currentModel?.contextWindow ?: 0
            }

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

    // ============ Init-time loading (called from ChatViewModel.init) ============

    fun loadProviders() {
        scope.launch {
            try {
                val response = selectModelUseCase.loadProviders(serverId)
                _allProviders.value = response.providers
                applyProviderFilter()
                _defaultModels.value = response.default
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${response.providers.size} providers, defaults: ${response.default}")
                // No need to set default here, combine block handles fallback
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load providers", e)
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${agents.size} agents: ${agents.map { it.name }}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load agents", e)
            }
        }
    }

    fun loadCommands() {
        scope.launch {
            try {
                val commands = manageAgentUseCase.loadCommands(serverId)
                _commands.value = commands
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${commands.size} commands: ${commands.map { it.name }}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load commands", e)
            }
        }
    }

    /** Observe hidden-models setting and re-filter providers on change. */
    fun observeHiddenModels() {
        scope.launch {
            settingsRepository.hiddenModels(serverId).collect { hidden ->
                _hiddenModels.value = hidden
                applyProviderFilter()
            }
        }
    }

    // ============ Selection (UI facades) ============

    fun selectAgent(name: String) {
        _selectedAgent.value = name to true
    }

    /**
     * Cycle through available thinking effort variants for the current model.
     * Cycles: none -> first -> second -> ... -> last -> none (default).
     */
    fun cycleVariant() {
        val variants = modelConfigState.value.variantNames
        if (variants.isEmpty()) return
        val current = _selectedVariant.value
        if (current == null || current !in variants) {
            _selectedVariant.value = variants.first()
        } else {
            val idx = variants.indexOf(current)
            _selectedVariant.value = if (idx == variants.lastIndex) null else variants[idx + 1]
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        // MUST set flag BEFORE modifying StateFlows — on Main.immediate dispatcher,
        // setting a StateFlow value synchronously triggers combine recomputation,
        // which would overwrite our values if the flag isn't set yet.
        isModelExplicitlySelected = true
        _selectedProviderId.value = providerId
        _selectedModelId.value = modelId
        // Remember selection for this session (in-memory, cleared on app restart)
        sessionModelCache[sessionIdFlow.value] = providerId to modelId
    }

    // ============ Restoration (called from ChatViewModel.init) ============

    /** Apply agent/variant restored from a persisted draft. */
    fun applyDraftRestore(agent: String?, variant: String?) {
        if (!agent.isNullOrBlank()) {
            _selectedAgent.value = agent to true
        }
        if (!variant.isNullOrBlank()) {
            _selectedVariant.value = variant
        }
    }

    /** Restore model selection from the in-memory cache (survives session switching). */
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
         * In-memory cache mapping sessionId → (providerId, modelId).
         * Survives session switching (ViewModel recreation) but clears on app restart (process death).
         */
        private val sessionModelCache = mutableMapOf<String, Pair<String, String>>()
    }
}
