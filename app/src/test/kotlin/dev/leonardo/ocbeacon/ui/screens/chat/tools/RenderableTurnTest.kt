package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenderableTurnTest {

    private fun assistantMsg(id: String, created: Long, completed: Long?) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = created, completed = completed),
            parentId = "",
            modelId = "test-model"
        ),
        parts = emptyList()
    )

    private fun compute(msgs: List<ChatMessage>): RenderableTurn =
        computeRenderableTurn(msgs, msgs.last(), true) { null }

    @Test
    fun `single completed message duration equals its own span`() {
        val t = compute(listOf(assistantMsg("a1", 1000L, 5000L)))
        assertEquals(4000L, t.durationMs)
        assertEquals(1000L, t.turnStartMs)
    }

    @Test
    fun `multi-message turn duration spans first created to last completed`() {
        val msgs = listOf(assistantMsg("a1", 1000L, 2000L), assistantMsg("a2", 2500L, 8000L))
        val t = compute(msgs)
        assertEquals(7000L, t.durationMs)      // 8000 - 1000
        assertEquals(1000L, t.turnStartMs)     // 首条 created，而非代表消息 a2 的 2500
    }

    @Test
    fun `streaming turn has null duration but stable turnStartMs`() {
        val msgs = listOf(assistantMsg("a1", 1000L, 2000L), assistantMsg("a2", 2500L, null))
        val t = compute(msgs)
        assertNull(t.durationMs)               // a2 未完成 → 交给流式 ticker
        assertEquals(1000L, t.turnStartMs)
    }

    @Test
    fun `single streaming message has null duration`() {
        val t = compute(listOf(assistantMsg("a1", 1000L, null)))
        assertNull(t.durationMs)
        assertEquals(1000L, t.turnStartMs)
    }
}
