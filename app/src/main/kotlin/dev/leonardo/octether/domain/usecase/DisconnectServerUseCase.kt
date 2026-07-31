package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: disconnect from a server.
 * Used by Phase 4 HomeViewModel.
 */
class DisconnectServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(serverId: String): Result<Unit> =
        serverRepository.disconnect(serverId)
}
