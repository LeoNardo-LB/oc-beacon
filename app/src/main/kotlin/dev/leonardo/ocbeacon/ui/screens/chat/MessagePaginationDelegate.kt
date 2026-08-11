package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.PaginationCursor
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.LoadOlderSource
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import dev.leonardo.ocbeacon.domain.usecase.PaginationFSM
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "MessagePaginationDelegate"

/**
 * 消息分页职责 —— 从 [MessageDataDelegate] 拆出。
 *
 * 拥有分页限制（[currentMessageLimit]）、翻页游标与防风暴状态
 *（统一收敛于 [PaginationFSM]，#56 TD-1）、"是否还有更早消息"
 *（[hasOlderMessages]）以及"加载更早"进行中状态（[isLoadingOlder]）。
 * 会话加载/刷新/加载更早三个入口方法集中于此；对主体共享的加载/错误状态通过
 * [loadingSink] / [errorSink] 回写 [MessageDataDelegate]。
 *
 * 状态收敛（9 → 3 个可变成员）：
 * 1. [currentMessageLimit] —— 配置（进入会话时刷新）
 * 2. [_paginationState] —— [PaginationFSM.State]（游标 + hasOlder + 防风暴 7 合 1）
 * 3. [_isLoadingOlder] —— 互斥标志（"加载更早"进行中，生命周期性质不进 FSM）
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
    // ============ 分页状态（#56：9 个散落可变成员 → 3 个） ============

    /**
     * 每页加载的消息数。进入会话时从用户的 initialMessageCount 设置刷新。
     * 游标翻页下不再翻倍——"加载更早"靠 [messageStore] 最旧消息 ID 作 before 游标。
     */
    private var currentMessageLimit = 30

    /**
     * 分页 FSM 状态 —— 游标 + hasOlder + 自动续载防风暴的单一真相源。
     * 全部写入经 [applyTransition]（[PaginationFSM.transition] 纯函数 + 同步投影）。
     *
     * 游标语义（重构前逐条保留，详见 [PaginationCursor]）：
     * - HotStart → 首次翻页：热表最老作 beforeId
     * - Archive → 归档时间游标优先（beforeCreated）
     * - Network → 归档已读尽，直接网络（beforeId=游标 ID + networkBeforeCreated）
     */
    private val _paginationState = MutableStateFlow(PaginationFSM.State())
    private val paginationState: StateFlow<PaginationFSM.State> = _paginationState.asStateFlow()

    /** 服务器上是否存在超出当前限制的更多消息（FSM 同步投影）。 */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** 自动续载暂停（连续失败达上限）——UI 停止自动分页，等待手动触发（FSM 同步投影）。 */
    private val _autoLoadPaused = MutableStateFlow(false)
    /** "加载更早" 请求是否进行中（入口互斥标志，生命周期性质，不属 FSM 状态）。 */
    private val _isLoadingOlder = MutableStateFlow(false)

    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()
    val autoLoadPaused: StateFlow<Boolean> = _autoLoadPaused.asStateFlow()

    /**
     * FSM 转移统一入口：纯函数转移 + 投影 StateFlow 同步赋值。
     * 与 loadOlderMessages 入口互斥共用同一把锁——状态变更串行化
     *（无 CAS 重试、无 update lambda 内副作用，见 #40 约定）。
     */
    private fun applyTransition(event: PaginationFSM.Event) {
        synchronized(this) {
            val newState = PaginationFSM.transition(_paginationState.value, event)
            _paginationState.value = newState
            _hasOlderMessages.value = newState.hasOlderMessages
            _autoLoadPaused.value = newState.autoLoadPaused
        }
    }

    /** 同步读取当前分页限制（诊断/测试用）。 */
    internal val currentLimitValue: Int get() = currentMessageLimit

    /** 自动续载的退避等待毫秒（0 = 无需等待）。UI 触发前查询。 */
    fun autoLoadWaitMillis(): Long =
        (paginationState.value.autoLoadPausedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

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
            // 会话重新加载 → 归档/网络游标重置（use case 内部回落到热表最老）
            applyTransition(PaginationFSM.Event.SessionReloaded(messages.size >= currentMessageLimit))
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "V1 loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit, hasOlder=${paginationState.value.hasOlderMessages})")
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
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to load messages", e)
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    AppLogger.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                        chatRepository.upsertMessages(sid, messages, MergeStrategy.APPEND_ONLY)
                        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                    } catch (retryEx: Throwable) {
                        if (retryEx is CancellationException) throw retryEx
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
            // 入口互斥（#41）：check-then-set 非原子，用 synchronized 包住防止并发 launch 双双通过
            synchronized(this) {
                if (_isLoadingOlder.value) return@launch
                _isLoadingOlder.value = true
            }
            try {
                // 游标策略（#56：统一从 FSM 状态读取）：
                //   1. Network 游标非空 → 网络边界已建立，beforeId=游标 ID + networkBeforeCreated
                //      （use case 跳过归档直接网络，防重复/循环）
                //   2. Archive 游标非空 → 继续读归档（beforeCreated）
                //   3. HotStart → 首次翻页：热表最老作 beforeId
                val hotOldestId = messageStore.oldestMessageId(sid)
                val cursor = paginationState.value.cursor
                val beforeId = if (cursor is PaginationCursor.Network) cursor.id else hotOldestId
                val beforeCreated = (cursor as? PaginationCursor.Archive)?.created
                val networkBeforeCreated = (cursor as? PaginationCursor.Network)?.created
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadOlder START sid=${sid.take(12)} limit=$currentMessageLimit beforeId=${beforeId?.take(16)} cursor=$cursor failures=${paginationState.value.autoLoadFailures} paused=${paginationState.value.autoLoadPaused}")
                }
                val result = messagePaging.loadOlderMessages(
                    serverId, sid, currentMessageLimit, beforeId,
                    beforeCreated = beforeCreated,
                    networkBeforeCreated = networkBeforeCreated,
                ).getOrThrow()
                // 归档来源只进内存（不落热表 → 防死循环）；网络来源保持现状（upsert 内自控落库）
                chatRepository.upsertMessages(sid, result.messages, MergeStrategy.APPEND_ONLY)
                // 单次遍历取最老消息（同时服务 ID 与 created 游标推进）
                val oldest = result.messages.minByOrNull { it.info.time.created }
                applyTransition(
                    PaginationFSM.Event.LoadSucceeded(
                        source = result.source,
                        oldestId = oldest?.info?.id,
                        oldestCreated = oldest?.info?.time?.created,
                        pageSize = result.messages.size,
                        limit = currentMessageLimit,
                    ),
                )
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadOlder ${result.source}: ${result.messages.size} msgs (limit=$currentMessageLimit), cursor -> ${paginationState.value.cursor}, hasOlder=${paginationState.value.hasOlderMessages}")
                }
                if (paginationState.value.autoLoadFailures > 0 || paginationState.value.autoLoadPaused) {
                    AppLogger.d(TAG, "Auto load older RECOVERED (failures=${paginationState.value.autoLoadFailures} -> 0, paused=${paginationState.value.autoLoadPaused} -> false)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load older messages", e)
                applyTransition(PaginationFSM.Event.LoadFailed())
                AppLogger.w(TAG, "Auto load failure #${paginationState.value.autoLoadFailures}: backoff=${autoLoadWaitMillis()}ms, paused=${paginationState.value.autoLoadPaused}")
            } finally {
                _isLoadingOlder.value = false
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadOlder END sid=${sid.take(12)} isLoadingOlder=false failures=${paginationState.value.autoLoadFailures} paused=${paginationState.value.autoLoadPaused} waitMs=${autoLoadWaitMillis()}")
                }
            }
        }
    }
}
