package dev.leonardo.octether.data.repository

import dev.leonardo.octether.data.api.provider.ProviderApi
import dev.leonardo.octether.data.api.system.SystemApi
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.domain.model.McpServerStatus
import dev.leonardo.octether.domain.repository.McpRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpRepositoryImpl @Inject constructor(
    private val systemApi: SystemApi,
    private val providerApi: ProviderApi
) : McpRepository {

    @Volatile
    private var connection: ServerConnection? = null

    override fun setConnection(conn: ServerConnection) {
        connection = conn
    }

    private fun requireConnection(): ServerConnection =
        connection ?: throw IllegalStateException("McpRepository: ServerConnection not set. Call setConnection() first.")

    override suspend fun getMcpServers(): Result<List<McpServerStatus>> = runCatching {
        val conn = requireConnection()
        val statusMap = systemApi.getMcpStatus(conn)
        val configMap = providerApi.getConfig(conn).mcp ?: emptyMap()

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

    override suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean> = runCatching {
        val conn = requireConnection()
        if (connect) {
            systemApi.connectMcpServer(conn, name)
        } else {
            systemApi.disconnectMcpServer(conn, name)
        }
    }
}
