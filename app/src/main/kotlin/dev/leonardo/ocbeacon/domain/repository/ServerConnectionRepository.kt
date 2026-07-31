package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.ServerConfig

/**
 * Connection lifecycle operations (connect/disconnect/test).
 */
interface ServerConnectionRepository {
    suspend fun connect(server: ServerConfig): Result<Unit>
    suspend fun disconnect(serverId: String): Result<Unit>
    suspend fun testConnection(server: ServerConfig): Result<Boolean>
}
