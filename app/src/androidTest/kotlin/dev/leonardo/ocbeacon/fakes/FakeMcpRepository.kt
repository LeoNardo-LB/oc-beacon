package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import javax.inject.Singleton

@Singleton
class FakeMcpRepository @Inject constructor() : McpRepository {

    var getMcpServersResult: Result<List<McpServerStatus>> = Result.success(emptyList())
    var toggleMcpResult: Result<Boolean> = Result.success(true)

    var fakeConnection: ServerConnection? = null

    override suspend fun getMcpServers(): Result<List<McpServerStatus>> = getMcpServersResult

    override suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean> = toggleMcpResult

    override fun setConnection(conn: ServerConnection) {
        fakeConnection = conn
    }
}
