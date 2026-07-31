package dev.leonardo.octether.domain.repository

import dev.leonardo.octether.domain.model.McpServerStatus
import dev.leonardo.octether.domain.model.ServerConnection

interface McpRepository {
    suspend fun getMcpServers(): Result<List<McpServerStatus>>
    suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean>
    fun setConnection(conn: ServerConnection)
}
