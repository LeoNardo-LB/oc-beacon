package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.OptimisticMessage
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.UserMsgStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 乐观消息存储 —— 从 [MessageDataDelegate] 拆出。
 *
 * 拥有 pending 消息 ID 集合（[pendingMessageIds]）、等待服务器
 * 确认的乐观消息列表（[pendingMessages]）以及发送中标志
 *（[isSending]）。发送生命周期方法（[onSendStarted] /
 * [onSendSuccess] / [onSendError] / [onRetryStarted]）由
 * [ChatSendDelegate] 驱动；对账 API（[restorePendingPrompts] /
 * [pendingOptimisticSnapshot] / [markPendingAsFailed]）由
 * [ChatViewModel] 在应用重启恢复时驱动。
 *
 * 发送失败时通过 [errorSink] 回写 [MessageDataDelegate] 的共享
 * 错误状态。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel
 * 的运行时上下文（ViewModel 的协程作用域），由 [MessageDataDelegate]
 * 直接构造。
 */
internal class OptimisticMessageStore(
    private val scope: CoroutineScope,
    private val errorSink: (String) -> Unit,
) {
    private val _isSending = MutableStateFlow(false)
    /** 同步读取 [_isSending]，用于竞态条件保护（RS-007）。 */
    internal val isSendingValue: Boolean get() = _isSending.value
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** 本地生成的乐观消息 ID。用于与服务器确认的消息区分。 */
    private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingMessageIds: StateFlow<Set<String>> = _pendingMessageIds.asStateFlow()

    /** 等待服务器通过 SSE 确认的乐观消息。 */
    private val _pendingMessages = MutableStateFlow<List<OptimisticMessage>>(emptyList())
    val pendingMessages: StateFlow<List<OptimisticMessage>> = _pendingMessages.asStateFlow()

    // ============ 发送生命周期（ChatSendDelegate 的 intent 方法） ============

    /**
     * 标记乐观发送的开始：翻转 [_isSending]，注册
     * [pendingId]，并存储乐观消息以立即显示。
     */
    fun onSendStarted(pendingId: String, optimisticMsg: Message.User, optimisticParts: List<Part>) {
        _isSending.value = true
        _pendingMessageIds.update { it + pendingId }
        _pendingMessages.update { it + OptimisticMessage(pendingId, optimisticMsg, optimisticParts, UserMsgStatus.Sending) }
        // 注意：乐观消息不注入共享的 _messages/_parts
        // 缓存。它们在 [MessageDataDelegate.messageListState] 的 combine
        // 体内合并（参见下方的 `activePending`），并在服务器投递任何
        // 时间戳大于等于 pending 发送时间的消息后移除。
    }

    /** 标记发送成功：将状态翻转为 Sent。乐观消息以稳定 key 保留在缓存中
     *  —— 仅状态（以及指示器）变化。 */
    fun onSendSuccess(pendingId: String) {
        _isSending.value = false
        _pendingMessageIds.update { it - pendingId }
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Sent) else it }
        }
        // 无定时器清理 —— 乐观消息以稳定 key 保留直到
        // 会话切换（自然缓存清除 + 用真实 ID 的 REST 重载）。
    }

    /**
     * 标记发送失败：清除 [_isSending]，设置共享错误状态，将消息标记为 Failed。
     */
    fun onSendError(message: String, pendingId: String) {
        _isSending.value = false
        _pendingMessageIds.update { it - pendingId }
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Failed) else it }
        }
        errorSink(message)
    }

    /** 标记重试进行中：将 pending 消息翻转回 Sending。 */
    fun onRetryStarted(pendingId: String) {
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Sending) else it }
        }
        _pendingMessageIds.update { it + pendingId }
        _isSending.value = true
    }

    /** 通过 ID 获取 pending 乐观消息（用于重试内容提取）。 */
    fun getPendingMessage(pendingId: String): OptimisticMessage? {
        return _pendingMessages.value.find { it.pendingId == pendingId }
    }

    /** 移除 pending 消息（在重试提取内容并重新发送后使用）。 */
    fun removePendingMessage(pendingId: String) {
        _pendingMessages.update { it.filter { p -> p.pendingId != pendingId } }
    }

    // ============ Pending Prompt 持久化与对账 ============

    /**
     * 应用重启后恢复已持久化的 pending prompt。
     *
     * 每条记录重新物化为 [OptimisticMessage]，状态为
     * [UserMsgStatus.Sending]。对账（将丢失的发送标记为
     * [UserMsgStatus.Failed]）在服务器权威消息列表加载后
     * 进行 —— 由 [ChatViewModel] 通过
     * [pendingOptimisticSnapshot] + [markPendingAsFailed] 驱动。
     */
    internal fun restorePendingPrompts(records: List<PendingPromptRecord>) {
        if (records.isEmpty()) return
        _pendingMessageIds.update { ids -> ids + records.map { it.messageId }.toSet() }
        _pendingMessages.update { existing ->
            // distinctBy 保留已有条目；避免重复恢复时的重复。
            (existing + records.map { it.toOptimisticMessage() }).distinctBy { it.pendingId }
        }
    }

    /**
     * 当前 pending 乐观消息的快照 —— 供 [ChatViewModel] 中的对账
     * 循环使用，用于检测重启中丢失的发送。
     */
    internal fun pendingOptimisticSnapshot(): List<OptimisticMessage> = _pendingMessages.value

    /**
     * 将 pending prompt 标记为失败并从活跃 pending 集合中移除。
     * 当发送被判定为丢失（覆盖 + 过期）时由对账使用。
     */
    internal fun markPendingAsFailed(pendingId: String) {
        _pendingMessageIds.update { it - pendingId }
        _pendingMessages.update { pending ->
            pending.map { if (it.pendingId == pendingId) it.copy(status = UserMsgStatus.Failed) else it }
        }
    }

    /** 从持久化记录重建 [OptimisticMessage]（sendParts 的逆操作）。 */
    private fun PendingPromptRecord.toOptimisticMessage(): OptimisticMessage {
        val optimisticParts = parts.mapIndexed { index, pp ->
            Part.Text(
                id = "${messageId}-part-$index",
                sessionId = sessionId,
                messageId = messageId,
                text = pp.text ?: "",
            )
        }
        return OptimisticMessage(
            pendingId = messageId,
            message = Message.User(
                id = messageId,
                sessionId = sessionId,
                time = TimeInfo(created = createdAt),
            ),
            parts = optimisticParts,
            status = UserMsgStatus.Sending,
        )
    }
}
