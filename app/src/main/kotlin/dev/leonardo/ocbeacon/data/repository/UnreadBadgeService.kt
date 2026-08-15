package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

private const val TAG = "UnreadBadgeService"

/**
 * 红点时间源——每会话最后完成 assistant 消息的 completed（**服务器时刻**）的单一真相源。
 *
 * 从 EventDispatcher 抽出（原 _lastCompletedReplyTime + 4 处增量维护 + runBlocking 落盘）。
 *
 * 语义不变量（2026-08-07 历史决策）：
 * - maxCompleted **只增不减**：REST 快照滞后（流式中 completed=null）不移除已记录值
 * - 只有 removeSession（SessionDeleted）移除；clearForServer/clearAll 不清红点数据
 * - 判定只用服务器 completed，不用客户端 now
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

    /** SSE MessageUpdated(completed!=null) 增量：max 合并 + 异步落盘。 */
    fun onMessageCompleted(sessionId: String, completed: Long) {
        val old = _lastCompletedReplyTime.value
        _lastCompletedReplyTime.updateAndGet { map ->
            if (completed > (map[sessionId] ?: 0L)) map + (sessionId to completed) else map
        }
        if (_lastCompletedReplyTime.value != old) persistAsync()
    }

    /**
     * 2026-08-15（research/11 P1）：会话错误产生未读——对齐官方 Web
     *（notification.tsx:366-397：session.error 计入未读）。复用 maxCompleted
     * 水位线通道（error 时刻 > 已读时刻 → isUnread=true），无需新存储。
     */
    fun onSessionError(sessionId: String) {
        onMessageCompleted(sessionId, System.currentTimeMillis())
    }

    /** REST 整批替换后重算：只增不减（见类注释）。 */
    fun recomputeMaxCompleted(sessionId: String, messages: List<Message>) {
        val maxTs = messages.filterIsInstance<Message.Assistant>()
            .mapNotNull { it.time.completed }
            .maxOrNull()
        val old = _lastCompletedReplyTime.value
        _lastCompletedReplyTime.updateAndGet { map ->
            if (maxTs == null) map
            else if (maxTs > (map[sessionId] ?: 0L)) map + (sessionId to maxTs)
            else map
        }
        if (_lastCompletedReplyTime.value != old) persistAsync()
    }

    /** SessionDeleted 级联：删除会话的红点条目。 */
    fun removeSession(sessionId: String) {
        val old = _lastCompletedReplyTime.value
        _lastCompletedReplyTime.updateAndGet { it - sessionId }
        if (_lastCompletedReplyTime.value != old) persistAsync()
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
