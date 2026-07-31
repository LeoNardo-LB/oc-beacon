package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.CreateSessionOpts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use case: create a new session on a server.
 * Delegates to [SessionRepository.createSession].
 */
class CreateSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(serverId: String, opts: CreateSessionOpts): Result<Session> {
        return sessionRepository.createSession(serverId, opts)
    }
}
