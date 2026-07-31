package dev.leonardo.ocbeacon.ui.screens.home

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.leonardo.ocbeacon.data.repository.LocalServerManager
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import java.util.UUID
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageServerProvidersUseCase
import dev.leonardo.ocbeacon.domain.usecase.UpdateSettingsUseCase
import dev.leonardo.ocbeacon.service.OpenCodeConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

enum class LocalRuntimeStatus {
    Unavailable,
    NeedsSetup,
    Stopped,
    Starting,
    Stopping,
    Running,
    Error,
}

data class HomeUiState(
    val servers: List<ServerConfig> = emptyList(),
    val connectedServerIds: Set<String> = emptySet(),
    val serverSettingsReadyIds: Set<String> = emptySet(),
    val connectingServerIds: Set<String> = emptySet(),
    val connectionErrors: Map<String, String> = emptyMap(),
    val showAddServerDialog: Boolean = false,
    val editingServer: ServerConfig? = null,
    val isLoading: Boolean = true,
    val termuxInstalled: Boolean = false,
    val localRuntimeStatus: LocalRuntimeStatus = LocalRuntimeStatus.Unavailable,
    val localRuntimeMessage: String? = null,
    val localRuntimeFixCommand: String? = null,
    val localRuntimeNeedsOverlaySettings: Boolean = false,
    val setupCommand: String? = null,
    val showLocalRuntime: Boolean = true,
    val localProxyEnabled: Boolean = false,
    val localProxyUrl: String = "",
    val localProxyNoProxy: String = LocalServerManager.DEFAULT_NO_PROXY_LIST,
    val localServerAllowLan: Boolean = false,
    val localServerUsername: String = "",
    val localServerPassword: String = "",
    val localServerRunInBackground: Boolean = true,
    val localServerAutoStart: Boolean = false,
    val localServerStartupTimeoutSec: Int = 30,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val serverRepository: ServerRepository,
    private val localServerManager: LocalServerManager,
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    private val manageServerProvidersUseCase: ManageServerProvidersUseCase,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Snapshot of current settings, updated from [GetSettingsFlowUseCase] flow. */
    private var currentSettings: AppSettings = AppSettings()

    private var serviceBinder: OpenCodeConnectionService.LocalBinder? = null
    private var sseObserverJob: Job? = null
    private val serverSettingsCheckJobs = mutableMapOf<String, Job>()

    private val localServerDelegate = LocalServerDelegate(
        application = getApplication(),
        scope = viewModelScope,
        localServerManager = localServerManager,
        updateSettingsUseCase = updateSettingsUseCase,
        serverRepository = serverRepository,
        uiState = _uiState,
        currentSettingsProvider = { currentSettings },
        connectToServer = ::connectToServer,
        disconnectFromServer = ::disconnectFromServer,
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBinder = service as? OpenCodeConnectionService.LocalBinder
            restoreConnectionStateFromService()
            observeServiceConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            sseObserverJob?.cancel()
            sseObserverJob = null
            _uiState.update { it.copy(connectedServerIds = emptySet()) }
        }
    }

    init {
        loadServers()
        bindToService()
        observeSettings()
        refreshLocalRuntimeState()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            getSettingsFlowUseCase().collect { settings ->
                currentSettings = settings
                _uiState.update { state ->
                    state.copy(
                        showLocalRuntime = settings.showLocalRuntime,
                        localProxyEnabled = settings.localProxyEnabled,
                        localProxyUrl = settings.localProxyUrl,
                        localProxyNoProxy = settings.localProxyNoProxy.ifEmpty {
                            LocalServerManager.DEFAULT_NO_PROXY_LIST
                        },
                        localServerAllowLan = settings.localServerAllowLan,
                        localServerUsername = settings.localServerUsername,
                        localServerPassword = settings.localServerPassword,
                        localServerRunInBackground = settings.localServerRunInBackground,
                        localServerAutoStart = settings.localServerAutoStart,
                        localServerStartupTimeoutSec = settings.localServerStartupTimeoutSec,
                    )
                }
            }
        }
    }

    /**
     * Restore connected state from the already-running service.
     */
    private fun restoreConnectionStateFromService() {
        val service = serviceBinder?.getService() ?: return
        val ids = service.connectedServerIds.value
        if (ids.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Restoring connected state from service: serverIds=$ids")
            _uiState.update { it.copy(connectedServerIds = ids) }
        }
    }

    /**
     * Observe connectedServerIds and connectingServerIds from the service.
     */
    private fun observeServiceConnectionState() {
        sseObserverJob?.cancel()
        val service = serviceBinder?.getService() ?: return
        sseObserverJob = viewModelScope.launch {
            launch {
                service.connectedServerIds.collect { ids ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "Service connected server IDs changed: $ids")
                    _uiState.update {
                        it.copy(
                            connectedServerIds = ids,
                            serverSettingsReadyIds = it.serverSettingsReadyIds.intersect(ids)
                        )
                    }
                    refreshServerSettingsAvailability(ids)
                }
            }
            launch {
                service.connectingServerIds.collect { ids ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "Service connecting server IDs changed: $ids")
                    _uiState.update { it.copy(connectingServerIds = ids) }
                }
            }
        }
    }

    private fun loadServers() {
        viewModelScope.launch {
            serverRepository.getServersFlow().collect { servers ->
                _uiState.update { 
                    it.copy(
                        servers = servers,
                        isLoading = false
                    )
                }
                refreshServerSettingsAvailability(_uiState.value.connectedServerIds)
            }
        }
    }

    private fun refreshServerSettingsAvailability(connectedIds: Set<String>) {
        // Cancel checks for disconnected servers
        val disconnected = serverSettingsCheckJobs.keys - connectedIds
        disconnected.forEach { id ->
            serverSettingsCheckJobs.remove(id)?.cancel()
        }

        // Start or restart checks for connected servers
        connectedIds.forEach { serverId ->
            serverSettingsCheckJobs.remove(serverId)?.cancel()
            serverSettingsCheckJobs[serverId] = viewModelScope.launch {
                val server = _uiState.value.servers.find { it.id == serverId }
                if (server == null) {
                    _uiState.update { it.copy(serverSettingsReadyIds = it.serverSettingsReadyIds - serverId) }
                    return@launch
                }

                try {
                    val result = manageServerProvidersUseCase.loadProviders(serverId)
                    val hasModels = result.getOrNull()?.any { it.models.isNotEmpty() } == true
                    _uiState.update {
                        it.copy(
                            serverSettingsReadyIds = if (hasModels) {
                                it.serverSettingsReadyIds + serverId
                            } else {
                                it.serverSettingsReadyIds - serverId
                            }
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(serverSettingsReadyIds = it.serverSettingsReadyIds - serverId) }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Providers check failed for $serverId: ${e.message}")
                }
            }
        }
    }

    private fun bindToService() {
        val intent = Intent(getApplication(), OpenCodeConnectionService::class.java)
        getApplication<Application>().bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun showAddServerDialog() {
        _uiState.update { it.copy(showAddServerDialog = true, editingServer = null) }
    }

    fun showEditServerDialog(server: ServerConfig) {
        _uiState.update { it.copy(showAddServerDialog = true, editingServer = server) }
    }

    fun hideServerDialog() {
        _uiState.update { it.copy(showAddServerDialog = false, editingServer = null) }
    }

    fun saveServer(
        name: String,
        url: String,
        username: String,
        password: String,
        autoConnect: Boolean
    ) {
        viewModelScope.launch {
            val editingServer = _uiState.value.editingServer
            
            if (editingServer != null) {
                val updatedServer = editingServer.copy(
                    name = name,
                    url = url,
                    username = username,
                    password = password,
                    autoConnect = autoConnect
                )
                serverRepository.updateServer(updatedServer)
            } else {
                serverRepository.addServer(
                    ServerConfig(
                        id = UUID.randomUUID().toString(),
                        url = url.trimEnd('/'),
                        username = username,
                        password = password,
                        name = name,
                        autoConnect = autoConnect,
                    )
                )
            }
            
            hideServerDialog()
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            // Disconnect first if connected or connecting
            if (_uiState.value.connectedServerIds.contains(serverId) ||
                _uiState.value.connectingServerIds.contains(serverId)) {
                disconnectFromServer(serverId)
            }
            serverRepository.removeServer(serverId)
        }
    }

    /**
     * Connect to a specific server. Multiple servers can be connected simultaneously.
     */
    fun connectToServer(serverId: String) {
        val server = _uiState.value.servers.find { it.id == serverId } ?: return

        // Already connected or connecting? No-op.
        if (_uiState.value.connectedServerIds.contains(serverId) ||
            _uiState.value.connectingServerIds.contains(serverId)) return

        _uiState.update {
            it.copy(
                connectingServerIds = it.connectingServerIds + serverId,
                connectionErrors = it.connectionErrors - serverId
            )
        }

        viewModelScope.launch {
            try {
                val isHealthy = serverRepository.testConnection(server).getOrElse { false }
                if (!isHealthy) {
                    _uiState.update {
                        it.copy(
                            connectingServerIds = it.connectingServerIds - serverId,
                            connectionErrors = it.connectionErrors + (serverId to "Server is not responding")
                        )
                    }
                    return@launch
                }

                val context = getApplication<Application>()
                val intent = Intent(context, OpenCodeConnectionService::class.java).apply {
                    putExtra("server_id", server.id)
                    putExtra("server_name", server.name)
                    putExtra("server_url", server.url)
                    putExtra("server_username", server.username)
                    putExtra("server_password", server.password)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // Connection state will be updated by the service via
                // observeServiceConnectionState() — no optimistic update needed.
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        connectingServerIds = it.connectingServerIds - serverId,
                        connectionErrors = it.connectionErrors + (serverId to (e.message ?: "Connection failed"))
                    )
                }
            }
        }
    }

    fun refreshLocalRuntimeState() {
        localServerDelegate.refreshLocalRuntimeState()
    }

    /**
     * Copy the setup command and open Termux so the user can paste it.
     */
    fun setupLocalServer(callerContext: Context) {
        localServerManager.openTermux(callerContext)
    }

    fun getLocalSetupCommand(): String = localServerManager.getSetupCommand()

    fun startLocalServer(callerContext: Context) {
        localServerDelegate.startLocalServer(callerContext)
    }

    fun stopLocalServer(callerContext: Context) {
        localServerDelegate.stopLocalServer(callerContext)
    }

    // ── Settings setters via UpdateSettingsUseCase ──

    fun setLocalProxyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            updateSettingsUseCase(currentSettings.copy(localProxyEnabled = enabled))
        }
    }

    fun setLocalProxyUrl(url: String) {
        viewModelScope.launch {
            updateSettingsUseCase(currentSettings.copy(localProxyUrl = url))
        }
    }

    fun setLocalProxyNoProxy(value: String) {
        viewModelScope.launch {
            updateSettingsUseCase(currentSettings.copy(localProxyNoProxy = value))
        }
    }

    fun setLocalServerAllowLan(enabled: Boolean) {
        viewModelScope.launch {
            updateSettingsUseCase(currentSettings.copy(localServerAllowLan = enabled))
        }
    }

    fun setLocalServerUsername(value: String) {
        viewModelScope.launch {
            updateSettingsUseCase(currentSettings.copy(localServerUsername = value))
        }
    }

    fun setLocalServerPassword(value: String) {
        viewModelScope.launch {
            updateSettingsUseCase(currentSettings.copy(localServerPassword = value))
        }
    }

    fun setLocalServerRunInBackground(enabled: Boolean) {
        viewModelScope.launch {
            updateSettingsUseCase(
                currentSettings.copy(
                    localServerRunInBackground = enabled,
                    localServerAutoStart = if (!enabled) false else currentSettings.localServerAutoStart,
                )
            )
        }
    }

    fun setLocalServerAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            val canEnable = enabled && currentSettings.localServerRunInBackground
            updateSettingsUseCase(
                currentSettings.copy(localServerAutoStart = canEnable)
            )
        }
    }

    fun setLocalServerStartupTimeoutSec(value: Int) {
        viewModelScope.launch {
            updateSettingsUseCase(currentSettings.copy(localServerStartupTimeoutSec = value))
        }
    }

    /**
     * Disconnect from a specific server.
     */
    fun disconnectFromServer(serverId: String) {
        serviceBinder?.getService()?.disconnect(serverId)
        _uiState.update {
            it.copy(connectedServerIds = it.connectedServerIds - serverId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseObserverJob?.cancel()
        serverSettingsCheckJobs.values.forEach { it.cancel() }
        serverSettingsCheckJobs.clear()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {
            // Service might not be bound
            Log.w(TAG, "unbindService failed: ${e.message}", e)
        }
    }
}
