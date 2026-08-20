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

/**
 * 2026-08-16 根治（回复不可见）：新增 [strategy] 参数——SSE 断连窗口补漏
 * 用 SSE_PRIORITY（SSE 已累积的流式文本不动，仅补本地缺失）；
 * L3 校验路径传 REST_AUTHORITY（服务器已确认终态，权威覆盖）。
 */

private const val TAG = "SessionStateService"
private const val HISTORY_MAX = 20
private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
private const val STALENESS_THRESHOLD_MS = 15_000L

/** #122 D2-15：lastEventAt 无变化节流窗口——仅时间戳变化的事件在该窗口内
 *  跳过状态写入（lastEventAt 消费阈值最小 15s，1s 粒度无损）。 */
private const val LAST_EVENT_THROTTLE_MS = 1_000L

/** 2026-08-16（状态对账）：正向自愈确认轮数（×轮询间隔 ≈10s，防 started 在途误判）。 */
private const val ACTIVE_POSITIVE_CONFIRM_ROUNDS = 2

/** 2026-08-16（状态对账）：「active 缺失即 idle」的新鲜度护栏——SSE 近期有
 *  活动的会话不因快照缺失强转 Idle（active 是不完整快照，SSE 是更强证据）。 */
private const val ABSENT_FRESHNESS_GRACE_MS = 60_000L
/** L3 REST 校验补漏消息数：最新 50 条足够（陈旧窗口漏消息远少于 50；避免 limit=0 全量拉取大会话）。 */
private const val REST_REFRESH_LIMIT = 50
/** 2026-08-16（F5 补漏失败重试）：补漏失败最大重试次数（线性退避 attempt×base）。 */
private const val REST_BACKFILL_MAX_RETRIES = 3
/** 2026-08-16（F5）：重试退避基数——attempt 1→2s、2→4s、3→6s。 */
private const val REST_BACKFILL_RETRY_BASE_MS = 2_000L
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
    private val collaborator: SessionStateCollaborator,
) : SessionStateRepository {

    @Volatile private var currentServerId: String? = null

    /**
     * #110（D2-12）：session → 归属服务器映射。onSseEvent 时由实际投递的
     * serverId 记录——多服务器并发时各会话的 REST 校验打到自己的服务器
     *（原全局 currentServerId 单值被后连接者覆盖 → L3 校验打错服务器 →
     * forceComplete 提前完结流式）。currentServerId 保留为 fallback
     *（无归属记录的外部校验请求，如 SessionListViewModel 手动刷新）。
     */
    private val sessionServerOwnership = java.util.concurrent.ConcurrentHashMap<String, String>()

    private var stalenessJob: Job? = null

    /**
     * RS-012 修复：进行中的 REST 校验的去重 map。以 sessionId 为键。
     * 当某会话已有进行中的校验时又请求了新校验，
     * 旧 job 会被取消并替换。这防止了多个触发器快速触发时
     *（staleness guard + 可疑转移 + 外部）产生 REST 请求风暴。
     */
    private val activeValidations = ConcurrentHashMap<String, Job>()

    /** 2026-08-16（状态对账）：正向自愈连续采样计数（active 含但 FSM 非 Busy）。 */
    private val activePositiveStreak = ConcurrentHashMap<String, Int>()

    /**
     * 2026-08-16 修复（F5 补漏失败重试）：L3 REST 校验的消息补漏
     * （V2 NEWER 游标 / V1 limit=50）失败退避重试计数（会话级）。
     * 根因：原实现只 onSuccess 刷新——补漏请求失败（网络抖动/服务器 5xx）
     * 被静默吞掉，SSE 断连窗口漏掉的消息无人补齐，直到下次 staleness 触发。
     * 重试上限 [REST_BACKFILL_MAX_RETRIES]，线性退避；成功即清零。
     */
    private val restBackfillRetries = ConcurrentHashMap<String, Int>()

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
            if (state.core is SessionStatus.Idle && collaborator.hasIncompleteAssistant(sessionId)) {
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

    /**
     * active 轮询结果与 FSM 的双向对账——2026-08-16 根治（会话状态显示不对：
     * 输出中但列表页/对话页均不显示，重进才发现内容已变多）。
     *
     * 根因（实测+调研）：V2 无 session.status SSE，turn 信号只有
     * execution.started/succeeded。SSE 断连窗口（App 后台冻结/半开连接，
     * 重连需 40s 心跳超时+退避）丢失 execution.started 后：
     * - FSM 不进 Busy → 列表页/对话页共用 statusFlow → 都不显示「进行中」
     * - 后续 delta 在 Idle core 只标 suspicious 不恢复 Busy（FSM 不可自愈）
     * - active 轮询每 5s 都拿到 running（实测 type=running）却无回写机制
     *
     * 对账规则（active 是不完整快照——服务器僵尸会持续 running，不能盲信）：
     * - **空集直接返回**：V1 active 恒空（无信息不做任何判定——防 L3 风暴）；
     *   V2 无活跃会话同理
     * - **正向**（核心，修 SSE 断连丢 started）：active 含会话但 FSM 非 Busy →
     *   连续 2 次采样（≈10s，防 execution.started 仍在传输路上的误判）确认后
     *   触发 L3 校验——走完整链路（directory 查询+僵尸判定）：服务器确认
     *   running → onRestValidation(Busy)；服务器僵尸会被既有 L2+ZOMBIE_BUSY_MS
     *   链路收拾（quietMs>3min 强制 Idle/interrupt），不会永久 Busy
     * - **反向**（R3 僵尸自愈+新鲜度护栏）：FSM Busy 但不在 active **且**
     *   lastEventAt 陈旧（≥STALENESS_THRESHOLD_MS，L2 同款判据——活跃流式中
     *   的会话绝不送 L3，防 REST「缺失即 idle」误杀正在输出的会话）
     */
    override fun reconcileWithActiveSessions(activeIds: Set<String>) {
        if (activeIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val states = _fsmStates.value
        val busyIds = states.filterValues { it.core is SessionStatus.Busy }.keys

        // 正向：active 含但 FSM 非 Busy——连续采样确认后触发 L3（恢复 Busy）
        for (sid in activeIds) {
            if (sid in busyIds) {
                activePositiveStreak.remove(sid)
                continue
            }
            val streak = activePositiveStreak.merge(sid, 1, Int::plus) ?: 1
            if (streak >= ACTIVE_POSITIVE_CONFIRM_ROUNDS) {
                activePositiveStreak.remove(sid)
                AppLogger.d(TAG, "[$sid] active says running but FSM not Busy (streak=$streak, SSE gap?) -> L3 validation to recover")
                requestValidation(sid)
            }
        }
        // 会话不在本次 active 的正向计数清零（抖动保护）
        activePositiveStreak.keys.removeAll { it !in activeIds }

        // 反向：FSM Busy 但不在 active 且事件陈旧——L3 校验（僵尸自愈）。
        // 活跃流式（lastEventAt 新鲜）绝不送——SSE 是比 active 快照更强的证据。
        for (sid in busyIds) {
            if (sid in activeIds) continue
            val lastEventAt = states[sid]?.lastEventAt ?: 0L
            if (now - lastEventAt >= STALENESS_THRESHOLD_MS) {
                AppLogger.d(TAG, "[$sid] FSM Busy but absent from active & stale for ${now - lastEventAt}ms -> L3 validation")
                requestValidation(sid)
            }
        }
    }

    // ============ SSE 断连窗口补漏（2026-08-16 根治·回复不可见） ============

    /** 补漏 job 去重表（同会话并发补漏：merge 取消旧 job）。 */
    private val backfillJobs = ConcurrentHashMap<String, Job>()

    /**
     * 2026-08-16 根治（用户报告"发送后必须退出会话重进才能看到 agent 回复"）。
     *
     * 根因链（实证）：
     * 1. 发送后 FSM Busy → 用户切后台/锁屏 → Android 冻结进程 → SSE 断连
     *    （半开，服务器不知情继续流式生成）；
     * 2. 断连窗口内服务器发出的 MessageUpdated/PartDelta 全部丢失；
     * 3. 切回后 L3 校验：服务器仍 running（含 V2 僵尸 drain）→ **跳过消息刷新**
     *    （8-12 修复的护栏——REST_AUTHORITY 合并会丢 SSE 累积文本）；
     * 4. SSE 重连后服务器只发新事件（不重发历史）→ 无任何补漏触发点；
     * 5. 服务器僵尸 running 时 active 恒含该会话 → 反向对账（Idle 时补漏）
     *    永不触发 → 回复永久不可见，直到用户退出会话重进（进入时增量拉取）。
     *
     * 修复：cursor 增量补漏（anchorId 之后的消息）+ **SSE_PRIORITY 合并**——
     * 流式进行中调用也安全（不覆盖 SSE 已有内容）。与 L3 补漏的差异：
     * 不做任何状态判定（Busy 也补），只补内容不碰 FSM。
     */
    override fun backfillMissedMessages(sessionId: String) {
        val sid = sessionServerOwnership[sessionId] ?: currentServerId ?: return
        // merge 模式（同 RS-012）：新 job 取消旧 job，防重复拉取
        val job = appScope.launch {
            try {
                // 2026-08-17 R3 缺口修复（缺口①）：anchorId 为 null 时不再直接放弃——
                // 本地无锚点消息（清数据/新装后重连）会永久漏补。退化为无 cursor
                // 拉最新 REST_REFRESH_LIMIT 条（V1/V2 均传 null cursor，与 L3 校验
                // 路径的兜底同款）。
                val anchorId = collaborator.latestMessageId(sessionId)
                if (anchorId == null) {
                    AppLogger.d(TAG, "[$sessionId] backfill no anchor -> fallback latest")
                }
                val directory = collaborator.resolveDirectory(sessionId)
                val isV2 = sessionRepoProvider.get().getApiVersion(sid).isV2
                val cursor = if (isV2 && anchorId != null) {
                    dev.leonardo.ocbeacon.domain.util.CursorCodec.encodeV2(
                        anchorId,
                        dev.leonardo.ocbeacon.domain.util.CursorCodec.V2Direction.NEWER,
                    )
                } else null
                sessionRepoProvider.get()
                    .listMessages(sid, sessionId, limit = REST_REFRESH_LIMIT, before = cursor)
                    .onSuccess { page ->
                        if (page.messages.isNotEmpty()) {
                            collaborator.refreshMessages(sessionId, page.messages, MergeStrategy.SSE_PRIORITY)
                            if (BuildConfig.DEBUG) {
                                AppLogger.d(TAG, "[$sessionId] reconnect backfill: +${page.messages.size} msgs (SSE_PRIORITY)")
                            }
                        } else if (cursor != null) {
                            // 2026-08-17 R3 缺口修复（缺口②）：V2 cursor 是服务器窗口
                            // 语义，anchorId 滑出服务器 cursor 窗口时返回 200+空页 →
                            // 增量补漏永远拿不到消息。兜底：无 cursor 重拉最新
                            // REST_REFRESH_LIMIT 条一次（同 L3 校验路径兜底）；兜底
                            // 结果不再递归兜底（只兜底一层，防死循环）。
                            AppLogger.d(TAG, "[$sessionId] backfill empty page -> fallback latest")
                            sessionRepoProvider.get()
                                .listMessages(sid, sessionId, limit = REST_REFRESH_LIMIT, before = null)
                                .onSuccess { fallbackPage ->
                                    if (fallbackPage.messages.isNotEmpty()) {
                                        collaborator.refreshMessages(sessionId, fallbackPage.messages, MergeStrategy.SSE_PRIORITY)
                                    }
                                    if (BuildConfig.DEBUG) {
                                        AppLogger.d(TAG, "[$sessionId] reconnect backfill fallback: +${fallbackPage.messages.size} msgs (SSE_PRIORITY)")
                                    }
                                }
                        }
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "[$sessionId] reconnect backfill failed: ${e.message}", e)
            } finally {
                backfillJobs.remove(sessionId)
            }
        }
        backfillJobs.merge(sessionId, job) { old, new -> old.cancel(); new }
    }

    /**
     * SSE 重连成功时补漏该服务器名下的活跃会话（Busy 或 FSM 有状态且近期有事件）。
     * 重连=断连窗口结束的权威信号——错过的事件无法重发，靠本方法内容对账。
     */
    fun backfillActiveForServer(serverId: String) {
        val now = System.currentTimeMillis()
        _fsmStates.value.forEach { (sid, state) ->
            val owner = sessionServerOwnership[sid] ?: return@forEach
            if (owner != serverId) return@forEach
            val isBusy = state.core is SessionStatus.Busy
            val recent = now - state.lastEventAt < STATE_RETENTION_MS
            if (isBusy || recent) backfillMissedMessages(sid)
        }
    }

    // ============ 事件入口 ============
    override fun onClientSendParts(sessionId: String) {
        applyTransition(sessionId, FsmEvent.ClientSendParts)
    }
    override fun onClientAbort(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientAbort)
    override fun onRestValidation(sessionId: String, status: SessionStatus) =
        applyTransition(sessionId, FsmEvent.RestValidation(status))

    fun onSseEvent(event: SseEvent, sessionId: String, serverId: String) {
        // #110（D2-12）：记录事件投递来源——REST 校验的服务器归属。
        sessionServerOwnership[sessionId] = serverId
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
        // #122 D2-15：无变化短路是否命中（见下方判断处注释）
        var stateChanged = true
        _fsmStates.update { states ->
            val current = states[sessionId] ?: SessionFSMState.initial()
            val transitionResult = SessionStateFSM.transition(current, event)
            fromState = current
            result = transitionResult
            val ns = transitionResult.newState
            // #122 D2-15（流式 GC 压力根治）：流式期间高频事件（TextDelta/
            // MessageUpdated ~48ms/次）在稳定活动态（如 Streaming）下 newState
            // 与 current 的差异**仅剩 lastEventAt**——而 lastEventAt 的全部
            // 消费阈值（最小 STALENESS_THRESHOLD_MS=15s）远大于秒级。仅时间戳
            // 变化且增量 < LAST_EVENT_THROTTLE_MS 时跳过整表拷贝与下游发射
            //（返回原 map 引用 → StateFlow equality O(1) 短路），同时跳过
            // history 记录（状态未变即无转移可记，也省一次 O(n) 拷贝）。
            // 副作用（suspicious/forceComplete）不受影响照常执行。
            val onlyTimestampChanged =
                ns.copy(lastEventAt = current.lastEventAt) == current
            if (onlyTimestampChanged &&
                ns.lastEventAt - current.lastEventAt < LAST_EVENT_THROTTLE_MS
            ) {
                stateChanged = false
                states
            } else {
                states + (sessionId to ns)
            }
        }
        val from = fromState!!
        val res = result!!
        if (stateChanged) {
            recordHistory(sessionId, from, res, event)
        }
        if (BuildConfig.DEBUG) logTransition(sessionId, from, res, event)
        // 副作用
        if (res.forceComplete) collaborator.forceCompleteSession(sessionId)
        if (res.isSuspicious) triggerRestValidation(sessionId)
        // 堆积消息推进：仅自然成功 turn 结束触发（见 naturalTurnEndListener 注释）
        if (from.core is SessionStatus.Busy &&
            res.newState.core is SessionStatus.Idle &&
            (event is FsmEvent.SseIdle || (event is FsmEvent.SseStatus && event.status is SessionStatus.Idle))
        ) {
            collaborator.onNaturalTurnEnd(sessionId, sessionServerOwnership[sessionId] ?: currentServerId)
        }
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
        sessionServerOwnership.remove(sessionId)
        // 2026-08-16（F5）：清理补漏重试计数（会话已清除，重试不再有意义）
        restBackfillRetries.remove(sessionId)
    }

    override fun clearForServer(sessionIds: Set<String>) {
        _fsmStates.update { it - sessionIds }
        _histories.update { it - sessionIds }
        // 取消已清除会话进行中的 REST 校验（RS-012）
        for (sessionId in sessionIds) {
            activeValidations.remove(sessionId)?.cancel()
            sessionServerOwnership.remove(sessionId)
            // 2026-08-16（F5）：清理补漏重试计数
            restBackfillRetries.remove(sessionId)
        }
    }

    override fun clearAll() {
        // RS-011 修复：使用 .update{} 参与 CAS，防止并发的
        // applyTransition 通过其自身的 CAS 写入复活已清除的状态。
        _fsmStates.update { emptyMap() }
        _histories.update { emptyMap() }
        sessionServerOwnership.clear()
        // 取消所有进行中的 REST 校验（RS-012）
        activeValidations.values.forEach { it.cancel() }
        activeValidations.clear()
        // 2026-08-16（F5）：清理补漏重试计数
        restBackfillRetries.clear()
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
        // #110（D2-12）：优先用会话归属服务器（SSE 投递记录）；
        // 无归属（手动刷新等）回退全局 currentServerId。
        val sid = sessionServerOwnership[sessionId] ?: currentServerId ?: return
        val directory = collaborator.resolveDirectory(sessionId)
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
                            // 僵尸判定：FSM lastEventAt 由真实事件更新（restValidation 不刷新——见 SessionStateFSM.restValidation 修正注释）
                            val lastEventAt = _fsmStates.value[sessionId]?.lastEventAt ?: 0L
                            val quietMs = System.currentTimeMillis() - lastEventAt
                            if (quietMs > ZOMBIE_BUSY_MS) {
                                // 2026-08-14 走查修复（误杀防护）：pending question/permission 时服务器在合法
                                // 等待用户输入（此期间无 SSE 事件属正常，非僵尸）——不得 interrupt（会杀掉等待中的
                                // 提问/权限对话框，用户 >3 分钟未回答即被误杀）。QuestionAsked/PermissionAsked
                                // 事件不映射 FSM（mapSseEventToFsm 返回 null）→ lastEventAt 不更新，故必须显式检查。
                                val hasPendingUserInput = collaborator.hasPendingUserInput(sessionId)
                                // 2026-08-15（僵尸误杀修复·二）：有活跃子会话（后台任务/
                                // subagent running）时主会话 running 是 V2 drain 合法等待
                                // 状态——不 interrupt（否则等待后台任务的主会话被误杀，
                                // 用户零操作被打断）。仅本地转 Idle 跟随显示。
                                val hasActiveChildren = collaborator.hasActiveChildren(sid, sessionId)
                                if (hasPendingUserInput || hasActiveChildren) {
                                    // pending 用户输入 / 活跃子会话：不 interrupt，也**不强转 Idle**
                                    //（2026-08-18 E2E-G 修复：原"仅本地强制 Idle"与 :150 的 active-running
                                    // 校验形成 Busy↔Idle 每 10s 抖动循环——服务器仍 running 是真实状态
                                    // （等待用户输入），FSM 应保持 Busy 跟随；用户提交答案/后台完成后
                                    // 事件流恢复自然转 Idle。抖动还会与 BACK pop 的 fade 过渡竞态致全屏空白）
                                    AppLogger.w(TAG, "[$sessionId] server says Busy but no SSE events for ${quietMs}ms; ${if (hasActiveChildren) "active background children" else "pending user input"} -> skip zombie interrupt, keep Busy (waiting)")
                                } else {
                                    // 2026-08-14 根因修复（转圈/无回复）：仅本地强制 Idle 只是“装样子”——
                                    // 服务器 runner 仍处于僵尸 running（/active 持续返回 running），用户再发消息
                                    // POST /prompt 虽 200+admitted，但僵尸 runner 永不消费 inbox → 无执行事件 →
                                    // 消息永远无回复 + UI 转圈。实测（V2 next-17403）：POST interrupt 返回 204 且
                                    // /active 中该会话从 running 消失 = 服务器僵尸被解除。interrupt 幂等安全（idle
                                    // 会话调用无副作用；V1 abortSession / V2 interruptSession 已按 apiVersion 分流）。
                                    AppLogger.w(TAG, "[$sessionId] server says Busy but no SSE events for ${quietMs}ms -> zombie runner, forcing Idle")
                                    interruptZombieRunner(sid, sessionId, directory)
                                }
                                // 仅僵尸路径强制本地 Idle（服务器已被 interrupt 解除）；
                                // pending/子会话路径保持 FSM 跟随服务器（Busy）——见上方注释
                                if (!hasPendingUserInput && !hasActiveChildren) {
                                    onRestValidation(sessionId, SessionStatus.Idle)
                                }
                            } else {
                                onRestValidation(sessionId, serverStatus)
                            }
                        } else {
                            onRestValidation(sessionId, serverStatus)
                        }
                    } else if (directory != null) {
                        // 服务器会从其状态 map 中删除 idle 会话——缺失即 idle。
                        // 仅当查询的是会话自身的 directory 时才信任此结论。
                        // 2026-08-16 护栏（状态误杀修复）：SSE 近期（<60s）有事件的
                        // 会话不因 active 快照缺失强转 Idle——active 是不完整快照
                        //（目录路由差异/时序），活跃流式是更强证据，宁可信 SSE。
                        val lastEventAt = _fsmStates.value[sessionId]?.lastEventAt ?: 0L
                        val sseFresh = System.currentTimeMillis() - lastEventAt < ABSENT_FRESHNESS_GRACE_MS
                        if (sseFresh) {
                            AppLogger.w(TAG, "[$sessionId] L3: absent from active but SSE fresh (${System.currentTimeMillis() - lastEventAt}ms ago) -> keep current status")
                        } else {
                            if (BuildConfig.DEBUG) AppLogger.d(TAG, "[$sessionId] L3 REST validation: absent from own directory -> idle")
                            onRestValidation(sessionId, SessionStatus.Idle)
                        }
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
                        // #55 根因修复：V2 游标增量补漏替代固定 limit=50 拉最新——
                        // 长时间离线陈旧窗口 >50 条仍丢消息。用本地最新消息 id 构造
                        // NEWER 方向游标（CursorCodec.encodeV2 direction=previous），
                        // 服务器返回该 id 之后的消息（limit 内）；V1 无 after/cursor
                        // 能力保持 limit=50 拉最新（协议限制，不更差）。
                        val anchorId = collaborator.latestMessageId(sessionId)
                        val isV2 = sessionRepoProvider.get().getApiVersion(sid).isV2
                        val cursor = if (isV2 && anchorId != null) {
                            dev.leonardo.ocbeacon.domain.util.CursorCodec.encodeV2(
                                anchorId,
                                dev.leonardo.ocbeacon.domain.util.CursorCodec.V2Direction.NEWER,
                            )
                        } else null
                        val backfillResult = sessionRepoProvider.get()
                            .listMessages(sid, sessionId, limit = REST_REFRESH_LIMIT, before = cursor)
                        backfillResult.onSuccess { page ->
                            // 2026-08-16（F5）：补漏成功清零重试计数
                            restBackfillRetries.remove(sessionId)
                            if (page.messages.isNotEmpty()) {
                                collaborator.refreshMessages(sessionId, page.messages, MergeStrategy.REST_AUTHORITY)
                            } else if (cursor != null) {
                                // 2026-08-16 根治（窗口外锚点静默空转）：curl 实证 V2
                                // cursor 是服务器窗口语义——本地构造的 encodeV2 游标
                                // 若锚点落在服务器近期窗口外（长时间断连后本地最新
                                // id 已老），服务器返回 200+空页而非错误 → 增量补漏
                                // 静默拿不到任何消息。兜底：无游标重拉最新窗口
                                //（REST_REFRESH_LIMIT 条），refreshMessages 合并
                                // 幂等（已存在的消息不会重复）。
                                AppLogger.w(TAG, "[$sessionId] L3 backfill empty page with local cursor (anchor out of server window?) -> fallback fetch latest window")
                                sessionRepoProvider.get()
                                    .listMessages(sid, sessionId, limit = REST_REFRESH_LIMIT, before = null)
                                    .onSuccess { fallbackPage ->
                                        if (fallbackPage.messages.isNotEmpty()) {
                                            collaborator.refreshMessages(sessionId, fallbackPage.messages, MergeStrategy.REST_AUTHORITY)
                                        }
                                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "[$sessionId] L3 fallback refresh: ${fallbackPage.messages.size} msgs")
                                    }
                            }
                            if (BuildConfig.DEBUG) AppLogger.d(TAG, "[$sessionId] L3 REST message refresh: ${page.messages.size} msgs (cursor=${cursor?.take(10)})")
                        }
                        backfillResult.onFailure { e ->
                            // 2026-08-16 修复（F5 补漏失败重试）：原实现只 onSuccess——
                            // 补漏失败被静默吞掉，SSE 断连窗口漏掉的消息无人补齐
                            //（外层 catch 捕不到 Result.failure）。改为退避重试整个
                            // triggerRestValidation（保留"先转 Idle 后补漏"的原顺序——
                            // 重试仅补漏段；状态已收敛为 Idle，重跑校验幂等安全）。
                            val attempt = restBackfillRetries.merge(sessionId, 1, Int::plus) ?: 1
                            if (attempt <= REST_BACKFILL_MAX_RETRIES) {
                                AppLogger.w(TAG, "[$sessionId] L3 backfill failed (attempt $attempt/$REST_BACKFILL_MAX_RETRIES): ${e.message} -> retry with backoff")
                                appScope.launch {
                                    delay(REST_BACKFILL_RETRY_BASE_MS * attempt)
                                    triggerRestValidation(sessionId)
                                }
                            } else {
                                AppLogger.w(TAG, "[$sessionId] L3 backfill failed after $attempt attempts, giving up (下次 staleness 检查会重新触发)")
                                restBackfillRetries.remove(sessionId)
                            }
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
        // 2026-08-15（对齐官方调研结论，research/05 文档）：官方客户端（TUI/Web）
        // **不存在任何自动 interrupt**——所有 interrupt 由用户显式动作触发
        //（Esc 三连击/停止按钮/undo 前置）。官方对"running 但无事件"的态度是
        // 无限期等待、只修本地显示。我们的自动 zombie interrupt 已实证误杀
        //（主会话等待后台子代理被打断——用户零操作）。
        // 收紧：默认只修显示（本地转 Idle，不调服务器 interrupt）。
        // 自动 interrupt 关闭；未来如需恢复，须以用户手动入口（会话详情
        // "强制解除卡死"）+ 长工具静默防护 + V1 禁用（V1 abort 级联取消
        // 后台 job，run-state.ts:77-86）为前提。
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[$sessionId] zombie display-fix only (auto interrupt disabled per official semantics)")
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
                aggregated[sessionId] = if (collaborator.hasIncompleteAssistant(sessionId)) state.core  // 保护（SSE 可能仍在流式传输）
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

