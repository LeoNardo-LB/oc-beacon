package dev.leonardo.ocbeacon.service

import java.util.concurrent.ConcurrentHashMap
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.NetworkMonitor
import dev.leonardo.ocbeacon.data.api.SseClient
import dev.leonardo.ocbeacon.data.api.SseReadTimeoutTracker
import dev.leonardo.ocbeacon.data.api.dsh.DshConnectionOrchestrator
import dev.leonardo.ocbeacon.data.api.dsh.DshFrameSourceFactory
import dev.leonardo.ocbeacon.data.api.dsh.DshRpcClient
import dev.leonardo.ocbeacon.data.api.dsh.DshRpcHistorySource
import dev.leonardo.ocbeacon.data.api.dsh.DshSessionSeqTracker
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseConnManager"
private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L
private const val RECONNECT_BACKOFF_FACTOR = 2.0
private const val COOLDOWN_CHECK_INTERVAL_MS = 30_000L

/** #150 方向③：preLoadSessions 项目间并发拉取 /session 的受控并发上限。 */
private const val PRELOAD_PROJECT_CONCURRENCY = 4

/** #278：播种（syncFromRest）NonCancellable 保护区的时间上限——服务器失联时防悬挂。 */
private const val PRELOAD_SEED_TIMEOUT_MS = 30_000L

/**
 * 每服务器的连接状态。
 */
data class ServerConnectionState(
    val config: ServerConfig,
    val conn: ServerConnection,
    val sseJob: Job,
    val isConnected: Boolean = false,
    val onEvent: (ServerConfig, SseEvent) -> Unit = { _, _ -> }
)

/**
 * 管理到多个服务器的 SSE 连接。
 * 处理连接生命周期、自动重连、会话预加载，
 * 以及通过 [EventDispatcher] 进行事件分发。
 *
 * 事件路由（例如用于通知）通过 [onEvent] 回调委托给调用方。
 */
@Singleton
class SseConnectionManager @Inject constructor(
    private val sessionApi: SessionApi,
    private val messageApi: MessageApi,
    private val fileApi: FileApi,
    private val sseClient: SseClient,
    private val sseClientV2: dev.leonardo.ocbeacon.data.api.v2.SseClientV2,
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val sessionStateRepository: SessionStateService,
    // #276 步骤⑤：DSH 分支——双 WS 纯下行 + 对账编排（设计 §1.6/§2.3）
    private val dshConnectionOrchestrator: DshConnectionOrchestrator,
    private val dshFrameSourceFactory: DshFrameSourceFactory,
    private val dshRpcClient: DshRpcClient,
    // #267：REST 传输层失败上拍（origin → serverId → 踢重连）
    private val transportFailureTap: dev.leonardo.ocbeacon.data.api.TransportFailureTap,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            // 纵深防御：确保 SSE 连接生命周期内的未捕获异常
            // （DNS 失败、重连竞争）不会使进程崩溃。
            // startSseConnection 中的 launch 会将网络调用包裹在 try/catch 中，
            // 但此处捕获任何漏网异常——尤其重要，因为 reconnectServer()
            // 会取消正在运行的 job 再启动新的，这可能与进行中的 DNS 解析竞争，
            // 并表现为 UnknownHostException。
            AppLogger.e(TAG, "Unhandled coroutine exception in SSE connection scope", exception)
        }
    )

    /** 所有活跃/待处理的服务器连接，以 serverId 为键。 */
    val connections = ConcurrentHashMap<String, ServerConnectionState>()

    /** 每服务器的超时跟踪器，用于 SSE 读取超时冷却逻辑。 */
    private val timeoutTrackers = ConcurrentHashMap<String, SseReadTimeoutTracker>()

    /**
     * #276：每服务器 DSH 已应用 seq 水位表——连接生命周期内跨重连存活
     * （engine 自重连后 subscribed 基线重推，reconciler 据此算缺口）；
     * stopConnection 清理（下次连接全量 InitialFetch）。
     */
    private val dshSeqTrackers = ConcurrentHashMap<String, DshSessionSeqTracker>()

    init {
        // 2026-08-15（research/06 P0）：接线 durable.seq gap 检测——服务器每事件
        // seq 严格递增（core/event.ts:294）；连接代内 gap = 事件丢失（非断连，
        // 如订阅队列溢出丢弃）→ 记录 gapDetected（L3/观测层消费；后续可接
        // /api/session/:id/event?after= 精确补漏——官方端点已支持）。
        sseClientV2.sequenceTracker = { aggregateId, seq ->
            eventDispatcher.trackSequence(aggregateId, seq)
        }
        // #267（spec §3.3）：REST 传输失败上拍接线——共享 HttpClient 拦截器
        // 上报 origin，映射回 serverId 后踢重连（自检式恢复，见 reportTransportFailure）。
        transportFailureTap.reportFailure = ::onTransportFailureByOrigin
    }

    // ============ #267 连接三态 + 传输失败回灌 ============

    /** 单服务器连接三态快照（[ServerLinkState.derive] 矩阵）。 */
    fun linkState(serverId: String): ServerLinkState =
        ServerLinkState.derive(serverId, _connectedServerIds.value, _connectingServerIds.value)

    /** 单服务器连接三态流（UI 条幅消费）。 */
    fun observeLinkState(serverId: String): Flow<ServerLinkState> =
        deriveLinkStateFlow(_connectedServerIds, _connectingServerIds, serverId)

    /**
     * #267（spec §3.3 检测滞后补刀）：REST 传输层失败回灌——不等 SSE 读循环
     * 超时。**踢一次重连自检**：服务器健康则秒级恢复 Connected（条幅闪现即
     * 事实：连接确实被扰动过）；真死则进入既有退避重连循环（条幅常驻正确）。
     * 不做「仅翻状态」——SSE 若实际存活无人翻回，条幅会错误常驻。
     * RS-017 守卫天然去重并发上报风暴。
     */
    fun reportTransportFailure(serverId: String) {
        if (!connections.containsKey(serverId)) return
        AppLogger.w(TAG, "Transport failure reported for server $serverId, kicking reconnect")
        scope.launch { reconnectServer(serverId) }
    }

    /** origin（scheme://host:port）→ serverId 反查（不匹配则忽略——非受管主机）。 */
    private fun onTransportFailureByOrigin(origin: String) {
        val hit = connections.entries.firstOrNull { it.value.conn.baseUrl == origin } ?: return
        reportTransportFailure(hit.key)
    }

    /**
     * RS-017 修复：每服务器的重连守卫。防止在网络状态抖动
     *（Available → Lost → Available）且 debounce + distinctUntilChanged
     * 无法完全去重时出现重叠的重连尝试。
     */
    private val reconnectingServers = ConcurrentHashMap.newKeySet<String>()

    /** 已实际连接（SSE 流活跃）的服务器 ID 的可观察集合。 */
    val connectedServerIds: StateFlow<Set<String>>
        get() = _connectedServerIds.asStateFlow()
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())

    /** 正在尝试连接的服务器 ID 的可观察集合。 */
    val connectingServerIds: StateFlow<Set<String>>
        get() = _connectingServerIds.asStateFlow()
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 启动到 [server] 的 SSE 连接。
     *
     * @param server  要连接的服务器配置。
     * @param onEvent  每个 SSE 事件触发时调用的回调（用于通知路由等）。
     * @return 表示连接协程的 [Job]。
     */
    fun startConnection(
        server: ServerConfig,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ): Job {
        // #276：from(config) 单点（serverType 沿传——DSH 三分路由 + 传输分支依据）
        val conn = ServerConnection.from(server)
        val previous = connections[server.id]
        val job: Job = if (previous != null && previous.sseJob.isActive) {
            // RS-004 修复：针对重复调用的自我保护。若已存在连接，在启动新连接前
            // 先取消旧连接。调用方（OpenCodeConnectionService.connect）也会检查，
            // 但这可以防止直接调用 startConnection 时（测试、未来重构）的泄漏。
            // #133（D2-L40）：与 reconnectServer 统一为 cancelAndJoin 语义——
            // 裸 cancel 后立即启动会新旧 SSE 流短暂并发（旧协程取消是异步的）；
            // 先完全停止旧协程再启动新连接。
            scope.launch {
                previous.sseJob.cancelAndJoin()
                runSseConnectionLoop(server, conn, onEvent)
            }
        } else {
            startSseConnection(server, conn, onEvent)
        }

        connections[server.id] = ServerConnectionState(
            config = server, conn = conn, sseJob = job, isConnected = false, onEvent = onEvent
        )
        _connectingServerIds.update { it + server.id }

        return job
    }

    /**
     * 停止到指定服务器的 SSE 连接。
     */
    fun stopConnection(serverId: String) {
        val state = connections.remove(serverId) ?: return
        state.sseJob.cancel()
        timeoutTrackers.remove(serverId)
        dshSeqTrackers.remove(serverId) // #276：水位表随连接销毁（重连=全量 InitialFetch）
        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }
        eventDispatcher.clearForServer(serverId)
    }

    /**
     * 停止所有 SSE 连接。
     */
    fun stopAllConnections() {
        // RS-017 修复：在拆除期间阻止重连尝试
        val serverIds = connections.keys.toList()
        for ((_, state) in connections) {
            state.sseJob.cancel()
        }
        connections.clear()
        timeoutTrackers.clear()
        dshSeqTrackers.clear()
        // RS-002 修复：使用 .update{} 而非直接赋值以参与 CAS，
        // 防止已取消但仍运行的 SSE 协程的 updateServerConnected
        // 调用复活已被清除的 server ID。
        _connectedServerIds.update { emptySet() }
        _connectingServerIds.update { emptySet() }
        for (serverId in serverIds) {
            eventDispatcher.clearForServer(serverId)
        }
    }

    /**
     * 重连所有活跃连接。在网络从丢失中恢复时使用。
     * 为每个服务器重启 SSE 连接，使自动重连循环
     * 重置其尝试计数器并立即重连。
     *
     * RS-001 修复：现为 suspend——每个 reconnectServer 使用 cancelAndJoin()
     * 确保旧的 SSE 协程在新连接启动前完全停止。
     */
    suspend fun reconnectAll() {
        for ((serverId, _) in connections.toMap()) {
            reconnectServer(serverId)
        }
    }

    /**
     * 重连单个服务器连接。重启 SSE 连接，
     * 使自动重连循环重置其尝试计数器并立即重连。
     *
     * RS-001 修复：使用 cancelAndJoin() 而非裸 cancel()，确保旧协程
     *（包括 preLoadSessions/recoverMessages）在启动新协程前完全完成。
     * 防止重叠的 eventDispatcher 变更。
     *
     * RS-017 修复：每服务器重连守卫防止网络状态快速抖动时的重叠重连尝试。
     */
    private suspend fun reconnectServer(serverId: String) {
        // RS-017：若此服务器的重连已在进行中则跳过
        if (!reconnectingServers.add(serverId)) {
            AppLogger.w(TAG, "Reconnect already in progress for $serverId, skipping")
            return
        }
        try {
            val state = connections[serverId] ?: return
            // #152：重连路径降级 d——风暴期不再刷 info（审计：每断连 5-8 条灌水）
            AppLogger.d(TAG, "Reconnecting server $serverId after network recovery")
            timeoutTrackers[serverId]?.reset()
            // RS-001：等待旧协程完全停止后再启动新的
            state.sseJob.cancelAndJoin()
            val newJob = startSseConnection(state.config, state.conn, state.onEvent)
            // RS-003 修复：使用 computeIfPresent 进行原子更新——若服务器
            // 在 cancelAndJoin 期间被移除，则不复活它
            var replaced = false
            connections.computeIfPresent(serverId) { _, current ->
                replaced = true
                current.copy(sseJob = newJob)
            }
            // 泄漏修复（路径 1a）：cancelAndJoin 挂起期间 Service 可能已 onDestroy
            // → stopAllConnections() 清空了本 map（scope.cancel 只作用于 Service 的
            // serviceScope，本单例 scope 不受影响）。若 computeIfPresent 未命中，
            // newJob 即为无人引用的孤儿——必须立即取消，否则其闭包持有已销毁
            // Service 的 onEvent（::processEvent）回调，SSE 流永不退出（僵尸协程）。
            if (!replaced) {
                AppLogger.w(TAG, "Server $serverId removed during reconnect, cancelling orphaned SSE job")
                newJob.cancel()
                return
            }
        } finally {
            reconnectingServers.remove(serverId)
        }
    }

    /**
     * 检查指定服务器是否已连接。
     */
    fun isConnected(serverId: String): Boolean {
        // #110（D2-13）：返回真实连接标志——sseJob.isActive 在退避/重连
        // 期间仍为 true（重连循环活跃），但此时无活跃 SSE 流；依赖
        // isConnected 的调用方（如 question 轮询）会误以为在线而继续
        // 打 REST。SSE 首事件到达时置 true，流错误/结束/断开置 false。
        return connections[serverId]?.isConnected == true
    }

    /**
     * 获取给定服务器的 [ServerConnection]（若存在）。
     */
    fun getConnection(serverId: String): ServerConnection? {
        return connections[serverId]?.conn
    }

    // ============ DSH 事件循环（#276 步骤⑤） ============

    /**
     * DSH 双 WS 事件循环：帧源（每服务器独立引擎）→ DshEventMapper →
     * EventDispatcher.processEvent + 通知路由；subscribed 基线 → reconciler 对账。
     * [onConnected] 接收聚合连接状态（双流 Connected 才 Connected，取最差）。
     * 挂起直到取消——engine 自重连，重连后服务端重推 subscribed 基线触发增量对账。
     */
    private suspend fun runDshEventLoop(
        server: ServerConfig,
        conn: ServerConnection,
        onEvent: (ServerConfig, SseEvent) -> Unit,
        onConnected: (Boolean) -> Unit,
    ) {
        val tracker = dshSeqTrackers.getOrPut(server.id) { DshSessionSeqTracker() }
        dshConnectionOrchestrator.run(
            baseUrl = conn.baseUrl,
            frameSource = dshFrameSourceFactory.create(),
            historySource = DshRpcHistorySource(dshRpcClient, conn),
            tracker = tracker,
            dispatch = { event -> eventDispatcher.processEvent(event, server.id) },
            onEvent = { event -> onEvent(server, event) },
            // #275 flagged ②：SessionUpdated 整替换防御——最小 Session 与 handler 缓存合并
            sessionLookup = { sid -> eventDispatcher.sessions.value.firstOrNull { it.id == sid } },
            onConnected = onConnected,
        )
    }

    // ============ 带自动重连的 SSE 连接 ============

    private fun startSseConnection(
        server: ServerConfig,
        conn: ServerConnection,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ): Job = scope.launch {
        runSseConnectionLoop(server, conn, onEvent)
    }

    /** SSE 连接主循环（重连退避）；[startConnection] 的 cancelAndJoin 分支也复用。 */
    private suspend fun CoroutineScope.runSseConnectionLoop(
        server: ServerConfig,
        conn: ServerConnection,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ) {
            var attempt = 0
            val tracker = timeoutTrackers.getOrPut(server.id) { SseReadTimeoutTracker() }
            // 2026-08-15 修复（断连窗口流式内容缺失）：attempt 在连接成功后会被
            // 重置为 0（下方 collect 内），"曾成功连接→断连→重连"场景下 attempt
            // 恒为 1 → 原 `if (attempt > 1)` 永不触发 recoverMessages → 断连窗口
            //（40s 心跳超时+退避期间）的消息事件永久丢失，流式内容缺失大段且不
            // 恢复（直到 text.ended 全量覆盖/重进会话 REST）。
            // 修复：独立 hasConnectedOnce 标志——本次循环内曾成功连接过，
            // 后续每次重连都执行 recoverMessages（REST 快照补漏）。
            var hasConnectedOnce = false

            while (isActive) {
                attempt++

                // 若处于冷却中，等待并跳过重连尝试
                if (tracker.isInCooldown()) {
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "[${server.displayName}] SSE in cooldown, waiting ${COOLDOWN_CHECK_INTERVAL_MS}ms")
                    delay(COOLDOWN_CHECK_INTERVAL_MS)
                    continue
                }

                if (BuildConfig.DEBUG) AppLogger.d(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                // #150 方向②（2026-08-21）：预加载与 SSE 并行——SSE 首事件到达即翻转
                // "已连接"，不再被整段预加载阻塞（V1 实测 preload 串行 ~134ms 占首连
                // ~165ms 的大头，issue #1 遗留"v1 连接慢"的主因）。
                // 并发安全：EventDispatcher.setSessions 为 CAS 合并语义（SessionEventHandler
                // 的两个 update 均原子）；REST 与 SSE 并存的数据一致性由 MergeStrategy
                // 既有优先级设计处理（REST_AUTHORITY 快照 + SSE_PRIORITY 增量）。
                // 串行化护栏：本轮流结束/异常/取消后在 finally cancelAndJoin——防止与
                // 下一轮 preload 重叠写 eventDispatcher（对齐 reconnectServer 的
                // cancelAndJoin 防重叠语义）；取消的是即将被下一轮重跑刷新的中间态，无损失。
                // #276 步骤⑤：DSH 分支——不走 SSE（§1.6-1：events GET 拦 426，只能 WS）。
                // 预加载（session.list 基线）与 SSE 路径共用；断连补漏不走
                // recoverMessages（REST 全量重拉）而走 DSH reconciler（subscribed
                // 基线 → seq 缺口 → session.history 精确回填，§1.6-5）。
                if (conn.serverType == ServerType.Dsh) {
                    val preloadJob = scope.launch { preLoadSessions(server, conn) }
                    try {
                        runDshEventLoop(server, conn, onEvent) { connected ->
                            if (connected) {
                                attempt = 0
                                hasConnectedOnce = true
                            }
                            updateServerConnected(server.id, connected)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 引擎内部自重连（500ms×2ⁿ cap 10s）；此处仅兜底未预期异常
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "DSH event loop ended for " + server.displayName + ": " + e.message, e)
                        updateServerConnected(server.id, false)
                    } finally {
                        preloadJob.cancelAndJoin()
                    }
                    if (!connections.containsKey(server.id)) break
                    delay(calculateBackoff(attempt))
                    continue
                }
                val attemptNow = attempt
                val preloadJob = scope.launch {
                    // 通过 REST API 为所有项目预加载会话
                    preLoadSessions(server, conn)
                    // 重连时（非首次连接），恢复断连期间错过的消息
                    if (attemptNow > 1 || hasConnectedOnce) {
                        recoverMessages(server, conn)
                    }
                }

                try {
                    // V2 连接使用 V2 SSE 客户端，V1 使用原始 V1 客户端
                    val sseFlow = if (conn.apiVersion.isV2) {
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "[${server.displayName}] Using V2 SSE client")
                        sseClientV2.connectToEvents(conn)
                    } else {
                        sseClient.connectToGlobalEvents(conn)
                    }
                    sseFlow
                        // 泄漏修复（路径 1a 兜底）：条目已从 connections 移除
                        //（stopConnection/stopAllConnections）时在下一次发射处
                        // 正常完成流 → 落入下方"stream completed"与循环底部
                        // containsKey break 的既有退出路径。健康连接不受影响：
                        // 条目在场时谓词恒真，正常退出仍由流完成/错误/取消驱动。
                        .takeWhile { connections.containsKey(server.id) }
                        .catch { error ->
                            // #152：流中断是网络常态（非程序错误）——降 w；同一异常由外层 catch 统一终结记录
                            AppLogger.w(TAG, "[${server.displayName}] SSE stream error", error)
                            updateServerConnected(server.id, false)
                            tracker.recordTimeout()
                            throw error
                        }
                        .collect { event ->
                            // RS-005 修复：防范断连后到达的事件。
                            // stopConnection 会从 connections 中移除条目；若此处
                            // 看到为 null，说明服务器已断连，应跳过事件分发
                            // 以避免 EventDispatcher 中出现孤儿事件。
                            val currentState = connections[server.id]
                            if (currentState == null) return@collect
                            if (!currentState.isConnected) {
                                updateServerConnected(server.id, true)
                                attempt = 0
                                hasConnectedOnce = true
                                // 2026-08-16 根治（回复不可见）：重连成功 =
                                // 断连窗口结束的权威信号——窗口内丢失的
                                // MessageUpdated/PartDelta 无法重发，靠 cursor
                                // 增量补漏对账（SSE_PRIORITY，流式中安全）。
                                eventDispatcher.backfillActiveForServer(server.id)
                            }
                            tracker.recordSuccess()
                            // 分发到 EventDispatcher 以更新状态
                            eventDispatcher.processEvent(event, server.id)
                            // 路由给调用方进行通知处理
                            onEvent(server, event)
                        }

                    // Flow 正常完成（服务器关闭了连接）
                    AppLogger.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false)
                    if (tracker.shouldEnterCooldown()) {
                        // 2026-08-18：先读计数再 enterCooldown（其内部清零计数——
                        // 冷却代价付清后重新累积，防「5min 冷却→1 次超时→再冷却」永续）
                        val timeouts = tracker.consecutiveTimeouts
                        tracker.enterCooldown()
                        AppLogger.w(TAG, "[${server.displayName}] Entering SSE cooldown after $timeouts consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: Exception) {
                    // #152：连接失败带 throwable（原缺——审计 7 处之一）；e→d 避免与 :337 双记（同一异常两连发）
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "[${server.displayName}] SSE connection failed: ${e.message}", e)
                    updateServerConnected(server.id, false)
                    if (tracker.shouldEnterCooldown()) {
                        val timeouts = tracker.consecutiveTimeouts
                        tracker.enterCooldown()
                        AppLogger.w(TAG, "[${server.displayName}] Entering SSE cooldown after $timeouts consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
                } finally {
                    // 串行化护栏（见上方 #150 方向②注释）：流结束/异常/取消路径统一
                    // 收束本轮 preload job，再进入退避/重连/退出循环。
                    preloadJob.cancelAndJoin()
                }

                // 若此服务器已从 connections 中移除，则停止循环
                if (!connections.containsKey(server.id)) break

                val delayMs = calculateBackoff(attempt)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms (attempt #$attempt)")
                delay(delayMs)
            }
    }

    private suspend fun preLoadSessions(server: ServerConfig, conn: ServerConnection) {
        try {
            val projects = fileApi.listProjects(conn)
            // 状态先行（#278）：播种（syncFromRest）先于会话正文预载——僵尸 Busy
            // 收敛依赖 running 语义尽早落地；正文预载（百级会话列表）在启动期
            // 占大头，若播种排其后会拉宽「强杀重启→Busy 恢复显示」窗口
            //（2026-09-01 真机实测：预载 8s + 播种 6s）。
            sessionStateRepository.setServerId(server.id)
            // #278（2026-09-01 集成缺口修复）：播种在启动取消风暴下曾被掐死——
            // DSH 分支事件循环 finally cancelAndJoin(preloadJob) 撞上双服务器
            // 自动连/重连竞态 → 播种 session.list 全灭（JobCancellationException
            // 风暴，真机实证）→ 僵尸 Busy 收敛失效。NonCancellable 保证已开始的
            // 播种跑完（cancelAndJoin 会等它落地）；30s 上限防服务器失联时悬挂。
            withContext(NonCancellable) {
                withTimeout(PRELOAD_SEED_TIMEOUT_MS) {
                    sessionStateRepository.syncFromRest(projects)
                }
            }
            if (projects.isEmpty()) {
                // 降级：加载不带 directory 头的会话（仅服务器 CWD）
                val sessions = sessionApi.listSessions(conn)
                eventDispatcher.setSessions(server.id, sessions)
                AppLogger.i(TAG, "[${server.displayName}] Pre-loaded ${sessions.size} sessions (no projects)")
            } else {
                // #150 方向③（2026-08-21）：项目间并发拉取（受控并发 [PRELOAD_PROJECT_CONCURRENCY]）
                // ——多项目用户首连时 N 次串行 /session 往返改并发。setSessions 为 CAS 合并语义
                // 并发调用安全；单项目失败不拖垮其余（保留原逐项目 catch）。
                val totalSessions = java.util.concurrent.atomic.AtomicInteger(0)
                kotlinx.coroutines.coroutineScope {
                    val permits = Semaphore(PRELOAD_PROJECT_CONCURRENCY)
                    for (project in projects) {
                        launch {
                            permits.withPermit {
                                try {
                                    val sessions = sessionApi.listSessions(conn, directory = project.worktree)
                                    eventDispatcher.setSessions(server.id, sessions)
                                    totalSessions.addAndGet(sessions.size)
                                } catch (e: Exception) {
                                    // #278（2026-09-01 集成缺口）：取消必须传播——
                                    // 原实现吞掉 CancellationException 会让取消风暴
                                    // 静默变成"预加载完成"假象。
                                    if (e is CancellationException) throw e
                                    AppLogger.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                                }
                            }
                        }
                    }
                }
                AppLogger.i(TAG, "[${server.displayName}] Pre-loaded ${totalSessions.get()} sessions across ${projects.size} projects")
            }
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(TAG, "[${server.displayName}] Session status seeding timed out after ${PRELOAD_SEED_TIMEOUT_MS}ms (server unreachable?)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
        }
    }

    /**
     * 在 SSE 重连后恢复服务器所有活跃会话的消息。
     * 阶段 1：用 REST 数据替换消息（真相源）。
     * 阶段 2：从服务器同步会话状态——仅将 idle 会话标记为 idle。
     */
    private suspend fun recoverMessages(server: ServerConfig, conn: ServerConnection) {
        val sessionIds = eventDispatcher.serverSessions.value[server.id] ?: return
        if (sessionIds.isEmpty()) return

        // 阶段 1：恢复消息（REST 作为真相源）
        AppLogger.i(TAG, "[${server.displayName}] Recovering messages for ${sessionIds.size} sessions")
        var recoveredCount = 0
        for (sessionId in sessionIds) {
            try {
                val messages = messageApi.listMessages(conn, sessionId).messages
                eventDispatcher.upsertMessages(sessionId, messages, MergeStrategy.REST_AUTHORITY)
                recoveredCount++
            } catch (e: Exception) {
                AppLogger.w(TAG, "[${server.displayName}] Failed to recover messages for session $sessionId: ${e.message}")
            }
        }
        AppLogger.i(TAG, "[${server.displayName}] Recovered messages for $recoveredCount/${sessionIds.size} sessions")

        // 阶段 2：通过统一的 FSM 管线从服务器同步真实会话状态。
        // 包裹在 try-catch 中：当服务器返回非 JSON 错误响应（例如反向
        // 代理的 502 Bad Gateway）时，listProjects / syncFromRest 可能抛出
        // NoTransformationFoundException。阶段 1 的消息恢复此时已完成，
        // 因此阶段 2 的失败不应传播并中断重连循环。
        try {
            val projects = fileApi.listProjects(conn)
            sessionStateRepository.setServerId(server.id)
            sessionStateRepository.syncFromRest(projects)
        } catch (e: Exception) {
            AppLogger.w(TAG, "[${server.displayName}] Failed to sync session statuses during recovery: ${e.message}")
        }
    }

    private fun updateServerConnected(serverId: String, connected: Boolean) {
        // RS-003 修复：使用 computeIfPresent 进行原子读-改-写。
        // 旧模式（先读 state，再 replace）存在 TOCTOU 窗口，reconnectServer
        // 可能在读和写之间用新的 sseJob 替换 state，导致旧 state（带旧 sseJob）
        // 覆盖新 state。
        var found = false
        connections.computeIfPresent(serverId) { _, state ->
            found = true
            state.copy(isConnected = connected)
        }
        if (!found) return
        if (connected) {
            _connectingServerIds.update { it - serverId }
            _connectedServerIds.update { it + serverId }
            AppLogger.i(TAG, "Connected to server $serverId")
        } else {
            _connectedServerIds.update { it - serverId }
            _connectingServerIds.update { it + serverId }
            AppLogger.w(TAG, "Disconnected from server $serverId")
        }
    }

    // C8（2026-08-26）衔接点注记：本函数的指数退避数学与 data/api/RetryPolicy
    // .calculateDelay 同构（base * factor^(attempt-1)，上限截断），但 SSE 重连对
    // 所有失败**无条件重试**（无 isTransient 门槛——连接级恢复语义），而
    // retryWithPolicy 仅对瞬时错误（isTransientException：IOException/超时/
    // ApiError.isTransient）重试。语义部分重合、策略不同，不强改统一；
    // 未来若统一，此处应改为组合 RetryPolicy 配置 + ApiError.isTransient 分类。
    private suspend fun calculateBackoff(attempt: Int): Long {
        val maxDelay = when (settingsRepository.reconnectMode().first()) {
            "aggressive" -> 5_000L
            "conservative" -> 60_000L
            else -> RECONNECT_MAX_DELAY_MS // normal：30s
        }
        val delay = (RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_BACKOFF_FACTOR, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }
}

