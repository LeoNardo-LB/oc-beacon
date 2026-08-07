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
import dev.leonardo.ocbeacon.domain.model.GlobalConfig
import dev.leonardo.ocbeacon.domain.model.GlobalConfigPatch
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderAuthMethod
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderOauthAuthorization
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.ui.navigation.routes.safeDecodeParam
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
    val error: String? = null
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
    private val serverConfigRepository: ServerConfigRepository
) : ViewModel() {

    private val serverId: String = safeDecodeParam(
        savedStateHandle.get<String>("serverId") ?: ""
    )
    private var serverDisplayName: String = ""

    private val _allProviders = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _providerCatalog = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _providerConnected = MutableStateFlow<Set<String>>(emptySet())
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    private val _config = MutableStateFlow(GlobalConfig())
    private val _authMethods = MutableStateFlow<Map<String, List<ProviderAuthMethod>>>(emptyMap())
    private val _hiddenModels = MutableStateFlow<Set<String>>(emptySet())
    private val _uiState = MutableStateFlow(ServerSettingsUiState(isLoading = true))
    val uiState: StateFlow<ServerSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val config = serverConfigRepository.getServer(serverId)
            if (config != null) {
                serverDisplayName = config.displayName
                _uiState.update { it.copy(serverName = serverDisplayName) }
            }
        }
        viewModelScope.launch {
            settingsRepository.hiddenModels(serverId).collect { hidden ->
                _hiddenModels.value = hidden
                rebuildUi()
            }
        }
        loadProviders()
        loadConfig()
        loadAgents()
        loadAuthMethods()
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
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load providers", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: context.getString(R.string.server_settings_providers_load_failed)
                    )
                }
            }
        }
    }

    private fun loadAuthMethods() {
        viewModelScope.launch {
            try {
                _authMethods.value = providerRepository.getProviderAuthMethods(serverId).getOrThrow()
                rebuildUi()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load auth methods", e)
            }
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            try {
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                rebuildUi()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load config", e)
            }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                _agents.value = agentRepository.listAgents(serverId).getOrThrow()
                rebuildUi()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load agents", e)
            }
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
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
                val disabled = _config.value.disabledProviders.toSet() - providerId
                providerRepository.updateGlobalConfig(
                    serverId,
                    GlobalConfigPatch(disabledProviders = disabled.toList().sorted())
                ).getOrThrow()
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                loadProviders()
            } catch (e: Exception) {
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
                providerRepository.updateGlobalConfig(
                    serverId,
                    GlobalConfigPatch(disabledProviders = disabled.toList().sorted())
                ).getOrThrow()
                _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
                _uiState.update { it.copy(pendingOauth = null) }
                loadProviders()
            } catch (e: Exception) {
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
                val removed = providerRepository.removeProviderAuth(serverId, providerId).getOrThrow()
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
        val before = _config.value
        try {
            providerRepository.updateGlobalConfig(serverId, patch).getOrThrow()
            _config.value = providerRepository.getGlobalConfig(serverId).getOrThrow()
            rebuildUi()
        } catch (e: Exception) {
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
                isLoading = false,
                error = it.error
            )
        }
    }

    private fun modelVisible(hidden: Set<String>, providerId: String, model: ModelCatalog): Boolean {
        return "$providerId:${model.id}" !in hidden
    }

}
