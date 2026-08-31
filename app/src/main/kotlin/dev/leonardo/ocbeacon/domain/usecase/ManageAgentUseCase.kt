package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import javax.inject.Inject

/**
 * Use Case：管理 agents、命令和文件搜索。
 * 委托给 AgentRepository。
 */
class ManageAgentUseCase @Inject constructor(
    private val agentRepository: AgentRepository
) {
    suspend fun loadAgents(serverId: String): List<AgentInfo> =
        agentRepository.listAgents(serverId).getOrThrow()

    /** [sessionId] 只对 DSH 有效（commands/list 是 agent-scoped 的）；OpenCode 忽略。 */
    suspend fun loadCommands(serverId: String, sessionId: String? = null): List<CommandInfo> =
        agentRepository.loadCommands(serverId, sessionId).getOrThrow()

    suspend fun searchFiles(serverId: String, query: String, dirs: String, directory: String?, limit: Int): List<String> =
        agentRepository.searchFiles(serverId, query, dirs, directory, limit).getOrThrow()
}