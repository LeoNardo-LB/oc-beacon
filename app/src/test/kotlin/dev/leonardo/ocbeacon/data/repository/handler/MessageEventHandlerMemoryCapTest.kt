package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #95（H-4 泄漏）：消息热视图内存上限——与 Room SESSION_MESSAGE_LIMIT=1000 对齐。
 * MessageEventHandler 是 @Singleton，活跃会话的 _messages/_parts 无上限时
 * 长会话 + 多会话可达数百 MB。超出上限后保留最新 1000 条，
 * 被裁剪消息的 parts 与 assistantMessageIds 同步清理。
 */
class MessageEventHandlerMemoryCapTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    private fun userMsg(id: String, created: Long) = Message.User(
        id = id,
        sessionId = "s1",
        time = TimeInfo(created = created)
    )

    @Test
    fun `session messages capped at 1000 newest`() {
        val total = 1005
        for (i in 0 until total) {
            handler.handleMessageUpdated(SseEvent.MessageUpdated(userMsg("m" + i, created = 1000L + i)))
        }
        val messages = handler.messages.value["s1"].orEmpty()
        assertTrue("memory hot view must not exceed 1000 (#95), got " + messages.size,
            messages.size <= MessageEventHandler.MEMORY_SESSION_MESSAGE_LIMIT)
        assertEquals(1000, messages.size)
        // keep newest: m5..m1004 (oldest m0..m4 trimmed)
        assertEquals("m5", messages.first().id)
        assertEquals("m1004", messages.last().id)
    }

    @Test
    fun `trimmed messages drop their parts`() {
        for (i in 0 until 1001) {
            handler.handleMessageUpdated(SseEvent.MessageUpdated(userMsg("m" + i, created = 1000L + i)))
            if (i == 0) {
                handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(
                    Part.Text(id = "p0", sessionId = "s1", messageId = "m0", text = "oldest")
                ))
            }
        }
        assertTrue("parts of trimmed messages must not linger (#95 leak)",
            handler.parts.value["m0"] == null)
        assertEquals(1000, handler.messages.value["s1"].orEmpty().size)
    }

    @Test
    fun `small sessions unaffected`() {
        for (i in 0 until 50) {
            handler.handleMessageUpdated(SseEvent.MessageUpdated(userMsg("m" + i, created = 1000L + i)))
        }
        assertEquals(50, handler.messages.value["s1"].orEmpty().size)
    }
}