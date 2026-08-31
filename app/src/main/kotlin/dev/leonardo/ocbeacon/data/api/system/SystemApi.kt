package dev.leonardo.ocbeacon.data.api.system

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
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
     * 列出可用的斜杠命令（DSH 会话级：commands/list typert 通道需要 agentId；V1/V2 忽略）。
     */
    suspend fun listCommands(conn: ServerConnection, sessionId: String?): List<CommandInfo> = listCommands(conn)

    /**
     * 列出可用的技能。
     * GET /skill
     */
    suspend fun listSkills(conn: ServerConnection, directory: String? = null): List<SkillInfo>

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry>

    suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean

    suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean
}

/**
 * C1-4（2026-08-27，#238 五域收编）：分发层收缩为单点路由 + 逐方法单行委托。
 * [V1ApiClient]/[V2ApiClient] 已直接实现 [SystemApi]，本类不再逐方法
 * if (conn.apiVersion.isV2) 分发。
 */
@Singleton
class SystemApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient,
    private val dsh: DshApiClient,
) : SystemApi {

    /** #276 三分：serverType==Dsh 优先（apiVersion 不参与 DSH 路由，设计 §2.1）。 */
    private fun pick(conn: ServerConnection): SystemApi = when (conn.serverType) {
        dev.leonardo.ocbeacon.domain.model.ServerType.Dsh -> dsh
        else -> if (conn.apiVersion.isV2) v2 else v1
    }

    override suspend fun getHealth(conn: ServerConnection): ServerHealth =
        pick(conn).getHealth(conn)

    override suspend fun getServerPaths(conn: ServerConnection): ServerPaths =
        pick(conn).getServerPaths(conn)

    override suspend fun listAgents(conn: ServerConnection): List<AgentInfo> =
        pick(conn).listAgents(conn)

    override suspend fun listCommands(conn: ServerConnection): List<CommandInfo> =
        pick(conn).listCommands(conn)

    override suspend fun listCommands(conn: ServerConnection, sessionId: String?): List<CommandInfo> =
        pick(conn).listCommands(conn, sessionId)

    override suspend fun listSkills(conn: ServerConnection, directory: String?): List<SkillInfo> =
        pick(conn).listSkills(conn, directory)

    override suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> =
        pick(conn).getMcpStatus(conn)

    override suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean =
        pick(conn).connectMcpServer(conn, name)

    override suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean =
        pick(conn).disconnectMcpServer(conn, name)
}