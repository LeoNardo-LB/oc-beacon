package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.SseEvent
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
    onAgentClick: ((String) -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onLocateTask: ((String) -> Unit)? = null,
    /** 嵌入思考卡片的待处理提问（按 tool.messageId 匹配，2026-08-14）。 */
    pendingQuestion: SseEvent.QuestionAsked? = null,
    onQuestionSubmit: ((String, List<List<String>>) -> Unit)? = null,
    onQuestionReject: ((String) -> Unit)? = null,
    /** E2E-C 终版：应用级答案存储透传（QuestionAnswerStore 单例） */
    questionAnswersCache: dev.leonardo.ocbeacon.ui.screens.chat.QuestionAnswerStore? = null,
    /** #234：事件卡统一展开表（屏幕级，#227 模式）——synthetic 卡与 assistant
     *  turn 内防御性 RenderItem.SyntheticNotice 渲染共用同一记忆。 */
    eventExpandedStates: MutableMap<String, Boolean>,
    /** #241 标签行保护：synthetic 事件卡渲染前补偿透传（LazyListState）。 */
    eventRevealListState: LazyListState? = null,
    /** #243 连续同内容去重：本卡代表的被抑制重复数（0=无）。 */
    eventDupCount: Int = 0,
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
            onViewSubSession = onViewSubSession,
            onLocateTask = onLocateTask,
            eventExpandedStates = eventExpandedStates,
            expandRevealListState = eventRevealListState,
            dupCount = eventDupCount,
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
            onAgentClick = onAgentClick,
            onCopy = onCopy,
            onLocateTask = onLocateTask,
            pendingQuestion = pendingQuestion,
            onQuestionSubmit = onQuestionSubmit,
            onQuestionReject = onQuestionReject,
            questionAnswersCache = questionAnswersCache,
            eventExpandedStates = eventExpandedStates,
        )
    }
}
