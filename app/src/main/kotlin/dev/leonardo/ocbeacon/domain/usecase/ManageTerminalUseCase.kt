package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use Case：管理终端操作。
 * 委托给 ChatRepository 执行命令。
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
