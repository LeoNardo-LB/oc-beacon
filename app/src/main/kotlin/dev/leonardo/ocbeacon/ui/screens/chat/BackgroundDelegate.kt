package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 后台 subagent 摘要（面板列表项数据）。
 */
data class SubagentSummary(
    val sessionId: String,
    val agent: String?,
    val title: String?,
    val isRunning: Boolean,
    /** 描述（从 tool part input 提取，可能为 null） */
    val description: String? = null
)

/**
 * 后台活动聚合状态——驱动入口角标、转后台工具栏与后台面板。
 */
data class BackgroundUiState(
    val shells: List<ShellJob> = emptyList(),
    val subagents: List<SubagentSummary> = emptyList(),
    /** 运行中的 subagent 数（角标计数） */
    val runningSubagentCount: Int = 0,
    /** 前台运行中的 subagent 数（>0 时显示转后台工具栏） */
    val foregroundSubagentCount: Int = 0,
    /** 运行中的 shell 数（角标计数） */
    val runningShellCount: Int = 0
) {
    /** 入口角标总数（运行中 subagent + shell）。 */
    val badgeCount: Int get() = runningSubagentCount + runningShellCount
    /** 是否有前台 subagent 在运行（显示转后台工具栏）。 */
    val showBackgroundToolbar: Boolean get() = foregroundSubagentCount > 0
}

/**
 * 后台活动聚合器——遵循"单一真相源"：不维护独立状态，从现有数据源实时派生：
 * - 子会话：SessionRepository.getSessionsFlow 过滤 parentId（TUI session.family 语义）
 * - 前台 subagent：消息流 tool part（tool=="task"/"subagent" && Running && !background）
 *   （TUI foregroundTasks 语义）
 * - shell：ShellJobsStore（SSE 事件 + REST 快照）
 */
class BackgroundAggregator(
    private val sessionRepository: SessionRepository,
    private val chatRepository: ChatRepository,
    private val shellJobsStore: ShellJobsStore,
    serverId: String,
    sessionIdFlow: kotlinx.coroutines.flow.Flow<String>,
    scope: CoroutineScope
) {
    private val subagents = combine(
        sessionRepository.getSessionsFlow(serverId),
        sessionRepository.getSessionStatusesFlow(serverId),
        chatRepository.getAllPartsMap(),
        sessionIdFlow
    ) { sessions, statuses, partsMap, currentSessionId ->
        val children = sessions
            .filter { it.parentId == currentSessionId }
            .sortedByDescending { it.time.updated }
        val runningIds = statuses.filterValues { it == SessionStatus.Busy }.keys
        val toolParts = partsMap[currentSessionId].orEmpty()
            .filterIsInstance<Part.Tool>()

        children.map { child ->
            val toolPart = toolParts.firstOrNull { part ->
                part.state is ToolState.Running &&
                    part.metadata?.get("sessionId")?.let { it.jsonPrimitive.contentOrNull } == child.id
            }
            SubagentSummary(
                sessionId = child.id,
                agent = child.agent,
                title = child.title,
                isRunning = child.id in runningIds,
                description = toolPart?.let { extractSubagentDescription(it) }
            )
        }
    }.distinctUntilChanged()

    /** 聚合状态：角标计数 + 面板数据。 */
    val uiState: StateFlow<BackgroundUiState> = combine(
        subagents,
        shellJobsStore.jobsBySession,
        sessionIdFlow
    ) { subagents, jobsBySession, currentSessionId ->
        val shells = jobsBySession[currentSessionId].orEmpty()
        val runningSubagents = subagents.filter { it.isRunning }
        BackgroundUiState(
            shells = shells,
            subagents = subagents,
            runningSubagentCount = runningSubagents.size,
            foregroundSubagentCount = runningSubagents.size,
            runningShellCount = shells.count { it.isRunning }
        )
    }.stateIn(scope, SharingStarted.Eagerly, BackgroundUiState())

    private fun extractSubagentDescription(tool: Part.Tool): String? {
        val state = tool.state
        val input = when (state) {
            is ToolState.Running -> state.input
            is ToolState.Completed -> state.input
            else -> emptyMap()
        }
        return (input["description"] as? JsonPrimitive)?.contentOrNull
    }
}
