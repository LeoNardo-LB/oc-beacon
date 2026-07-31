package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ProviderInfo
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: manage server providers (load/enable/disable/connect/disconnect/setModelVisible/save).
 * Used by Phase 4 ServerProvidersScreen / ServerModelFilterScreen.
 */
class ManageServerProvidersUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>> =
        serverRepository.loadProviders(serverId)

    suspend fun setProviderEnabled(serverId: String, providerId: String, enabled: Boolean): Result<Unit> =
        serverRepository.setProviderEnabled(serverId, providerId, enabled)

    suspend fun connectProviderApi(serverId: String, providerId: String, apiKey: String): Result<Unit> =
        serverRepository.connectProviderApi(serverId, providerId, apiKey)

    suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit> =
        serverRepository.disconnectProvider(serverId, providerId)

    suspend fun setModelVisible(serverId: String, providerId: String, modelId: String, visible: Boolean): Result<Unit> =
        serverRepository.setModelVisible(serverId, providerId, modelId, visible)

    suspend fun saveServerConfig(serverId: String): Result<Unit> =
        serverRepository.saveServerConfig(serverId)
}
