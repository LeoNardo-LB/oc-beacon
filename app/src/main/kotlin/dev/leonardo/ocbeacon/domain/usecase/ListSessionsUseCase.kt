package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use Case：通过 REST 列出服务器上的会话（支持按目录/搜索/游标分页）。
 * 委托给 [SessionRepository.listSessions]。
 */
class ListSessionsUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(
        serverId: String,
        directory: String? = null,
        search: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ): List<Session> = sessionRepository.listSessions(serverId, directory, search, cursor, limit)
}
