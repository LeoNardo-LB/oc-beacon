package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use case: delete a session by ID.
 * Delegates to [SessionRepository.deleteSession].
 */
class DeleteSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(serverId: String, id: String): Result<Unit> {
        return sessionRepository.deleteSession(serverId, id)
    }
}
