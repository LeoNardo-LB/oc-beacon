package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.ModelSelection
import dev.leonardo.octether.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use case: manage terminal operations.
 * Delegates to ChatRepository for command execution.
 */
class ManageTerminalUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String,
        directory: String?
    ): Boolean =
        chatRepository.executeCommand(
            serverId = serverId,
            sessionId = sessionId,
            command = command,
            arguments = arguments,
            directory = directory
        ).getOrThrow()

    suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection?,
        directory: String?
    ): Boolean =
        chatRepository.runShellCommand(
            serverId = serverId,
            sessionId = sessionId,
            command = command,
            agent = agent,
            providerId = model?.providerId,
            modelId = model?.modelId,
            directory = directory
        ).getOrThrow()
}
