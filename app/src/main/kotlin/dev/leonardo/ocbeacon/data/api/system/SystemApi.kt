package dev.leonardo.ocbeacon.data.api.system

import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import javax.inject.Inject
import javax.inject.Singleton

interface SystemApi {
    suspend fun getHealth(conn: ServerConnection): ServerHealth

    /**
     * 获取服务器路径（home 目录、worktree 等）。
     * GET /path
     */
    suspend fun getServerPaths(conn: ServerConnection): ServerPaths

    /**
     * 列出可用的 agent（build、plan 等）。
     * GET /agent
     * 返回已过滤为主要/可见 agent，用于模式选择器。
     */
    suspend fun listAgents(conn: ServerConnection): List<AgentInfo>

    /**
     * 列出可用的斜杠命令。
     * GET /command
     */
    suspend fun listCommands(conn: ServerConnection): List<CommandInfo>

    /**
     * 列出可用的技能。
     * GET /skill
     */
    suspend fun listSkills(conn: ServerConnection, directory: String? = null): List<SkillInfo>

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry>

    suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean

    suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean
}

@Singleton
class SystemApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : SystemApi {

    override suspend fun getHealth(conn: ServerConnection): ServerHealth =
        if (conn.apiVersion.isV2) v2.getHealth(conn) else v1.getHealth(conn)

    override suspend fun getServerPaths(conn: ServerConnection): ServerPaths =
        if (conn.apiVersion.isV2) v2.getServerPaths(conn) else v1.getServerPaths(conn)

    override suspend fun listAgents(conn: ServerConnection): List<AgentInfo> =
        if (conn.apiVersion.isV2) v2.listAgents(conn) else v1.listAgents(conn)

    override suspend fun listCommands(conn: ServerConnection): List<CommandInfo> =
        if (conn.apiVersion.isV2) v2.listCommands(conn) else v1.listCommands(conn)

    override suspend fun listSkills(conn: ServerConnection, directory: String?): List<SkillInfo> =
        if (conn.apiVersion.isV2) v2.listSkills(conn, directory) else v1.listSkills(conn, directory)

    override suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> =
        if (conn.apiVersion.isV2) v2.getMcpStatus(conn) else v1.getMcpStatus(conn)

    override suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean =
        if (conn.apiVersion.isV2) v2.connectMcpServer(conn, name) else v1.connectMcpServer(conn, name)

    override suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean =
        if (conn.apiVersion.isV2) v2.disconnectMcpServer(conn, name) else v1.disconnectMcpServer(conn, name)
}
