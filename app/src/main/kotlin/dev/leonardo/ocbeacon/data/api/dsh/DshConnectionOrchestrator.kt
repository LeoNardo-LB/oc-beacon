package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
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

/** 每服务器帧源工厂（多服务器并存：每连接独立引擎实例，互不 stop）。 */
class DshFrameSourceFactory @Inject constructor() {
    fun create(): DshFrameSource = DshWsEngineFrameSource()
}

/** 生产帧源：包一个 [DshWsEventEngine]（scope = IO + SupervisorJob；重连在引擎内部）。 */
private class DshWsEngineFrameSource(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DshFrameSource {
    private val engine = DshWsEventEngine(scope = scope)
    override val connectionState: StateFlow<DshWsConnectionState> get() = engine.connectionState
    override fun start(
        baseUrl: String,
        onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit,
    ) = engine.start(baseUrl, onFrame)

    override fun stop() = engine.stop()
}

/** 生产历史源：session.history RPC（[conn] 为该服务器的连接参数）。 */
class DshRpcHistorySource(
    private val rpc: DshRpcClient,
    private val conn: ServerConnection,
) : DshHistorySource {
    override suspend fun fetchPage(sessionId: String, beforeSeq: Long?, maxMessages: Int): DshHistoryPage {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            beforeSeq?.let { put("beforeSeq", it) }
            put("maxMessages", maxMessages)
        }
        val value = rpc.call(conn, "session.history", payload) { it }.getOrElse { e ->
            AppLogger.w(TAG, "session.history failed for $sessionId: " + e.message)
            throw e
        }
        val rows = (value.dshArr("entries") ?: value.dshArr("events") ?: emptyList())
            .filterIsInstance<JsonObject>()
        val minSeq = rows.minOfOrNull { row ->
            val entry = row.dshObj("event") ?: row
            entry.dshLong("seq") ?: entry.dshLong("seq0") ?: Long.MAX_VALUE
        }?.takeIf { it != Long.MAX_VALUE }
        return DshHistoryPage(rows = rows, hasMore = value.dshBool("hasMore") ?: false, minSeq = minSeq)
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
                            val defended = defendSessionReplacement(mapped.event, sessionLookup)
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
     *   （minSeq <= localAtStart + 1，off-by-one 边界与 DshReconciler 同契约）；
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
        )
        return when (event) {
            is SseEvent.SessionUpdated -> event.copy(info = merged)
            is SseEvent.SessionCreated -> event.copy(info = merged)
            else -> event
        }
    }

    private data class DshIncomingFrame(val method: String, val payload: JsonObject, val rpcId: String)

    private companion object {
        /** subscribed 基线成批落定的静默窗（毫秒）——防半批触发对账。 */
        const val DEFAULT_SETTLE_MS = 400L

        /** 回填翻页护栏（单会话单轮对账上限——2M 事件库的全量翻页由 prefetch 承担）。 */
        const val MAX_BACKFILL_PAGES = 500
    }
}
