package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse as DataProvidersResponse
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
 * [ServerRepository] 的实现。
 * 包装现有的 [ServerDataStore]（DataStore CRUD）和 [ProviderApi]（providers/config）。
 */
@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val dataRepo: dev.leonardo.ocbeacon.data.repository.ServerDataStore,
    private val api: ProviderApi
) : ServerRepository {

    // ── 服务器 CRUD ──

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

    // ── 连接生命周期 ──

    override suspend fun connect(server: ServerConfig): Result<Unit> = runCatching {
        // Phase 4：委托给 OpenCodeConnectionService.connect(server)
        throw NotImplementedError("ServerRepository.connect — Phase 4")
    }

    override suspend fun disconnect(serverId: String): Result<Unit> = runCatching {
        // Phase 4：委托给 OpenCodeConnectionService.disconnect(serverId)
        throw NotImplementedError("ServerRepository.disconnect — Phase 4")
    }

    override suspend fun testConnection(server: ServerConfig): Result<Boolean> = runCatching {
        dataRepo.checkHealth(server).isSuccess
    }

    // ── 提供商管理 ──

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
            // Phase 4：通过 config API 实现 provider 的启用/禁用
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
        // 委托给 SettingsRepository 的隐藏模型跟踪
        // 这将在阶段 4 中正确接入
        Unit
    }

    override suspend fun saveServerConfig(serverId: String): Result<Unit> = runCatching {
        // Phase 4：持久化服务端配置
        Unit
    }

    // ── Repository 辅助方法 ──

    override suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = dataRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }
}
