package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord
import dev.leonardo.ocbeacon.domain.repository.PendingPromptRepository
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ChatSendDelegate"

/**
 * 管理此前内联在 [ChatViewModel] 中的消息发送/重试逻辑。
 *
 * 包含乐观消息创建、pending prompt 持久化、发送状态协调和
 * 延迟的会话标题刷新。跨 delegate 协调器（写入 messageData 的
 * pending 状态、draftDelegate 的失败草稿恢复）通过直接引用
 * [MessageDataDelegate] 和 [DraftInputDelegate] 实现。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的
 * 运行时上下文（ViewModel 的协程作用域、跨 delegate 引用、server-id/会话
 * provider），Hilt 无法提供这些。ChatViewModel 直接构造它并将每个成员
 * 作为门面重新暴露，因此 UI 文件无需改动。
 */
internal class ChatSendDelegate(
    private val scrollSignal: SessionScrollSignal,
    private val sendMessageUseCase: SendMessageUseCase,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val sessionStateService: SessionStateRepository,
    private val pendingPromptRepository: PendingPromptRepository,
    private val scope: CoroutineScope,
    private val serverId: String,
    private val sessionIdProvider: () -> String,
    private val sessionDirectoryProvider: () -> String?,
    private val ensureSession: suspend () -> String,
    private val modelConfigProvider: () -> ModelConfigState,
    private val selectedVariantProvider: () -> String?,
    private val messageData: MessageDataDelegate,
    private val draftDelegate: DraftInputDelegate,
) {
    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        val parts = mutableListOf<PromptPart>()
        if (text.isNotBlank()) {
            parts.add(PromptPart(type = "text", text = text))
        }
        parts.addAll(attachments)
        sendParts(parts)
    }

    /** 发送预构建的 prompt parts（当 @ 文件提及需要结构化 parts 时使用）。 */
    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>) {
        val parts = promptParts + attachments
        if (parts.isEmpty()) return
        sendParts(parts)
    }

    /**
     * 安排延迟的 REST 刷新以获取更新后的会话标题。
     * 仅当当前标题看起来像默认占位符时才刷新
     *（null、空或匹配 "New session - ..." 模式）。
     */
    private fun refreshSessionTitleDelayed(sid: String) {
        scope.launch {
            delay(8_000) // 等待服务器异步标题生成
            try {
                val refreshed = manageSessionUseCase.getSession(serverId, sid)
                val currentSession = chatRepository.getSessionsSnapshot().find { it.id == sid }
                val currentTitle = currentSession?.title
                // 仅在标题实际变化时更新（如果 SSE 已投递则跳过）
                if (refreshed.title != currentTitle) {
                    val msg = "[Title] REST fallback: title updated from '$currentTitle' to '${refreshed.title}'"
                    Log.i(TAG, msg)
                    sessionRepository.setSessions(serverId, listOf(refreshed))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh session title for $sid: ${e.message}")
            }
        }
    }

    private fun sendParts(parts: List<PromptPart>) {
        // RS-007 修复：防止快速双击。_isSending 由 onSendStarted 同步设置，
        // 但 Compose 重组（禁用按钮）有 1 帧延迟。此检查消除了竞态窗口。
        if (messageData.isSendingValue) {
            if (BuildConfig.DEBUG) Log.d(TAG, "sendParts: already sending, ignoring duplicate")
            return
        }
        scrollSignal.requestScrollToTop()
        val pendingId = "pending-${java.util.UUID.randomUUID()}"

        // 创建乐观消息以立即显示
        val now = System.currentTimeMillis()
        val currentSid = sessionIdProvider()
        val optimisticMsg = Message.User(
            id = pendingId,
            sessionId = currentSid,
            time = TimeInfo(created = now),
        )
        val optimisticParts = parts.mapIndexed { index, pp ->
            Part.Text(
                id = "${pendingId}-part-$index",
                sessionId = currentSid,
                messageId = pendingId,
                text = pp.text ?: "",
            )
        }
        messageData.onSendStarted(pendingId, optimisticMsg, optimisticParts)
        // 持久化乐观发送，使其在发送中途应用被杀时存活。
        // 下次启动时的对账会检测服务器从未回显的发送。
        pendingPromptRepository.save(
            PendingPromptRecord(
                messageId = pendingId,
                sessionId = currentSid,
                parts = parts,
                createdAt = now,
            )
        )
        scope.launch {
            try {
                val currentSessionId = ensureSession()
                sessionStateService.onClientSendParts(currentSessionId)
                // P5-5：从 modelConfigState（已解析的有效值）读取，而非
                // 原始 _selectedProviderId（在新会话首次发送时可能为 null）。
                val modelCfg = modelConfigProvider()
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId,
                        modelId = modelCfg.selectedModelId
                    )
                } else null

                // 发送前清除 revert —— message.removed SSE 事件已从缓存中
                // 清理旧消息，因此不会闪烁。
                chatRepository.clearRevert(currentSessionId)

                sendMessageUseCase.sendPrompt(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    parts = parts,
                    model = model,
                    agent = modelCfg.selectedAgent,
                    variant = selectedVariantProvider(),
                    directory = sessionDirectoryProvider()
                )
                messageData.onSendSuccess(pendingId)
                pendingPromptRepository.remove(pendingId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $currentSessionId (${parts.size} parts)")
                refreshSessionTitleDelayed(currentSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                // 从失败的发送恢复草稿
                val failedText = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                if (failedText.isNotBlank()) {
                    draftDelegate.setRestoredDraft(RevertedDraftPayload(text = failedText))
                }
                messageData.onSendError(e.message ?: "Failed to send message", pendingId)
                pendingPromptRepository.remove(pendingId)
            }
        }
    }

    /** 通过 pending ID 重试发送失败的乐观消息。 */
    fun retrySendMessage(pendingId: String) {
        val pending = messageData.getPendingMessage(pendingId) ?: return
        val parts = pending.parts.mapNotNull { part ->
            (part as? Part.Text)?.let { PromptPart(type = "text", text = it.text) }
        }
        messageData.removePendingMessage(pendingId)
        sendParts(parts)
    }
}
