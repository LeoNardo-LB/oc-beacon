package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.LocalServerState
import dev.leonardo.octether.domain.model.ProviderInfo
import dev.leonardo.octether.domain.model.ProvidersResponse
import dev.leonardo.octether.domain.model.ServerConfig
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.domain.repository.LocalServerRepository
import dev.leonardo.octether.domain.repository.ProviderRepository
import dev.leonardo.octether.domain.repository.ServerConfigRepository
import dev.leonardo.octether.domain.repository.ServerConnectionRepository
import dev.leonardo.octether.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

/**
 * Fake implementing all 5 server-related interfaces.
 * DomainModule binds a single ServerRepositoryImpl as all 5 interfaces;
 * FakeDomainModule binds this single instance the same way.
 */
@Singleton
class FakeServerRepository @Inject constructor() :
    ServerRepository,
    ServerConfigRepository,
    ServerConnectionRepository,
    LocalServerRepository,
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

    // ============ ServerConnectionRepository ============

    val connectedServers = mutableSetOf<String>()

    override suspend fun connect(server: ServerConfig): Result<Unit> {
        connectedServers.add(server.id)
        return Result.success(Unit)
    }

    override suspend fun disconnect(serverId: String): Result<Unit> {
        connectedServers.remove(serverId)
        return Result.success(Unit)
    }

    override suspend fun testConnection(server: ServerConfig): Result<Boolean> =
        Result.success(true)

    // ============ LocalServerRepository ============

    var fakeLocalSetupCommand: String = ""
    var localServerStateResult: Result<LocalServerState> = Result.success(LocalServerState())

    override fun getLocalSetupCommand(): String = fakeLocalSetupCommand

    override suspend fun setupLocalServer(): Result<Unit> = Result.success(Unit)

    override suspend fun startLocalServer(): Result<Unit> = Result.success(Unit)

    override suspend fun stopLocalServer(): Result<Unit> = Result.success(Unit)

    override suspend fun getLocalServerState(): Result<LocalServerState> = localServerStateResult

    // ============ ProviderRepository ============

    var providersResult: Result<List<ProviderInfo>> = Result.success(emptyList())
    var catalogResult: Result<ProvidersResponse> = Result.success(ProvidersResponse(emptyList()))

    override suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>> = providersResult

    override suspend fun loadProviderCatalog(serverId: String): Result<ProvidersResponse> = catalogResult

    override suspend fun setProviderEnabled(
        serverId: String,
        providerId: String,
        enabled: Boolean
    ): Result<Unit> = Result.success(Unit)

    override suspend fun connectProviderApi(
        serverId: String,
        providerId: String,
        apiKey: String
    ): Result<Unit> = Result.success(Unit)

    override suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun setModelVisible(
        serverId: String,
        providerId: String,
        modelId: String,
        visible: Boolean
    ): Result<Unit> = Result.success(Unit)

    override suspend fun saveServerConfig(serverId: String): Result<Unit> = Result.success(Unit)

    // ============ ServerRepository ============

    override suspend fun resolveConnection(serverId: String): ServerConnection =
        ServerConnection.from("http://localhost:4096", "opencode", "test")
}
