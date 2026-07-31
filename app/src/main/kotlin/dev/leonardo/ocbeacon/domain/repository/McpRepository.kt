package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.ServerConnection

interface McpRepository {
    suspend fun getMcpServers(): Result<List<McpServerStatus>>
    suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean>
    fun setConnection(conn: ServerConnection)
}
