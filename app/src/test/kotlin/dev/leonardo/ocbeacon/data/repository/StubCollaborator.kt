package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.MergeStrategy

/**
 * 测试助手（#174）：可单点覆写的协作者桩——主 interface 保持全抽象无默认，
 * 测试侧经 lambda 字段按需定制（迁移自原 8 个 var 回调旋钮的赋值模式）。
 */
open class StubCollaborator : SessionStateCollaborator {
    var onForceCompleteSession: (String) -> Unit = {}
    var onNaturalTurnEnd: (String, String?) -> Unit = { _, _ -> }
    var hasPendingUserInputImpl: (String) -> Boolean = { false }
    var resolveDirectoryImpl: (String) -> String? = { null }
    var latestMessageIdImpl: (String) -> String? = { null }
    var hasIncompleteAssistantImpl: (String) -> Boolean = { false }
    var refreshMessagesImpl: (String, List<MessageWithParts>, MergeStrategy) -> Unit = { _, _, _ -> }
    var hasActiveChildrenImpl: (String, String) -> Boolean = { _, _ -> false }

    override fun hasIncompleteAssistant(sessionId: String) = hasIncompleteAssistantImpl(sessionId)
    override fun resolveDirectory(sessionId: String) = resolveDirectoryImpl(sessionId)
    override fun forceCompleteSession(sessionId: String) = onForceCompleteSession(sessionId)
    override fun refreshMessages(sessionId: String, messages: List<MessageWithParts>, strategy: MergeStrategy) =
        refreshMessagesImpl(sessionId, messages, strategy)
    override fun latestMessageId(sessionId: String) = latestMessageIdImpl(sessionId)
    override fun hasPendingUserInput(sessionId: String) = hasPendingUserInputImpl(sessionId)
    override fun hasActiveChildren(serverId: String, sessionId: String) = hasActiveChildrenImpl(serverId, sessionId)
    override fun onNaturalTurnEnd(sessionId: String, serverId: String?) = this.onNaturalTurnEnd.invoke(sessionId, serverId)
}
