package dev.leonardo.ocbeacon.ui.screens.home

import dev.leonardo.ocbeacon.logging.AppLogger

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import java.util.UUID
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageServerProvidersUseCase
import dev.leonardo.ocbeacon.domain.usecase.UpdateSettingsUseCase
import dev.leonardo.ocbeacon.service.OpenCodeConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

data class HomeUiState(
    val servers: List<ServerConfig> = emptyList(),
    val connectedServerIds: Set<String> = emptySet(),
    val serverSettingsReadyIds: Set<String> = emptySet(),
    val connectingServerIds: Set<String> = emptySet(),
    val connectionErrors: Map<String, String> = emptyMap(),
    val showAddServerDialog: Boolean = false,
    val editingServer: ServerConfig? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val serverRepository: ServerRepository,
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    private val manageServerProvidersUseCase: ManageServerProvidersUseCase,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 当前设置的快照，从 [GetSettingsFlowUseCase] flow 更新。 */
    private var currentSettings: AppSettings = AppSettings()

    private var serviceBinder: OpenCodeConnectionService.LocalBinder? = null
    private var sseObserverJob: Job? = null
    private val serverSettingsCheckJobs = mutableMapOf<String, Job>()

    /** 进行中的连接尝试（testConnection 阶段），支持取消。 */
    private val connectJobs = mutableMapOf<String, Job>()

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
    }

    private fun observeSettings() {
        viewModelScope.launch {
            getSettingsFlowUseCase().collect { settings ->
                currentSettings = settings
            }
        }
    }

    /**
     * 从已在运行的服务恢复已连接状态。
     */
    private fun restoreConnectionStateFromService() {
        val service = serviceBinder?.getService() ?: return
        val ids = service.connectedServerIds.value
        if (ids.isNotEmpty()) {
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Restoring connected state from service: serverIds=$ids")
            _uiState.update { it.copy(connectedServerIds = ids) }
        }
    }

    /**
     * 观察服务中的 connectedServerIds 和 connectingServerIds。
     */
    private fun observeServiceConnectionState() {
        sseObserverJob?.cancel()
        val service = serviceBinder?.getService() ?: return
        sseObserverJob = viewModelScope.launch {
            launch {
                service.connectedServerIds.collect { ids ->
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "Service connected server IDs changed: $ids")
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
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "Service connecting server IDs changed: $ids")
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
        // 取消对已断开服务器的检查
        val disconnected = serverSettingsCheckJobs.keys - connectedIds
        disconnected.forEach { id ->
            serverSettingsCheckJobs.remove(id)?.cancel()
        }

        // 为已连接的服务器启动或重启检查
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
                } catch (ce: CancellationException) {
                    // 协程取消必须传播（#128 根因：吞掉取消 → 取消链 handler 异常 → 主线程崩溃）
                    throw ce
                } catch (e: Exception) {
                    _uiState.update { it.copy(serverSettingsReadyIds = it.serverSettingsReadyIds - serverId) }
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "Providers check failed for $serverId: ${e.message}")
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
            // 如已连接或正在连接，先断开
            if (_uiState.value.connectedServerIds.contains(serverId) ||
                _uiState.value.connectingServerIds.contains(serverId)) {
                disconnectFromServer(serverId)
            }
            serverRepository.removeServer(serverId)
        }
    }

    /**
     * 连接到指定服务器。支持同时连接多个服务器。
     */
    fun connectToServer(serverId: String) {
        val server = _uiState.value.servers.find { it.id == serverId } ?: return

        // 已连接或正在连接？直接返回。
        if (_uiState.value.connectedServerIds.contains(serverId) ||
            _uiState.value.connectingServerIds.contains(serverId)) return

        // backlog #34：同后端第二连接预检 —— 若该后端已通过另一服务器条目连接，
        // 直接拒绝并提示，避免 Service 静默拒绝导致 UI 永久显示 "Connecting"。
        val duplicate = serviceBinder?.getService()?.findDuplicateBackend(server.url, server.username)
        if (duplicate != null) {
            AppLogger.w(TAG, "Server '${server.displayName}' shares backend with already-connected '${duplicate.displayName}', rejecting duplicate connection")
            _uiState.update {
                it.copy(
                    connectionErrors = it.connectionErrors + (serverId to getApplication<Application>().getString(R.string.home_error_already_connected))
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                connectingServerIds = it.connectingServerIds + serverId,
                connectionErrors = it.connectionErrors - serverId
            )
        }

        // 取消之前的连接尝试（若有），保存当前 job 供取消使用。
        connectJobs[serverId]?.cancel()
        connectJobs[serverId] = viewModelScope.launch {
            try {
                val isHealthy = serverRepository.testConnection(server).getOrElse { false }
                // 用户已取消：不再处理结果。
                if (!isActive) return@launch
                if (!isHealthy) {
                    _uiState.update {
                        it.copy(
                            connectingServerIds = it.connectingServerIds - serverId,
                            connectionErrors = it.connectionErrors + (serverId to "Server is not responding")
                        )
                    }
                    return@launch
                }

                // 健康检查通过后、启动服务前再次确认未被取消，
                // 避免用户取消后仍启动连接。
                if (!isActive) return@launch

                val context = getApplication<Application>()
                val intent = Intent(context, OpenCodeConnectionService::class.java).apply {
                    putExtra("server_id", server.id)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // 连接状态将由服务通过
                // observeServiceConnectionState() 更新 — 无需乐观更新。
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
                _uiState.update {
                    it.copy(
                        connectingServerIds = it.connectingServerIds - serverId,
                        connectionErrors = it.connectionErrors + (serverId to (e.message ?: "Connection failed"))
                    )
                }
            } finally {
                connectJobs.remove(serverId)
            }
        }
    }

    /**
     * 断开与指定服务器的连接。
     *
     * 同时处理两种状态：
     * - 连接进行中（testConnection 阶段）：取消协程并立即清除 connecting 状态；
     * - 已连接：通知服务断开。
     */
    fun disconnectFromServer(serverId: String) {
        connectJobs.remove(serverId)?.cancel()
        serviceBinder?.getService()?.disconnect(serverId)
        _uiState.update {
            it.copy(
                connectedServerIds = it.connectedServerIds - serverId,
                connectingServerIds = it.connectingServerIds - serverId
            )
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
            // 服务可能尚未绑定
            AppLogger.w(TAG, "unbindService failed: ${e.message}", e)
        }
    }
}
