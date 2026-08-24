package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

private const val TAG = "UnreadBadgeService"

/**
 * 红点域事件——时钟域编码进类型（#171）：
 * - [ServerMessageCompleted]/[RestSnapshot] 携带**服务器时刻**（唯一常规来源）
 * - [SessionErrorOccurred] 携带**客户端时刻**——唯一的故意例外（research/11 P1：
 *   会话错误产生未读，复用 maxCompleted 水位线通道，对齐官方 Web notification.tsx）
 *
 * 调用方构造事件时必须选择类型：什么域的时间装进什么事件，传错编译不过。
 * 时间戳域从注释升级为签名上的显式契约。
 */
sealed class UnreadEvent {
    abstract val sessionId: String

    /** SSE MessageUpdated(completed != null) 增量——服务器 completed。 */
    data class ServerMessageCompleted(
        override val sessionId: String,
        val serverTs: Long,
    ) : UnreadEvent()

    /** REST 载荷快照——从 REST 响应原文提取的 maxCompleted（服务器域）。 */
    data class RestSnapshot(
        override val sessionId: String,
        val serverTs: Long,
    ) : UnreadEvent()

    /** 会话错误——客户端 now（故意例外，见类注释）。 */
    data class SessionErrorOccurred(
        override val sessionId: String,
        val clientNow: Long,
    ) : UnreadEvent()
}

/**
 * 红点时间源——每会话最后完成 assistant 消息的 completed（**服务器时刻**）的单一真相源。
 *
 * 从 EventDispatcher 抽出（原 _lastCompletedReplyTime + 4 处增量维护 + runBlocking 落盘）。
 *
 * 语义不变量（2026-08-07 历史决策，#171 起由事件类型承载）：
 * - maxCompleted **只增不减**：REST 快照滞后（流式中 completed=null）不移除已记录值
 * - 只有 removeSession（SessionDeleted）移除；clearForServer/clearAll 不清红点数据
 * - 判定只用服务器 completed，不用客户端 now（例外：SessionErrorOccurred 显式携带客户端时刻）
 *
 * 落盘策略：异步（[persistAsync]）+ seed 恢复兜底。相比旧 runBlocking 同步写，
 * kill 进程窗口内未落盘的值由下次启动 [seedFromStorage] 的 max 合并恢复（有界丢失：毫秒级）。
 */
@Singleton
class UnreadBadgeService @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _lastCompletedReplyTime = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastCompletedReplyTime: StateFlow<Map<String, Long>> = _lastCompletedReplyTime

    // L-3：合并写通道——单消费者 + 写前取最新快照，不取消进行中的写
    //（原 persistAsync 每次 cancel 上一个 DataStore 写；突发写请求经 CONFLATED 合并为最新一次）。
    private val persistChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    /** 单消费者协程（声明先于 init：属性初始化按声明顺序执行，后声明会被 init 前的初始值覆盖）。 */
    private var persistChannelConsumer: Job? = null

    init {
        persistChannelConsumer = scope.launch {
            for (msg in persistChannel) {
                val snapshot = _lastCompletedReplyTime.value
                runCatchingCancellable { settingsDataStore.saveLastCompletedReplyTimes(snapshot) }
                    .onFailure { e -> AppLogger.e(TAG, "persist failed (seed will recover on next start)", e) }
            }
        }
    }

    /** 事件入口（#171）：时钟域由事件类型承载，本模块不接收裸时间戳。 */
    fun onEvent(event: UnreadEvent) {
        val (sessionId, ts) = when (event) {
            is UnreadEvent.ServerMessageCompleted -> event.sessionId to event.serverTs
            is UnreadEvent.RestSnapshot -> event.sessionId to event.serverTs
            is UnreadEvent.SessionErrorOccurred -> event.sessionId to event.clientNow
        }
        val old = _lastCompletedReplyTime.value
        _lastCompletedReplyTime.updateAndGet { map ->
            if (ts > (map[sessionId] ?: 0L)) map + (sessionId to ts) else map
        }
        if (_lastCompletedReplyTime.value != old) persistAsync()
    }

    /** SSE MessageUpdated(completed!=null) 增量：max 合并 + 异步落盘。 */
    @Deprecated("Use onEvent(UnreadEvent.ServerMessageCompleted)",
        ReplaceWith("onEvent(UnreadEvent.ServerMessageCompleted(sessionId, completed))"))
    fun onMessageCompleted(sessionId: String, completed: Long) {
        onEvent(UnreadEvent.ServerMessageCompleted(sessionId, completed))
    }

    /**
     * 2026-08-15（research/11 P1）：会话错误产生未读——对齐官方 Web
     *（notification.tsx:366-397：session.error 计入未读）。复用 maxCompleted
     * 水位线通道（error 时刻 > 已读时刻 → isUnread=true），无需新存储。
     */
    @Deprecated("Use onEvent(UnreadEvent.SessionErrorOccurred)",
        ReplaceWith("onEvent(UnreadEvent.SessionErrorOccurred(sessionId, System.currentTimeMillis()))"))
    fun onSessionError(sessionId: String) {
        onEvent(UnreadEvent.SessionErrorOccurred(sessionId, System.currentTimeMillis()))
    }

    /** REST 整批替换后重算：只增不减（见类注释）。 */
    @Deprecated("Use onEvent(UnreadEvent.RestSnapshot)",
        ReplaceWith("onEvent(UnreadEvent.RestSnapshot(sessionId, maxTs))"))
    fun recomputeMaxCompleted(sessionId: String, messages: List<Message>) {
        val maxTs = messages.filterIsInstance<Message.Assistant>()
            .mapNotNull { it.time.completed }
            .maxOrNull()
        if (maxTs != null) onEvent(UnreadEvent.RestSnapshot(sessionId, maxTs))
    }

    /** SessionDeleted 级联：删除会话的红点条目（水位线 + 内存已读信号）。 */
    fun removeSession(sessionId: String) {
        val old = _lastCompletedReplyTime.value
        _lastCompletedReplyTime.updateAndGet { it - sessionId }
        _justRead.update { it - sessionId }
        if (_lastCompletedReplyTime.value != old) persistAsync()
    }

    // ============ 已读侧（#171 阶段 2：吸收 SessionReadSignal + 持久化已读） ============

    /**
     * 内存即时已读（跨屏幕）——DataStore 异步写窗口期的先行信号，消除
     * "退出瞬间持久化未完成 → 列表读到旧已读时间 → 红点闪一下再消失"（2026-08-07）。
     */
    private val _justRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    val justRead: StateFlow<Map<String, Long>> = _justRead

    /** 合并读：持久 readTimes ∥ 内存 justRead，每会话取 max（吸收原 mergeReadTimes）。 */
    fun mergedReadTimes(serverId: String): Flow<Map<String, Long>> =
        combine(
            settingsDataStore.sessionReadTimes(serverId).catch { emit(emptyMap()) },
            justRead,
        ) { persisted, inMemory ->
            (persisted.keys + inMemory.keys).associateWith {
                maxOf(persisted[it] ?: 0L, inMemory[it] ?: 0L)
            }
        }.distinctUntilChanged()

    /** 该服务器的一键已读位置（透传持久层）。 */
    fun allReadAt(serverId: String): Flow<Long> = settingsDataStore.allReadAt(serverId)

    /**
     * 标记会话已读（退出会话/列表消费路径）：已读位置 = **模块自身水位线**（服务器域），
     * 不再扫描消息缓存（#171——ChatViewModel 原 markSessionRead 的泄漏入口封死）。
     * 无水位线记录（秒退/消息未加载）则跳过：用户未消费内容，之后红点合理。
     * 内存信号先行；持久化走模块 ApplicationScope——比调用方 ViewModel 活得久，
     * 导航返回销毁 VM 不丢写入（原 NonCancellable+viewModelScope 方案的语义强化）。
     */
    fun markSessionRead(serverId: String, sessionId: String) {
        val ts = _lastCompletedReplyTime.value[sessionId] ?: return
        _justRead.update { it + (sessionId to ts) }
        scope.launch {
            runCatchingCancellable { settingsDataStore.markSessionRead(serverId, sessionId, ts) }
                .onFailure { e -> AppLogger.e(TAG, "markSessionRead persist failed", e) }
        }
    }

    /**
     * 一键已读：作用域化到 [sessionIds]（本服务器会话集）后取水位线 max（#184 修复：
     * 原全局 max 会把别服务器（快钟）条目写进本服务器 allReadAt 造成未来窗口漏杀，
     * 且 _justRead 广播溢出到别服务器会话造成进程期错杀；SessionError 客户端 now
     * 第三时钟域顺带被限制在本集内）。空集或集内无水位线记录时 no-op。
     */
    fun markAllSessionsRead(serverId: String, sessionIds: Set<String>) {
        val scoped = _lastCompletedReplyTime.value.filterKeys { it in sessionIds }
        val globalMax = scoped.values.maxOrNull() ?: return
        scoped.keys.forEach { sid ->
            _justRead.update { it + (sid to globalMax) }
        }
        scope.launch {
            runCatchingCancellable { settingsDataStore.markAllSessionsRead(serverId, globalMax) }
                .onFailure { e -> AppLogger.e(TAG, "markAllSessionsRead persist failed", e) }
        }
    }

    companion object {
        /**
         * 未读判定（#171 从 SessionListStateBuilder 迁入——判定与时间源同域所有权）：
         * 会话 Idle（turn 完全结束）且有水位线记录，且晚于 max(已读位置, 一键已读位置)。
         * 全部服务器时刻，纯函数。
         */
        fun isUnread(
            sessionId: String,
            maxCompleted: Map<String, Long>,
            readTimes: Map<String, Long>,
            allReadAt: Long = 0L,
            status: SessionStatus,
        ): Boolean {
            if (status != SessionStatus.Idle) return false
            val last = maxCompleted[sessionId] ?: return false
            return last > maxOf(readTimes[sessionId] ?: 0L, allReadAt)
        }
    }

    /**
     * 启动种子化：DataStore 读 seed（服务器域值）→ max 合并进内存 → 落盘合并结果。
     * 幂等；迁移（runUnreadStateV2Migration）必须先于本方法执行（EventDispatcher init 顺序保证）。
     */
    suspend fun seedFromStorage() {
        val seed = runCatchingCancellable { settingsDataStore.lastCompletedReplyTimes().first() }
            .getOrDefault(emptyMap())
        AppLogger.d(TAG, "[seed] loaded ${seed.size} entries: ${seed.entries.take(3)}")
        _lastCompletedReplyTime.update { current ->
            val merged = current.toMutableMap()
            for ((sid, ts) in seed) {
                if (ts > (merged[sid] ?: 0L)) merged[sid] = ts
            }
            merged
        }
        persistNow()
    }

    // ---- 落盘 ----------------------------------------------------

    /**
     * 异步落盘（合并批量写）：状态变化时调度一次写；量小频低，DataStore 原子写。
     * 相比旧 runBlocking 同步写，本方案用"写前 snapshot + 幂等 seed 恢复"兜底：
     * kill 进程窗口内未落盘的值由下次启动 seedFromStorage 的 max 合并恢复（有界丢失：仅毫秒级增量）。
     *
     * 暴露为 public：EventDispatcher.messageForceCompleter（markSessionIdle 兜底）需在 idle 到达时
     * 把当前内存红点值落盘（无新值产生，仅触发已有值的持久化）。
     */
    fun persistAsync(): Boolean {
        // L-3：入队（CONFLATED 天然合并突发写）——不再取消进行中的写。
        persistChannel.trySend(Unit)
        return true
    }

    private suspend fun persistNow() {
        runCatchingCancellable { settingsDataStore.saveLastCompletedReplyTimes(_lastCompletedReplyTime.value) }
            .onFailure { e -> AppLogger.e(TAG, "seed persist failed", e) }
    }
}
