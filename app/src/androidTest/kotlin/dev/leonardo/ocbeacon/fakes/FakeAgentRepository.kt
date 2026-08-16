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

    override suspend fun loadCommands(serverId: String): Result<List<CommandInfo>> = commandsResult

    override suspend fun searchFiles(
        serverId: String,
        query: String,
        dirs: String,
        directory: String?,
        limit: Int
    ): Result<List<String>> = searchFilesResult
}
