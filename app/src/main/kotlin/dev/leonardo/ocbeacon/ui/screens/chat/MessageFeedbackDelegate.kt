package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.MessageFeedbackAction
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackToggle
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MessageFeedbackDelegate"

/**
 * toggle 交付结果（UI 据此选择是否 snackbar 及文案）。
 * 顶层 public：ChatViewModel 公有方法以此为返回型。
 */
enum class MessageFeedbackOutcome {
    /** 评价/换向已落定（静默成功）。 */
    Done,

    /** 同向撤销成功（提示「已撤销」）。 */
    Removed,

    /** 重同步后重试一次仍冲突（提示再试）。 */
    Conflict,

    /** 业务/传输失败。 */
    Failed,
}

/**
 * #310② 消息反馈委托（消息级 rating 状态 + CAS 裁决；单测
 * MessageFeedbackDelegateTest）。
 *
 * - 状态面：[wireId → 整项] 映射（会话进入 list 拉种子；每次成功
 *   mutation 以服务器回执落定——不做乐观本地写）；
 * - 裁决面：[MessageFeedbackToggle] 纯函数（未评→put／同向→delete／换向→put）；
 * - CAS 冲突：以服务器 current 重同步本地后**重试一次**（重试的裁决
 *   随新观察重算——如未评首试被已有同向项拒绝，重试即同向撤销）；
 *   仍被拒 → [MessageFeedbackOutcome.Conflict] 交付 UI snackbar 提示。
 */
internal class MessageFeedbackDelegate(
    private val chatRepository: ChatRepository,
    private val serverId: String,
) {


    private val _items = MutableStateFlow<Map<String, MessageFeedbackItem>>(emptyMap())

    /** 当前会话的反馈快照（键 = 服务器规范消息 id，即 wireId）。 */
    val items: StateFlow<Map<String, MessageFeedbackItem>> = _items.asStateFlow()

    /** 会话进入时 list 拉种子；失败告警保留旧值（不清空之前的正确状态）。 */
    suspend fun seed(sessionId: String) {
        chatRepository.messageFeedbackList(serverId, sessionId)
            .onSuccess { list ->
                _items.value = (list ?: emptyList()).associateBy { it.messageId }
            }
            .onFailure { e ->
                if (e is CancellationException) throw e
                AppLogger.w(TAG, "messageFeedbackList seed failed for $sessionId: " + e.message)
            }
    }

    /** 离开 DSH 会话/服务器时清空状态。 */
    fun reset() {
        _items.value = emptyMap()
    }

    /**
     * 点击主入口：裁决 → 执行 →（冲突？重同步 + 重试一次）→
     * 以服务器回执/权威 current 落定本地状态。
     */
    suspend fun toggle(sessionId: String, messageId: String, desired: MessageFeedbackRating): MessageFeedbackOutcome {
        var observed = _items.value[messageId]
        var conflicts = 0
        while (true) {
            when (val action = MessageFeedbackToggle.decide(observed, desired)) {
                is MessageFeedbackAction.Put -> when (val result = putChecked(sessionId, messageId, action)) {
                    is MessageFeedbackPutResult.Success -> {
                        _items.value = _items.value + (result.item.messageId to result.item)
                        return MessageFeedbackOutcome.Done
                    }
                    is MessageFeedbackPutResult.VersionConflict -> {
                        val next = resyncAfterConflict(messageId, result.current, ++conflicts) ?: return MessageFeedbackOutcome.Conflict
                        observed = next
                    }
                    is MessageFeedbackPutResult.Failure -> return MessageFeedbackOutcome.Failed
                }
                is MessageFeedbackAction.Delete -> when (val result = deleteChecked(sessionId, messageId, action)) {
                    is MessageFeedbackDeleteResult.Absent -> {
                        _items.value = _items.value - messageId
                        return MessageFeedbackOutcome.Removed
                    }
                    is MessageFeedbackDeleteResult.VersionConflict -> {
                        val next = resyncAfterConflict(messageId, result.current, ++conflicts) ?: return MessageFeedbackOutcome.Conflict
                        observed = next
                    }
                    is MessageFeedbackDeleteResult.Failure -> return MessageFeedbackOutcome.Failed
                }
            }
        }
    }

    private suspend fun putChecked(
        sessionId: String,
        messageId: String,
        action: MessageFeedbackAction.Put,
    ): MessageFeedbackPutResult = chatRepository
        .messageFeedbackPut(serverId, sessionId, messageId, action.rating, null, action.ifVersion)
        .getOrElse { e ->
            if (e is CancellationException) throw e
            AppLogger.w(TAG, "messageFeedbackPut failed for $messageId: " + e.message)
            MessageFeedbackPutResult.Failure(null, e.message ?: "put failed")
        }

    private suspend fun deleteChecked(
        sessionId: String,
        messageId: String,
        action: MessageFeedbackAction.Delete,
    ): MessageFeedbackDeleteResult = chatRepository
        .messageFeedbackDelete(serverId, sessionId, messageId, action.ifVersion)
        .getOrElse { e ->
            if (e is CancellationException) throw e
            AppLogger.w(TAG, "messageFeedbackDelete failed for $messageId: " + e.message)
            MessageFeedbackDeleteResult.Failure(null, e.message ?: "delete failed")
        }

    /**
     * 冲突后以服务器权威 current 覆盖本地；返回重试的新观察值，
     * 超过一次重试（conflicts > 1）返回 null = 交付 Conflict。
     */
    private fun resyncAfterConflict(
        messageId: String,
        current: MessageFeedbackItem?,
        conflicts: Int,
    ): MessageFeedbackItem? {
        _items.value = if (current == null) _items.value - messageId
        else _items.value + (current.messageId to current)
        return if (conflicts > 1) null else current
    }

    /** 测试便利：直接植入观察到的状态（替代 seed 的网络路径）。 */
    internal fun seedFrom(seeded: List<MessageFeedbackItem>) {
        _items.value = seeded.associateBy { it.messageId }
    }
}
