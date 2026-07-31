package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo

interface AgentRepository {
    suspend fun listAgents(serverId: String): Result<List<AgentInfo>>
    suspend fun switchAgent(serverId: String, sessionId: String, agentId: String): Result<Unit>
    suspend fun loadCommands(serverId: String): Result<List<CommandInfo>>
    suspend fun searchFiles(
        serverId: String,
        query: String,
        dirs: String,
        directory: String?,
        limit: Int
    ): Result<List<String>>
}
