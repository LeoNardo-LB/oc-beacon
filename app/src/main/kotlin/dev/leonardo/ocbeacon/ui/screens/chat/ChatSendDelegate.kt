package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ChatSendDelegate"

/**
 * 管理此前内联在 [ChatViewModel] 中的消息发送逻辑。
 *
 * **悲观消息模式**（与 opencode 官方一致）：发送后不创建乐观消息，
 * 等待服务器 SSE 回显（MessageUpdated）后消息出现在列表；发送失败
 * 时恢复草稿到输入框并通过 [errorSink] 提示。跨 delegate 协调器
 * （draftDelegate 的失败草稿恢复）通过直接引用实现。
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
    private val sendStateStore: SendStateStore,
    private val scope: CoroutineScope,
    private val serverId: String,
    private val sessionIdProvider: () -> String,
    private val sessionDirectoryProvider: () -> String?,
    private val ensureSession: suspend () -> String,
    private val modelConfigProvider: () -> ModelConfigState,
    private val selectedVariantProvider: () -> String?,
    private val errorSink: (String) -> Unit,
    /** 发送失败弹窗通道（AlertDialog）——与 [errorSink]（snackbar）分离。 */
    private val sendFailureSink: (String) -> Unit,
    /** 发送成功信号（驱动输入框清空——失败时输入框消息保留，用户要求）。 */
    private val onSendSuccess: () -> Unit,
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
                    AppLogger.i(TAG, msg)
                    sessionRepository.setSessions(serverId, listOf(refreshed))
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to refresh session title for $sid: ${e.message}")
            }
        }
    }

    private fun sendParts(parts: List<PromptPart>) {
        // RS-007 修复：防止快速双击。_isSending 由 setSending 同步设置，
        // 但 Compose 重组（禁用按钮）有 1 帧延迟。此检查消除了竞态窗口。
        if (sendStateStore.isSendingValue) {
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "sendParts: already sending, ignoring duplicate")
            return
        }
        sendStateStore.setSending(true)
        scrollSignal.requestScrollToTop()
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

                // 悲观消息：POST 受理后不显示任何占位，等待服务器 SSE
                // 回显 MessageUpdated 时消息出现在列表（opencode 官方行为）。
                // 发送期间 UI 由 isSending 驱动发送按钮转圈（SendStopButton）；
                // 失败 → 草稿回退输入框 + AlertDialog（sendFailureSink）。
                sendMessageUseCase.sendPrompt(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    parts = parts,
                    model = model,
                    agent = modelCfg.selectedAgent,
                    variant = selectedVariantProvider(),
                    directory = sessionDirectoryProvider()
                )
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Sent prompt to session $currentSessionId (${parts.size} parts)")
                refreshSessionTitleDelayed(currentSessionId)
                // 发送成功：通知 UI 清空输入框（失败时不通知——消息保留在输入框）
                onSendSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to send message", e)
                // 2026-08-11 用户要求：输入框内容在发送期间保留（发送时不清空，
                // 成功才由 onSendSuccess 驱动清空）→ 失败时消息自然留在输入框，
                // 无需 setRestoredDraft 回填（避免附件重复添加）。
                // 仅弹 AlertDialog 提示失败。
                sendFailureSink(e.message ?: "Failed to send message")
            } finally {
                sendStateStore.setSending(false)
            }
        }
    }
}
