package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use Case：撤销和重做消息（revert/unrevert 会话）。
 * 委托给 ChatRepository。
 */
class UndoRedoUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun revertSession(serverId: String, sessionId: String, messageId: String) {
        chatRepository.revertSession(serverId, sessionId, messageId).getOrThrow()
    }

    suspend fun unrevertSession(serverId: String, sessionId: String) {
        chatRepository.unrevertSession(serverId, sessionId).getOrThrow()
    }
}
