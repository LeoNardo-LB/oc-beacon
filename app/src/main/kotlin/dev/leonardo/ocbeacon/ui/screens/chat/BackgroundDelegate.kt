package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val serverId: String,
    sessionIdFlow: kotlinx.coroutines.flow.Flow<String>,
    scope: CoroutineScope
) {
    /** 前台活跃会话 ID（V2 /api/session/active 轮询）——运行中会话的权威来源。
     *  V2 不广播 session.status SSE 事件（V1 才有），FSM 的 statusFlow 无法
     *  覆盖子会话；实测子会话 running 只能通过 active 轮询感知。
     *  轮询循环由 [startPolling] 显式启动（ChatScreen 组合时调用，
     *  避免 ViewModel 测试在 runTest 虚拟时间下无限循环 OOM）。 */
    private val activeSessionIds = MutableStateFlow<Set<String>>(emptySet())

    /** 启动 active 会话轮询（幂等）。 */
    fun startPolling(scope: CoroutineScope) {
        if (pollingStarted) return
        pollingStarted = true
        scope.launch {
            while (true) {
                refreshActiveSessions()
                delay(5_000)
            }
        }
    }

    /** 单次刷新（供一次性同步与测试）。 */
    suspend fun refreshActiveSessions() {
        activeSessionIds.value = chatRepository.listActiveSessions(serverId)
            .getOrNull()
            ?.filterValues { it.type == "running" || it.type == "busy" }
            ?.keys
            ?: emptySet()
    }

    private var pollingStarted = false

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
                // FSM Busy（SSE 驱动，V1）或 active 轮询（V2）任一命中即运行中
                isRunning = child.id in runningIds || child.id in activeSessionIds.value,
                description = toolPart?.let { extractSubagentDescription(it) }
            )
        }
    }.distinctUntilChanged()

    /** 前台 subagent 计数——TUI foregroundTasks 语义：
     *  主会话 busy（正在等待）+ 消息流中存在 running 的 task/subagent tool part。
     *  V2 转后台后主会话立即恢复（idle/继续工作），前台归零；
     *  子会话本身继续 running（计入角标 runningSubagentCount）。 */
    private fun foregroundCount(
        currentSessionId: String,
        runningIds: Set<String>,
        toolParts: List<Part.Tool>
    ): Int {
        val mainBusy = currentSessionId in runningIds || currentSessionId in activeSessionIds.value
        if (!mainBusy) return 0
        return toolParts.count { part ->
            part.state is ToolState.Running &&
                (part.tool == "task" || part.tool == "subagent")
        }
    }

    private val foregroundCountFlow = combine(
        sessionRepository.getSessionStatusesFlow(serverId),
        chatRepository.getAllPartsMap(),
        sessionIdFlow
    ) { statuses, partsMap, currentSessionId ->
        val runningIds = statuses.filterValues { it == SessionStatus.Busy }.keys
        val toolParts = partsMap[currentSessionId].orEmpty().filterIsInstance<Part.Tool>()
        foregroundCount(currentSessionId, runningIds, toolParts)
    }.distinctUntilChanged()

    /** 聚合状态：角标计数 + 面板数据。 */
    val uiState: StateFlow<BackgroundUiState> = combine(
        subagents,
        shellJobsStore.jobsBySession,
        foregroundCountFlow,
        sessionIdFlow
    ) { subagents, jobsBySession, foregroundCount, currentSessionId ->
        val shells = jobsBySession[currentSessionId].orEmpty()
        val runningSubagents = subagents.filter { it.isRunning }
        BackgroundUiState(
            shells = shells,
            subagents = subagents,
            runningSubagentCount = runningSubagents.size,
            foregroundSubagentCount = foregroundCount,
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
