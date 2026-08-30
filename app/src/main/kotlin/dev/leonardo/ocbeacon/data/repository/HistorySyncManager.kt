package dev.leonardo.ocbeacon.data.repository

import android.util.Log
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.data.local.SessionSyncDao
import dev.leonardo.ocbeacon.data.local.SessionSyncEntity
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #271：会话历史全量同步（drain）唯一所有者。
 *
 * 职责：把服务器会话历史分页拉取到本地全量缓存（热表 + 溢出 zstd 冷存），
 * 供 BM25 内容检索（#272）与离线浏览使用。同步状态仅长按菜单展示
 *（用户四轮裁决：行内/会话内零展示）。
 *
 * 设计（spec 2026-08-30-full-retention-bm25-search-design.md §2.7）：
 * - 状态机：none/syncing/synced/failed per 会话，持久化 session_sync_state（v5）
 * - 幂等：中断重跑 = 从头分页重拉（upsert 幂等），不做游标续传
 * - 并发：单会话单 flight；全局并发 1（顺序队列）；页间 [PAGE_INTERVAL_MS] 限速
 * - 让位：拉取前若会话 busy/retry（用户发消息）→ 延迟让位流式
 * - 解耦：只落库；FTS 增量（#272）挂在落库事务，drain 自动入索引
 */
@Singleton
class HistorySyncManager @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val messageStore: MessageStore,
    private val sessionSyncDao: SessionSyncDao,
    private val sessionStateRepository: dev.leonardo.ocbeacon.domain.repository.SessionStateRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val queueMutex = Mutex()

    private val _syncStates = MutableStateFlow<Map<String, SessionSyncEntity>>(emptyMap())
    val syncStates: StateFlow<Map<String, SessionSyncEntity>> = _syncStates.asStateFlow()

    init {
        scope.launch {
            sessionSyncDao.observeAll().collect { rows ->
                _syncStates.value = rows.associateBy { it.sessionId }
            }
        }
    }

    /**
     * 请求全量同步。已 synced → no-op；该会话已有 flight → no-op。
     * 首开自动触发（ChatViewModel）与长按手动共用此入口。
     */
    fun requestSync(serverId: String, sessionId: String) {
        if (jobs[sessionId]?.isActive == true) return
        val current = _syncStates.value[sessionId]
        if (current?.state == SessionSyncEntity.STATE_SYNCED) return
        jobs[sessionId] = scope.launch { runDrain(serverId, sessionId) }
    }

    /** 取消进行中的 drain（长按菜单「取消」）。状态回未同步（可再次触发）。 */
    fun cancel(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
    }

    /**
     * #271：会话删除级联——取消进行中 drain + 异步清理本地痕迹
     * （热表/冷存/FTS 经 messageStore.clearSession；同步状态行经 sessionSyncDao）。
     * EventDispatcher SessionDeleted 分支调用。
     */
    fun onSessionDeleted(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
        scope.launch {
            runCatching { messageStore.clearSession(sessionId) }
                .onFailure { e -> Log.w(TAG, "[drain] clearSession failed: " + e.message) }
            runCatching { sessionSyncDao.clearSession(sessionId) }
        }
    }

    private suspend fun runDrain(serverId: String, sessionId: String) {
        setSyncState(sessionId, SessionSyncEntity.STATE_SYNCING, null)
        try {
            var cursor: String? = null
            var pages = 0
            var total = 0
            while (pages < MAX_PAGES) {
                yieldIfBusy(sessionId)
                val page = sessionRepository.listMessages(serverId, sessionId, PAGE_SIZE, cursor)
                    .getOrThrow()
                if (page.messages.isNotEmpty()) {
                    messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = true)
                    total += page.messages.size
                }
                cursor = page.nextCursor
                pages++
                if (cursor == null || page.messages.isEmpty()) break
                delay(PAGE_INTERVAL_MS)
            }
            setSyncState(sessionId, SessionSyncEntity.STATE_SYNCED, null, lastSyncAt = System.currentTimeMillis())
            Log.i(TAG, "[drain] session=$sessionId: synced $total msgs in $pages pages")
        } catch (e: CancellationException) {
            setSyncState(sessionId, SessionSyncEntity.STATE_NONE, null)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[drain] session=$sessionId: failed", e)
            setSyncState(sessionId, SessionSyncEntity.STATE_FAILED, e.message)
        } finally {
            jobs.remove(sessionId)
        }
    }

    /** 让位：会话 busy/retry（agent 流式中）时等待，避免 drain 分页与流式抢带宽/写路径。 */
    private suspend fun yieldIfBusy(sessionId: String) {
        var waited = 0L
        while (sessionStateRepository.statusFlow.value[sessionId] is SessionStatus.Busy ||
            sessionStateRepository.statusFlow.value[sessionId] is SessionStatus.Retry
        ) {
            delay(YIELD_CHECK_MS)
            waited += YIELD_CHECK_MS
            if (waited > MAX_YIELD_MS) return // 让位上限：超时后照常尝试（服务器繁忙自然报错）
        }
    }

    private suspend fun setSyncState(
        sessionId: String,
        state: String,
        errorMessage: String?,
        lastSyncAt: Long? = null,
    ) {
        queueMutex.withLock {
            sessionSyncDao.upsert(
                SessionSyncEntity(
                    sessionId = sessionId,
                    state = state,
                    lastSyncAt = lastSyncAt ?: sessionSyncDao.get(sessionId)?.lastSyncAt,
                    errorMessage = errorMessage,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "HistorySync"
        private const val PAGE_SIZE = 50
        private const val PAGE_INTERVAL_MS = 150L
        private const val MAX_PAGES = 400 // 2 万条保护上限（幂等重跑不放大体积）
        private const val YIELD_CHECK_MS = 2_000L
        private const val MAX_YIELD_MS = 10 * 60_000L
    }
}
