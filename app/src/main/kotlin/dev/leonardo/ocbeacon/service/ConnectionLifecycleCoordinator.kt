package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接生命周期协调器（Connection Lifecycle）——一台服务器从纳入连接到断开的
 * 完整编排的单一决策点（2026-08-21 架构评审 #170：从 OpenCodeConnectionService
 * 外移；术语见根目录 CONTEXT.md）。
 *
 * 职责（全部收进本 implementation）：
 * - connect 编排：同服务器幂等 / 同后端（url+username 归一化）去重 / SSE 启动 /
 *   question 轮询启动 / 持久通知刷新——单入口单写法
 * - disconnect 编排：四路清理（轮询取消 + SSE 停止 + 终端工作区释放 + 通知去重
 *   缓存清除）单点化——历史上 disconnect/disconnectAllInternal 写过两遍，漏一路
 *   即泄漏（RS 系列修复的根源）；最后连接断开时通知宿主（FGS 决策）
 * - registry：纳入管理的服务器集合（serverId → config）——生命周期成员资格的
 *   真相源；FGS/wakeLock 的保活/释放由宿主订阅 [activeServerIds] 派生
 *
 * 协作深模块（被调用，内部不搬）：
 * - [SseConnectionManager]：SSE 传输实现（连接循环/退避重连/超时跟踪）
 * - [ServerTerminalRegistry] / [AppNotificationManager]：各自域的深模块，
 *   在正确的生命周期时机被调用（removeWorkspace / clearForServer）
 *
 * 宿主适配（OpenCodeConnectionService，Android FGS adapter）：
 * - question 轮询体留宿主（通知域能力，依赖 Context/NotificationManager），
 *   经 [QuestionPollingFactory] 注入；启停决策归本模块
 * - FGS/wakeLock/stopSelf 由宿主订阅 [activeServerIds] 派生，不在本模块
 */
@Singleton
class ConnectionLifecycleCoordinator @Inject constructor(
    private val connectionManager: SseConnectionManager,
    private val terminalRegistry: ServerTerminalRegistry,
    private val appNotificationManager: AppNotificationManager,
) {

    /** question 轮询工厂：宿主提供轮询体（通知域），返回可取消的轮询协程。 */
    fun interface QuestionPollingFactory {
        fun startPolling(server: ServerConfig): Job
    }

    /** 轮询工厂（Service 构造期注入；@Inject 构造无法带 lambda，见 Service.init）。 */
    @Volatile
    var questionPollingFactory: QuestionPollingFactory? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            // 纵深防御（与 Service.serviceScope 同款）：编排协程内未捕获异常
            // 不允许击穿进程。
            AppLogger.e(TAG, "Unhandled coroutine exception in lifecycle scope", exception)
        }
    )

    /** registry：纳入生命周期管理的服务器（成员资格真相源）。 */
    private val servers = ConcurrentHashMap<String, ServerConfig>()

    /** 每服务器的 question 轮询协程（disconnect 单点取消）。 */
    private val pollingJobs = mutableMapOf<String, Job>()

    /** 活跃（纳入管理）服务器 ID 的可观察集合——FGS/wakeLock 决策数据源。 */
    private val _activeServerIds = MutableStateFlow<Set<String>>(emptySet())
    val activeServerIds: StateFlow<Set<String>> = _activeServerIds.asStateFlow()

    /**
     * 连接到服务器。幂等：同 serverId 已在管理中或同后端（url+username 归一化）
     * 已连接则跳过——同一后端两条 SSE 会投递重复事件，
     * MessagePartDelta 的追加语义会使流式文本翻倍（backlog #34）。
     */
    fun connect(server: ServerConfig) {
        if (servers.containsKey(server.id)) {
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Already connected to server ${server.id}, skipping")
            return
        }
        // 按后端签名去重（host 大小写/默认端口/尾斜杠归一化——#34）：
        // "看起来不同但实际相同"的 URL 不得绕过。
        val existingBackend = servers.values.firstOrNull { state ->
            ServerConfig.sameBackend(state.url, state.username, server.url, server.username)
        }
        if (existingBackend != null) {
            AppLogger.w(
                TAG,
                "Backend ${server.url} already connected via '${existingBackend.displayName}'" +
                    " (id=${existingBackend.id}), skipping duplicate for '${server.displayName}'",
            )
            return
        }

        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Connecting to server: ${server.displayName} (${server.url})")

        servers[server.id] = server
        _activeServerIds.update { it + server.id }

        // SSE 传输（事件回调由宿主路由——通知域）
        connectionManager.startConnection(server, onEvent)

        // question 轮询（通知兜底）：启停归本模块，轮询体在宿主
        questionPollingFactory?.startPolling(server)?.let { pollingJobs[server.id] = it }

        // 持久通知刷新（宿主订阅 activeServerIds 派生 FGS；通知内容更新走回调）
        onLifecycleChanged?.invoke(server.id, true)
    }

    /**
     * 断开单个服务器——四路清理单点（轮询 / SSE / 终端工作区 / 通知去重缓存）。
     * 最后一个服务器断开时经 [onLifecycleChanged] 通知宿主（FGS 停止决策）。
     */
    fun disconnect(serverId: String) {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Disconnecting server $serverId")
        if (servers.remove(serverId) == null) return
        _activeServerIds.update { it - serverId }

        // ① 取消轮询协程（防重连后旧协程重复轮询）
        synchronized(pollingJobs) { pollingJobs.remove(serverId) }?.cancel()
        // ② 停止 SSE 连接（含 eventDispatcher.clearForServer）
        connectionManager.stopConnection(serverId)
        // ③ 释放终端工作区（关闭 tab + 取消协程作用域，防泄漏）
        terminalRegistry.removeWorkspace(serverId)
        // ④ 清除通知去重缓存（防跨会话残留增长）
        appNotificationManager.clearForServer(serverId)

        onLifecycleChanged?.invoke(serverId, false)
    }

    /** 断开所有服务器（单实现——原 disconnect/disconnectAllInternal 双份 teardown 合一）。 */
    fun disconnectAll() {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Disconnecting all servers")
        for (serverId in servers.keys.toList()) {
            disconnect(serverId)
        }
    }

    /** 服务器是否纳入管理（生命周期成员资格）。 */
    fun isManaged(serverId: String): Boolean = servers.containsKey(serverId)

    /**
     * 查找与给定 (url, username) 指向同一后端的已管理服务器配置。
     * 供 UI 在发起连接前预检：非 null 表示该后端已连接，应拒绝新连接并
     * 提示用户（避免 Service 静默拒绝导致 UI 永久显示 "Connecting"）。
     */
    fun findDuplicateBackend(url: String, username: String?): ServerConfig? =
        servers.values.firstOrNull { state ->
            ServerConfig.sameBackend(state.url, state.username, url, username)
        }

    /** 活跃服务器配置视图（FGS 持久通知内容用）。 */
    fun activeServers(): List<ServerConfig> = servers.values.toList()

    /**
     * 生命周期变化回调（宿主注入：FGS/wakeLock/持久通知联动）。
     * 注入式而非 StateFlow 订阅——disconnect 的"最后一个服务器"判断需要
     * 同步确定性（订阅 collect 是异步的，存在窗口）。
     */
    @Volatile
    var onLifecycleChanged: ((serverId: String, active: Boolean) -> Unit)? = null

    /** SSE 事件回调（宿主注入——事件路由是通知/权限域职责）。 */
    @Volatile
    lateinit var onEvent: (ServerConfig, dev.leonardo.ocbeacon.domain.model.SseEvent) -> Unit

    companion object {
        private const val TAG = "ConnLifecycle"
    }
}
