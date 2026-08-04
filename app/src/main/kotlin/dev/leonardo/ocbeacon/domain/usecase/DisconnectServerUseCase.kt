package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use Case：断开与服务器的连接。
 * 供 Phase 4 HomeViewModel 使用。
 */
class DisconnectServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(serverId: String): Result<Unit> =
        serverRepository.disconnect(serverId)
}
