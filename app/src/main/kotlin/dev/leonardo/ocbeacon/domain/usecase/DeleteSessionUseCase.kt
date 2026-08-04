package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use Case：按 ID 删除会话。
 * 委托给 [SessionRepository.deleteSession]。
 */
class DeleteSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(serverId: String, id: String): Result<Unit> {
        return sessionRepository.deleteSession(serverId, id)
    }
}
