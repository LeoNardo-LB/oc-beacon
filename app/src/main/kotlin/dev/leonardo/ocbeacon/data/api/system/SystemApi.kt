package dev.leonardo.ocbeacon.data.api.system

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.isSuccess
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
    private val apiClient: ApiClient
) : SystemApi {

    private val httpClient get() = apiClient.httpClient

    override suspend fun getHealth(conn: ServerConnection): ServerHealth {
        return httpClient.get("${conn.baseUrl}/global/health") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 获取服务器路径（home 目录、worktree 等）。
     * GET /path
     */
    override suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return httpClient.get("${conn.baseUrl}/path") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 列出可用的 agent（build、plan 等）。
     * GET /agent
     * 返回已过滤为主要/可见 agent，用于模式选择器。
     */
    override suspend fun listAgents(conn: ServerConnection): List<AgentInfo> {
        return httpClient.get("${conn.baseUrl}/agent") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 列出可用的斜杠命令。
     * GET /command
     */
    override suspend fun listCommands(conn: ServerConnection): List<CommandInfo> {
        return httpClient.get("${conn.baseUrl}/command") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * 列出可用的技能。
     * GET /skill
     */
    override suspend fun listSkills(conn: ServerConnection, directory: String?): List<SkillInfo> {
        return httpClient.get("${conn.baseUrl}/skill") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    override suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> {
        return httpClient.get("${conn.baseUrl}/mcp") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    override suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/connect") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    override suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/disconnect") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }
}
