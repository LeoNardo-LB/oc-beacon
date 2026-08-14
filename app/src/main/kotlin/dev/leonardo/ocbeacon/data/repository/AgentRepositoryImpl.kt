package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

@Singleton
class AgentRepositoryImpl @Inject constructor(
    private val systemApi: SystemApi,
    private val fileApi: FileApi,
    private val serverRepo: ServerDataStore
) : AgentRepository {

    override suspend fun listAgents(serverId: String): Result<List<AgentInfo>> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        systemApi.listAgents(conn).map { it.toDomain() }
    }

    override suspend fun switchAgent(serverId: String, sessionId: String, agentId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("switchAgent not yet supported by API"))
    }

    override suspend fun loadCommands(serverId: String): Result<List<CommandInfo>> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        systemApi.listCommands(conn).map { it.toDomain() }
    }

    override suspend fun searchFiles(
        serverId: String,
        query: String,
        dirs: String,
        directory: String?,
        limit: Int
    ): Result<List<String>> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        fileApi.findFiles(conn, query, dirs = dirs, directory = directory, limit = limit)
    }

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password, config.apiVersion)
    }
}

private fun dev.leonardo.ocbeacon.data.dto.response.AgentInfo.toDomain() = AgentInfo(
    name = name,
    description = description,
    mode = mode,
    hidden = hidden,
    color = color,
)

private fun dev.leonardo.ocbeacon.data.dto.response.CommandInfo.toDomain() = CommandInfo(
    name = name,
    description = description,
    source = source,
    hints = hints,
)
