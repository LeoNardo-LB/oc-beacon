package dev.leonardo.octether.ui.screens.chat.util

import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.TimeInfo
import dev.leonardo.octether.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnGroupCalculatorTest {

    private fun assistantMsg(id: String) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = 1000L, completed = 2000L),
            parentId = "",
            modelId = "test-model"
        ),
        parts = emptyList()
    )

    private fun userMsg(id: String) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = 500L)
        ),
        parts = emptyList()
    )

    @Test
    fun `empty messages returns empty map`() {
        val result = computeTurnGroups(emptyList())
        assertEquals(emptyMap<Int, List<ChatMessage>>(), result)
    }

    @Test
    fun `single assistant message returns one group`() {
        val msgs = listOf(assistantMsg("a1"))
        val result = computeTurnGroups(msgs)
        assertEquals(1, result.size)
        assertEquals(listOf(msgs[0]), result[0])
    }

    @Test
    fun `three consecutive assistants grouped as one turn`() {
        val msgs = listOf(assistantMsg("a1"), assistantMsg("a2"), assistantMsg("a3"))
        val result = computeTurnGroups(msgs)
        assertEquals(3, result.size)
        assertEquals(msgs, result[0])
        assertEquals(msgs, result[1])
        assertEquals(msgs, result[2])
    }

    @Test
    fun `mixed user and assistant correct grouping`() {
        val msgs = listOf(
            userMsg("u1"), assistantMsg("a1"), assistantMsg("a2"),
            userMsg("u2"), assistantMsg("a3")
        )
        val result = computeTurnGroups(msgs)
        assertEquals(listOf(msgs[1], msgs[2]), result[1])
        assertEquals(listOf(msgs[1], msgs[2]), result[2])
        assertEquals(listOf(msgs[4]), result[4])
        assertEquals(null, result[0])
        assertEquals(null, result[3])
    }

    @Test
    fun `only user messages returns empty map`() {
        val msgs = listOf(userMsg("u1"), userMsg("u2"))
        val result = computeTurnGroups(msgs)
        assertEquals(emptyMap<Int, List<ChatMessage>>(), result)
    }
}
