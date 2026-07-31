package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.AgentInfo
import dev.leonardo.octether.domain.model.CommandInfo
import dev.leonardo.octether.domain.repository.AgentRepository
import javax.inject.Inject

/**
 * Use case: manage agents, commands, and file search.
 * Delegates to AgentRepository.
 */
class ManageAgentUseCase @Inject constructor(
    private val agentRepository: AgentRepository
) {
    suspend fun loadAgents(serverId: String): List<AgentInfo> =
        agentRepository.listAgents(serverId).getOrThrow()

    suspend fun loadCommands(serverId: String): List<CommandInfo> =
        agentRepository.loadCommands(serverId).getOrThrow()

    suspend fun searchFiles(serverId: String, query: String, dirs: String, directory: String?, limit: Int): List<String> =
        agentRepository.searchFiles(serverId, query, dirs, directory, limit).getOrThrow()
}
