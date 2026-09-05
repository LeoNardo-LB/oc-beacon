package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.model.DshSkillInfo

interface AgentRepository {
    suspend fun listAgents(serverId: String): Result<List<AgentInfo>>
    /** [sessionId] 只对 DSH 有效（commands/list 是 agent-scoped 的）；OpenCode 忽略。 */
    suspend fun loadCommands(serverId: String, sessionId: String? = null): Result<List<CommandInfo>>
    /** #324④：会话技能（DSH skills/list；V1/V2 → 空）。 */
    suspend fun listSessionSkills(serverId: String, sessionId: String): Result<List<DshSkillInfo>>
    suspend fun searchFiles(
        serverId: String,
        query: String,
        dirs: String,
        directory: String?,
        limit: Int
    ): Result<List<String>>
}