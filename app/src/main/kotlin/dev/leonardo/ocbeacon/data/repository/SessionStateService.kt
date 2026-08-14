package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

fun interface DirectoryResolver { fun resolve(sessionId: String): String? }
fun interface IncompleteAssistantChecker { fun hasIncomplete(sessionId: String): Boolean }
fun interface MessageForceCompleter { fun markIdle(sessionId: String) }
fun interface MessageRefresher { fun refreshMessages(sessionId: String, messages: List<MessageWithParts>) }

private const val TAG = "SessionStateService"
private const val HISTORY_MAX = 20
private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
private const val STALENESS_THRESHOLD_MS = 15_000L
/** L3 REST 校验补漏消息数：最新 50 条足够（陈旧窗口漏消息远少于 50；避免 limit=0 全量拉取大会话）。 */
private const val REST_REFRESH_LIMIT = 50
/** 防御性清理阈值：会话状态超过该时长无事件且非 Busy 时从状态容器移除。 */
private const val STATE_RETENTION_MS = 24 * 60 * 60 * 1000L
/** 2026-08-14 僵尸判定阈值：服务器说 Busy 但 App 侧超过该时长无任何 SSE 事件
 *  （reasoning/text/tool/usage 均无）→ 视为服务器 runner 卡死（僵尸 running），
 *  强制转 Idle 恢复列表状态。真实执行中即使模型思考也会有 reasoning delta。 */
internal const val ZOMBIE_BUSY_MS = 3 * 60 * 1000L

@Singleton
class SessionStateService @Inject constructor(
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val sessionRepoProvider: Provider<SessionRepository>,
) : SessionStateRepository {
    // 构造后注入（打破与 EventDispatcher 的循环依赖）
    var directoryResolver: DirectoryResolver = DirectoryResolver { null }
    var incompleteChecker: IncompleteAssistantChecker = IncompleteAssistantChecker { false }
    var messageForceCompleter: MessageForceCompleter = MessageForceCompleter {}
    var messageRefresher: MessageRefresher = MessageRefresher { _, _ -> }

    @Volatile private var currentServerId: String? = null

    private var stalenessJob: Job? = null

    /**
     * RS-012 修复：进行中的 REST 校验的去重 map。以 sessionId 为键。
     * 当某会话已有进行中的校验时又请求了新校验，
     * 旧 job 会被取消并替换。这防止了多个触发器快速触发时
     *（staleness guard + 可疑转移 + 外部）产生 REST 请求风暴。
     */
    private val activeValidations = ConcurrentHashMap<String, Job>()

    init { startStalenessGuard() }

    private fun startStalenessGuard() {
        stalenessJob?.cancel()
        stalenessJob = appScope.launch {
            while (isActive) {
                delay(STALENESS_CHECK_INTERVAL_MS)
                checkStaleness()
            }
        }
    }

    private fun checkStaleness() {
        val now = System.currentTimeMillis()
        val expired = mutableListOf<String>()
        _fsmStates.value.forEach { (sessionId, state) ->
            if (state.core is SessionStatus.Busy && now - state.lastEventAt > STALENESS_THRESHOLD_MS) {
                AppLogger.w(TAG, "[$sessionId] L2 stale for ${now - state.lastEventAt}ms, triggering REST validation")
                triggerRestValidation(sessionId)
            }
            if (state.core is SessionStatus.Idle && incompleteChecker.hasIncomplete(sessionId)) {
                AppLogger.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete messages")
                triggerRestValidation(sessionId)
            }
            // 防御性清理：长时间（>24h）无事件且非 Busy 的孤儿状态
            // （孤儿 SSE 事件、服务器会话 id 复用、已关闭会话残留），
            // 防止 _fsmStates/_histories 无界增长。
            if (state.core !is SessionStatus.Busy && now - state.lastEventAt > STATE_RETENTION_MS) {
                expired += sessionId
            }
        }
        if (expired.isNotEmpty()) {
            AppLogger.i(TAG, "Sweeping ${expired.size} stale session state(s): $expired")
            _fsmStates.update { it - expired.toSet() }
            _histories.update { it - expired.toSet() }
        }
    }

    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())
    private val _histories = MutableStateFlow<Map<String, List<TransitionRecord>>>(emptyMap())

    override val statusFlow: StateFlow<Map<String, SessionStatus>> = _fsmStates
        .map { it.mapValues { e -> e.value.core } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    override val activityFlow: StateFlow<Map<String, SessionActivity?>> = _fsmStates
        .map { it.mapValues { e -> e.value.activity } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    override val historyFlow: StateFlow<Map<String, List<TransitionRecord>>> = _histories
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    override fun setServerId(serverId: String) { currentServerId = serverId }

    /**
     * [triggerRestValidation] 的公共包装——让外部调用方（例如
     * [SessionActionsDelegate] 的单会话进入/恢复）能为某会话请求权威的
     * REST 状态检查。当 REST 确认为 Idle 时，FSM 的 forceComplete
     * 机制会自动处理不完整消息的修复。
     */
    override fun requestValidation(sessionId: String) = triggerRestValidation(sessionId)

    // ============ 事件入口 ============
    override fun onClientSendParts(sessionId: String) {
        applyTransition(sessionId, FsmEvent.ClientSendParts)
    }
    override fun onClientAbort(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientAbort)
    override fun onRestValidation(sessionId: String, status: SessionStatus) =
        applyTransition(sessionId, FsmEvent.RestValidation(status))

    fun onSseEvent(event: SseEvent, sessionId: String) {
        val fsmEvent = mapSseEventToFsm(event) ?: return
        applyTransition(sessionId, fsmEvent)
    }

    private fun mapSseEventToFsm(event: SseEvent): FsmEvent? = when (event) {
        is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
        is SseEvent.SessionIdle -> FsmEvent.SseIdle
        is SseEvent.SessionError -> FsmEvent.SseError(event.error)
        is SseEvent.SessionNext -> mapSessionNextEvent(event.event)
        // 2026-08-12 根因修复（流式内容消失）：V2 的文本/消息事件（V2SseMapper
        // 直接映射为消息级事件，不走 SessionNext）也必须更新 FSM lastEventAt——
        // 否则 staleness guard（15s 无活动）误判连接陈旧 → 触发 REST 校验 →
        // REST_AUTHORITY 合并因 part ID 契约差异（REST id="" vs SSE 派生 id）
        // 丢弃 SSE 累积文本 → 流式内容周期性消失（V1→V2 回归：V1 文本走
        // SessionNext(TextDelta) 正常更新 lastEventAt）。
        // 语义：delta → Streaming 活动；part/消息级更新 → 内容活动（TextStarted）。
        // activityEvent 在 Busy 时更新 activity+lastEventAt；Idle 时仅更新
        // lastEventAt 并标记 suspicious（无害，语义正确）。
        is SseEvent.MessagePartDelta -> FsmEvent.TextDelta(event.delta)
        is SseEvent.MessagePartUpdated -> FsmEvent.TextStarted
        is SseEvent.MessageUpdated -> FsmEvent.TextStarted
        else -> null
    }

    private fun mapSessionNextEvent(event: SessionNextEvent): FsmEvent? = when (event) {
        is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
        is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
        is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(event.delta)
        is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
        is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(event.tool, event.callId)
        // SessionNextEvent.StepEnded 没有 finish 字段——传 null。FSM 将非 "tool-calls"
        // 的 finish 视为"保持当前 Activity，等待 Core Idle"。
        is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(finish = null)
        is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
        is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
        else -> null
    }

    // ============ 核心管线 ============
    //
    // RS-010 修复：整个 读-计算-写 都在 `.update{}` 内完成，使并发转移
    // 参与 CAS 重试。之前的模式（读在外面、计算在外面、通过 `.update{}`
    // 写）在两个线程读取相同陈旧快照、其中一个覆盖另一个时会丢失转移。
    fun applyTransition(sessionId: String, event: FsmEvent) {
        // 在 CAS 保护的 lambda 内捕获值的持有者；供更新后的
        // 副作用（历史记录、forceComplete 等）使用。
        var fromState: SessionFSMState? = null
        var result: SessionStateFSM.TransitionResult? = null
        _fsmStates.update { states ->
            val current = states[sessionId] ?: SessionFSMState.initial()
            val transitionResult = SessionStateFSM.transition(current, event)
            fromState = current
            result = transitionResult
            states + (sessionId to transitionResult.newState)
        }
        val from = fromState!!
        val res = result!!
        recordHistory(sessionId, from, res, event)
        if (BuildConfig.DEBUG) logTransition(sessionId, from, res, event)
        // 副作用
        if (res.forceComplete) messageForceCompleter.markIdle(sessionId)
        if (res.isSuspicious) triggerRestValidation(sessionId)
    }

    private fun recordHistory(sessionId: String, from: SessionFSMState, result: SessionStateFSM.TransitionResult, event: FsmEvent) {
        val record = TransitionRecord(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            event = event::class.simpleName ?: "Unknown",
            fromCore = from.core::class.simpleName ?: "?",
            toCore = result.newState.core::class.simpleName ?: "?",
            fromActivity = from.activity?.let { it::class.simpleName },
            toActivity = result.newState.activity?.let { it::class.simpleName },
            isSuspicious = result.isSuspicious,
            reason = null,
        )
        _histories.update { h ->
            val list = (h[sessionId] ?: emptyList()) + record
            val trimmed = if (list.size > HISTORY_MAX) list.takeLast(HISTORY_MAX) else list
            h + (sessionId to trimmed)
        }
    }

    private fun logTransition(sessionId: String, from: SessionFSMState, result: SessionStateFSM.TransitionResult, event: FsmEvent) {
        val actFrom = from.activity?.let { "/${it::class.simpleName}" } ?: ""
        val actTo = result.newState.activity?.let { "/${it::class.simpleName}" } ?: ""
        val flags = buildString {
            if (result.isSuspicious) append(" [SUSPICIOUS]")
            if (result.forceComplete) append(" [force-complete]")
        }
        AppLogger.d(TAG, "[$sessionId] ${from.core::class.simpleName}$actFrom --${event::class.simpleName}--> ${result.newState.core::class.simpleName}$actTo$flags")
    }

    // ============ 生命周期 ============
    override fun clearSession(sessionId: String) {
        _fsmStates.update { it - sessionId }
        _histories.update { it - sessionId }
        // 取消此会话进行中的 REST 校验（RS-012）
        activeValidations.remove(sessionId)?.cancel()
    }

    override fun clearForServer(sessionIds: Set<String>) {
        _fsmStates.update { it - sessionIds }
        _histories.update { it - sessionIds }
        // 取消已清除会话进行中的 REST 校验（RS-012）
        for (sessionId in sessionIds) {
            activeValidations.remove(sessionId)?.cancel()
        }
    }

    override fun clearAll() {
        // RS-011 修复：使用 .update{} 参与 CAS，防止并发的
        // applyTransition 通过其自身的 CAS 写入复活已清除的状态。
        _fsmStates.update { emptyMap() }
        _histories.update { emptyMap() }
        // 取消所有进行中的 REST 校验（RS-012）
        activeValidations.values.forEach { it.cancel() }
        activeValidations.clear()
    }

    // ============ L3：REST 校验（absence=idle 闭环）============
    //
    // 触发条件：
    //   - applyTransition 当 result.isSuspicious（丢失 SSE）
    //   - checkStaleness（L2 陈旧 Busy / L5 Idle 但不完整）
    //   - 外部调用方（例如手动刷新）
    //
    // 缺失语义：当查询的 [directory] 是会话自身的 directory 且会话在服务器
    // 状态 map 中缺失时，将其视为 Idle（服务器会从 map 中丢弃 idle 会话）。
    // 当 [directory] 为 null（未知实例）时，缺失是歧义的——跳过以避免误判 Idle。
    internal fun triggerRestValidation(sessionId: String) {
        val sid = currentServerId ?: return
        val directory = directoryResolver.resolve(sessionId)
        // RS-012 修复：对同一会话的并发校验去重。
        // 模式：launch → merge（原子替换，取消前一个）→ invokeOnCompletion（清理）。
        // merge 函数仅取消旧 job 并返回新 job——它不修改
        // activeValidations，避免 ConcurrentHashMap.compute() 死锁。
        val job = appScope.launch {
            try {
                val result = sessionRepoProvider.get().fetchSessionStatuses(sid, directory)
                result.onSuccess { statuses ->
                    val serverStatus = statuses[sessionId]
                    if (serverStatus != null) {
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
                        // 2026-08-14 僵尸兜底（用户反馈"会话已结束但列表仍显示进行中"）：
                        // opencode 服务器（next-17403 实测）在会话结束后 drain 可能不释放
                        // → /active 持续返回 running → App 忠实跟随 → 列表图标卡"进行中"。
                        // 判定：服务器说 Busy + App 侧超过 ZOMBIE_BUSY_MS 无任何 SSE 事件
                        // （reasoning/text/tool/usage 均无）→ 服务器 runner 卡死/僵尸 →
                        // 强制转 Idle 恢复列表（真实执行中即使思考也有 reasoning delta，
                        // 3 分钟完全无事件 = 僵尸；服务器恢复执行时 execution.started
                        // 事件会重新置 Busy）。
                        if (serverStatus is SessionStatus.Busy) {
                            // 僵尸判定：FSM lastEventAt 由真实事件更新（restValidation
                            // 不刷新——见 SessionStateFSM.restValidation 修正注释）
                            val lastEventAt = _fsmStates.value[sessionId]?.lastEventAt ?: 0L
                            val quietMs = System.currentTimeMillis() - lastEventAt
                            if (quietMs > ZOMBIE_BUSY_MS) {
                                AppLogger.w(TAG, "[$sessionId] server says Busy but no SSE events for ${quietMs}ms -> zombie runner, forcing Idle")
                                // 2026-08-14 根因修复（转圈/无回复）：仅本地强制 Idle 只是"装样子"——
                                // 服务器 runner 仍处于僵尸 running（/active 持续返回 running），
                                // 用户再发消息 POST /prompt 虽 200+admitted，但僵尸 runner 永不消费
                                // inbox → 无执行事件 → 消息永远无回复 + UI 转圈。
                                // 实测（V2 next-17403）：POST /api/session/{id}/interrupt 返回 204
                                // 且 /active 中该会话从 running 列表消失 = 服务器僵尸被解除。
                                // 此处主动调用服务器 interrupt（V1 走 abortSession / V2 走
                                // interruptSession，SessionRepository.abort 已按 apiVersion 分流），
                                // 从根因解除僵尸；幂等安全（idle 会话调用无副作用）。
                                interruptZombieRunner(sid, sessionId, directory)
                                onRestValidation(sessionId, SessionStatus.Idle)
                            } else {
                                onRestValidation(sessionId, serverStatus)
                            }
                        } else {
                            onRestValidation(sessionId, serverStatus)
                        }
                    } else if (directory != null) {
                        // 服务器会从其状态 map 中删除 idle 会话——缺失即 idle。
                        // 仅当查询的是会话自身的 directory 时才信任此结论。
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "[$sessionId] L3 REST validation: absent from own directory -> idle")
                        onRestValidation(sessionId, SessionStatus.Idle)
                    }
                    // directory == null + 缺失 -> 跳过（避免在未知实例上误判 idle）

                    // 同时刷新消息——陈旧/可疑恢复应追上
                    // 陈旧期间 SSE 错过的任何消息。
                    // limit=0 是服务器语义"全量"——大会话（1989 条）每次校验全量拉取
                    // + 解析 + upsert 会造成偶发 UI 阻塞（2026-08-10 真机/模拟器实证：
                    // 滑动第二轮 slowUI 26-30 恰好撞上 L3 校验）。校验只需补漏最新消息，
                    // 取最新 50 条足够（陈旧窗口的漏消息远少于 50，且进入会话时
                    // loadMessagesForSession 已做增量同步）。
                    // 2026-08-12 根因修复（流式内容消失）：服务器确认 Busy（流式
                    // 进行中）时跳过消息刷新——SSE 连接活着无需 REST 补漏；且
                    // REST_AUTHORITY 合并会因 part ID 契约差异（REST text id=""
                    // vs SSE 派生 id）丢弃 SSE 累积文本 → 内容周期性消失。
                    // 仅 Idle / 缺失（需要补漏）时才刷新。
                    if (serverStatus !is SessionStatus.Busy) {
                        sessionRepoProvider.get().listMessages(sid, sessionId, limit = REST_REFRESH_LIMIT)
                            .onSuccess { page ->
                                messageRefresher.refreshMessages(sessionId, page.messages)
                                if (BuildConfig.DEBUG) AppLogger.d(TAG, "[$sessionId] L3 REST message refresh: ${page.messages.size} msgs")
                            }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
            }
        }
        // 原子替换此会话的任何现有 job（取消旧 job）
        activeValidations.merge(sessionId, job) { oldJob, newJob ->
            oldJob.cancel()
            newJob
        }
        // 清理：job 完成时从去重 map 中移除。
        // invokeOnCompletion 即使 job 已完成也会触发。
        // 使用 remove(key, value)，使其仅移除自己的条目，而非更新 job 的。
        job.invokeOnCompletion { activeValidations.remove(sessionId, job) }
    }

    /**
     * 解除服务器端僵尸 runner（2026-08-14 根因修复）。
     *
     * 触发条件：L3 REST 校验确认服务器说 Busy，但 App 侧超过 [ZOMBIE_BUSY_MS]
     * 无任何 SSE 事件——服务器 runner 卡死但 /active 仍返回 running。
     *
     * 动作：调用服务器 interrupt/abort（按 apiVersion 分流：V2
     * POST /api/session/{id}/interrupt，V1 POST /session/{id}/abort）。
     * 幂等安全：对 idle 会话调用无副作用；服务器恢复执行时 execution.started
     * 事件会重新置 Busy（FSM 自然跟随）。
     *
     * 注意：interrupt 是 fire-and-forget——僵尸解除后服务器可能发
     * session.status/idle 事件，FSM 会自然同步；本方法不等待结果。
     * 失败仅告警不阻断（本地 Idle 兜底仍会执行）。
     */
    private fun interruptZombieRunner(serverId: String, sessionId: String, directory: String?) {
        val sid = currentServerId ?: serverId
        appScope.launch {
            try {
                val result = sessionRepoProvider.get().abort(sid, sessionId, directory)
                result.onSuccess {
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(TAG, "[$sessionId] zombie interrupt sent (server runner released)")
                    }
                }.onFailure { e ->
                    AppLogger.w(TAG, "[$sessionId] zombie interrupt failed: ${e.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "[$sessionId] zombie interrupt error: ${e.message}")
            }
        }
    }

    // ============ L4：完整 REST 同步（统一恢复）============
    //
    // 跨 [projects] 的 directory 从服务器拉取每个会话状态（当 [projects]
    // 为空时用单次实例级查询），再叠加本地缺失语义：本地非 Idle 但在 REST
    // 中缺失的会话视为 Idle——除非它有不完整的 assistant 消息，此时保留
    // 本地状态（SSE 可能仍在流式传输）。
    //
    // 注意：[SessionRepository.fetchSessionStatuses] 已将原始 REST DTO
    //（`RestSessionStatusInfo`）映射为领域 [SessionStatus]，因此此处无需逐条转换。
    override suspend fun syncFromRest(projects: List<Project>): SyncResult {
        val sid = currentServerId ?: return SyncResult(0, 0)
        val aggregated = mutableMapOf<String, SessionStatus>()
        val dirs: List<String?> = if (projects.isEmpty()) listOf(null) else projects.map { it.worktree }
        for (dir in dirs) {
            sessionRepoProvider.get().fetchSessionStatuses(sid, dir)
                .onSuccess { aggregated += it }
        }
        // 缺失语义：本地非 Idle 但在 REST 中缺失
        for ((sessionId, state) in _fsmStates.value) {
            if (state.core !is SessionStatus.Idle && sessionId !in aggregated) {
                aggregated[sessionId] = if (incompleteChecker.hasIncomplete(sessionId)) state.core  // 保护（SSE 可能仍在流式传输）
                                          else SessionStatus.Idle                                       // 缺失 = idle
            }
        }
        if (BuildConfig.DEBUG) {
            val busyCount = aggregated.count { it.value is SessionStatus.Busy }
            AppLogger.d(TAG, "[syncFromRest] aggregated=${aggregated.size} busy=${busyCount} dirs=${dirs.size}")
        }
        for ((sessionId, status) in aggregated) applyTransition(sessionId, FsmEvent.RestValidation(status))
        return SyncResult(aggregated.size, aggregated.count { it.value is SessionStatus.Busy })
    }
}

