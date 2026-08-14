package dev.leonardo.ocbeacon.service

import java.util.concurrent.ConcurrentHashMap
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.NetworkMonitor
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.api.SseClient
import dev.leonardo.ocbeacon.data.api.SseReadTimeoutTracker
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseConnManager"
private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L
private const val RECONNECT_BACKOFF_FACTOR = 2.0
private const val COOLDOWN_CHECK_INTERVAL_MS = 30_000L

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
    private val settingsRepository: SettingsDataStore,
    private val networkMonitor: NetworkMonitor,
    private val sessionStateService: SessionStateService,
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
        // RS-004 修复：针对重复调用的自我保护。若已存在连接，
        // 在启动新连接前先取消旧连接。调用方
        //（OpenCodeConnectionService.connect）也会检查，但这可以防止
        // 直接调用 startConnection 时（测试、未来重构）的泄漏。
        connections[server.id]?.sseJob?.cancel()

        val conn = ServerConnection.from(server.url, server.username, server.password, server.apiVersion)
        val job = startSseConnection(server, conn, onEvent)

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
            AppLogger.i(TAG, "Reconnecting server $serverId after network recovery")
            timeoutTrackers[serverId]?.reset()
            // RS-001：等待旧协程完全停止后再启动新的
            state.sseJob.cancelAndJoin()
            val newJob = startSseConnection(state.config, state.conn, state.onEvent)
            // RS-003 修复：使用 computeIfPresent 进行原子更新——若服务器
            // 在 cancelAndJoin 期间被移除，则不复活它
            connections.computeIfPresent(serverId) { _, current ->
                current.copy(sseJob = newJob)
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

    /**
     * 取消内部协程作用域。在服务销毁时调用。
     */
    fun cancelScope() {
        scope.cancel()
    }

    // ============ 带自动重连的 SSE 连接 ============

    private fun startSseConnection(
        server: ServerConfig,
        conn: ServerConnection,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ): Job {
        return scope.launch {
            var attempt = 0
            val tracker = timeoutTrackers.getOrPut(server.id) { SseReadTimeoutTracker() }

            while (isActive) {
                attempt++

                // 若处于冷却中，等待并跳过重连尝试
                if (tracker.isInCooldown()) {
                    AppLogger.i(TAG, "[${server.displayName}] SSE in cooldown, waiting ${COOLDOWN_CHECK_INTERVAL_MS}ms")
                    delay(COOLDOWN_CHECK_INTERVAL_MS)
                    continue
                }

                AppLogger.i(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                // 通过 REST API 为所有项目预加载会话
                preLoadSessions(server, conn)

                // 重连时（非首次连接），恢复断连期间错过的消息
                if (attempt > 1) {
                    recoverMessages(server, conn)
                }

                try {
                    // V2 连接使用 V2 SSE 客户端，V1 使用原始 V1 客户端
                    val sseFlow = if (conn.apiVersion.isV2) {
                        AppLogger.i(TAG, "[${server.displayName}] Using V2 SSE client")
                        sseClientV2.connectToEvents(conn)
                    } else {
                        sseClient.connectToGlobalEvents(conn)
                    }
                    sseFlow
                        .catch { error ->
                            AppLogger.e(TAG, "[${server.displayName}] SSE stream error", error)
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
                        tracker.enterCooldown()
                        AppLogger.w(TAG, "[${server.displayName}] Entering SSE cooldown after ${tracker.consecutiveTimeouts} consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false)
                    if (tracker.shouldEnterCooldown()) {
                        tracker.enterCooldown()
                        AppLogger.w(TAG, "[${server.displayName}] Entering SSE cooldown after ${tracker.consecutiveTimeouts} consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
                }

                // 若此服务器已从 connections 中移除，则停止循环
                if (!connections.containsKey(server.id)) break

                val delayMs = calculateBackoff(attempt)
                AppLogger.i(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms (attempt #$attempt)")
                delay(delayMs)
            }
        }
    }

    private suspend fun preLoadSessions(server: ServerConfig, conn: ServerConnection) {
        try {
            val projects = fileApi.listProjects(conn)
            if (projects.isEmpty()) {
                // 回退：加载不带 directory 头的会话（仅服务器 CWD）
                val sessions = sessionApi.listSessions(conn)
                eventDispatcher.setSessions(server.id, sessions)
                AppLogger.i(TAG, "[${server.displayName}] Pre-loaded ${sessions.size} sessions (no projects)")
            } else {
                var totalSessions = 0
                for (project in projects) {
                    try {
                        val sessions = sessionApi.listSessions(conn, directory = project.worktree)
                        eventDispatcher.setSessions(server.id, sessions)
                        totalSessions += sessions.size
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                    }
                }
                AppLogger.i(TAG, "[${server.displayName}] Pre-loaded $totalSessions sessions across ${projects.size} projects")
            }
            // 通过统一的 FSM 管线从服务器初始化会话状态
            //（跨项目 worktree 聚合 + 缺失=idle + 不完整保护）。
            sessionStateService.setServerId(server.id)
            sessionStateService.syncFromRest(projects)
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
            sessionStateService.setServerId(server.id)
            sessionStateService.syncFromRest(projects)
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

    private suspend fun calculateBackoff(attempt: Int): Long {
        val maxDelay = when (settingsRepository.reconnectMode.first()) {
            "aggressive" -> 5_000L
            "conservative" -> 60_000L
            else -> RECONNECT_MAX_DELAY_MS // normal：30s
        }
        val delay = (RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_BACKOFF_FACTOR, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }
}

