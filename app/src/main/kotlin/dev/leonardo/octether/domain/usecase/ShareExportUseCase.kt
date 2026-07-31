package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.repository.SessionRepository
import java.io.OutputStream
import javax.inject.Inject

/**
 * Use case: share, export, and compact sessions.
 * Delegates to SessionRepository.
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
