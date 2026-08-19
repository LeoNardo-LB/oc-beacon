package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import dev.leonardo.ocbeacon.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PendingMsgPipeline"

/**
 * 堆积消息推进管线（设计定稿 2026-08-20）。
 *
 * 触发规则（单一无例外）：任何一次**自然成功 turn 结束**（Busy→Idle 且事件
 * 为 SSE 自然成功信号）→ 发送该会话队首 1 条。手动停止/错误/退出不触发；
 * 出错滞留后的下一次自然成功（含插队直发消息的 turn）同样重启流水线。
 *
 * 发送语义：
 * - peek → POST → 成功才 delete（at-least-once：POST 成功但 delete 失败的
 *   极端场景宁可重复发送也不丢失用户文本）；
 * - 发送失败：条目留在队首，等下一次自然结束或面板「继续」重试；
 * - POST 成功后 onClientSendParts 置 Busy（与 ChatSendDelegate 一致），
 *   该 turn 自然结束时流水线自动推进下一条；
 * - 模型/agent/variant 均不带（用会话/服务器默认）——入队时不快照 UI 配置。
 *
 * 并发防护：会话级 in-flight 去重（[drainingSessions] 暴露给 UI 标记
 * 「发送中」并锁定该条的编辑/删除）。
 */
@Singleton
class PendingMessagePipeline @Inject constructor(
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val pendingMessageRepository: PendingMessageRepository,
    // Provider 打破 EventDispatcher → ChatRepositoryImpl → EventDispatcher 循环
    private val sendMessageUseCaseProvider: Provider<SendMessageUseCase>,
    private val sessionStateService: SessionStateService,
) {
    /** 正在发送堆积消息的会话集合（UI「发送中」状态源）。 */
    private val _drainingSessions = MutableStateFlow<Set<String>>(emptySet())
    val drainingSessions: StateFlow<Set<String>> = _drainingSessions

    private val draining = ConcurrentHashMap<String, Boolean>()

    /** 自然成功 turn 结束回调入口（SessionStateService.naturalTurnEndListener）。 */
    fun onNaturalTurnEnd(sessionId: String, serverId: String?) {
        if (serverId == null) {
            AppLogger.w(TAG, "natural turn end without server ownership, skip drain: " + sessionId)
            return
        }
        launchDrain(sessionId, serverId)
    }

    /** 面板「继续」按钮：空闲会话手动放行队首 1 条。 */
    fun continueNow(sessionId: String, serverId: String) = launchDrain(sessionId, serverId)

    /** 面板单条「发送」按钮：立即发送指定条目（非队首也行，插队语义）。 */
    fun sendOneNow(sessionId: String, serverId: String, id: Long, text: String) {
        if (draining.putIfAbsent(sessionId, true) != null) return
        appScope.launch {
            _drainingSessions.update { it + sessionId }
            try {
                if (sendText(serverId, sessionId, text)) {
                    pendingMessageRepository.delete(id)
                }
            } finally {
                draining.remove(sessionId)
                _drainingSessions.update { it - sessionId }
            }
        }
    }

    private fun launchDrain(sessionId: String, serverId: String) {
        if (draining.putIfAbsent(sessionId, true) != null) {
            if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                AppLogger.d(TAG, "drain already in-flight, skip: " + sessionId)
            }
            return
        }
        appScope.launch {
            _drainingSessions.update { it + sessionId }
            try {
                val head = pendingMessageRepository.peekHead(sessionId) ?: return@launch
                if (sendText(serverId, sessionId, head.text)) {
                    pendingMessageRepository.delete(head.id)
                }
                // 失败：留在队首，下一次自然结束或「继续」重试
            } finally {
                draining.remove(sessionId)
                _drainingSessions.update { it - sessionId }
            }
        }
    }

    private suspend fun sendText(serverId: String, sessionId: String, text: String): Boolean {
        return try {
            sendMessageUseCaseProvider.get().sendPrompt(
                serverId = serverId,
                sessionId = sessionId,
                parts = listOf(PromptPart(type = "text", text = text)),
                model = null,
                agent = "",
                variant = null,
                directory = null,
            )
            sessionStateService.onClientSendParts(sessionId)
            AppLogger.i(TAG, "queued message sent: " + sessionId + " (" + text.length + " chars)")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "queued message send failed: " + sessionId + " - " + e.message, e)
            false
        }
    }
}
