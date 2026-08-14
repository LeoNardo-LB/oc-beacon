package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.ServerConnection

interface McpRepository {
    /** #110（D2-24）：显式传 conn——单例共享可变 connection 在多服务器并发下
     *  互相覆盖（后连接者赢），改为调用方持有并传入。 */
    suspend fun getMcpServers(conn: ServerConnection): Result<List<McpServerStatus>>
    suspend fun toggleMcpServer(conn: ServerConnection, name: String, connect: Boolean): Result<Boolean>
    fun setConnection(conn: ServerConnection)
}
