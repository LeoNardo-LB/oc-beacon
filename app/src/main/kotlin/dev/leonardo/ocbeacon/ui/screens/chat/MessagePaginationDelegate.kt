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
    /** 服务器上是否存在超出当前限制的更多消息。 */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** "加载更早" 请求是否进行中。 */
    private val _isLoadingOlder = MutableStateFlow(false)

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
            // 会话重新加载 → 归档时间游标重置（use case 内部回落到热表最老）
            archiveCursorCreated = null
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
            _isLoadingOlder.value = true
            try {
                // before 游标：优先用归档时间游标（归档翻页推进）；null 时 use case 回落到热表最老
                val beforeId = messageStore.oldestMessageId(sid)
                val result = messagePaging.loadOlderMessages(
                    serverId, sid, currentMessageLimit, beforeId,
                    beforeCreated = archiveCursorCreated,
                ).getOrThrow()
                // 归档来源只进内存（不落热表 → 防死循环）；网络来源保持现状（upsert 内自控落库）
                chatRepository.upsertMessages(sid, result.messages, MergeStrategy.APPEND_ONLY)
                when (result.source) {
                    LoadOlderSource.ARCHIVE -> {
                        // 归档时间游标推进为本次返回的最老消息 created（下次翻页继续读更早归档）
                        archiveCursorCreated = result.messages.minOfOrNull { it.info.time.created }
                            ?: archiveCursorCreated
                        _hasOlderMessages.value = true
                    }
                    LoadOlderSource.NETWORK -> {
                        // 网络来源：归档已读尽，游标回落（后续若网络失败仍可从热表边界回读归档）
                        archiveCursorCreated = null
                        _hasOlderMessages.value = result.messages.size >= currentMessageLimit
                    }
                }
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "Loaded older: ${result.messages.size} msgs (source=${result.source}, cursor=$archiveCursorCreated, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load older messages", e)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }
}
