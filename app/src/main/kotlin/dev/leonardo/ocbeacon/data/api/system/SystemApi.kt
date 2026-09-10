package dev.leonardo.ocbeacon.data.api.system

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth

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

    /**
     * #324④：列出会话维度技能（DSH skills/list {sessionId} → 触发组；
     * V1/V2 无该面 → 空）。
     */
    suspend fun listSessionSkills(conn: ServerConnection, sessionId: String): List<dev.leonardo.ocbeacon.domain.model.DshSkillInfo> =
        emptyList()

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry>

    suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean

    suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean
}
