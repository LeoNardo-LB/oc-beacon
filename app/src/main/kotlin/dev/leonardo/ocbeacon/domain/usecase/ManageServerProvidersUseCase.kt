package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ProviderInfo
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use Case：管理服务器 providers（加载/连接/断开）。
 * 供 HomeViewModel / ServerProvidersScreen 使用。
 */
class ManageServerProvidersUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>> =
        serverRepository.loadProviders(serverId)

    suspend fun connectProviderApi(serverId: String, providerId: String, apiKey: String): Result<Unit> =
        serverRepository.connectProviderApi(serverId, providerId, apiKey)

    suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit> =
        serverRepository.disconnectProvider(serverId, providerId)
}
