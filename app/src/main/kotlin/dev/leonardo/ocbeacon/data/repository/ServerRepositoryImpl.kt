package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse as DataProvidersResponse
import dev.leonardo.ocbeacon.domain.model.LocalServerState
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderInfo as DomainProviderInfo
import dev.leonardo.ocbeacon.domain.model.ModelInfo
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ServerRepository].
 * Wraps the existing [ServerDataStore] (DataStore CRUD),
 * [LocalServerManager] (Termux lifecycle), and [ProviderApi] (providers/config).
 */
@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val dataRepo: dev.leonardo.ocbeacon.data.repository.ServerDataStore,
    private val localServerManager: LocalServerManager,
    private val api: ProviderApi,
    @ApplicationContext private val appContext: Context
) : ServerRepository {

    // ── Server CRUD ──

    override fun getServersFlow(): Flow<List<ServerConfig>> = dataRepo.getAllServers()

    override suspend fun addServer(config: ServerConfig): Result<Unit> = runCatching {
        dataRepo.addServer(
            url = config.url,
            username = config.username,
            password = config.password,
            name = config.name,
            autoConnect = config.autoConnect
        )
    }

    override suspend fun removeServer(id: String): Result<Unit> = runCatching {
        dataRepo.deleteServer(id)
    }

    override suspend fun updateServer(server: ServerConfig): Result<Unit> = runCatching {
        dataRepo.updateServer(server)
    }

    override suspend fun getServer(id: String): ServerConfig? = dataRepo.getServer(id)

    // ── Connection lifecycle ──

    override suspend fun connect(server: ServerConfig): Result<Unit> = runCatching {
        // Phase 4: delegate to OpenCodeConnectionService.connect(server)
        throw NotImplementedError("ServerRepository.connect — Phase 4")
    }

    override suspend fun disconnect(serverId: String): Result<Unit> = runCatching {
        // Phase 4: delegate to OpenCodeConnectionService.disconnect(serverId)
        throw NotImplementedError("ServerRepository.disconnect — Phase 4")
    }

    override suspend fun testConnection(server: ServerConfig): Result<Boolean> = runCatching {
        dataRepo.checkHealth(server).isSuccess
    }

    // ── Local server management ──

    override fun getLocalSetupCommand(): String = localServerManager.getSetupCommand()

    override suspend fun setupLocalServer(): Result<Unit> = runCatching {
        if (localServerManager.isTermuxInstalled()) Unit
        else throw IllegalStateException("Termux is not installed")
    }

    override suspend fun startLocalServer(): Result<Unit> = runCatching {
        localServerManager.startServer(appContext)
    }

    override suspend fun stopLocalServer(): Result<Unit> = runCatching {
        localServerManager.stopServer(appContext)
    }

    override suspend fun getLocalServerState(): Result<LocalServerState> = runCatching {
        if (!localServerManager.isTermuxInstalled()) {
            LocalServerState(status = "unavailable", message = "Termux is not installed")
        } else {
            val healthy = localServerManager.isServerHealthy()
            if (healthy) {
                LocalServerState(status = "running")
            } else {
                LocalServerState(status = "stopped")
            }
        }
    }

    // ── Provider management ──

    override suspend fun loadProviders(serverId: String): Result<List<DomainProviderInfo>> = runCatching {
        val conn = resolveConnection(serverId)
        val catalog = api.listProviderCatalog(conn)
        val connected = catalog.connected.toSet()
        catalog.all.map { dto ->
            DomainProviderInfo(
                id = dto.id,
                name = dto.name,
                enabled = dto.id in connected,
                connected = dto.id in connected,
                models = dto.models.values.map { model ->
                    ModelInfo(
                        id = model.id,
                        name = model.name,
                        visible = true
                    )
                }
            )
        }
    }

    override suspend fun loadProviderCatalog(serverId: String): Result<ProvidersResponse> = runCatching {
        val conn = resolveConnection(serverId)
        val response: DataProvidersResponse = api.getProviders(conn)
        ProvidersResponse(
            providers = response.providers.map { dto ->
                ProviderCatalog(
                    id = dto.id,
                    name = dto.name,
                    source = dto.source,
                    models = dto.models.mapValues { (_, model) ->
                        ModelCatalog(
                            id = model.id,
                            name = model.name,
                            contextWindow = model.limit?.context ?: 0,
                            costInput = model.cost?.input ?: 0.0,
                            variantNames = model.variants?.keys?.toList()?.sorted() ?: emptyList()
                        )
                    }
                )
            },
            default = response.default
        )
    }

    override suspend fun setProviderEnabled(
        serverId: String,
        providerId: String,
        enabled: Boolean
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        if (enabled) {
            // Phase 4: implement provider enable/disable via config API
        } else {
            api.removeProviderAuth(conn, providerId)
        }
    }

    override suspend fun connectProviderApi(
        serverId: String,
        providerId: String,
        apiKey: String
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        api.setProviderApiKey(conn, providerId, apiKey)
    }

    override suspend fun disconnectProvider(
        serverId: String,
        providerId: String
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        api.removeProviderAuth(conn, providerId)
    }

    override suspend fun setModelVisible(
        serverId: String,
        providerId: String,
        modelId: String,
        visible: Boolean
    ): Result<Unit> = runCatching {
        // Delegate to SettingsRepository's hidden models tracking
        // This will be properly wired in Phase 4
        Unit
    }

    override suspend fun saveServerConfig(serverId: String): Result<Unit> = runCatching {
        // Phase 4: persist server-side config
        Unit
    }

    // ── Repository Helpers ──

    override suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = dataRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }
}
