package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DshConnOrch"

/**
 * 双 WS 帧源缝隙（测试注入假实现；生产 = 每服务器一个 [DshWsEventEngine] 实例）。
 * #276：DSH 传输只能走 WS（§1.6-1，GET 拦 426）且纯下行——上行全部走 HTTP。
 */
interface DshFrameSource {
    val connectionState: StateFlow<DshWsConnectionState>
    fun start(baseUrl: String, onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit)
    fun stop()
}

/** 历史页取数缝隙（测试注入；生产 = [DshRpcHistorySource] 走 DshRpcClient）。 */
interface DshHistorySource {
    /**
     * 拉一页历史。[beforeSeq] null = 尾页；返回原始 HistoryEntry 行 + hasMore +
     * 页内最小 seq（向前翻页游标）。页边界按 append-origin 消息对齐（§1.5 结论 4）。
     */
    suspend fun fetchPage(sessionId: String, beforeSeq: Long?, maxMessages: Int): DshHistoryPage
}

/** session.history 单页产物。 */
data class DshHistoryPage(
    val rows: List<JsonObject>,
    val hasMore: Boolean,
    val minSeq: Long?,
)

/**
 * 每服务器帧源工厂（多服务器并存：每连接独立引擎实例，互不 stop）。
 *
 * #318（2026-09-04）：按注册表线面版本路由——0.1.2 走 [DshRemoteMuxEngine]
 * （单 WS remote.mux，帧合成 0.1.1 词汇），否则走 0.1.1 双流引擎。探测在
 * 连接循环先行（SseConnectionManager DSH 分支），start 时协议已就位。
 */
class DshFrameSourceFactory @Inject constructor(
    private val rpc: DshRpcClient,
    private val registry: DshConnectionRegistry,
) {
    fun create(): DshFrameSource = DshProtocolRoutingFrameSource(rpc, registry)
}

/** follow 限界窗口：running 或最近活跃（24h）会话才开流（#319 生产实证）。 */
private const val FOLLOW_RECENCY_MS = 24L * 60 * 60 * 1000

/**
 * 时钟域容差（Standards 轴审查）：[FOLLOW_RECENCY_MS] 比较混用设备钟与服务端
 * updatedAt——设备钟快偏会使临界会话（如 23h）被判过期漏 follow；慢偏天然保守。
 */
internal const val FOLLOW_CLOCK_SKEW_MS = 30L * 60 * 1000

/** follow 开流目标：会话 id + 已装配的 SessionAddress wire 参数（#310①）。 */
data class DshFollowTarget(val sessionId: String, val address: JsonObject) {
    /** session/follow open 帧 args：{request:{address}}（SessionFollowRequest）。 */
    internal fun followArgs(): JsonObject = buildJsonObject {
        put("request", buildJsonObject { put("address", address) })
    }
}

/**
 * session.list 条目 → 动态 follow 目标（#319 生产实证：2026-09-04 生产 440 会话
 * 全量 follow 拖垮服务端，RPC 全线超时）。窗口 = running || 24h 内活跃（带
 * [FOLLOW_CLOCK_SKEW_MS] 容差）；updatedAt 缺席按 0 = 判远古不 follow（保守）。
 * 窗口外的会话不丢事件：引擎 added/status(running)/activity 三事件动态补开。
 *
 * #310① A8 缺陷A修复：子会话不再整体跳过——#319 全跳过的根因是彼时
 * session/follow 恒 {kind:session} 地址、必被服务器拒（"subagent Sessions
 * require their durable parent address"，A7 logcat 实证动态补开也被拒）；现按
 * [DshSessionAddress.fromListItem] 装配 durable subagent 地址开流（子会话转录
 * 增量自此可达）。mode 投影缺席或 origin=subagent 却无父址的孤儿行无法寻址，
 * 仍保守跳过。
 */
internal fun followTargets(items: List<JsonObject>, nowMs: Long): List<DshFollowTarget> =
    items.mapNotNull { item ->
        val sid = item.dshStr("sessionId") ?: return@mapNotNull null
        val running = item.dshBool("running") == true
        val updatedAt = item.dshLong("updatedAt") ?: 0L
        val recent = nowMs - updatedAt < FOLLOW_RECENCY_MS + FOLLOW_CLOCK_SKEW_MS
        if (!running && !recent) return@mapNotNull null
        if (item.dshStr("origin") == "subagent" && item.dshStr("parentSessionId") == null) {
            return@mapNotNull null
        }
        val address = DshSessionAddress.fromListItem(item) ?: return@mapNotNull null
        DshFollowTarget(sid, address)
    }

/** 协议路由帧源：start 时按 [DshConnectionRegistry.protocolOf] 选引擎。 */
private class DshProtocolRoutingFrameSource(
    private val rpc: DshRpcClient,
    private val registry: DshConnectionRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DshFrameSource {
    private var legacy: DshWsEventEngine? = null
    private var mux: DshRemoteMuxEngine? = null
    private val state = kotlinx.coroutines.flow.MutableStateFlow(DshWsConnectionState.Disconnected)

    override val connectionState: StateFlow<DshWsConnectionState> get() = state

    override fun start(
        baseUrl: String,
        onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit,
    ) {
        stop()
        if (registry.protocolOf(baseUrl) == DshWireProtocol.V012) {
            val conn = ServerConnection.from(baseUrl)
            // #310①：follow 目标缓存（sessionId → wire 地址）——动态补开
            // （added/status/activity）时解析；未命中（连接后才创建/激活的会话）
            // 单次 session.list 刷新（#319 语义：follow 流才是服务端负载，
            // session.list 单 RPC 便宜）。
            val targetCache = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
            suspend fun refreshTargets(): List<DshFollowTarget> =
                rpc.call(conn, "session.list", buildJsonObject {}) { value ->
                    followTargets(
                        (value.dshArr("items") ?: emptyList()).filterIsInstance<JsonObject>(),
                        nowMs = System.currentTimeMillis(),
                    )
                }.getOrElse { e ->
                    AppLogger.w(TAG, "session.list 拉取失败——本轮零 follow，重连重试: " + e.message)
                    emptyList()
                }.also { targets -> targets.forEach { targetCache[it.sessionId] = it.address } }
            mux = DshRemoteMuxEngine(
                scope = scope,
                baseUrl = baseUrl,
                registry = registry,
                listFollowTargets = { refreshTargets() },
                resolveFollowTarget = { sid ->
                    targetCache[sid]?.let { DshFollowTarget(sid, it) }
                        ?: refreshTargets().firstOrNull { it.sessionId == sid }
                },
            ).also {
                scope.launch { it.connectionState.collect { s -> state.value = s } }
                it.start(onFrame)
            }
        } else {
            legacy = DshWsEventEngine(scope = scope).also {
                scope.launch { it.connectionState.collect { s -> state.value = s } }
                it.start(baseUrl, onFrame)
            }
        }
    }

    override fun stop() {
        legacy?.stop()
        legacy = null
        mux?.stop()
        mux = null
        state.value = DshWsConnectionState.Disconnected
    }
}

/**
 * 生产历史源：session.history RPC（[conn] 为该服务器的连接参数）。
 *
 * #318 V012（journal §2.3）：session.history → session/page——payload 换
 * {address:{kind:session,sessionId}, throughSeq, beforeSeq?, maxMessages?}；
 * throughSeq 必填且 ≤ 会话当前 cursor（探测实测 past-cursor 拒绝）——由
 * session.list 该会话 projections.asOfSeq 提供（每次翻页现查，服务器权威）。
 * 行数组键 entries/events（0.1.1）→ records（0.1.2），chunk 压缩行跳过。
 */
class DshRpcHistorySource(
    private val rpc: DshRpcClient,
    private val conn: ServerConnection,
    private val protocolOf: () -> DshWireProtocol = { DshWireProtocol.V011 },
) : DshHistorySource {
    override suspend fun fetchPage(sessionId: String, beforeSeq: Long?, maxMessages: Int): DshHistoryPage {
        val protocol = registryOf(conn)
        val payload = if (protocol == DshWireProtocol.V012) {
            val item = currentListItem(sessionId)
                ?: throw IllegalStateException("session not found for page fetch: $sessionId")
            val asOfSeq = item.dshObj("projections")?.dshLong("asOfSeq")
                ?: throw IllegalStateException("session $sessionId has no projections.asOfSeq for page fetch")
            buildJsonObject {
                // #310① A8 缺陷A修复：子会话按 durable subagent 地址寻页（旧恒
                // {kind:session} 被服务器拒——"subagent Sessions require their
                // durable parent address"）；投影缺席回退 session 形态。
                put("address", DshSessionAddress.fromListItem(item) ?: buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("throughSeq", asOfSeq)
                beforeSeq?.let { put("beforeSeq", it) }
                put("maxMessages", maxMessages)
            }
        } else {
            buildJsonObject {
                put("sessionId", sessionId)
                beforeSeq?.let { put("beforeSeq", it) }
                put("maxMessages", maxMessages)
            }
        }
        val value = rpc.call(conn, "session.history", payload) { it }.getOrElse { e ->
            AppLogger.w(TAG, "session.history failed for $sessionId: " + e.message)
            throw e
        }
        val rows = (value.dshArr("entries") ?: value.dshArr("events") ?: value.dshArr("records") ?: emptyList())
            .filterIsInstance<JsonObject>()
        val minSeq = rows.minOfOrNull { row ->
            val entry = row.dshObj("event") ?: row
            entry.dshLong("seq") ?: entry.dshLong("seq0") ?: Long.MAX_VALUE
        }?.takeIf { it != Long.MAX_VALUE }
        return DshHistoryPage(rows = rows, hasMore = value.dshBool("hasMore") ?: false, minSeq = minSeq)
    }

    private fun registryOf(conn: ServerConnection): DshWireProtocol = protocolOf()

    private suspend fun currentListItem(sessionId: String): JsonObject? {
        val list = rpc.call(conn, "session.list", buildJsonObject {}) { it }.getOrElse { return null }
        return (list.dshArr("items") ?: emptyList())
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.dshStr("sessionId") == sessionId }
    }
}

/**
 * DSH 连接编排器（backlog #276 步骤⑤；设计 §2.3 事件层接线 + §1.6-5 对账协议）。
 *
 * 职责（单一 run() 会话内）：
 * 1. 启动双 WS 帧源（engine 内部自重连：500ms×2ⁿ 封顶 10s 带抖动）；
 * 2. 帧 → [DshEventMapper.mapFrame]（**rpcId 透传**，#275 flagged ①）→ Sse 分支
 *    经 [dispatch]（EventDispatcher.processEvent）注入 + [onEvent] 路由通知域；
 * 3. **SessionUpdated/SessionCreated 整替换防御**（#275 flagged ②）：最小 Session
 *    （session/title、host/session-added 产物）先与 [sessionLookup] 缓存合并
 *    directory/created（实现取「合并缓存」而非「首帧后再拉 session.list」——
 *    零额外 RPC，session.list 基线由 preLoadSessions 并行承担）；
 * 4. **本地 seq 跟踪**（[DshSessionSeqTracker]）：session/event 帧推进水位；
 * 5. **对账**：subscribed 基线帧成批到达（[settleMs] 静默窗）→ [DshReconciler.plan]
 *    → Backfill/InitialFetch 逐会话 session.history 向旧翻页（beforeSeq=baseline 起，
 *    直到页 minSeq 与回填前本地水位重叠或页尽）→ fold → 逐事件重放 + 水位推进；
 *    SessionVanished → SessionDeleted 清理本地状态。
 *
 * 线程模型：帧回调在 OkHttp 读线程 → Channel 缓冲 → 本协程串行消费（dispatch
 * 与对账互不并发，EventDispatcher 写路径无需额外同步）。
 */
@Singleton
class DshConnectionOrchestrator @Inject constructor() {

    /**
     * 运行一条 DSH 事件连接直到取消/异常（engine 自重连不返回）。调用方
     * （SseConnectionManager DSH 分支）在自己的 scope 里 launch 本方法。
     */
    suspend fun run(
        baseUrl: String,
        frameSource: DshFrameSource,
        historySource: DshHistorySource,
        tracker: DshSessionSeqTracker,
        dispatch: (SseEvent) -> Unit,
        onEvent: (SseEvent) -> Unit,
        sessionLookup: (String) -> Session?,
        onConnected: (Boolean) -> Unit,
        settleMs: Long = DEFAULT_SETTLE_MS,
        pageSize: Int = DshReconciler.DEFAULT_PAGE_SIZE,
    ) = coroutineScope {
        val frames = Channel<DshIncomingFrame>(Channel.UNLIMITED)
        val stateJob = launch {
            frameSource.connectionState.collect { state ->
                // 双流 Connected → connected（聚合取最差在 engine 内完成）
                onConnected(state == DshWsConnectionState.Connected)
            }
        }
        try {
            frameSource.start(baseUrl) { method, payload, rpcId ->
                frames.trySend(DshIncomingFrame(method, payload, rpcId))
            }
            val baseline = LinkedHashMap<String, Long>()
            var pendingBaseline = false
            while (true) {
                val frame = if (pendingBaseline) {
                    // 基线静默窗：settleMs 无新帧 → 对账（成批 subscribed 落定）
                    withTimeoutOrNull(settleMs) { frames.receive() }
                } else {
                    frames.receive()
                }
                if (frame == null) {
                    pendingBaseline = false
                    reconcile(tracker, baseline.toMap(), historySource, dispatch, onEvent, pageSize)
                    continue
                }
                when (frame.method) {
                    // seq 先行：session/event 帧内层 seq/seq0 推进水位（乱序取 max）
                    "session/event" -> frame.payload.dshStr("sessionId")?.let { sid ->
                        val event = frame.payload.dshObj("event")
                        val seq = event?.dshLong("seq") ?: event?.dshLong("seq0")
                        seq?.let { tracker.applied(sid, it) }
                    }
                    "session/subscribed" -> pendingBaseline = true
                }
                for (mapped in DshEventMapper.mapFrame(frame.method, frame.payload, frame.rpcId)) {
                    when (mapped) {
                        is DshMappedEvent.Sse -> {
                            val defended = defendDurableSubagentRemoval(
                                defendSessionReplacement(mapped.event, sessionLookup),
                                sessionLookup,
                            )
                            dispatch(defended)
                            onEvent(defended)
                        }
                        is DshMappedEvent.Subscribed -> {
                            baseline[mapped.value.sessionId] = mapped.value.lastSeq
                            pendingBaseline = true
                        }
                        is DshMappedEvent.Ignored -> Unit
                    }
                }
            }
        } finally {
            stateJob.cancel()
            frameSource.stop()
        }
    }

    // ============ 对账执行（§1.6-5） ============

    private suspend fun reconcile(
        tracker: DshSessionSeqTracker,
        baseline: Map<String, Long>,
        historySource: DshHistorySource,
        dispatch: (SseEvent) -> Unit,
        onEvent: (SseEvent) -> Unit,
        pageSize: Int,
    ) {
        if (baseline.isEmpty()) return
        val plan = DshReconciler.plan(tracker.snapshot(), baseline, pageSize)
        if (plan.isFullySynced) return
        AppLogger.i(TAG, "DSH 对账：" + plan.actions.size + " 个动作")
        for (action in plan.actions) {
            when (action) {
                is DshReconcileAction.SessionVanished -> {
                    // 会话消失（不在新基线中）→ 服务器侧已删——清本地状态
                    AppLogger.i(TAG, "DSH 会话消失：" + action.sessionId)
                    val event = SseEvent.SessionDeleted(Session(id = action.sessionId, time = Session.Time(0L, 0L)))
                    dispatch(event)
                    onEvent(event)
                    tracker.remove(action.sessionId)
                }
                is DshReconcileAction.InitialFetch ->
                    backfill(action.sessionId, action.beforeSeq, tracker, historySource, dispatch, onEvent, pageSize, initialOnly = true)
                is DshReconcileAction.Backfill ->
                    backfill(action.sessionId, action.beforeSeq, tracker, historySource, dispatch, onEvent, pageSize, initialOnly = false)
            }
        }
    }

    /**
     * 逐会话回填：beforeSeq 起 session.history 向旧翻页，fold 后重放。
     *
     * - [initialOnly]=true（新会话首拉）：只取尾页——全量历史走会话进入时的
     *   REST prefetch（对齐 V1/V2 行为，避免首连翻完 2M 事件）；
     * - 终止条件：页尽（hasMore=false）或页 minSeq 与**回填前**本地水位重叠
     *   （minSeq <= localAtStart + 1：页内最旧事件已是首个未应用 seq 或已应用——
     *   与 DshReconciler 排他水位契约对齐；回放起点 cursor = baseline + 1 见 plan）；
     * - refusedRebuild（未知事件类型）：放弃该会话后续页，水位仍推进（防重试风暴），
     *   已重放页保留（§5 fold 安全规则）。InitialFetch 全页 refused 时同样推进——
     *   残缺优于死循环。
     */
    private suspend fun backfill(
        sessionId: String,
        beforeSeq: Long,
        tracker: DshSessionSeqTracker,
        historySource: DshHistorySource,
        dispatch: (SseEvent) -> Unit,
        onEvent: (SseEvent) -> Unit,
        pageSize: Int,
        initialOnly: Boolean,
    ) {
        val localAtStart = tracker.get(sessionId) ?: 0L
        var cursor: Long? = beforeSeq
        var pages = 0
        try {
            while (cursor != null && pages < MAX_BACKFILL_PAGES) {
                val page = historySource.fetchPage(sessionId, cursor, pageSize)
                pages++
                val fold = DshHistoryFolder.fold(page.rows, sessionId)
                if (!fold.refusedRebuild) {
                    for (event in fold.sseEvents) {
                        dispatch(event)
                        onEvent(event)
                    }
                } else {
                    AppLogger.w(TAG, "DSH 回填拒绝重建（$sessionId 第 $pages 页）：" + fold.unknownUnignorable)
                }
                // 水位无论如何推进（lastSeq 与事件语义无关，#275 契约）
                if (fold.lastSeq > 0) tracker.applied(sessionId, fold.lastSeq)
                if (initialOnly) return
                if (!page.hasMore || page.minSeq == null || page.minSeq <= localAtStart + 1) return
                cursor = page.minSeq
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "DSH 回填失败（$sessionId，已翻 $pages 页）：" + e.message)
        }
    }

    // ============ SessionUpdated 整替换防御（#275 flagged ②） ============

    /**
     * 最小 Session（session/title → SessionUpdated、host/session-added → SessionCreated
     * 产物）与 handler 缓存合并：directory 空白回填缓存值、created=0 回填缓存时刻、
     * title null 回填缓存标题——防整对象替换抹掉已有元数据。其余事件原样透传。
     */
    internal fun defendSessionReplacement(event: SseEvent, sessionLookup: (String) -> Session?): SseEvent {
        val incoming = when (event) {
            is SseEvent.SessionUpdated -> event.info
            is SseEvent.SessionCreated -> event.info
            else -> return event
        }
        val existing = sessionLookup(incoming.id) ?: return event
        val merged = incoming.copy(
            directory = incoming.directory.ifBlank { existing.directory },
            title = incoming.title ?: existing.title,
            time = incoming.time.copy(
                created = if (incoming.time.created == 0L) existing.time.created else incoming.time.created,
            ),
            // 权限预设状态同样防整替换抹除（session/title 等最小 Session 不携带 permissions）
            permissions = incoming.permissions ?: existing.permissions,
            // 双轴审查 (c)1 写漏补防：agentPreset/tokenUsage/subagentTiming 同为
            // handler 折叠态且最小事件不携带——缺席保留缓存值（a16ee74b 同类
            // 回归防线：预设卡门控/高亮、token 弹窗子代理区随最小更新帧丢失）。
            agentPreset = incoming.agentPreset ?: existing.agentPreset,
            tokenUsage = incoming.tokenUsage ?: existing.tokenUsage,
            subagentTiming = incoming.subagentTiming ?: existing.subagentTiming,
            // backlog #286：goal/contextPressure/contextBreakdown/sessionStats 同为
            // handler 折叠态投影且最小事件不携带——缺席保留缓存值（GoalSheet/环入口
            // 随 session/title 最小更新帧丢失的同款回归防线）。
            goal = incoming.goal ?: existing.goal,
            contextPressure = incoming.contextPressure ?: existing.contextPressure,
            contextBreakdown = incoming.contextBreakdown ?: existing.contextBreakdown,
            sessionStats = incoming.sessionStats ?: existing.sessionStats,
            // blank 非空类型无法区分缺席——本函数只处理 session/title 与
            // host/session-added 最小事件（构造走 Session 默认 blank=false，
            // 从不携带真实 blank；全量会话走 list/投影路径不经此处），故此处
            // 无条件保留缓存值。
            blank = existing.blank,
            // #310① A8 缺陷B修复：parentId 同为 handler 折叠态字段——added 摘要可
            // 缺 parentSessionId、session/title 恒不携——缺席保留缓存值，防最小
            // 事件整对象替换抹掉子会话父址致续聊分流失效（a16ee74b 同族防线
            // 补全：ChatSendDelegate 分流条件 = 快照 parentId 非空）。
            parentId = incoming.parentId ?: existing.parentId,
        )
        return when (event) {
            is SseEvent.SessionUpdated -> event.copy(info = merged)
            is SseEvent.SessionCreated -> event.copy(info = merged)
            else -> event
        }
    }

    // ============ durable 子会话 removal 降级（#310① A8轮3） ============

    /**
     * host/session-removed 的 durable 子会话防御：DSH 对 continuable 子会话在每个
     * 轮次落定时处置**激活**（进程内 Agent 驻留期）并广播 host/session-removed——
     * 其 durable Session 在服务器持久保留、可续聊（A8 轮2 父转录 wrap-up "is now
     * idle and available for follow-ups" 实证；官方 client
     * SessionManager.handleSessionRemoved 对 origin=subagent 行记
     * `{kind:'status', running:false}` 而非 `{kind:'remove'}`）。
     *
     * app 此前无差别映射 [SseEvent.SessionDeleted]，级联（EventDispatcher
     * SessionDeleted 分支 clearForSession + handleSessionDeleted 删行）在完结回复
     * 到达 ~10ms 内抹掉整个子会话转录与会话行（a8r2.log 17:28:04.803→.814）——
     * ①完结回复永不呈现（缺陷C①）、②会话行消失使 sessionMeta 断流、消息清空触发
     * ChatEmptyState（缺陷D 自发回空态 Chat）、③重入后仅剩本地残迹（缺陷C② 破坏腿）。
     *
     * 判定与官方同构：缓存行 parentId 非空 = subagent origin（host/session-added /
     * session.list 投影两路都会带上父址；[defendSessionReplacement] 已钉缺席保留）。
     * 降级产物 = [SseEvent.SessionStatus] Idle（对齐官方 running:false；removed 帧前
     * 服务器已发 status/idle，此处幂等强化）。普通会话与未知行原样透传真删除语义。
     * 对账腿（SessionVanished）不经此处——不在 session.list 基线的行才是真消失。
     */
    internal fun defendDurableSubagentRemoval(
        event: SseEvent,
        sessionLookup: (String) -> Session?,
    ): SseEvent {
        if (event !is SseEvent.SessionDeleted) return event
        val existing = sessionLookup(event.info.id) ?: return event
        if (existing.parentId == null) return event
        AppLogger.i(
            TAG,
            "host/session-removed for durable subagent ${event.info.id} downgraded to idle " +
                "(activation disposal; session row and transcript retained)",
        )
        return SseEvent.SessionStatus(event.info.id, SessionStatus.Idle)
    }

    private data class DshIncomingFrame(val method: String, val payload: JsonObject, val rpcId: String)

    private companion object {
        /** subscribed 基线成批落定的静默窗（毫秒）——防半批触发对账。 */
        const val DEFAULT_SETTLE_MS = 400L

        /** 回填翻页护栏（单会话单轮对账上限——2M 事件库的全量翻页由 prefetch 承担）。 */
        const val MAX_BACKFILL_PAGES = 500
    }
}