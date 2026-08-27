package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * 服务器 CRUD 操作。
 */
interface ServerConfigRepository {
    fun getServersFlow(): Flow<List<ServerConfig>>
    suspend fun addServer(config: ServerConfig): Result<Unit>
    suspend fun removeServer(id: String): Result<Unit>
    suspend fun updateServer(server: ServerConfig): Result<Unit>
    suspend fun getServer(id: String): ServerConfig?

    /**
     * 2026-08-28（#251 根因修复）：调试后端提升——目标条目置自连 + 打调试标记，
     * 其余被标记的调试条目降级自连（「最近激活的调试后端至多一个自连」不变量）。
     * @return 被降级自连的服务器 id（#253：供调用方对仍连接者发起断连）。
     */
    suspend fun promoteDebugBackend(targetId: String): Result<List<String>>

    /** 健康检查（连接测试） */
    suspend fun testConnection(server: ServerConfig): Result<Boolean>
}
