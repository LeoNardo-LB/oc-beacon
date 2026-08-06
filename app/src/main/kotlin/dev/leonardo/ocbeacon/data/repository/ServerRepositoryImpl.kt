package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.mapper.ConfigMapper
import dev.leonardo.ocbeacon.data.mapper.ProviderMapper
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse as DataProvidersResponse
import dev.leonardo.ocbeacon.domain.model.GlobalConfig
import dev.leonardo.ocbeacon.domain.model.GlobalConfigPatch
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderAuthMethod
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderConnectionStatus
import dev.leonardo.ocbeacon.domain.model.ProviderInfo as DomainProviderInfo
import dev.leonardo.ocbeacon.domain.model.ProviderOauthAuthorization
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

    override suspend fun connectProviderApi(
        serverId: String,
        providerId: String,
        apiKey: String
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        val success = api.setProviderApiKey(conn, providerId, apiKey)
        check(success) { "Failed to set provider API key" }
        Unit
    }

    override suspend fun disconnectProvider(
        serverId: String,
        providerId: String
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        api.removeProviderAuth(conn, providerId)
    }

    // ── Provider 连接状态与全局配置 ──

    override suspend fun loadProviderConnectionStatus(
        serverId: String
    ): Result<ProviderConnectionStatus> = runCatching {
        val conn = resolveConnection(serverId)
        val catalog = api.listProviderCatalog(conn)
        ProviderMapper.toConnectionStatus(catalog)
    }

    override suspend fun getGlobalConfig(serverId: String): Result<GlobalConfig> = runCatching {
        val conn = resolveConnection(serverId)
        ConfigMapper.toDomain(api.getGlobalConfig(conn))
    }

    override suspend fun updateGlobalConfig(
        serverId: String,
        patch: GlobalConfigPatch
    ): Result<GlobalConfig> = runCatching {
        val conn = resolveConnection(serverId)
        val dtoPatch = ConfigMapper.toDto(patch)
        ConfigMapper.toDomain(api.updateGlobalConfig(conn, dtoPatch))
    }

    override suspend fun getProviderAuthMethods(
        serverId: String
    ): Result<Map<String, List<ProviderAuthMethod>>> = runCatching {
        val conn = resolveConnection(serverId)
        ProviderMapper.toDomainAuthMethods(api.getProviderAuthMethods(conn))
    }

    override suspend fun authorizeProviderOauth(
        serverId: String,
        providerId: String,
        methodIndex: Int
    ): Result<ProviderOauthAuthorization?> = runCatching {
        val conn = resolveConnection(serverId)
        api.authorizeProviderOauth(conn, providerId, methodIndex)?.let { ProviderMapper.toDomain(it) }
    }

    override suspend fun completeProviderOauth(
        serverId: String,
        providerId: String,
        methodIndex: Int,
        code: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        api.completeProviderOauth(conn, providerId, methodIndex, code)
    }

    override suspend fun removeProviderAuth(
        serverId: String,
        providerId: String
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        api.removeProviderAuth(conn, providerId)
    }

    override suspend fun disposeGlobal(serverId: String): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        api.disposeGlobal(conn)
    }

    // ── Repository 辅助方法 ──

    override suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = dataRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }
}
