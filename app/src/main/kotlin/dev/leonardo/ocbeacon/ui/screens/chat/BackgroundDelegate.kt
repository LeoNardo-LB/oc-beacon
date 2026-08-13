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
    /** 前台（阻塞主会话）标记：子代理运行中 + 主会话 busy——2026-08-13
     *  任务面板改造（区分前台/后台执行；转后台后主会话 idle → false） */
    val isForeground: Boolean = false,
    /** 描述（从 tool part input 提取，可能为 null） */
    val description: String? = null,
    /** 开始时间（子会话创建时间，2026-08-12 面板第二行展示） */
    val startedAt: Long? = null,
    /** 子会话模型 id（2026-08-12 面板第二行展示） */
    val modelId: String? = null,
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

        children.mapNotNull { child ->
            val toolPart = toolParts.firstOrNull { part ->
                part.state is ToolState.Running &&
                    part.metadata?.get("sessionId")?.let { it.jsonPrimitive.contentOrNull } == child.id
            }
            // 2026-08-12 修复（历史 subagent 恒空）：V2 服务器主会话消息流中
            // 不存在 task/subagent tool part（实测翻 1000 条消息 0 个——
            // V2 派发子代理走 session.create，无工具调用记录）→ 原
            // isExplicitlyBackground 过滤恒 false → subagents 恒空。
            // 子会话（parentId==currentSessionId）本身就是后台任务，直接展示。
            val childRunning = child.id in runningIds || child.id in activeSessionIds.value
            // 2026-08-13：前台/后台标记——子代理运行中 + 主会话 busy（阻塞中）
            // = 前台；转后台后主会话恢复（idle）→ 后台
            val mainBusy = currentSessionId in runningIds || currentSessionId in activeSessionIds.value
            SubagentSummary(
                sessionId = child.id,
                agent = child.agent,
                title = child.title,
                // FSM Busy（SSE 驱动，V1）或 active 轮询（V2）任一命中即运行中
                isRunning = childRunning,
                isForeground = mainBusy && childRunning,
                description = toolPart?.let { extractSubagentDescription(it) },
                // 2026-08-12：面板第二行（agent 徽章 + 开始时间 + 模型）
                startedAt = child.time.created.takeIf { it > 0 },
                modelId = child.model?.id
            )
        }
    }.distinctUntilChanged()

    /** 前台 subagent 计数——TUI foregroundTasks 语义：
     *  主会话 busy（正在等待）+ 消息流中存在 running 的 task/subagent tool part
     *  且**非 background 派发**（2026-08-12 修复：task 工具 background=true 的
     *  子会话是后台任务，不算前台——修复前 background 任务也被计入 → 转后台
     *  工具栏误显示"有前台任务"，用户反馈"代理还是前台的，没转后台"）。
     *  V2 转后台后主会话立即恢复（idle/继续工作），前台归零；
     *  子会话本身继续 running（计入角标 runningSubagentCount）。 */
    private fun foregroundCount(
        currentSessionId: String,
        runningIds: Set<String>,
        toolParts: List<Part.Tool>
    ): Int {
        val mainBusy = currentSessionId in runningIds || currentSessionId in activeSessionIds.value
        if (!mainBusy) return 0
        // 2026-08-12 放宽：V2 下 AI 派发 subagent 基本都带 background=true，
        // 严格按"前台"过滤工具栏几乎永不显示（用户反馈看不到）——
        // 改为：主会话 busy + 存在 task/subagent tool part（不限 background）
        // 即显示工具栏（提供"全部转后台"操作——服务器对已后台任务 no-op）。
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
        // 2026-08-12 用户要求：面板支持"进行中/历史"切换——shells 保留全部
        //（含已结束的，ShellJobsStore 注释"便于面板展示历史"），过滤交给
        // UI 层（BackgroundSheet showHistory 切换）。此前仅保留运行中——
        // 用户反馈"没看到历史记录切换"后扩展。
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
