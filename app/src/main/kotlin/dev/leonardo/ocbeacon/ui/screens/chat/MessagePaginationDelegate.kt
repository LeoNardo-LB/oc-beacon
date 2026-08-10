package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.LoadOlderSource
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "MessagePaginationDelegate"

/** 自动续载失败退避基础时长（ms）：连续失败指数增长（500→1000→2000→…）。 */
private const val AUTO_LOAD_BACKOFF_BASE_MS = 500L
/** 退避指数上限（1L shl MAX = 最大 2^4=16 倍 = 8s）。 */
private const val AUTO_LOAD_BACKOFF_MAX_SHIFT = 4
/** 自动续载最大连续失败次数：达此值暂停自动续载（手动滑动恢复）。 */
private const val MAX_AUTO_LOAD_FAILURES = 3

/**
 * 消息分页职责 —— 从 [MessageDataDelegate] 拆出。
 *
 * 拥有分页限制（[currentMessageLimit]）、是否还有更早消息
 *（[hasOlderMessages]）以及"加载更早"进行中状态
 *（[isLoadingOlder]）。会话加载/刷新/加载更早三个入口方法
 * 集中于此；对主体共享的加载/错误状态通过
 * [loadingSink] / [errorSink] 回写 [MessageDataDelegate]。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个
 * ChatViewModel 的运行时上下文（ViewModel 的协程作用域、
 * server-id/session-id provider），由 [MessageDataDelegate]
 * 直接构造。
 */
internal class MessagePaginationDelegate(
    private val manageSessionUseCase: ManageSessionUseCase,
    private val messagePaging: MessagePaginationUseCase,
    private val messageStore: MessageStore,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val serverId: String,
    private val scope: CoroutineScope,
    private val sessionIdProvider: () -> String,
    private val loadingSink: (Boolean) -> Unit,
    private val errorSink: (String?) -> Unit,
) {
    // ============ 分页 ============
    /**
     * 每页加载的消息数。进入会话时从用户的 initialMessageCount 设置刷新。
     * 游标翻页下不再翻倍——"加载更早"靠 [messageStore] 最旧消息 ID 作 before 游标。
     */
    private var currentMessageLimit = 30
    /**
     * 归档翻页时间游标：最近一次 ARCHIVE 来源加载返回的最老消息 created（毫秒）。
     * 归档读取不落热表 → 热表最老不变；若始终用热表最老作 before，
     * 每次翻页会读到同一批归档桶（死循环）。此游标持久化"已显示到哪"，
     * 使下次翻页能继续读更早的归档。
     * - 进入会话 → 重置为 null（use case 内部回落到热表最老）
     * - ARCHIVE 来源 → 推进为本次返回的最老消息 created
     * - NETWORK 来源 → 重置为 null（归档已读尽，回落到热表边界）
     */
    private var archiveCursorCreated: Long? = null
    /**
     * 网络分页游标（ID + created）：最近一次 NETWORK 来源返回的最老消息。
     * 与 [archiveCursorCreated] 同语义（"分页已显示到哪"）但独立跟踪——ARCHIVE 读
     * 归档桶、NETWORK 走服务器，两者边界不同，分开推进避免互相污染。
     * 需要 ID：use case 的网络 before 编码 = CursorCodec.encode(id, created)，
     * 游标消息不在热表（窗口外不落库），必须由 delegate 记住 ID 才能编码。
     */
    private var networkCursorId: String? = null
    private var networkCursorCreated: Long? = null
    /** 服务器上是否存在超出当前限制的更多消息。 */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** "加载更早" 请求是否进行中。 */
    private val _isLoadingOlder = MutableStateFlow(false)

    // ── 自动续载防风暴（2026-08-10）────────────────────────────
    // 场景：用户滑到顶停住时自动续载（snapshotFlow 驱动）。若服务器持续失败
    // （网络/归档损坏），无保护会无限重试风暴。防护：
    //   1. 失败退避：连续失败指数退避（500ms→1s→2s→…）
    //   2. 最大连续失败次数：达 [MAX_AUTO_LOAD_FAILURES] 后暂停自动续载
    //      （[autoLoadPaused]），用户手动滑动/重新加载后恢复
    //   3. 成功重置：任何成功加载清零计数与退避
    private var autoLoadFailures = 0
    private var autoLoadPausedUntil = 0L
    private val _autoLoadPaused = MutableStateFlow(false)
    val autoLoadPaused: StateFlow<Boolean> = _autoLoadPaused.asStateFlow()

    /** 自动续载的退避等待毫秒（0 = 无需等待）。UI 触发前查询。 */
    fun autoLoadWaitMillis(): Long =
        (autoLoadPausedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun recordAutoLoadFailure() {
        autoLoadFailures++
        val backoffMs = AUTO_LOAD_BACKOFF_BASE_MS *
            (1L shl (autoLoadFailures - 1).coerceAtMost(AUTO_LOAD_BACKOFF_MAX_SHIFT))
        autoLoadPausedUntil = System.currentTimeMillis() + backoffMs
        AppLogger.w(TAG, "Auto load failure #$autoLoadFailures: backoff=${backoffMs}ms (until=${autoLoadPausedUntil}), pausedUntilReached=${autoLoadFailures >= MAX_AUTO_LOAD_FAILURES}")
        if (autoLoadFailures >= MAX_AUTO_LOAD_FAILURES) {
            _autoLoadPaused.value = true
            AppLogger.w(TAG, "Auto load older PAUSED after $autoLoadFailures consecutive failures (manual scroll to retry)")
        }
    }

    private fun recordAutoLoadSuccess() {
        if (autoLoadFailures > 0 || _autoLoadPaused.value) {
            AppLogger.d(TAG, "Auto load older RECOVERED (failures=$autoLoadFailures -> 0, paused=${_autoLoadPaused.value} -> false)")
        }
        autoLoadFailures = 0
        autoLoadPausedUntil = 0L
        _autoLoadPaused.value = false
    }

    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()
    /** 同步读取当前分页限制（诊断/测试用）。 */
    internal val currentLimitValue: Int get() = currentMessageLimit

    /**
     * 通过 V1 API 为当前会话加载消息。
     * 从 [SessionLifecycleDelegate.loadSession] 回调（跨集群
     * 回调），使 C 集群 delegate 拥有完整的加载编排，
     * 而此处保留 MessageData 集群关注点（分页限制 +
     * list/set）。
     */
    suspend fun loadMessagesForSession() {
        // 应用用户配置的初始消息数量作为分页起点
        currentMessageLimit = settingsRepository.getSettingsFlow().first().initialMessageCount
        val sid = sessionIdProvider()
        try {
            val messages = messagePaging.loadMessagesForSession(serverId, sid, currentMessageLimit)
                .getOrThrow()
            chatRepository.upsertMessages(sid, messages, MergeStrategy.SSE_PRIORITY)
            _hasOlderMessages.value = messages.size >= currentMessageLimit
            // 会话重新加载 → 归档/网络游标重置（use case 内部回落到热表最老）
            archiveCursorCreated = null
            networkCursorId = null
            networkCursorCreated = null
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "V1 loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load messages", e)
        }
    }

    /**
     * 通过 V1 API 加载消息以解析 modelConfigState（从历史中解析模型/agent）。
     * 不修改分页状态（_hasOlderMessages）—— 该状态由
     * loadMessagesForSession（会话进入）和 loadOlderMessages（分页）管理。
     */
    fun loadMessages() {
        val sid = sessionIdProvider()
        scope.launch {
            loadingSink(true)
            errorSink(null)
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                chatRepository.upsertMessages(sid, messages, MergeStrategy.SSE_PRIORITY)

                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "Loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit)")
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Failed to load messages", e)
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    AppLogger.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                        chatRepository.upsertMessages(sid, messages, MergeStrategy.APPEND_ONLY)
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                    } catch (retryEx: Throwable) {
                        AppLogger.e(TAG, "Retry also failed", retryEx)
                        errorSink(retryEx.message ?: "Failed to load messages")
                    }
                } else {
                    errorSink(e.message ?: "Failed to load messages")
                }
            } finally {
                loadingSink(false)
            }
        }
    }

    fun loadOlderMessages() {
        val sid = sessionIdProvider()
        scope.launch {
            // 入口互斥：check-then-set 非原子，用 synchronized 包住防止并发 launch 双双通过
            synchronized(this) {
                if (_isLoadingOlder.value) return@launch
                _isLoadingOlder.value = true
            }
            try {
                // 游标策略：
                //   1. 网络分页游标（networkCursorId/Created）非空 → 网络边界已建立，
                //      beforeId=网络游标 ID + networkBeforeCreated=网络游标时间
                //      （use case 跳过归档直接网络，防重复/循环）
                //   2. 归档游标（archiveCursorCreated）非空 → 继续读归档
                //   3. 都为空 → 首次翻页：热表最老作 beforeId
                val hotOldestId = messageStore.oldestMessageId(sid)
                val beforeId = networkCursorId ?: hotOldestId
                val beforeCreated = archiveCursorCreated
                val networkBeforeCreated = networkCursorCreated
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadOlder START sid=${sid.take(12)} limit=$currentMessageLimit beforeId=${beforeId?.take(16)} archiveCursor=$archiveCursorCreated networkCursor=$networkCursorId/$networkCursorCreated failures=$autoLoadFailures paused=${_autoLoadPaused.value}")
                }
                val result = messagePaging.loadOlderMessages(
                    serverId, sid, currentMessageLimit, beforeId,
                    beforeCreated = beforeCreated,
                    networkBeforeCreated = networkBeforeCreated,
                ).getOrThrow()
                // 归档来源只进内存（不落热表 → 防死循环）；网络来源保持现状（upsert 内自控落库）
                chatRepository.upsertMessages(sid, result.messages, MergeStrategy.APPEND_ONLY)
                when (result.source) {
                    LoadOlderSource.ARCHIVE -> {
                        // 归档时间游标推进为本次返回的最老消息 created（下次翻页继续读更早归档）
                        archiveCursorCreated = result.messages.minOfOrNull { it.info.time.created }
                            ?: archiveCursorCreated
                        _hasOlderMessages.value = true
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(TAG, "loadOlder ARCHIVE: ${result.messages.size} msgs, cursor advanced -> $archiveCursorCreated")
                        }
                    }
                    LoadOlderSource.NETWORK -> {
                        // 网络来源：归档已读尽，游标回落（后续若网络失败仍可从热表边界回读归档）
                        archiveCursorCreated = null
                        // 网络分页游标推进为本次返回的最老消息（ID + created；下次翻页继续读更早；
                        // 游标消息不在热表，必须由 delegate 记住 ID 供 use case 编码）
                        result.messages.minByOrNull { it.info.time.created }?.let { oldest ->
                            networkCursorId = oldest.info.id
                            networkCursorCreated = oldest.info.time.created
                        }
                        _hasOlderMessages.value = result.messages.size >= currentMessageLimit
                        if (BuildConfig.DEBUG) {
                            AppLogger.d(TAG, "loadOlder NETWORK: ${result.messages.size} msgs (limit=$currentMessageLimit), networkCursor -> $networkCursorId/$networkCursorCreated, hasOlder=${_hasOlderMessages.value}")
                        }
                    }
                }
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "Loaded older: ${result.messages.size} msgs (source=${result.source}, cursor=$archiveCursorCreated, hasOlder=${_hasOlderMessages.value})")
                }
                recordAutoLoadSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load older messages", e)
                recordAutoLoadFailure()
            } finally {
                _isLoadingOlder.value = false
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadOlder END sid=${sid.take(12)} isLoadingOlder=false failures=$autoLoadFailures paused=${_autoLoadPaused.value} waitMs=${autoLoadWaitMillis()}")
                }
            }
        }
    }
}
