package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import java.io.OutputStream
import javax.inject.Inject

/**
 * Use Case：分享、导出和压缩会话。
 * 委托给 SessionRepository。
 */
class ShareExportUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend fun shareSession(serverId: String, sessionId: String): Session =
        sessionRepository.shareSession(serverId, sessionId).getOrThrow()

    suspend fun unshareSession(serverId: String, sessionId: String) {
        sessionRepository.unshareSession(serverId, sessionId).getOrThrow()
    }

    suspend fun compactSession(serverId: String, sessionId: String, providerId: String, modelId: String) {
        sessionRepository.compactSession(serverId, sessionId, providerId, modelId).getOrThrow()
    }

    suspend fun exportSessionToStream(
        serverId: String,
        sessionId: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ) {
        sessionRepository.exportSessionToStream(serverId, sessionId, outputStream, onProgress).getOrThrow()
    }
}
