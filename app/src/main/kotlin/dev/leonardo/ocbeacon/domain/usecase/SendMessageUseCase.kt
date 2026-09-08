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
        directory: String?,
        steer: Boolean = false,
        /** #362：busy+queue 不播种转录（排队消息仅队列 UI；见 ChatRepository.promptAsync）。 */
        seedTranscript: Boolean = true
    ) {
        chatRepository.promptAsync(
            serverId = serverId,
            sessionId = sessionId,
            parts = parts,
            model = model,
            agent = agent,
            variant = variant,
            directory = directory,
            steer = steer,
            seedTranscript = seedTranscript
        ).getOrThrow()
    }
}
