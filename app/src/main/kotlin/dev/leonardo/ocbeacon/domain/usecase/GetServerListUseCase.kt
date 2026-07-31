package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe the list of configured servers.
 * Delegates to [ServerRepository.getServersFlow].
 */
class GetServerListUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    operator fun invoke(): Flow<List<ServerConfig>> {
        return serverRepository.getServersFlow()
    }
}
