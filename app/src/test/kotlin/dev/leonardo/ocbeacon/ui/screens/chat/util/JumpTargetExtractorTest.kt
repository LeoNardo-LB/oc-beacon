package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JumpTargetExtractorTest {

    private fun userMsg(id: String, text: String, created: Long = 0L) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "s1",
            role = "user",
            time = TimeInfo(created = created)
        ),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "s1", messageId = id, text = text))
    )

    private fun assistantMsg(id: String, created: Long = 0L) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "s1",
            role = "assistant",
            time = TimeInfo(created = created),
            parentId = "parent"
        ),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "s1", messageId = id, text = "response"))
    )

    private fun syntheticMsg(id: String, text: String, created: Long = 0L) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "s1",
            role = "synthetic",
            time = TimeInfo(created = created)
        ),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "s1", messageId = id, text = text))
    )

    @Test
    fun `extractJumpTargets returns only user messages with sequential Q labels`() {
        val msgs = listOf(
            userMsg("u1", "hello", 1000),
            assistantMsg("a1", 2000),
            userMsg("u2", "world", 3000),
            assistantMsg("a2", 4000)
        )
        val targets = extractJumpTargets(msgs)
        assertEquals(2, targets.size)
        assertEquals("Q1", targets[0].label)
        assertEquals("Q2", targets[1].label)
        assertEquals("hello", targets[0].preview)
        assertEquals("world", targets[1].preview)
        assertEquals(1000L, targets[0].timestampMs)
        assertEquals("u1", targets[0].msgId)
        assertEquals(0, targets[0].rawIndex)
        assertEquals(2, targets[1].rawIndex)
    }

    @Test
    fun `extractJumpTargets sorts by time ascending even when input is newest-first`() {
        // rawMessages in production is newest-first (see ChatScreen.kt:993)
        val msgs = listOf(
            userMsg("u2", "world", 3000),      // newest, rawIndex 0
            assistantMsg("a2", 4000),
            userMsg("u1", "hello", 1000),      // oldest, rawIndex 2
            assistantMsg("a1", 2000)
        )
        val targets = extractJumpTargets(msgs)
        assertEquals(2, targets.size)
        // Q1 = oldest (u1, created=1000) regardless of input order
        assertEquals("Q1", targets[0].label)
        assertEquals("hello", targets[0].preview)
        assertEquals(1000L, targets[0].timestampMs)
        assertEquals("u1", targets[0].msgId)
        assertEquals(2, targets[0].rawIndex)
        // Q2 = newest (u2)
        assertEquals("Q2", targets[1].label)
        assertEquals("world", targets[1].preview)
        assertEquals(0, targets[1].rawIndex)
    }

    @Test
    fun `extractJumpTargets excludes synthetic user messages`() {
        // synthetic 完成通知（role="synthetic"）并入 assistant turn 组，
        // 不在 displayItems 独立存在 —— 跳转会找不到目标（2026-08-12 根因修复）
        val msgs = listOf(
            userMsg("u1", "real question", 1000),
            assistantMsg("a1", 2000),
            syntheticMsg("syn1", "subagent completed", 3000),
            userMsg("u2", "another question", 4000)
        )
        val targets = extractJumpTargets(msgs)
        assertEquals(2, targets.size)
        assertEquals(listOf("u1", "u2"), targets.map { it.msgId })
        assertEquals(listOf("Q1", "Q2"), targets.map { it.label })
    }

    @Test
    fun `extractJumpTargets uses placeholder when text is blank`() {
        val msgs = listOf(userMsg("u1", "   ", 1000))
        val targets = extractJumpTargets(msgs)
        assertEquals(1, targets.size)
        assertEquals("(无文本)", targets[0].preview)
    }

    @Test
    fun `extractJumpTargets empty when no user messages`() {
        val msgs = listOf(assistantMsg("a1"), assistantMsg("a2"))
        assertEquals(emptyList<JumpTarget>(), extractJumpTargets(msgs))
    }

    @Test
    fun `findNearestUserIndexBefore returns self when input is user`() {
        val msgs = listOf(userMsg("u1", "q1"), assistantMsg("a1"), userMsg("u2", "q2"))
        assertEquals(0, findNearestUserIndexBefore(msgs, 0))
        assertEquals(2, findNearestUserIndexBefore(msgs, 2))
    }

    @Test
    fun `findNearestUserIndexBefore walks back to nearest user`() {
        val msgs = listOf(
            userMsg("u1", "q1"),      // 0
            assistantMsg("a1"),        // 1
            assistantMsg("a2"),        // 2
            userMsg("u2", "q2"),      // 3
            assistantMsg("a3")         // 4
        )
        assertEquals(0, findNearestUserIndexBefore(msgs, 1))
        assertEquals(0, findNearestUserIndexBefore(msgs, 2))
        assertEquals(3, findNearestUserIndexBefore(msgs, 3))
        assertEquals(3, findNearestUserIndexBefore(msgs, 4))
    }

    @Test
    fun `findNearestUserIndexBefore null when no user at or before`() {
        val msgs = listOf(assistantMsg("a1"), assistantMsg("a2"))
        assertNull(findNearestUserIndexBefore(msgs, 0))
        assertNull(findNearestUserIndexBefore(msgs, 1))
    }

    @Test
    fun `findNearestUserIndexBefore null for out of bounds`() {
        val msgs = listOf(userMsg("u1", "q1"))
        assertNull(findNearestUserIndexBefore(msgs, -1))
        assertNull(findNearestUserIndexBefore(msgs, 5))
    }
}
