package dev.leonardo.octether.data.api.system

import dev.leonardo.octether.data.api.ApiClient
import dev.leonardo.octether.data.api.directoryHeader
import dev.leonardo.octether.data.dto.response.*
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.domain.model.ServerHealth
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
     * Get server paths (home directory, worktree, etc.).
     * GET /path
     */
    suspend fun getServerPaths(conn: ServerConnection): ServerPaths

    /**
     * List available agents (build, plan, etc.).
     * GET /agent
     * Returns agents filtered to primary/visible ones for the mode selector.
     */
    suspend fun listAgents(conn: ServerConnection): List<AgentInfo>

    /**
     * List available slash commands.
     * GET /command
     */
    suspend fun listCommands(conn: ServerConnection): List<CommandInfo>

    /**
     * List available skills.
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
     * Get server paths (home directory, worktree, etc.).
     * GET /path
     */
    override suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return httpClient.get("${conn.baseUrl}/path") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * List available agents (build, plan, etc.).
     * GET /agent
     * Returns agents filtered to primary/visible ones for the mode selector.
     */
    override suspend fun listAgents(conn: ServerConnection): List<AgentInfo> {
        return httpClient.get("${conn.baseUrl}/agent") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * List available slash commands.
     * GET /command
     */
    override suspend fun listCommands(conn: ServerConnection): List<CommandInfo> {
        return httpClient.get("${conn.baseUrl}/command") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * List available skills.
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
