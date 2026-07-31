package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Server CRUD operations.
 */
interface ServerConfigRepository {
    fun getServersFlow(): Flow<List<ServerConfig>>
    suspend fun addServer(config: ServerConfig): Result<Unit>
    suspend fun removeServer(id: String): Result<Unit>
    suspend fun updateServer(server: ServerConfig): Result<Unit>
    suspend fun getServer(id: String): ServerConfig?
}
