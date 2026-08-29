package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.logging.AppLogger

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.NetworkMonitor
import dev.leonardo.ocbeacon.data.api.NetworkState
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.ServerDataStore
import dev.leonardo.ocbeacon.domain.model.QuestionState
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import dev.leonardo.ocbeacon.util.applyAppLanguage

private const val TAG = "OpenCodeService"
private const val WAKELOCK_TAG = "OpenCodeRemote::SSEConnection"

/** #133（D2-L26）：wake lock 单次持有时长（超时兜底——异常路径下自动释放防永久持锁）。 */
private const val WAKELOCK_HOLD_MS = 10 * 60 * 1000L
/** 续期提前量：超时前 30s 重新 acquire（连接存活期间持续持锁）。 */
private const val WAKELOCK_RENEW_EARLY_MS = 30_000L
private const val QUESTION_POLL_INTERVAL_MS = 30_000L

/**
 * #260（2026-08-30）：question 轮询分层节拍——项目目录 fan-out 的轮周期。
 *
 * 实证（logcat /tmp/rel030/verify2.txt，pollstorm.py）：此前每轮都做
 * 「默认 location + 全部项目目录」全扫，项目数随服务器历史增长
 * （10 projects → 9 请求/30s），轮内顺序请求间隔 7-2813ms（中位 16ms）
 * 即用户看到的「15-20ms 密集轮询风暴」。分层后：默认 location（headerless，
 * 服务器默认 location，SSE 新 form 落点最高频处）每轮必查；
 * 项目目录 fan-out 每 [FANOUT_ROUNDS] 轮一次（10 轮 × 30s = 5min），
 * 稳态请求量 9/30s → ~1.9/30s。轮询生命周期铁律不变（2026-08-18：
 * 只跟随用户连接意图，永不因连接状态自停）。
 */
internal object QuestionPollPlanner {
    const val FANOUT_ROUNDS = 10

    /** 本轮是否执行项目目录 fan-out。round 0 恒 true——冷启动纯 REST 路径首轮即扫全部 location（2026-08-08 E2E-C 不变量）。 */
    fun isFanOutRound(round: Int): Boolean = round % FANOUT_ROUNDS == 0
}
/** #111：dataSync FGS 6h 时限后重启延迟（等待旧实例 stopSelf 销毁完成）。 */
private const val FGS_TIMEOUT_RESTART_DELAY_MS = 2_000L

/**
 * 用于维护到多个服务器的 OpenCode SSE 连接的前台服务。
 *
 * 本服务（#170/#111 后为 FGS adapter）：
 * - 同时维护到一个或多个服务器的持久 SSE 连接
 * - 将连接生命周期委托给 [ConnectionLifecycleCoordinator]（#170）
 * - 将 SSE 事件通知路由委托给 [SessionNotificationCoordinator]（C9）
 * - 托管 question 轮询引擎（REST 兜底，依赖 Context）
 * - 在任一服务器已连接时持有一个 partial WakeLock
 *
 * 连接会保持活跃，直到用户显式断开每个服务器
 *（或使用"Disconnect All"）。
 */
@AndroidEntryPoint
class OpenCodeConnectionService : Service() {

    override fun attachBaseContext(newBase: Context) {
        // D2-L20：与 MainActivity 共享语言应用逻辑。
        super.attachBaseContext(newBase.applyAppLanguage())
    }

    @Inject
    lateinit var connectionManager: SseConnectionManager

    @Inject
    lateinit var lifecycleCoordinator: ConnectionLifecycleCoordinator

    // C9（2026-08-26）：SSE 事件 → 通知路由策略外移（when 分发/提示音抑制/
    // 子会话冒泡/streak 门控/收敛等待），本类收缩为 FGS adapter + 轮询引擎宿主
    @Inject
    lateinit var notificationCoordinator: SessionNotificationCoordinator

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    lateinit var feedbackPlayer: InSessionFeedbackPlayer

    @Inject
    lateinit var eventDispatcher: EventDispatcher

    // C5：service 层直注 SettingsDataStore 具体类改经 domain SettingsRepository
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var serverDataStore: ServerDataStore

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var sessionFocusHolder: SessionFocusHolder

    @Inject
    lateinit var serverConfigRepository: ServerConfigRepository

    @Inject
    lateinit var managePermissionUseCase: ManagePermissionUseCase

    @Inject
    lateinit var fileRepository: dev.leonardo.ocbeacon.domain.repository.FileRepository

    // C7（2026-08-26）：堆积消息状态补偿驱动改由 service 层（连接生命周期属主）启动；
    // 原在 EventDispatcher init（幂等 start，时序等价——service 本就注入 dispatcher）。
    @Inject
    lateinit var pendingMessagePipeline: dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline

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
    /** #133（D2-L26）：wake lock 周期续期协程（release 时取消）。 */
    private var wakeLockRenewJob: Job? = null

    /** 每个服务器的 REST 轮询协程。disconnect 时取消，防止重连后重复轮询。 */
    private val pollingJobs = mutableMapOf<String, Job>()

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

        // C7：#176/#177 堆积消息状态补偿驱动（T1 心跳 + T3 Idle 观察）随服务启动
        //（幂等；原 EventDispatcher init 启动点迁此，边沿触发 naturalTurnEndListener 接线不变）
        pendingMessagePipeline.start()

        appNotificationManager.createNotificationChannels()
        // #155：会话内提示音的上下文（Ringtone/Vibrator/渠道快照读取）
        feedbackPlayer.attach(this)

        // ===== 连接生命周期接线（#170：编排收进 Coordinator，本类为 FGS adapter）=====
        // SSE 事件路由策略（C9）收进 SessionNotificationCoordinator——本类只提供
        // 协程上下文（serviceScope + 其异常兜底）；question 轮询体（通知域，
        // 依赖 Context）留宿主；启停决策在 Coordinator。
        lifecycleCoordinator.onEvent = { server, event ->
            serviceScope.launch { notificationCoordinator.processEvent(server, event) }
        }
        lifecycleCoordinator.questionPollingFactory =
            ConnectionLifecycleCoordinator.QuestionPollingFactory { server ->
                startQuestionPolling(server)
            }
        // FGS/wakeLock/持久通知联动：从生命周期状态派生（最后一个断开 → 收尾）。
        lifecycleCoordinator.onLifecycleChanged = { _, _ ->
            if (lifecycleCoordinator.activeServerIds.value.isEmpty()) {
                onLastServerDisconnected()
            } else {
                ensureForegroundStarted()
                acquireWakeLock()
                updatePersistentNotification()
            }
        }

        // 启动网络监控并观察恢复事件
        networkMonitor.startMonitoring()
        networkRecoveryJob = serviceScope.launch {
            networkMonitor.networkState
                .debounce(2_000L)
                .distinctUntilChanged()
                .collect { state ->
                    if (state == NetworkState.Available && lifecycleCoordinator.activeServerIds.value.isNotEmpty()) {
                        AppLogger.i(TAG, "Network recovered, reconnecting ${lifecycleCoordinator.activeServerIds.value.size} server(s)")
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
                disconnectAll()  // #170：registry 即全部可见集合
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

    /**
     * Android 15+（API 35）dataSync 前台服务 6 小时时限回调（#111）。
     *
     * 系统到达时限调用本方法；若不处理，服务将被系统强制停止 →
     * 手动连接静默丢失（用户无感知断连）。策略：
     * 1. 记录可观测日志（时限触发 + 当前活跃服务器）
     * 2. super 默认 stopSelf(startId)——满足系统"超时后必须停止"约束
     * 3. 有活跃连接时延迟 2s 重启服务（新 6h 周期）——已配置自动连接的
     *    服务器由 onCreate → autoConnectConfiguredServers 自动恢复
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        val activeServers = lifecycleCoordinator.activeServerIds.value.toList()
        AppLogger.w(TAG, "FGS dataSync timeout (6h) startId=$startId fgsType=$fgsType, activeServers=$activeServers")
        if (activeServers.isEmpty()) {
            AppLogger.i(TAG, "No active connections, skipping service restart after FGS timeout")
            return
        }
        // 延迟重启：等待当前实例 stopSelf 销毁完成后启动新实例，获得新的 6h 时限
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                AppLogger.i(TAG, "Restarting service after FGS timeout (new 6h window)")
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(this, OpenCodeConnectionService::class.java)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "FGS timeout restart failed", e)
            }
        }, FGS_TIMEOUT_RESTART_DELAY_MS)
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
        // #170：经协调器统一断开（registry + 四路清理单点）——
        // 与 Service 销毁语义一致（单例 registry 不残留已销毁会话）。
        lifecycleCoordinator.disconnectAll()
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
        // 泄漏修复（路径 1b）：onStartCommand/autoConnect 经 serviceScope.launch
        // 挂起（DB 读取）后到达此处；若期间 onDestroy 已执行（serviceScope.cancel
        // + 断开全部），继续执行会用已销毁 Service 的 ::processEvent
        // 重填单例 map，连接永久滞留。作用域已取消则放弃本次连接。
        if (!serviceScope.isActive) {
            AppLogger.w(TAG, "Service scope inactive (destroyed), skipping connect to ${server.displayName}")
            return
        }
        // #170：七步编排（幂等/同后端去重/FGS/wakeLock/SSE/轮询/通知）
        // 收进 ConnectionLifecycleCoordinator；FGS/wakeLock 经
        // onLifecycleChanged 回调派生。通知观察全局一次（onCreate/此处幂等）。
        startPersistentNotificationObserver()
        lifecycleCoordinator.connect(server)
    }

    /**
     * 断开单个服务器。
     */
    fun disconnect(serverId: String) {
        // #170：四路清理（轮询/SSE/终端工作区/通知去重缓存）单点在
        // Coordinator；"最后一个服务器断开"由 onLifecycleChanged 派生。
        lifecycleCoordinator.disconnect(serverId)
    }

    /** 最后一个服务器断开（Coordinator 回调派生）——清理并停止 FGS。 */
    private fun onLastServerDisconnected() {
        releaseWakeLock()
        connectionStateNotificationJob?.cancel()
        connectionStateNotificationJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    /**
     * 断开所有服务器并停止服务。
     */
    fun disconnectAll() {
        // #170：双份 teardown 合一——Coordinator.disconnectAll 单实现，
        // 最后一个服务器断开同样经回调收尾（stopService 语义）。
        lifecycleCoordinator.disconnectAll()
    }

    /**
     * 检查指定服务器是否已连接。
     */
    fun isConnected(serverId: String): Boolean {
        return connectionManager.isConnected(serverId)
    }

    /**
     * backlog #34：查找与给定 (url, username) 指向同一后端的已活跃连接对应的服务器配置。
     *
     * 供 UI 在发起连接前预检：若返回非 null，说明该后端已通过另一个服务器条目连接，
     * 应拒绝新连接并提示用户，避免 Service 静默拒绝导致 UI 永久显示 "Connecting"。
     */
    fun findDuplicateBackend(url: String, username: String?): ServerConfig? =
        lifecycleCoordinator.findDuplicateBackend(url, username)

    // ============ 内部 ============


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
        // 通知内容读传输真实状态（连接中/已连接显示）——数据源是 Manager；
        // FGS 启停决策才用 Coordinator 的生命周期 registry（#170 边界）。
        val notification = appNotificationManager.createPersistentNotification(
            connectionManager.connections
        )
        startForeground(AppNotificationManager.PERSISTENT_NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    // ============ 问题通知 REST 兜底 ============

    /**
     * 每隔 [QUESTION_POLL_INTERVAL_MS] 轮询 `GET /api/form/request`（#260 分层节拍：
     * 默认 location 每轮；项目目录 fan-out 按 [QuestionPollPlanner] 降频），
     * 对比上次已知问题 id
     * 集合，对新增问题触发通知。SSE 推 QuestionAsked 时也走相同通知路径，
     * 由 [AppNotificationManager.shouldNotifyQuestion] 二次去重，故不会重复。
     *
     * 协程在 [connect] 时启动；当服务器断连（[connectionManager.isConnected]
     * 返回 false）或 [disconnect] 取消 [pollingJobs] 时停止。
     *
     * 通知总开关：与 SSE 路径（[maybeNotify]）对齐——仅在
     * `settingsRepository.notificationsEnabled()` 为 true 时才投递通知；
     * `previousKnown` 在开关外更新，避免重新启用时一次性补发积压通知。
     */
    private fun startQuestionPolling(server: ServerConfig): Job {
        // 重连保护：若该服务器已有轮询协程在运行，先取消旧协程，
        // 避免与即将启动的新协程同时运行（旧协程可能因 isConnected
        // 再次变 true 而永远不会自停）。
        pollingJobs[server.id]?.cancel()
        val job = serviceScope.launch {
            var previousKnown = emptyMap<String, Set<String>>()
            var round = 0
            while (isActive) {
                // 2026-08-18 修复（question 轮询永久死亡，两轮迭代）：
                // 原版 `if (!isConnected) break` 在 SSE 握手窗口/40s 心跳超时
                // 重连窗口杀死轮询且永不复活（实测：启动 12 分钟 0 次
                // form/request，E2E-C 会话列表无 Pending 标记）。第一版改为
                // 等待重连仍不够——SSE 冷却/长断连时兜底随之失效，违背设计初衷
                //（兜底就是为 SSE 不可达场景而生）。轮询生命周期只跟随用户
                // 连接意图：disconnect() 显式取消 pollingJobs 即停，这里不再
                // 检查 isConnected。#260 分层：默认 location 每轮必查，
                // 项目目录 fan-out 按 QuestionPollPlanner 节拍降频。
                try {
                    // 2026-08-18 修复（location 覆盖缺口）：V2 form/request 按
                    // x-opencode-directory 头分 location 返回（单 location 严格
                    // 过滤，OpenAPI spec "Retrieve pending forms for a location"，
                    // 2026-08-30 真机矩阵实测）。headerless = 服务器默认 location。
                    // 项目目录 fan-out 不能省（2026-08-08 实测：Favorite Season
                    // form 在 oc-beacon location，headerless 查不到）——
                    // 但可按 QuestionPollPlanner 节拍分层降频（#260）。
                    val pending = pollPendingQuestionsAllLocations(
                        server,
                        fanOut = QuestionPollPlanner.isFanOutRound(round)
                    )
                    val grouped = pending
                        .map { it.toQuestionAsked() }
                        .groupBy { it.sessionId }
                    // 2026-08-14 修复：REST 数据合并进 QuestionEventHandler——
                    // V1 SSE 的 question.asked 可能不含 tool.messageID（导致提问卡片
                    // 无法嵌入触发消息气泡，降级为独立卡片）；REST 响应含 tool，
                    // 轮询合并补全，使 pendingQuestions 可关联到消息。
                    grouped.forEach { (sid, qs) ->
                        runCatching { eventDispatcher.mergeQuestionsFromREST(sid, qs) }
                            .onFailure { AppLogger.w(TAG, "[${server.displayName}] merge questions failed: ${it.message}") }
                    }
                    // 通知总开关与 SSE 路径保持一致（maybeNotify）；
                    // previousKnown 始终更新以避免重新启用后通知洪流。
                    if (settingsRepository.notificationsEnabled().first()) {
                        appNotificationManager.notifyPendingQuestionsFromREST(
                            server,
                            grouped,
                            previousKnown
                        )
                    }
                    previousKnown = grouped.mapValues { (_, qs) -> qs.map { it.id }.toSet() }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "[${server.displayName}] question polling failed: ${e.message}")
                }
                round++
                delay(QUESTION_POLL_INTERVAL_MS)
            }
        }
        pollingJobs[server.id] = job
        return job
    }

    /**
     * 默认 location（directory=null，headerless = 服务器默认 location）每轮必查；
     * [fanOut]=true 时追加全部项目目录（[QuestionPollPlanner] 节拍）。
     * 按 form id 去重（同一 form 理论上只属一个 location，防御性去重）。
     */
    private suspend fun pollPendingQuestionsAllLocations(
        server: ServerConfig,
        fanOut: Boolean
    ): List<dev.leonardo.ocbeacon.domain.model.QuestionState> {
        val result = mutableListOf<dev.leonardo.ocbeacon.domain.model.QuestionState>()
        result += managePermissionUseCase.listPendingQuestions(server.id, directory = null)
        if (fanOut) {
            for (dir in fetchPolledProjectDirectories(server)) {
                runCatching {
                    result += managePermissionUseCase.listPendingQuestions(server.id, directory = dir)
                }.onFailure {
                    AppLogger.w(TAG, "[${server.displayName}] question polling (dir=$dir) failed: ${it.message}")
                }
            }
        }
        return result.distinctBy { it.id }
    }

    /**
     * 项目目录列表（仅在 fan-out 轮调用，~5min 一次，无需再缓存）。
     * beta-17595 实测 /api/project 只返回 canonical（无 worktree/directory）；
     * 老版本返回 directory。两者都取，canonical 兜底。
     */
    private suspend fun fetchPolledProjectDirectories(server: ServerConfig): List<String> {
        return runCatching {
            fileRepository.listProjects(server.id).getOrDefault(emptyList())
                .mapNotNull { p ->
                    p.directory?.takeIf { it.isNotBlank() }
                        ?: p.canonical?.takeIf { it.isNotBlank() }
                }
                .filter { it != "/" } // global project 的 canonical=/ 对应 directory=null 已查
                .distinct()
        }.getOrDefault(emptyList())
    }

    // QuestionState → SseEvent.QuestionAsked 转换器（C9）随通知路由策略迁至
    // SessionNotificationCoordinator.kt（top-level internal，本包可见）——
    // 轮询路径继续经 toQuestionAsked() 复用 SSE 通知路由。

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

    /**
     * 获取共享 wake lock（首次 connect 获取，最后断开释放）。
     * #133（D2-L26）：原 acquire() 无超时——若释放路径异常（崩溃/被杀前未走
     * disconnect）则永久持锁耗电；现 acquire(timeout) 兜底 + 周期续期协程
     * （连接存活期间超时前重新持有，释放路径取消续期）。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(WAKELOCK_HOLD_MS)
        }
        // 周期续期：超时前重新 acquire（仅当连接仍活跃——release 后 renew job 取消）
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = serviceScope.launch {
            while (isActive) {
                delay(WAKELOCK_HOLD_MS - WAKELOCK_RENEW_EARLY_MS)
                val lock = wakeLock ?: break
                if (lock.isHeld) {
                    lock.release()
                    lock.acquire(WAKELOCK_HOLD_MS)
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "WakeLock renewed")
                }
            }
        }
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
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
            connectionManager.connections
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
