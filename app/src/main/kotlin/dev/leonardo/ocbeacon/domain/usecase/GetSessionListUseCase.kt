package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe session list for a specific server.
 * Used by Phase 4 SessionListViewModel.
 */
class GetSessionListUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    operator fun invoke(serverId: String): Flow<List<Session>> =
        sessionRepository.getSessionsFlow(serverId)
}
