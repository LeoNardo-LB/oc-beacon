package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.GlobalConfig
import dev.leonardo.ocbeacon.domain.model.GlobalConfigPatch
import dev.leonardo.ocbeacon.domain.model.ProviderAuthMethod
import dev.leonardo.ocbeacon.domain.model.ProviderConnectionStatus
import dev.leonardo.ocbeacon.domain.model.ProviderInfo
import dev.leonardo.ocbeacon.domain.model.ProviderOauthAuthorization
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

/**
 * 实现全部 3 个 server 相关接口的 Fake。
 * DomainModule 将单个 ServerRepositoryImpl 绑定为全部 3 个接口；
 * FakeDomainModule 以同样方式绑定此单个实例。
 */
@Singleton
class FakeServerRepository @Inject constructor() :
    ServerRepository,
    ServerConfigRepository,
    ProviderRepository {

    // ============ ServerConfigRepository ============

    val serversState = MutableStateFlow<List<ServerConfig>>(emptyList())

    override fun getServersFlow(): Flow<List<ServerConfig>> = serversState

    override suspend fun addServer(config: ServerConfig): Result<Unit> {
        serversState.value = serversState.value + config
        return Result.success(Unit)
    }

    override suspend fun removeServer(id: String): Result<Unit> {
        serversState.value = serversState.value.filterNot { it.id == id }
        return Result.success(Unit)
    }

    override suspend fun updateServer(server: ServerConfig): Result<Unit> {
        serversState.value = serversState.value.map { if (it.id == server.id) server else it }
        return Result.success(Unit)
    }

    override suspend fun getServer(id: String): ServerConfig? =
        serversState.value.find { it.id == id }

    override suspend fun testConnection(server: ServerConfig): Result<Boolean> =
        Result.success(true)

    // ============ ProviderRepository ============

    var providersResult: Result<List<ProviderInfo>> = Result.success(emptyList())
    var catalogResult: Result<ProvidersResponse> = Result.success(ProvidersResponse(emptyList()))

    override suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>> = providersResult

    override suspend fun loadProviderCatalog(serverId: String): Result<ProvidersResponse> = catalogResult

    override suspend fun connectProviderApi(
        serverId: String,
        providerId: String,
        apiKey: String
    ): Result<Unit> = Result.success(Unit)

    override suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun loadProviderConnectionStatus(
        serverId: String
    ): Result<ProviderConnectionStatus> =
        Result.success(ProviderConnectionStatus(providers = emptyList(), connected = emptySet()))

    override suspend fun getGlobalConfig(serverId: String): Result<GlobalConfig> =
        Result.success(GlobalConfig())

    override suspend fun updateGlobalConfig(
        serverId: String,
        patch: GlobalConfigPatch
    ): Result<GlobalConfig> = Result.success(
        GlobalConfig(
            disabledProviders = patch.disabledProviders ?: emptyList(),
            model = patch.model,
            smallModel = patch.smallModel,
            defaultAgent = patch.defaultAgent
        )
    )

    override suspend fun getProviderAuthMethods(
        serverId: String
    ): Result<Map<String, List<ProviderAuthMethod>>> = Result.success(emptyMap())

    override suspend fun authorizeProviderOauth(
        serverId: String,
        providerId: String,
        methodIndex: Int
    ): Result<ProviderOauthAuthorization?> = Result.success(null)

    override suspend fun completeProviderOauth(
        serverId: String,
        providerId: String,
        methodIndex: Int,
        code: String?
    ): Result<Boolean> = Result.success(true)

    override suspend fun removeProviderAuth(
        serverId: String,
        providerId: String
    ): Result<Boolean> = Result.success(true)

    override suspend fun disposeGlobal(serverId: String): Result<Boolean> = Result.success(true)

    // ============ ServerRepository ============

    override suspend fun resolveConnection(serverId: String): ServerConnection =
        ServerConnection.from("http://localhost:4096", "opencode", "test")
}
