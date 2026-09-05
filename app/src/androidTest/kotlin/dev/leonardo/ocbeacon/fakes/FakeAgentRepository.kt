package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import javax.inject.Singleton

@Singleton
class FakeAgentRepository @Inject constructor() : AgentRepository {

    var agentsResult: Result<List<AgentInfo>> = Result.success(emptyList())
    var commandsResult: Result<List<CommandInfo>> = Result.success(emptyList())
    var searchFilesResult: Result<List<String>> = Result.success(emptyList())

    val switchedAgents = mutableListOf<Triple<String, String, String>>()

    override suspend fun listAgents(serverId: String): Result<List<AgentInfo>> = agentsResult

    // 2026-08-16：switchAgent 已随死代码删除（2face6d7）——override 残留导致
    // androidTest 源集编译失败（接口无此方法）。switchedAgents 记录保留供
    // 历史断言迁移参考；agent 切换现走 prompt body（V2ApiClient prompt）。
    suspend fun switchAgent(serverId: String, sessionId: String, agentId: String): Result<Unit> {
        switchedAgents.add(Triple(serverId, sessionId, agentId))
        return Result.success(Unit)
    }

    // #285 基线对齐：loadCommands 增 sessionId（懒建会话补全命令列表）
    override suspend fun loadCommands(serverId: String, sessionId: String?): Result<List<CommandInfo>> = commandsResult

    // #324④ 基线对齐：会话技能（fake 恒空——skills 触发组不参与既有 UI 断言）
    override suspend fun listSessionSkills(
        serverId: String,
        sessionId: String,
    ): Result<List<dev.leonardo.ocbeacon.domain.model.DshSkillInfo>> = Result.success(emptyList())

    override suspend fun searchFiles(
        serverId: String,
        query: String,
        dirs: String,
        directory: String?,
        limit: Int
    ): Result<List<String>> = searchFilesResult
}
