package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

@Singleton
class McpRepositoryImpl @Inject constructor(
    private val adapters: ServerAdapterRegistry
) : McpRepository {

    @Volatile
    private var connection: ServerConnection? = null

    override fun setConnection(conn: ServerConnection) {
        connection = conn
    }

    private fun requireConnection(): ServerConnection =
        connection ?: throw IllegalStateException("McpRepository: ServerConnection not set. Call setConnection() first.")

    override suspend fun getMcpServers(conn: ServerConnection): Result<List<McpServerStatus>> = runCatchingCancellable {
        val statusMap = adapters.ports(conn).system.getMcpStatus(conn)
        val configMap = adapters.ports(conn).requireProvider(conn).getConfig(conn).mcp ?: emptyMap()

        statusMap.map { (name, entry) ->
            val config = configMap[name]
            McpServerStatus(
                name = name,
                type = config?.type ?: "local",
                status = entry.status,
                command = config?.command,
                url = config?.url,
            )
        }
    }

    override suspend fun toggleMcpServer(conn: ServerConnection, name: String, connect: Boolean): Result<Boolean> = runCatchingCancellable {
        if (connect) {
            adapters.ports(conn).system.connectMcpServer(conn, name)
        } else {
            adapters.ports(conn).system.disconnectMcpServer(conn, name)
        }
    }
}
