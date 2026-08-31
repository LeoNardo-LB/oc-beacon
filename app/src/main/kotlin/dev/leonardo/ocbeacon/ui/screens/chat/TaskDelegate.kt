package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.logging.AppLogger
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 任务 subagent 摘要（任务面板列表项数据）。
 */
data class SubagentSummary(
    val sessionId: String,
    val agent: String?,
    val title: String?,
    val isRunning: Boolean,
    /** 前台（阻塞主会话）标记：子智能体运行中 + 主会话 busy——2026-08-13
     *  任务面板改造（区分前台/后台执行；转后台后主会话 idle → false） */
    val isForeground: Boolean = false,
    /** 描述（从 tool part input 提取，可能为 null） */
    val description: String? = null,
    /** 开始时间（子智能体会话创建时间，2026-08-12 面板第二行展示） */
    val startedAt: Long? = null,
    /** 子智能体会话模型 id（2026-08-12 面板第二行展示） */
    val modelId: String? = null,
    /** 2026-08-16（#145）：执行时长 ms（完成态 updated-created 近似；运行中 null=UI 走时） */
    val durationMs: Long? = null,
)

/**
 * 任务聚合状态——驱动入口角标、任务工具栏与任务面板。
 */
data class TaskUiState(
    val shells: List<ShellJob> = emptyList(),
    val subagents: List<SubagentSummary> = emptyList(),
    /** 运行中的 subagent 数（角标计数） */
    val runningSubagentCount: Int = 0,
    /** 前台运行中的 subagent 数（>0 时显示任务工具栏） */
    val foregroundSubagentCount: Int = 0,
    /** 运行中的 shell 数（角标计数） */
    val runningShellCount: Int = 0,
    /** AgentSheet 多级树可见行（2026-09 树化：DSH 权威/V2 本地镜像双轨合流）。 */
    val subagentTreeRows: List<SubagentTreeRow> = emptyList(),
    /** 树懒加载中节点（展开箭头位转 spinner）。 */
    val subagentTreeLoadingIds: Set<String> = emptySet(),
    /** 树交互入口（面板打开刷新根层/展开收起）；null = 无树交互态（旧调用方）。 */
    val subagentTreeController: SubagentTreeController? = null,
) {
    /** 入口角标总数（运行中 subagent + shell）。 */
    val badgeCount: Int get() = runningSubagentCount + runningShellCount
    /** 是否有前台 subagent 在运行（显示任务工具栏）。 */
    val showTaskToolbar: Boolean get() = foregroundSubagentCount > 0
}

/**
 * 任务聚合器——遵循"单一真相源"：不维护独立状态，从现有数据源实时派生：
 * - 子智能体会话：SessionRepository.getSessionsFlow 过滤 parentId（TUI session.family 语义）
 * - 前台 subagent：消息流 tool part（tool=="task"/"subagent" && Running && !background）
 *   （TUI foregroundTasks 语义）
 * - shell：ShellJobsStore（SSE 事件 + REST 快照）
 */
class TaskAggregator(
    private val sessionRepository: SessionRepository,
    private val chatRepository: ChatRepository,
    private val shellJobsStore: ShellJobsStore,
    private val serverId: String,
    sessionIdFlow: kotlinx.coroutines.flow.Flow<String>,
    scope: CoroutineScope,
    /** 2026-08-16（R3 僵尸自愈）：active 轮询发现 FSM 与服务器状态分歧时触发
     *  L3 校验。可空——测试/旧调用方不传时跳过否定校验（仅派生展示）。 */
    private val sessionStateRepository: dev.leonardo.ocbeacon.domain.repository.SessionStateRepository? = null,
    /** DSH subagent.list 权威子目录（2026-09 AgentSheet 树化）：null 或
     * 成功(null)（OpenCode 无域）→ 本地镜像递归；失败 → 软降级本地递归。
     * 由 ChatViewModel 注入 sessionRepository.listSubagentChildren。 */
    private val subagentCatalog: (suspend (parentSessionId: String) -> Result<List<dev.leonardo.ocbeacon.domain.model.SubagentChild>?>)? = null,
) {
    /** 前台活跃会话 ID（V2 /api/session/active 轮询）——运行中会话的权威来源。
     *  V2 不广播 session.status SSE 事件（V1 才有），FSM 的 statusFlow 无法
     *  覆盖子智能体会话；实测子智能体会话 running 只能通过 active 轮询感知。
     *  轮询循环由 [startPolling] 显式启动（ChatScreen 组合时调用，
     *  避免 ViewModel 测试在 runTest 虚拟时间下无限循环 OOM）。 */
    private val activeSessionIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 启动 active 会话轮询（幂等）。
     * #99（M-10）：原每 5s 无条件打 REST（无任何活跃会话时也无限空转）；
     * 现连续无活跃会话时指数退避（5s → 10s → 30s 封顶），有活跃会话恢复 5s
     * 精度——active 轮询只是 V2 无 status SSE 的兜底观测，非实时依赖。
     */
    fun startPolling(scope: CoroutineScope) {
        if (pollingStarted) return
        pollingStarted = true
        scope.launch {
            var quietCycles = 0
            while (true) {
                refreshActiveSessions()
                val hasActive = activeSessionIds.value.isNotEmpty()
                val intervalMs = when {
                    hasActive -> POLL_INTERVAL_MS
                    quietCycles >= MAX_QUIET_CYCLES -> POLL_BACKOFF_MS
                    else -> POLL_INTERVAL_MS
                }
                if (hasActive) quietCycles = 0 else quietCycles++
                delay(intervalMs)
            }
        }
    }

    /** 单次刷新（供一次性同步与测试）。 */
    suspend fun refreshActiveSessions() {
        val active = chatRepository.listActiveSessions(serverId)
            .getOrNull()
            ?.filterValues { it.type == "running" || it.type == "busy" }
            ?.keys
            ?: emptySet()
        activeSessionIds.value = active
        // 2026-08-16 根治（会话状态显示不对——双向对账收口到 SessionStateService）：
        // 原 R3 内联逻辑只有反向否定（FSM Busy 但不在 active→L3），且无新鲜度护栏
        // （活跃流式中的会话也可能被送 L3 → REST「缺失即 idle」误杀 → FSM turn 内
        // 不可自愈 → 列表页/对话页都不显示进行中、内容冻结，重进才恢复——用户实测）。
        // 且缺正向自愈：SSE 断连窗口丢 execution.started 后 active 明明拿到 running
        // 却无回写机制。现统一走 reconcileWithActiveSessions：
        // - 正向：active 含但 FSM 非 Busy（连续 2 轮确认）→ L3 恢复 Busy
        // - 反向：FSM Busy 但 active 缺失**且**事件陈旧（≥15s）→ L3 僵尸自愈
        // - 空集直接返回（V1 active 恒空——无信息，防 L3 风暴与 activity 重置）
        sessionStateRepository?.reconcileWithActiveSessions(active)
    }

    private var pollingStarted = false

    private companion object {
        /** #99（M-10）：活跃时的轮询间隔。 */
        const val POLL_INTERVAL_MS = 5_000L
        /** 连续无活跃达到该轮数后进入退避间隔。 */
        const val MAX_QUIET_CYCLES = 2
        /** 空闲退避间隔（30s 封顶，REST 频率 5s → 30s）。 */
        const val POLL_BACKOFF_MS = 30_000L
    }

    /** subagent 派生中间产物：根层摘要（角标/工具栏）+ 全树本地镜像快照（AgentSheet 树）。 */
    private data class AggregatedSubagents(
        val rootSummaries: List<SubagentSummary> = emptyList(),
        val snapshot: SubagentLocalSnapshot = SubagentLocalSnapshot(""),
    )

    private val aggregatedSubagents = combine(
        sessionRepository.getSessionsFlow(serverId),
        sessionRepository.getSessionStatusesFlow(serverId),
        chatRepository.getAllPartsMap(),
        sessionIdFlow,
        // 2026-08-16 根治（任务面板 R1——进行中任务不显示）：activeSessionIds
        // 原先在 lambda 内读取但不在 combine 源中——V2 下 FSM 错过
        // execution.started（断线/冷启动）时子智能体会话无 Busy，active 轮询拿到
        // 正确结果也**不触发重算**，进行中任务永不出现在「运行中」视图。
        // 补为第 5 源后轮询结果直接驱动派生（combine 五参类型安全重载）。
        activeSessionIds,
    ) { sessions, statuses, partsMap, currentSessionId, activeIds ->
        val runningIds = statuses.filterValues { it == SessionStatus.Busy }.keys
        // 2026-09 树化：全量 parentId 分组（V2 权威递归子树 + DSH 降级/回退数据源），
        // 排序与根层一致（创建时间倒序 + id tie-break）。
        val childrenByParent = sessions
            .filter { !it.parentId.isNullOrEmpty() }
            .groupBy { it.parentId!! }
            .mapValues { (_, kids) ->
                kids.sortedWith(
                    compareByDescending<dev.leonardo.ocbeacon.domain.model.Session> { it.time.created }
                        .thenByDescending { it.id }
                ).map { s ->
                    dev.leonardo.ocbeacon.domain.model.SubagentChild(
                        sessionId = s.id,
                        label = s.title,
                        // FSM Busy（SSE 驱动，V1）或 active 轮询（V2）任一命中即运行中
                        isRunning = s.id in runningIds || s.id in activeIds,
                    )
                }
            }
        val snapshot = SubagentLocalSnapshot(
            rootSessionId = currentSessionId,
            childrenByParent = childrenByParent,
            // DSH label 缺失（one-shot 可选）回退 title 投影源
            titleById = sessions.mapNotNull { s ->
                s.title?.takeIf { it.isNotBlank() }?.let { s.id to it }
            }.toMap(),
        )
        val children = sessions
            .filter { it.parentId == currentSessionId }
            // 2026-08-16（用户需求）：任务列表按创建时间倒序（新任务在前）；
            // id tie-break 保证同毫秒创建的稳定排序（避免上游混合序抖动）。
            .sortedWith(
                compareByDescending<dev.leonardo.ocbeacon.domain.model.Session> { it.time.created }
                    .thenByDescending { it.id }
            )
        val toolParts = partsMap[currentSessionId].orEmpty()
            .filterIsInstance<Part.Tool>()

        val rootSummaries = children.mapNotNull { child ->
            val toolPart = toolParts.firstOrNull { part ->
                part.state is ToolState.Running &&
                    part.metadata?.get("sessionId")?.let { it.jsonPrimitive.contentOrNull } == child.id
            }
            // 2026-08-12 修复（历史 subagent 恒空）：V2 服务器主会话消息流中
            // 不存在 task/subagent tool part（实测翻 1000 条消息 0 个——
            // V2 派发子智能体走 session.create，无工具调用记录）→ 原
            // isExplicitlyBackground 过滤恒 false → subagents 恒空。
            // 子智能体会话（parentId==currentSessionId）本身就是后台任务，直接展示。
            val childRunning = child.id in runningIds || child.id in activeIds
            // 2026-08-13：前台/后台标记——子智能体运行中 + 主会话 busy（阻塞中）
            // = 前台；转后台后主会话恢复（idle）→ 后台
            val mainBusy = currentSessionId in runningIds || currentSessionId in activeIds
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
                modelId = child.model?.id,
                // 2026-08-16（#145 执行时长）🟠 服务器契约限制：V2 部署版
                // session.time.updated 创建后不随活动更新（实测 diff 6-13ms 恒定）、
                // V2 主会话无 task/subagent tool part（无 part.time.completed）、
                // 子智能体会话消息默认未加载——完成态执行时长**无数据源**，仅当
                // updated-created > 5s（真实活动过的罕见场景）才显示。
                // 运行中返回 null——UI 走时 now-created（核心场景：后台任务
                // 跑着回来看跑了多久）。upstream 候选见 backlog #146。
                durationMs = if (childRunning) null
                else (child.time.updated - child.time.created).takeIf { it > 5_000 },
            )
        }
        AggregatedSubagents(rootSummaries = rootSummaries, snapshot = snapshot)
    }.distinctUntilChanged()

    private val subagents = aggregatedSubagents.map { it.rootSummaries }.distinctUntilChanged()

    private val subagentSnapshots = aggregatedSubagents.map { it.snapshot }.distinctUntilChanged()

    /** 2026-09 树化：AgentSheet 多级树状态机（DSH 权威懒加载 / V2 本地递归双轨）。 */
    private val subagentTreeHolder = SubagentTreeHolder(
        scope = scope,
        fetcher = subagentCatalog,
        snapshots = subagentSnapshots,
    )

    /** AgentSheet 树交互入口（随 TaskUiState 下发到 UI）。 */
    val subagentTreeController: SubagentTreeController get() = subagentTreeHolder

    /** 前台 subagent 计数——TUI foregroundTasks 语义：
     *  主会话 busy（正在等待）+ 消息流中存在 running 的 task/subagent tool part
     *  且**非 background 派发**（2026-08-12 修复：task 工具 background=true 的
     *  子智能体会话是后台任务，不算前台——修复前 background 任务也被计入 → 转后台
     *  工具栏误显示"有前台任务"，用户反馈"代理还是前台的，没转后台"）。
     *  V2 转后台后主会话立即恢复（idle/继续工作），前台归零；
     *  子智能体会话本身继续 running（计入角标 runningSubagentCount）。 */
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

    /** 聚合状态：角标计数 + 面板数据 + AgentSheet 多级树。 */
    val uiState: StateFlow<TaskUiState> = combine(
        subagents,
        shellJobsStore.jobsBySession,
        foregroundCountFlow,
        sessionIdFlow,
        // 2026-09 树化：树行/懒加载态作为第 5 源（快照变更/展开收起/DSH 层拉取均驱动）
        subagentTreeHolder.state,
    ) { subagents, jobsBySession, foregroundCount, currentSessionId, tree ->
        // 2026-08-12 用户要求：面板支持"进行中/历史"切换——shells 保留全部
        //（含已结束的，ShellJobsStore 注释"便于面板展示历史"），过滤交给
        // UI 层（TaskSheet showHistory 切换）。此前仅保留运行中——
        // 用户反馈"没看到历史记录切换"后扩展。
        val shells = jobsBySession[currentSessionId].orEmpty()
        val runningSubagents = subagents.filter { it.isRunning }
        TaskUiState(
            shells = shells,
            subagents = subagents,
            runningSubagentCount = runningSubagents.size,
            foregroundSubagentCount = foregroundCount,
            runningShellCount = shells.count { it.isRunning },
            subagentTreeRows = tree.rows,
            subagentTreeLoadingIds = tree.loadingIds,
            subagentTreeController = subagentTreeController,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TaskUiState())

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
