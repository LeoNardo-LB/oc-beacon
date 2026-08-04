package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use Case：观察指定服务器的会话列表。
 * 供 Phase 4 SessionListViewModel 使用。
 */
class GetSessionListUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    operator fun invoke(serverId: String): Flow<List<Session>> =
        sessionRepository.getSessionsFlow(serverId)
}
