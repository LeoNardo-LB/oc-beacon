package dev.leonardo.ocbeacon.data.repository

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SyncResult
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
fun interface MessageRefresher { fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) }

private const val TAG = "SessionStateService"
private const val HISTORY_MAX = 20
private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
private const val STALENESS_THRESHOLD_MS = 15_000L

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
        _fsmStates.value.forEach { (sessionId, state) ->
            if (state.core is SessionStatus.Busy && now - state.lastEventAt > STALENESS_THRESHOLD_MS) {
                Log.w(TAG, "[$sessionId] L2 stale for ${now - state.lastEventAt}ms, triggering REST validation")
                triggerRestValidation(sessionId)
            }
            if (state.core is SessionStatus.Idle && incompleteChecker.hasIncomplete(sessionId)) {
                Log.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete messages")
                triggerRestValidation(sessionId)
            }
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
        Log.d(TAG, "[$sessionId] ${from.core::class.simpleName}$actFrom --${event::class.simpleName}--> ${result.newState.core::class.simpleName}$actTo$flags")
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
                        if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
                        onRestValidation(sessionId, serverStatus)
                    } else if (directory != null) {
                        // 服务器会从其状态 map 中删除 idle 会话——缺失即 idle。
                        // 仅当查询的是会话自身的 directory 时才信任此结论。
                        if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: absent from own directory -> idle")
                        onRestValidation(sessionId, SessionStatus.Idle)
                    }
                    // directory == null + 缺失 -> 跳过（避免在未知实例上误判 idle）

                    // 同时刷新消息——陈旧/可疑恢复应追上
                    // 陈旧期间 SSE 错过的任何消息。
                    sessionRepoProvider.get().listMessages(sid, sessionId, limit = 0)
                        .onSuccess { messages ->
                            messageRefresher.replaceMessages(sessionId, messages)
                            if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST message refresh: ${messages.size} msgs")
                        }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
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
        for ((sessionId, status) in aggregated) applyTransition(sessionId, FsmEvent.RestValidation(status))
        return SyncResult(aggregated.size, aggregated.count { it.value is SessionStatus.Busy })
    }
}

