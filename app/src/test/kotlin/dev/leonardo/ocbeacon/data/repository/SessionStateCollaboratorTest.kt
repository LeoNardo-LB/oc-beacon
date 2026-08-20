package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

/**
 * #174 接线完整性：SessionStateCollaboratorImpl 的 8 方法 = 原 EventDispatcher.init
 * 接线块的逐条行为等价（迁移零变更的回归守卫）。
 */
class SessionStateCollaboratorTest {

    private val messageHandler = MessageEventHandler()
    private val sessionHandler = SessionEventHandler()
    private val questionHandler = QuestionEventHandler()
    private val permissionHandler = PermissionEventHandler()
    private val unread = mockk<UnreadBadgeService>(relaxed = true)

    private fun impl() = SessionStateCollaboratorImpl(
        messageHandler = messageHandler,
        sessionHandler = sessionHandler,
        questionHandler = questionHandler,
        permissionHandler = permissionHandler,
        unreadBadgeService = unread,
        sessionRepoProvider = Provider { mockk(relaxed = true) },
        pendingMessagePipelineProvider = Provider { mockk(relaxed = true) },
    )

    private fun assistant(id: String, sessionId: String, completed: Long?): MessageWithParts =
        MessageWithParts(
            Message.Assistant(id = id, sessionId = sessionId, time = TimeInfo(created = 100L, completed = completed), parentId = ""),
            emptyList(),
        )

    @Test
    fun `hasIncompleteAssistant reflects streaming state of message cache`() {
        val c = impl()
        messageHandler.upsertMessages("s1", listOf(assistant("m1", "s1", completed = null)), MergeStrategy.APPEND_ONLY)
        assertTrue(c.hasIncompleteAssistant("s1"))
        messageHandler.upsertMessages("s1", listOf(assistant("m1", "s1", completed = 500L)), MergeStrategy.SSE_PRIORITY)
        assertFalse(c.hasIncompleteAssistant("s1"))
    }

    @Test
    fun `resolveDirectory returns null for unknown session`() {
        assertNull(impl().resolveDirectory("unknown"))
    }

    @Test
    fun `forceCompleteSession marks idle and persists unread watermark`() {
        val c = impl()
        messageHandler.upsertMessages("s1", listOf(assistant("m1", "s1", completed = null)), MergeStrategy.APPEND_ONLY)
        c.forceCompleteSession("s1")
        // 消息被终结（展示域客户端戳——红点域不读，#171）
        assertFalse(c.hasIncompleteAssistant("s1"))
        // 落盘兜底触发
        verify(exactly = 1) { unread.persistAsync() }
    }

    @Test
    fun `refreshMessages delegates to message cache upsert`() {
        val c = impl()
        c.refreshMessages("s1", listOf(assistant("m1", "s1", completed = 1L)), MergeStrategy.REST_AUTHORITY)
        assertEquals(1, messageHandler.messages.value["s1"]?.size)
    }

    @Test
    fun `latestMessageId returns newest by created`() {
        val c = impl()
        messageHandler.upsertMessages("s1", listOf(assistant("old", "s1", completed = 1L)), MergeStrategy.APPEND_ONLY)
        messageHandler.upsertMessages(
            "s1",
            listOf(MessageWithParts(
                Message.Assistant(id = "new", sessionId = "s1", time = TimeInfo(created = 200L, completed = 201L), parentId = ""),
                emptyList(),
            )),
            MergeStrategy.APPEND_ONLY,
        )
        assertEquals("new", c.latestMessageId("s1"))
    }

    @Test
    fun `hasPendingUserInput false without questions or permissions`() {
        assertFalse(impl().hasPendingUserInput("s1"))
    }

    @Test
    fun `onNaturalTurnEnd delegates to pending pipeline`() {
        val pipeline = mockk<PendingMessagePipeline>(relaxed = true)
        val c = SessionStateCollaboratorImpl(
            messageHandler = messageHandler,
            sessionHandler = sessionHandler,
            questionHandler = questionHandler,
            permissionHandler = permissionHandler,
            unreadBadgeService = unread,
            sessionRepoProvider = Provider { mockk(relaxed = true) },
            pendingMessagePipelineProvider = Provider { pipeline },
        )
        c.onNaturalTurnEnd("s1", "svr1")
        verify(exactly = 1) { pipeline.onNaturalTurnEnd("s1", "svr1") }
    }
}
