package dev.leonardo.ocbeacon.ui.screens.server

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.DshCustomProviderDraft
import dev.leonardo.ocbeacon.domain.model.DshDiscoveredModel
import dev.leonardo.ocbeacon.domain.model.DshProviderDirectoryEntry
import dev.leonardo.ocbeacon.domain.model.GlobalConfig
import dev.leonardo.ocbeacon.domain.model.GlobalConfigPatch
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderAuthMethod
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderOauthAuthorization
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.adapter.ServerAdapterResolver
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import dev.leonardo.ocbeacon.domain.repository.DshSettingsForbiddenException
import dev.leonardo.ocbeacon.domain.repository.DshSettingsRepository
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.ui.navigation.routes.safeDecodeParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ServerSettingsViewModel"

data class ServerSettingsUiState(
    val serverName: String = "",
    val providers: List<ProviderToggle> = emptyList(),
    val modelOptions: List<ModelOption> = emptyList(),
    val agentOptions: List<String> = emptyList(),
    val selectedModel: String? = null,
    val selectedSmallModel: String? = null,
    val selectedDefaultAgent: String? = null,
    val groups: List<ModelGroup> = emptyList(),
    val authMethods: Map<String, List<ProviderAuthMethod>> = emptyMap(),
    val pendingOauth: PendingOauth? = null,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    // ============ #324① DSH provider 目录/自定义增删 ============
    /** DSH 专属区块显隐（V1/V2 走既有 auth 管理）。 */
    val isDsh: Boolean = false,
    val dshDirectory: List<DshProviderDirectoryEntry> = emptyList(),
    val dshDirectoryLoading: Boolean = false,
    /** DSH 目录/CRUD 失败提示（含 loopback 403 标注）；null = 无错。 */
    val dshProviderError: String? = null,
    /** settings.* 特权面 403 → 目录只读（创建/删除入口禁用 + 标注）。 */
    val dshSettingsBlocked: Boolean = false,
)

data class PendingOauth(
    val providerId: String,
    val providerName: String,
    val methodIndex: Int,
    val authorization: ProviderOauthAuthorization,
    val fallbackFromHeadless: Boolean = false,
)

data class ProviderToggle(
    val providerId: String,
    val providerName: String,
    val source: String? = null,
    val connected: Boolean = false,
    val hasPaidModels: Boolean = false,
    val enabled: Boolean
)

data class ModelOption(
    val key: String,
    val label: String
)

data class ModelGroup(
    val providerId: String,
    val providerName: String,
    val models: List<ModelToggle>
)

data class ModelToggle(
    val modelId: String,
    val modelName: String,
    val visible: Boolean
)

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val providerRepository: ProviderRepository,
    private val agentRepository: AgentRepository,
    private val settingsRepository: SettingsRepository,
    private val serverConfigRepository: ServerConfigRepository,
    // #324①：DSH provider 目录/凭据/自定义增删（仅 DSH 连接使用）
    private val dshSettingsRepository: DshSettingsRepository,
    /** #391：能力位唯一来源（适配器解析器）。 */
    private val serverAdapters: ServerAdapterResolver,
) : ViewModel() {

    private companion object {
        /** #134（D2-L36）：初始加载源数量（config + hidden + providers + config + agents + authMethods）。 */
        const val INITIAL_LOAD_SOURCES = 6
    }

    private val serverId: String = safeDecodeParam(
        savedStateHandle.get<String>("serverId") ?: ""
    )
    private var serverDisplayName: String = ""
    /** #324①：DSH 连接配置缓存（conn 构建 + isDsh 门控）。 */
    private var serverConfig: ServerConfig? = null
    /** 服务器 API 版本（V2 配置只读——PATCH /api/config 404，见 backlog #85）。init 时从 ServerConfig 读取。 */
    // #172：配置可写能力位（V2 只读 #85）——版本比较收编进 ServerCapabilities
    private var configEditable = true

    private val _allProviders = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _providerCatalog = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _providerConnected = MutableStateFlow<Set<String>>(emptySet())
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    private val _config = MutableStateFlow(GlobalConfig())
    private val _authMethods = MutableStateFlow<Map<String, List<ProviderAuthMethod>>>(emptyMap())
    private val _hiddenModels = MutableStateFlow<Set<String>>(emptySet())
    private val _uiState = MutableStateFlow(ServerSettingsUiState(isLoading = true))
    val uiState: StateFlow<ServerSettingsUiState> = _uiState.asStateFlow()

    /** #134（D2-L36）：初始加载待完成计数——全部 loader 完成才结束 loading。
     * 原实现任一 loader 完成即 rebuildUi() 清 isLoading → 页面 loading 抖动 +
     * 部分数据未就绪即显示。计数：config + hiddenModels + providers + config + agents + authMethods = 6。 */
    private val initialLoadsPending = java.util.concurrent.atomic.AtomicInteger(INITIAL_LOAD_SOURCES)
    @Volatile
    private var initialLoadComplete = false

    init {
        viewModelScope.launch {
            val config = serverConfigRepository.getServer(serverId)
            if (config != null) {
                serverDisplayName = config.displayName
                // #276：能力位带 serverType 维度（DSH settings 特权面不开放 UI）
                configEditable = serverAdapters.capabilities(ServerConnection.from(config)).coreFlags.configEditable
                _uiState.update { it.copy(serverName = serverDisplayName) }
                // #324①：DSH 专属 provider 目录区块（V1/V2 不渲染）
                if (config.serverType == ServerType.Dsh) {
                    serverConfig = config
                    _uiState.update { it.copy(isDsh = true) }
                    loadDshProviderDirectory()
                }
            }
            markInitialLoadDone()
        }
        viewModelScope.launch {
            settingsRepository.hiddenModels(serverId).collect { hidden ->
                _hiddenModels.value = hidden
                rebuildUi()
                markInitialLoadDone()
            }
        }
        loadProviders()
        loadConfig()
        loadAgents()
        loadAuthMethods()
    }

    /** #134（D2-L36）：标记一路初始加载完成；全部完成后结束 isLoading。幂等（完成后再调无副作用）。 */
    private fun markInitialLoadDone() {
        if (initialLoadComplete) return
        if (initialLoadsPending.decrementAndGet() <= 0) {
            initialLoadComplete = true
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun loadProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val catalog = providerRepository.loadProviderCatalog(serverId).getOrThrow()
                _allProviders.value = catalog.providers
                val status = providerRepository.loadProviderConnectionStatus(serverId).getOrThrow()
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "loadProviders: status.connected=${status.connected}")
                _providerCatalog.value = status.providers
                _providerConnected.value = status.connected
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                rebuildUi()
                markInitialLoadDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load providers", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: context.getString(R.string.server_settings_providers_load_failed)
                    )
                }
                markInitialLoadDone()
            }
        }
    }

    private fun loadAuthMethods() {
        viewModelScope.launch {
            try {
                _authMethods.value = providerRepository.getProviderAuthMethods(serverId).getOrThrow()
                rebuildUi()
                markInitialLoadDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load auth methods", e)
                markInitialLoadDone()
            }
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            try {
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                rebuildUi()
                markInitialLoadDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load config", e)
                markInitialLoadDone()
            }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                _agents.value = agentRepository.listAgents(serverId).getOrThrow()
                rebuildUi()
                markInitialLoadDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load agents", e)
                markInitialLoadDone()
            }
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            // V2 配置只读（PATCH /api/config → 404，实测确认）——直接提示并返回
            if (!configEditable) {
                _uiState.update { it.copy(error = context.getString(R.string.server_settings_update_failed)) }
                return@launch
            }
            val before = _config.value
            val current = before.disabledProviders.toSet()
            val next = if (enabled) current - providerId else current + providerId
            _config.value = before.copy(disabledProviders = next.toList().sorted())
            rebuildUi()
            try {
                providerRepository.updateGlobalConfig(
                    serverId,
                    GlobalConfigPatch(disabledProviders = next.toList().sorted())
                ).getOrThrow()
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                rebuildUi()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to update provider state", e)
                _config.value = before
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.server_settings_update_failed)) }
                rebuildUi()
            }
        }
    }

    fun connectProviderApi(providerId: String, apiKey: String) {
        if (apiKey.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val result = providerRepository.connectProviderApi(serverId, providerId, apiKey.trim())
                if (!result.isSuccess) {
                    _uiState.update { it.copy(isSaving = false, error = context.getString(R.string.server_settings_provider_connect_failed)) }
                    return@launch
                }
                // 连接成功后确保 provider 处于启用状态
                // V2 配置只读：跳过 disabledProviders PATCH（/api/config PATCH 404），
                // 本地状态已乐观更新，Provider 连接本身（PATCH /api/credential）已成功
                val disabled = _config.value.disabledProviders.toSet() - providerId
                if (configEditable) {
                    providerRepository.updateGlobalConfig(
                        serverId,
                        GlobalConfigPatch(disabledProviders = disabled.toList().sorted())
                    ).getOrThrow()
                } else {
                    _config.value = _config.value.copy(disabledProviders = disabled.toList().sorted())
                }
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                loadProviders()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to connect provider via API key", e)
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.server_settings_provider_connect_failed)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun startProviderOauth(providerId: String, methodIndex: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val auth = providerRepository.authorizeProviderOauth(serverId, providerId, methodIndex).getOrThrow()

                if (auth == null) {
                    _uiState.update { it.copy(isSaving = false, error = context.getString(R.string.server_settings_oauth_unavailable)) }
                    return@launch
                }
                val providerName = (_providerCatalog.value.find { it.id == providerId }?.name ?: providerId)
                _uiState.update {
                    it.copy(
                        pendingOauth = PendingOauth(
                            providerId = providerId,
                            providerName = providerName,
                            methodIndex = methodIndex,
                            authorization = auth,
                            fallbackFromHeadless = false,
                        ),
                        isSaving = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to start provider oauth", e)
                _uiState.update { it.copy(isSaving = false, error = e.message ?: context.getString(R.string.server_settings_oauth_start_failed)) }
            }
        }
    }

    fun completeProviderOauth(code: String?) {
        val pending = _uiState.value.pendingOauth ?: return
        // 进行中时防止重复调用
        if (_uiState.value.isSaving) return
        // 在启动协程之前同步设置 isSaving，防止竞态
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val oauthCode = if (pending.authorization.method == "code") code?.trim()?.ifEmpty { null } else null
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "completeProviderOauth: calling callback for ${pending.providerId}, method=${pending.methodIndex}")
                val completed = providerRepository.completeProviderOauth(
                    serverId,
                    pending.providerId,
                    pending.methodIndex,
                    oauthCode
                ).getOrThrow()
                if (!completed) {
                    // 某些服务器版本会带外完成授权，回调可能返回非成功。
                    // 在显示错误前先刷新 provider 目录。
                    val status = providerRepository.loadProviderConnectionStatus(serverId).getOrThrow()
                    _providerCatalog.value = status.providers
                    _providerConnected.value = status.connected
                    _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                    if (pending.providerId in status.connected) {
                        _uiState.update { it.copy(pendingOauth = null) }
                        rebuildUi()
                        return@launch
                    }
                    _uiState.update { it.copy(isSaving = false, error = context.getString(R.string.server_settings_oauth_failed)) }
                    return@launch
                }
                val disabled = _config.value.disabledProviders.toSet() - pending.providerId
                // V2 配置只读：跳过 disabledProviders PATCH（见 setProviderEnabled 注释）
                if (configEditable) {
                    providerRepository.updateGlobalConfig(
                        serverId,
                        GlobalConfigPatch(disabledProviders = disabled.toList().sorted())
                    ).getOrThrow()
                } else {
                    _config.value = _config.value.copy(disabledProviders = disabled.toList().sorted())
                }
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                _uiState.update { it.copy(pendingOauth = null) }
                loadProviders()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to complete provider oauth", e)
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.server_settings_oauth_failed)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun cancelProviderOauth() {
        _uiState.update { it.copy(pendingOauth = null, error = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun disconnectProvider(providerId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "disconnectProvider: calling DELETE /auth/$providerId")
                val removed = providerRepository.removeProviderCredential(serverId, providerId).getOrThrow()
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "disconnectProvider: removed=$removed")
                if (!removed) {
                    _uiState.update { it.copy(isSaving = false, error = context.getString(R.string.server_settings_provider_disconnect_failed)) }
                    return@launch
                }

                val disposed = providerRepository.disposeGlobal(serverId).getOrElse { false }
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "disconnectProvider: disposed=$disposed")

                // 重新加载前乐观地从已连接集合中移除
                _providerConnected.update { it - providerId }
                rebuildUi()
                loadProviders()

                if (!disposed) {
                    _uiState.update { it.copy(error = context.getString(R.string.server_settings_provider_removed_refresh_failed)) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to disconnect provider", e)
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.server_settings_provider_disconnect_failed)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun setDefaultModel(model: String?) {
        viewModelScope.launch {
            updateConfigPatch(GlobalConfigPatch(model = model))
        }
    }

    fun setSmallModel(model: String?) {
        viewModelScope.launch {
            updateConfigPatch(GlobalConfigPatch(smallModel = model))
        }
    }

    fun setDefaultAgent(agent: String?) {
        viewModelScope.launch {
            updateConfigPatch(GlobalConfigPatch(defaultAgent = agent))
        }
    }

    private suspend fun updateConfigPatch(patch: GlobalConfigPatch) {
        // V2 配置只读（PATCH /api/config → 404，实测确认）——见 backlog #85
        if (!configEditable) {
            _uiState.update { it.copy(error = context.getString(R.string.server_settings_config_update_failed)) }
            return
        }
        val before = _config.value
        try {
            providerRepository.updateGlobalConfig(serverId, patch).getOrThrow()
            _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
            rebuildUi()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e(TAG, "Failed to update config", e)
            _config.value = before
            _uiState.update { it.copy(error = e.message ?: context.getString(R.string.server_settings_config_update_failed)) }
            rebuildUi()
        }
    }

    fun setModelVisible(providerId: String, modelId: String, visible: Boolean) {
        viewModelScope.launch {
            settingsRepository.setModelVisibility(serverId, providerId, modelId, visible)
        }
    }

    private fun rebuildUi() {
        val hidden = _hiddenModels.value
        val disabled = _config.value.disabledProviders.toSet()

        val providerSource = if (_providerCatalog.value.isNotEmpty()) _providerCatalog.value else _allProviders.value
        val providerToggles = providerSource
            .map {
                ProviderToggle(
                    providerId = it.id,
                    providerName = it.name.ifEmpty { it.id },
                    source = it.source.ifEmpty { null },
                    connected = (it.id in _providerConnected.value) && (it.id !in disabled),
                    hasPaidModels = it.models.values.any { model -> model.costInput > 0.0 },
                    enabled = it.id !in disabled
                )
            }
            .sortedWith(
                compareByDescending<ProviderToggle> { it.connected }
                    .thenBy { it.providerName.lowercase() }
            )

        val modelOptions = _allProviders.value
            .filter { it.id !in disabled }
            .flatMap { provider ->
                provider.models.values
                    .filter { modelVisible(hidden, provider.id, it) }
                    .map { model ->
                    ModelOption(
                        key = "${provider.id}/${model.id}",
                        label = "${provider.name.ifEmpty { provider.id }} / ${model.name}"
                    )
                    }
            }
            .sortedBy { it.label.lowercase() }

        val agentOptions = _agents.value
            .filter { it.mode != "subagent" && !it.hidden }
            .map { it.name }
            .distinct()
            .sorted()

        val groups = _allProviders.value
            .mapNotNull { provider ->
                val models = provider.models.values
                    .sortedBy { it.name.lowercase() }
                    .map { model ->
                        ModelToggle(
                            modelId = model.id,
                            modelName = model.name,
                            visible = modelVisible(hidden, provider.id, model)
                        )
                    }
                if (models.isEmpty()) return@mapNotNull null
                ModelGroup(
                    providerId = provider.id,
                    providerName = provider.name.ifEmpty { provider.id },
                    models = models
                )
            }
            .sortedBy { it.providerName.lowercase() }

        _uiState.update {
            it.copy(
                serverName = serverDisplayName,
                providers = providerToggles,
                modelOptions = modelOptions,
                agentOptions = agentOptions,
                selectedModel = _config.value.model,
                selectedSmallModel = _config.value.smallModel,
                selectedDefaultAgent = _config.value.defaultAgent,
                groups = groups,
                authMethods = _authMethods.value,
                pendingOauth = it.pendingOauth,
                isSaving = it.isSaving,
                // #134（D2-L36）：初始加载完成前不清 loading（全部 loader 就绪才显示）
                isLoading = if (initialLoadComplete) false else it.isLoading,
                error = it.error
            )
        }
    }

    private fun modelVisible(hidden: Set<String>, providerId: String, model: ModelCatalog): Boolean {
        return "$providerId:${model.id}" !in hidden
    }


    // ============ #324① DSH provider 目录/自定义增删 ============

    /** DSH 连接构建（config 缓存缺席 → null 调用方直接返回）。 */
    private fun dshConn(): ServerConnection? = serverConfig?.let { ServerConnection.from(it) }

    /** 目录加载（llm/listProviders × listConfigurableProviders × credentials/describe 合流）。 */
    fun loadDshProviderDirectory() {
        val conn = dshConn() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(dshDirectoryLoading = true, dshProviderError = null) }
            try {
                val directory = dshSettingsRepository.listProviderDirectory(conn)
                _uiState.update { it.copy(dshDirectory = directory, dshDirectoryLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is DshSettingsForbiddenException) {
                    // #298 同栅栏：保留目录（可能空）但标注 loopback 只读
                    _uiState.update { it.copy(dshDirectoryLoading = false, dshSettingsBlocked = true) }
                } else {
                    AppLogger.w(TAG, "loadDshProviderDirectory failed: " + e.message)
                    _uiState.update {
                        it.copy(dshDirectoryLoading = false, dshProviderError = e.message ?: "directory load failed")
                    }
                }
            }
        }
    }

    /**
     * llm/discoverModels（表单内探查按钮）：baseURL + 可选 apiKey 即可探测；
     * 失败回 Result.failure 由对话框内联提示（不动全局 error）。
     */
    suspend fun discoverDshModels(baseURL: String, apiKey: String): Result<List<DshDiscoveredModel>> {
        val conn = dshConn() ?: return Result.failure(IllegalStateException("no connection"))
        return try {
            val models = dshSettingsRepository.discoverModels(
                conn,
                dev.leonardo.ocbeacon.domain.model.DshModelDiscoveryRequest(
                    settingsNs = dev.leonardo.ocbeacon.domain.model.DshCustomProviders.SETTINGS_NS,
                    baseURL = baseURL.takeIf { it.isNotBlank() },
                    apiKey = apiKey.takeIf { it.isNotBlank() },
                ),
            )
            Result.success(models)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w(TAG, "discoverDshModels failed: " + e.message)
            Result.failure(e)
        }
    }

    /** 新建自定义 provider（成功后刷新目录；失败内联提示）。 */
    fun createDshCustomProvider(draft: DshCustomProviderDraft, onDone: (Boolean, String?) -> Unit) {
        val conn = dshConn() ?: return
        viewModelScope.launch {
            try {
                val ok = dshSettingsRepository.createCustomProvider(conn, draft)
                if (ok) loadDshProviderDirectory()
                onDone(ok, null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is DshSettingsForbiddenException) {
                    _uiState.update { it.copy(dshSettingsBlocked = true) }
                    onDone(false, context.getString(R.string.dsh_settings_loopback_required))
                } else {
                    AppLogger.w(TAG, "createDshCustomProvider failed: " + e.message)
                    onDone(false, e.message)
                }
            }
        }
    }

    /** 删除自定义 provider（成功后刷新目录）。 */
    fun deleteDshCustomProvider(route: String, onDone: (Boolean, String?) -> Unit) {
        val conn = dshConn() ?: return
        viewModelScope.launch {
            try {
                val ok = dshSettingsRepository.deleteCustomProvider(conn, route)
                if (ok) loadDshProviderDirectory()
                onDone(ok, null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is DshSettingsForbiddenException) {
                    _uiState.update { it.copy(dshSettingsBlocked = true) }
                    onDone(false, context.getString(R.string.dsh_settings_loopback_required))
                } else {
                    AppLogger.w(TAG, "deleteDshCustomProvider failed: " + e.message)
                    onDone(false, e.message)
                }
            }
        }
    }

}
