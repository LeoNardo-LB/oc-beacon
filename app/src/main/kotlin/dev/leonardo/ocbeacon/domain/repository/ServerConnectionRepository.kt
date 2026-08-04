package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.ServerConfig

/**
 * 连接生命周期操作（连接/断开/测试）。
 */
interface ServerConnectionRepository {
    suspend fun connect(server: ServerConfig): Result<Unit>
    suspend fun disconnect(serverId: String): Result<Unit>
    suspend fun testConnection(server: ServerConfig): Result<Boolean>
}
