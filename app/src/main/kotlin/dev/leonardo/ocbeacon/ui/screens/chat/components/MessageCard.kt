package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn

enum class MessageCardRole { USER, ASSISTANT, SYNTHETIC }

@Composable
internal fun MessageCard(
    role: MessageCardRole,
    currentMessage: ChatMessage,
    isQueued: Boolean = false,
    renderableTurn: RenderableTurn? = null,
    onViewSubSession: ((String) -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    isAmoled: Boolean = false,
    isTurnLast: Boolean = false,
    /** turn 级流式判定（turn 内任一消息 completed == null）。多消息 turn 时
     *  代表消息是 oldest（可能已完成），仅看代表消息会漏判流式 → 统计栏
     *  延迟到回复完毕才出现。由 ChatMessageList 传入 isStreamingMsg。 */
    isStreamingTurn: Boolean = false,
    agents: List<AgentInfo> = emptyList(),
    onCopy: (() -> Unit)? = null,
) {
    when (role) {
        MessageCardRole.USER -> MessageCardUser(
            currentMessage = currentMessage,
            isQueued = isQueued,
            onRevert = onRevert,
            onCopyText = onCopyText,
            isAmoled = isAmoled,
        )
        MessageCardRole.SYNTHETIC -> SyntheticNotificationCard(
            currentMessage = currentMessage,
            isAmoled = isAmoled,
            onViewSubSession = onViewSubSession,
        )
        MessageCardRole.ASSISTANT -> MessageCardAssistant(
            renderableTurn = renderableTurn ?: error("renderableTurn is required for ASSISTANT role"),
            currentMessage = currentMessage,
            onViewSubSession = onViewSubSession,
            onOpenFile = onOpenFile,
            isAmoled = isAmoled,
            isTurnLast = isTurnLast,
            isStreamingTurn = isStreamingTurn,
            agents = agents,
            onCopy = onCopy,
        )
    }
}
