package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use Case：向会话发送消息。
 * 委托给 ChatRepository。
 */
class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun sendPrompt(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String,
        variant: String?,
        directory: String?
    ) {
        chatRepository.promptAsync(
            serverId = serverId,
            sessionId = sessionId,
            parts = parts,
            model = model,
            agent = agent,
            variant = variant,
            directory = directory
        ).getOrThrow()
    }
}
