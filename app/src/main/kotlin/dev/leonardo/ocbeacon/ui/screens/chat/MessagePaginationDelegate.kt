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
    /** 服务器上是否存在更新方向（newer）的更多消息（FSM 同步投影）。 */
    private val _hasNewerMessages = MutableStateFlow(false)
    /** 自动续载暂停（连续失败达上限）——UI 停止自动分页，等待手动触发（FSM 同步投影）。 */
    private val _autoLoadPaused = MutableStateFlow(false)
    /** "加载更早" 请求是否进行中（入口互斥标志，生命周期性质，不属 FSM 状态）。 */
    private val _isLoadingOlder = MutableStateFlow(false)
    /** "定位加载" 请求是否进行中（快速导航 jumpToMessage 异步加载时显示加载指示）。 */
    private val _isLoadingAround = MutableStateFlow(false)
    /** "加载更新" 请求是否进行中（入口互斥标志）。 */
    private val _isLoadingNewer = MutableStateFlow(false)

    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()
    val hasNewerMessages: StateFlow<Boolean> = _hasNewerMessages.asStateFlow()
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()
    val isLoadingAround: StateFlow<Boolean> = _isLoadingAround.asStateFlow()
    val isLoadingNewer: StateFlow<Boolean> = _isLoadingNewer.asStateFlow()
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
            _hasNewerMessages.value = newState.hasNewerMessages
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
                //   1. Network 游标非空 → 网络边界已建立：serverCursor（V2）直接透传，
                //      或回落 id+created（V1）；跳过归档防重复/循环
                //   2. Archive 游标非空 → 继续读归档（beforeCreated）
                //   3. HotStart → 首次翻页：热表最老作 beforeId
                val hotOldestId = messageStore.oldestMessageId(sid)
                val cursor = paginationState.value.cursor
                val beforeId = when (cursor) {
                    is PaginationCursor.Network -> cursor.id ?: hotOldestId
                    else -> hotOldestId
                }
                val beforeCreated = (cursor as? PaginationCursor.Archive)?.created
                val networkBeforeCreated = (cursor as? PaginationCursor.Network)?.created
                val networkCursor = (cursor as? PaginationCursor.Network)?.serverCursor
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadOlder START sid=${sid.take(12)} limit=$currentMessageLimit beforeId=${beforeId?.take(16)} cursor=$cursor failures=${paginationState.value.autoLoadFailures} paused=${paginationState.value.autoLoadPaused}")
                }
                val result = messagePaging.loadOlderMessages(
                    serverId, sid, currentMessageLimit, beforeId,
                    beforeCreated = beforeCreated,
                    networkBeforeCreated = networkBeforeCreated,
                    networkCursor = networkCursor,
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
                        nextCursor = result.nextCursor,
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

    /**
     * 快速导航定位加载：以 [targetMessageId] 为中心双向加载（前后各 [currentMessageLimit] 条）。
     *
     * 流程（设计约束 #2）：
     * 1. 调用 useCase.loadAround（V2 双向 cursor / V1 降级单条+before）
     * 2. 合并 upsert（APPEND_ONLY 按 id 去重——SSE 实时新消息与定位加载不冲突）
     * 3. 写入 FSM AroundLoaded（设置 older+newer 游标，不破坏后续滚动分页）
     *
     * 调用方（ChatMessageList.jumpToMessage）在 await 完成后通过 snapshotFlow/LaunchedEffect
     * 等待目标进入 displayItems 再滚动 + 高亮。
     */
    suspend fun loadAround(targetMessageId: String) {
        val sid = sessionIdProvider()
        _isLoadingAround.value = true
        try {
            val result = messagePaging.loadAround(serverId, sid, targetMessageId, currentMessageLimit)
                .getOrThrow()
            // 合并 target + older + newer（APPEND_ONLY 按 id 去重）
            val all = listOf(result.target) + result.olderMessages + result.newerMessages
            chatRepository.upsertMessages(sid, all, MergeStrategy.APPEND_ONLY)

            // 构造 older 游标（后续 loadOlder 复用）
            val oldest = result.olderMessages.minByOrNull { it.info.time.created }
            val olderCursor = when {
                result.olderNextCursor != null -> PaginationCursor.Network(
                    serverCursor = result.olderNextCursor,
                    id = oldest?.info?.id,
                    created = oldest?.info?.time?.created,
                )
                oldest != null -> PaginationCursor.Network(
                    id = oldest.info.id,
                    created = oldest.info.time.created,
                )
                else -> null
            }
            val hasOlder = result.olderNextCursor != null || result.olderMessages.size >= currentMessageLimit

            // 构造 newer 游标（后续 loadNewer 复用；V1 为 null）
            val newest = result.newerMessages.maxByOrNull { it.info.time.created }
            val newerCursor = if (result.newerPreviousCursor != null) {
                PaginationCursor.Network(
                    serverCursor = result.newerPreviousCursor,
                    id = newest?.info?.id,
                    created = newest?.info?.time?.created,
                )
            } else null
            val hasNewer = result.newerPreviousCursor != null || result.newerMessages.size >= currentMessageLimit

            applyTransition(
                PaginationFSM.Event.AroundLoaded(
                    olderCursor = olderCursor,
                    hasOlderMessages = hasOlder,
                    newerCursor = newerCursor,
                    hasNewerMessages = hasNewer,
                ),
            )
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "loadAround sid=${sid.take(12)} target=${targetMessageId.take(12)} older=${result.olderMessages.size} newer=${result.newerMessages.size} hasOlder=$hasOlder hasNewer=$hasNewer")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load around target ${targetMessageId.take(12)}", e)
        } finally {
            _isLoadingAround.value = false
        }
    }

    /**
     * 向更新方向加载（定位到中间后，向下滑触发）。
     *
     * 读取 FSM 的 newerCursor.serverCursor 作为请求游标（V2 cursor.previous）。
     * 无游标（V1 / 已读尽 / 未定位过）→ 直接返回（UI 由 hasNewerMessages=false 不触发）。
     * 更新方向无更多数据时静默停止（hasNewerMessages=false）。
     */
    fun loadNewerMessages() {
        val sid = sessionIdProvider()
        scope.launch {
            // 入口互斥（与 loadOlder 同模式）
            synchronized(this) {
                if (_isLoadingNewer.value) return@launch
                _isLoadingNewer.value = true
            }
            try {
                val newerCursor = paginationState.value.newerCursor
                val serverCursor = (newerCursor as? PaginationCursor.Network)?.serverCursor
                if (serverCursor == null) return@launch
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadNewer START sid=${sid.take(12)} limit=$currentMessageLimit cursor=${serverCursor.take(16)}")
                }
                val result = messagePaging.loadNewerMessages(serverId, sid, currentMessageLimit, serverCursor)
                    .getOrThrow()
                chatRepository.upsertMessages(sid, result.messages, MergeStrategy.APPEND_ONLY)
                val newest = result.messages.maxByOrNull { it.info.time.created }
                applyTransition(
                    PaginationFSM.Event.LoadNewerSucceeded(
                        newestId = newest?.info?.id,
                        newestCreated = newest?.info?.time?.created,
                        previousCursor = result.previousCursor,
                        pageSize = result.messages.size,
                        limit = currentMessageLimit,
                    ),
                )
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "loadNewer: ${result.messages.size} msgs (limit=$currentMessageLimit), hasNewer=${paginationState.value.hasNewerMessages}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 更新方向失败不触发防风暴（older 的退避独立）；用户滚动即重试
                AppLogger.e(TAG, "Failed to load newer messages", e)
            } finally {
                _isLoadingNewer.value = false
            }
        }
    }
}
