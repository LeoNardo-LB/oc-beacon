package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.UserMsgStatus
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn

enum class MessageCardRole { USER, ASSISTANT }

@Composable
internal fun MessageCard(
    role: MessageCardRole,
    currentMessage: ChatMessage,
    isQueued: Boolean = false,
    pendingStatus: UserMsgStatus? = null,
    onRetry: (() -> Unit)? = null,
    renderableTurn: RenderableTurn? = null,
    onViewSubSession: ((String) -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    isAmoled: Boolean = false,
    isTurnLast: Boolean = false,
    agents: List<AgentInfo> = emptyList(),
    onCopy: (() -> Unit)? = null,
) {
    when (role) {
        MessageCardRole.USER -> MessageCardUser(
            currentMessage = currentMessage,
            isQueued = isQueued,
            pendingStatus = pendingStatus,
            onRetry = onRetry,
            onRevert = onRevert,
            onCopyText = onCopyText,
            isAmoled = isAmoled,
        )
        MessageCardRole.ASSISTANT -> MessageCardAssistant(
            renderableTurn = renderableTurn ?: error("renderableTurn is required for ASSISTANT role"),
            currentMessage = currentMessage,
            onViewSubSession = onViewSubSession,
            onOpenFile = onOpenFile,
            isAmoled = isAmoled,
            isTurnLast = isTurnLast,
            agents = agents,
            onCopy = onCopy,
        )
    }
}
