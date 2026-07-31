package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.AgentInfo
import dev.leonardo.octether.domain.model.CommandInfo
import dev.leonardo.octether.domain.repository.AgentRepository
import javax.inject.Singleton

@Singleton
class FakeAgentRepository @Inject constructor() : AgentRepository {

    var agentsResult: Result<List<AgentInfo>> = Result.success(emptyList())
    var commandsResult: Result<List<CommandInfo>> = Result.success(emptyList())
    var searchFilesResult: Result<List<String>> = Result.success(emptyList())
    var switchAgentResult: Result<Unit> = Result.success(Unit)

    val switchedAgents = mutableListOf<Triple<String, String, String>>()

    override suspend fun listAgents(serverId: String): Result<List<AgentInfo>> = agentsResult

    override suspend fun switchAgent(serverId: String, sessionId: String, agentId: String): Result<Unit> {
        switchedAgents.add(Triple(serverId, sessionId, agentId))
        return switchAgentResult
    }

    override suspend fun loadCommands(serverId: String): Result<List<CommandInfo>> = commandsResult

    override suspend fun searchFiles(
        serverId: String,
        query: String,
        dirs: String,
        directory: String?,
        limit: Int
    ): Result<List<String>> = searchFilesResult
}
