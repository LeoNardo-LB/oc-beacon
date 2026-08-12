package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
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

    private fun syntheticMsg(id: String) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "test-session",
            role = "synthetic",
            time = TimeInfo(created = 800L)
        ),
        parts = emptyList()
    )

    @Test
    fun `synthetic between assistants splits turns`() {
        // 2026-08-12 用户决策：synthetic 独立气泡——不并入 assistant turn，
        // 也不合并两侧 assistant（turn = 连续 assistant 序列）
        val msgs = listOf(assistantMsg("a1"), syntheticMsg("s1"), assistantMsg("a2"))
        val result = computeTurnGroups(msgs)
        assertEquals(listOf(msgs[0]), result[0])
        assertEquals(null, result[1])
        assertEquals(listOf(msgs[2]), result[2])
    }

    @Test
    fun `synthetic after assistant stays independent`() {
        // 2026-08-12 用户决策：synthetic 不再并入 assistant turn
        val msgs = listOf(assistantMsg("a1"), syntheticMsg("s1"))
        val result = computeTurnGroups(msgs)
        assertEquals(listOf(msgs[0]), result[0])
        assertEquals(null, result[1])
    }

    @Test
    fun `isolated synthetic stays independent`() {
        // 前后都无 assistant → 独立条目（不并入任何 turn）
        val msgs = listOf(userMsg("u1"), syntheticMsg("s1"), userMsg("u2"))
        val result = computeTurnGroups(msgs)
        assertEquals(null, result[1])
    }
}
