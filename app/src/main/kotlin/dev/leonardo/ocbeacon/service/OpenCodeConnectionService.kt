package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.logging.AppLogger

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.api.NetworkMonitor
import dev.leonardo.ocbeacon.data.api.NetworkState
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.ServerDataStore
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.domain.model.QuestionState
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository as DomainSettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import dev.leonardo.ocbeacon.util.parseLocale

private const val TAG = "OpenCodeService"
private const val WAKELOCK_TAG = "OpenCodeRemote::SSEConnection"
private const val QUESTION_POLL_INTERVAL_MS = 30_000L

/**
 * 用于维护到多个服务器的 OpenCode SSE 连接的前台服务。
 *
 * 本服务：
 * - 同时维护到一个或多个服务器的持久 SSE 连接
 * - 将连接生命周期委托给 [SseConnectionManager]
 * - 将通知管理委托给 [AppNotificationManager]
 * - 显示任务完成和权限请求的通知
 * - 在任一服务器已连接时持有一个 partial WakeLock
 *
 * 连接会保持活跃，直到用户显式断开每个服务器
 *（或使用"Disconnect All"）。
 */
@AndroidEntryPoint
class OpenCodeConnectionService : Service() {

    override fun attachBaseContext(newBase: Context) {
        val languageCode = SettingsDataStore.getStoredLanguage(newBase)
        if (languageCode.isNotEmpty()) {
            val locale = parseLocale(languageCode)
            Locale.setDefault(locale)
            val config = newBase.resources.configuration
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    @Inject
    lateinit var connectionManager: SseConnectionManager

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    lateinit var eventDispatcher: EventDispatcher

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var serverDataStore: ServerDataStore

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var sessionFocusHolder: SessionFocusHolder

    @Inject
    lateinit var terminalRegistry: ServerTerminalRegistry

    @Inject
    lateinit var serverConfigRepository: ServerConfigRepository

    @Inject
    lateinit var managePermissionUseCase: ManagePermissionUseCase

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            // 纵深防御：确保未捕获的异常（例如自动连接到不可达服务器
            // 如 Tailscale 节点时的 UnknownHostException）不会传播到线程的
            // UncaughtExceptionHandler 并导致进程崩溃。
            // 各个 launch 已将网络调用包裹在 try/catch 中；此处捕获任何
            // 漏网异常（重连期间的竞争、R8 内联等）。
            AppLogger.e(TAG, "Unhandled coroutine exception in serviceScope", exception)
        }
    )

    private var connectionStateNotificationJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 每个服务器的 REST 轮询协程。disconnect 时取消，防止重连后重复轮询。 */
    private val pollingJobs = mutableMapOf<String, Job>()

    private lateinit var systemNotificationManager: NotificationManager
    private var foregroundStarted: Boolean = false

    /** 已实际连接（SSE 流活跃）的服务器 ID 的可观察集合。 */
    val connectedServerIds get() = connectionManager.connectedServerIds

    /** 正在尝试连接的服务器 ID 的可观察集合。 */
    val connectingServerIds get() = connectionManager.connectingServerIds

    inner class LocalBinder : Binder() {
        fun getService(): OpenCodeConnectionService = this@OpenCodeConnectionService
    }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Service created")

        systemNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        appNotificationManager.createNotificationChannels(systemNotificationManager, this)

        // 启动网络监控并观察恢复事件
        networkMonitor.startMonitoring()
        networkRecoveryJob = serviceScope.launch {
            networkMonitor.networkState
                .debounce(2_000L)
                .distinctUntilChanged()
                .collect { state ->
                    if (state == NetworkState.Available && connectionManager.connections.isNotEmpty()) {
                        AppLogger.i(TAG, "Network recovered, reconnecting ${connectionManager.connections.size} server(s)")
                        connectionManager.reconnectAll()
                    }
                }
        }

        serviceScope.launch {
            autoConnectConfiguredServers()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Service started, action=${intent?.action}")

        when (intent?.action) {
            ACTION_DISCONNECT_ALL -> {
                AppLogger.i(TAG, "Disconnect All requested via notification")
                disconnectAllVisibleServers()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                val serverId = intent.getStringExtra("server_id")
                if (serverId != null) {
                    AppLogger.i(TAG, "Disconnect requested for server $serverId")
                    disconnect(serverId)
                }
                return START_NOT_STICKY
            }
        }

        ensureForegroundStarted()

        // 从 intent 读取 serverId，凭据从 ServerConfigRepository 异步获取
        intent?.let { i ->
            val serverId = i.getStringExtra("server_id") ?: return START_STICKY
            serviceScope.launch {
                val config = serverConfigRepository.getServer(serverId)
                if (config == null) {
                    AppLogger.w(TAG, "No saved config for server $serverId, skipping connect")
                    return@launch
                }
                connect(config)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Service destroyed")
        // RS-018 修复：取消 networkRecoveryJob 并等待其完成，
        // 再停止连接。若恢复 job 正处于 reconnectAll() 中间，
        // 可能在 stopAllConnections() 运行后启动新的 SSE job。
        // 通过在 serviceScope（最后才取消）中启动清理，
        // 确保顺序：恢复 job 停止 → 连接停止 → 作用域取消。
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        networkMonitor.stopMonitoring()
        connectionManager.stopAllConnections()
        serviceScope.cancel()
    }

    // ============ 公共 API ============

    /**
     * 连接到 OpenCode 服务器。若已连接到此服务器则为空操作。
     * 可同时连接多个服务器——但不能连接到同一个后端
     *（相同的 url + username）。重复的后端连接会
     * 投递两份相同的 SSE 事件，导致流式输出翻倍。
     */
    fun connect(server: ServerConfig) {
        if (connectionManager.connections.containsKey(server.id)) {
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Already connected to server ${server.id}, skipping")
            return
        }

        // 按后端签名去重：相同的 url + username = 同一个 OpenCode serve 实例。
        // 到同一后端的两条 SSE 连接会投递重复的全局事件，
        // 导致 MessagePartDelta（追加语义）使流式文本翻倍。
        val existingBackend = connectionManager.connections.values.firstOrNull { state ->
            state.config.url == server.url && state.config.username == server.username
        }
        if (existingBackend != null) {
            AppLogger.w(TAG, "Backend ${server.url} already connected via '${existingBackend.config.displayName}'" +
                " (id=${existingBackend.config.id}), skipping duplicate for '${server.displayName}'")
            return
        }

        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Connecting to server: ${server.displayName} (${server.url})")

        ensureForegroundStarted()

        // 获取 wake lock（共享——首次 connect 获取，最后断开释放）
        acquireWakeLock()

        // 启动带自动重连的 SSE 连接；事件路由到 processEvent
        connectionManager.startConnection(server, ::processEvent)

        // REST 兜底：SSE 不推 question 事件时定期轮询 GET /question
        startQuestionPolling(server)

        // 观察连接状态：SSE 连接建立后持久通知从"连接中"刷新为"已连接"
        startPersistentNotificationObserver()

        // 更新持久通知
        updatePersistentNotification()
    }

    /**
     * 断开单个服务器。
     */
    fun disconnect(serverId: String) {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Disconnecting server $serverId")

        // 取消该服务器的 REST 轮询协程，防止重连后旧协程继续运行导致重复轮询
        pollingJobs.remove(serverId)?.cancel()
        connectionManager.stopConnection(serverId)
        // 释放该服务器的终端工作区（关闭 tab + 取消协程作用域），防止泄漏
        terminalRegistry.removeWorkspace(serverId)
        // 清除该服务器的通知去重缓存，防止跨会话残留增长
        appNotificationManager.clearForServer(serverId)

        if (connectionManager.connections.isEmpty()) {
            // 最后一个服务器已断开——清理并停止服务
            releaseWakeLock()
            connectionStateNotificationJob?.cancel()
            connectionStateNotificationJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        } else {
            updatePersistentNotification()
        }
    }

    /**
     * 断开所有服务器并停止服务。
     */
    fun disconnectAll() {
        disconnectAllInternal(stopService = true)
    }

    /**
     * 检查指定服务器是否已连接。
     */
    fun isConnected(serverId: String): Boolean {
        return connectionManager.isConnected(serverId)
    }

    // ============ 内部 ============

    private fun disconnectAllVisibleServers() {
        val visibleServerIds = connectionManager.connections.values
            .map { it.config.id }

        if (visibleServerIds.isEmpty()) {
            updatePersistentNotification()
            return
        }

        for (serverId in visibleServerIds) {
            disconnect(serverId)
        }
    }

    private fun disconnectAllInternal(stopService: Boolean) {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Disconnecting all servers")

        val allServerIds = connectionManager.connections.keys.toList()
        // 取消所有服务器的 REST 轮询协程
        pollingJobs.keys.toList().forEach { pollingJobs.remove(it)?.cancel() }
        connectionManager.stopAllConnections()
        // 释放全部服务器终端工作区（防泄漏）
        terminalRegistry.removeAllWorkspaces()
        // 清除全部通知去重缓存
        allServerIds.forEach { appNotificationManager.clearForServer(it) }

        releaseWakeLock()
        connectionStateNotificationJob?.cancel()
        connectionStateNotificationJob = null

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private suspend fun autoConnectConfiguredServers() {
        try {
            val autoConnectServers = serverDataStore.servers.first().filter { it.autoConnect }
            if (autoConnectServers.isEmpty()) return
            AppLogger.i(TAG, "Auto-connecting ${autoConnectServers.size} server(s)")
            autoConnectServers.forEach { connect(it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to auto-connect servers", e)
        }
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        val notification = appNotificationManager.createPersistentNotification(
            this, connectionManager.connections
        )
        startForeground(AppNotificationManager.PERSISTENT_NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    // ============ 问题通知 REST 兜底 ============

    /**
     * 每隔 [QUESTION_POLL_INTERVAL_MS] 轮询 `GET /question`，对比上次已知问题 id
     * 集合，对新增问题触发通知。SSE 推 QuestionAsked 时也走相同通知路径，
     * 由 [AppNotificationManager.shouldNotifyQuestion] 二次去重，故不会重复。
     *
     * 协程在 [connect] 时启动；当服务器断连（[connectionManager.isConnected]
     * 返回 false）或 [disconnect] 取消 [pollingJobs] 时停止。
     *
     * 通知总开关：与 SSE 路径（[maybeNotify]）对齐——仅在
     * `settingsDataStore.notificationsEnabled` 为 true 时才投递通知；
     * `previousKnown` 在开关外更新，避免重新启用时一次性补发积压通知。
     */
    private fun startQuestionPolling(server: ServerConfig) {
        // 重连保护：若该服务器已有轮询协程在运行，先取消旧协程，
        // 避免与即将启动的新协程同时运行（旧协程可能因 isConnected
        // 再次变 true 而永远不会自停）。
        pollingJobs[server.id]?.cancel()
        pollingJobs[server.id] = serviceScope.launch {
            var previousKnown = emptyMap<String, Set<String>>()
            while (isActive) {
                if (!connectionManager.isConnected(server.id)) break
                try {
                    val pending = managePermissionUseCase.listPendingQuestions(
                        server.id, directory = null
                    )
                    val grouped = pending
                        .map { it.toQuestionAsked() }
                        .groupBy { it.sessionId }
                    // 通知总开关与 SSE 路径保持一致（maybeNotify）；
                    // previousKnown 始终更新以避免重新启用后通知洪流。
                    if (settingsDataStore.notificationsEnabled.first()) {
                        appNotificationManager.notifyPendingQuestionsFromREST(
                            this@OpenCodeConnectionService,
                            systemNotificationManager,
                            server,
                            grouped,
                            previousKnown
                        )
                    }
                    previousKnown = grouped.mapValues { (_, qs) -> qs.map { it.id }.toSet() }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "[${server.displayName}] question polling failed: ${e.message}")
                }
                delay(QUESTION_POLL_INTERVAL_MS)
            }
        }
    }

    /** 将 [QuestionState] 转换为 [SseEvent.QuestionAsked] 以复用通知路径。 */
    private fun QuestionState.toQuestionAsked(): SseEvent.QuestionAsked =
        SseEvent.QuestionAsked(
            id = id,
            sessionId = sessionId,
            questions = questions.map { q ->
                SseEvent.QuestionAsked.Question(
                    header = q.header,
                    question = q.question,
                    multiple = q.multiple,
                    custom = q.custom,
                    options = q.options.map { o ->
                        SseEvent.QuestionAsked.Option(label = o.label, description = o.description)
                    }
                )
            },
            tool = tool
        )

    // ============ 事件处理（仅通知路由）============

    /**
     * 在总开关开启时才执行通知动作。
     * 修复：此前 [SseEvent.PermissionAsked] / [SseEvent.QuestionAsked] / [SseEvent.SessionError]
     * 不检查 notificationsEnabled，用户关闭通知后权限/问题/错误仍会弹出。
     */
    private fun maybeNotify(server: ServerConfig, action: suspend () -> Unit) {
        serviceScope.launch {
            if (!settingsDataStore.notificationsEnabled.first()) return@launch
            action()
        }
    }

    private fun processEvent(server: ServerConfig, event: SseEvent) {
        // SSE 双日志治理（backlog #39）：移除每事件通用日志——SseClient 连接
        // 生命周期日志（打开/关闭/心跳/错误/eventCount 汇总）已提供 SSE 可观测性，
        // 关键业务事件（SessionIdle/PermissionAsked/QuestionAsked）在下方各分支
        // 有专门日志。每事件打印 ~50-90 条/s 会造成 logcat I/O + GC 压力。

        // EventDispatcher.processEvent 已由 SseConnectionManager 调用
        // 此处仅路由到通知逻辑
        when (event) {
            is SseEvent.SessionIdle -> {
                // 若用户正在主动查看此会话则抑制
                if (sessionFocusHolder.shouldSuppress(server.id, event.sessionId)) return
                if (appNotificationManager.isChildSession(event.sessionId)) return
                serviceScope.launch {
                    if (!settingsDataStore.notificationsEnabled.first()) return@launch

                    // 给 reducer 片刻时间接收后续的 message/part 事件。
                    delay(250)

                    val assistantMessageId = appNotificationManager.checkNewAssistantMessage(server.id, event.sessionId)
                    if (assistantMessageId == null) {
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(TAG, "[${server.displayName}] Skip response-ready: no assistant text output (${event.sessionId})")
                        }
                        return@launch
                    }

                    AppLogger.i(TAG, "[${server.displayName}] Session idle -> Response ready for ${event.sessionId}")
                    appNotificationManager.showTaskCompleteNotification(
                        this@OpenCodeConnectionService, systemNotificationManager, server, event.sessionId
                    )
                }
            }
            is SseEvent.PermissionAsked -> {
                maybeNotify(server) {
                    val targetSessionId = if (appNotificationManager.isChildSession(event.sessionId)) {
                        // 子 session 权限冒泡到父 session 通知
                        val session = eventDispatcher.sessions.value.find { it.id == event.sessionId }
                        session?.parentId ?: event.sessionId
                    } else {
                        event.sessionId
                    }
                    AppLogger.i(TAG, "[${server.displayName}] Permission asked: ${event.permission} (session=${event.sessionId}, target=$targetSessionId)")
                    if (sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) return@maybeNotify
                    appNotificationManager.showPermissionNotification(
                        this@OpenCodeConnectionService, systemNotificationManager, server, targetSessionId, event.permission
                    )
                }
            }
            is SseEvent.QuestionAsked -> {
                maybeNotify(server) {
                    val targetSessionId = if (appNotificationManager.isChildSession(event.sessionId)) {
                        // 子 session 问题冒泡到父 session 通知
                        val session = eventDispatcher.sessions.value.find { it.id == event.sessionId }
                        session?.parentId ?: event.sessionId
                    } else {
                        event.sessionId
                    }
                    AppLogger.i(TAG, "[${server.displayName}] Question asked for session ${event.sessionId} (target=$targetSessionId)")
                    if (sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) return@maybeNotify
                    val questionText = event.questions.firstOrNull()?.question
                        ?: getString(R.string.notification_has_question, getString(R.string.notification_new_session))
                    appNotificationManager.showQuestionNotification(
                        this@OpenCodeConnectionService, systemNotificationManager, server, targetSessionId, questionText
                    )
                }
            }
            is SseEvent.SessionError -> {
                maybeNotify(server) {
                    val targetSessionId = if (event.sessionId != null && appNotificationManager.isChildSession(event.sessionId)) {
                        // 子 session 错误冒泡到父 session 通知
                        val session = eventDispatcher.sessions.value.find { it.id == event.sessionId }
                        session?.parentId ?: event.sessionId
                    } else {
                        event.sessionId
                    }
                    AppLogger.i(TAG, "[${server.displayName}] Session error: ${event.error} (session=${event.sessionId}, target=$targetSessionId)")
                    if (targetSessionId != null && sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) return@maybeNotify
                    appNotificationManager.showErrorNotification(
                        this@OpenCodeConnectionService, systemNotificationManager, server, targetSessionId, event.error
                    )
                }
            }
            else -> { }
        }
    }

    // ============ 连接状态通知观察者 ============

    /**
     * 订阅连接状态变化并刷新持久通知。
     *
     * 根因修复：此前持久通知只在 connect()（连接尚未建立）和 disconnect() 时刷新，
     * SSE 连接成功后没有刷新路径，导致状态栏永远显示"连接中"。
     * 此观察者在 [SseConnectionManager.connectedServerIds] 或 [SseConnectionManager.connectingServerIds]
     * 变化时（即连接建立/断开）重新渲染持久通知。
     *
     * 不再强制恢复被用户清除的通知——用户希望连接状态通知可清理；
     * 服务被系统重启时 [ensureForegroundStarted] 仍会重建通知。
     */
    private fun startPersistentNotificationObserver() {
        if (connectionStateNotificationJob?.isActive == true) return
        connectionStateNotificationJob = serviceScope.launch {
            combine(
                connectionManager.connectedServerIds,
                connectionManager.connectingServerIds,
            ) { connected, connecting -> connected to connecting }
                .distinctUntilChanged()
                .collect {
                    // 仅在仍有活跃连接时刷新；最后一个断开由 disconnect() 清理
                    if (connectionManager.connections.isNotEmpty()) {
                        updatePersistentNotification()
                    }
                }
        }
    }

    // ============ WakeLock ============

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire()
        }
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ============ Helpers ============

    private fun updatePersistentNotification() {
        appNotificationManager.updatePersistentNotification(
            this, systemNotificationManager, connectionManager.connections
        )
    }

    companion object {
        const val ACTION_OPEN_SESSION = "dev.leonardo.ocbeacon.OPEN_SESSION"
        const val ACTION_DISCONNECT = "dev.leonardo.ocbeacon.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.leonardo.ocbeacon.DISCONNECT_ALL"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SESSION_PATH = "session_path"
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
