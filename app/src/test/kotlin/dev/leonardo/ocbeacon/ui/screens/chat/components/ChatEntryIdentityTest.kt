package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-B3 步1：Turn 条目身份字段（isUser/isStreaming）构建时编码——
 * items lambda 消除对 displayItems/turnGroups/streamingMsgId 的直接捕获
 * （每 flush 新实例捕获替换 → 全部可见 item 重组 = B3 根因之一）。
 */
class ChatEntryIdentityTest {

    private fun user(id: String, text: String = "问") = ChatMessage(
        message = Message.User(id = id, sessionId = "s1", time = TimeInfo(1, 1)),
        parts = listOf(Part.Text(id = id + "_p", sessionId = "s1", messageId = id, text = text)),
    )

    private fun assistant(id: String, text: String = "答", streaming: Boolean = false) = ChatMessage(
        message = Message.Assistant(
            id = id, sessionId = "s1",
            time = if (streaming) TimeInfo(2, null) else TimeInfo(2, 2),
            parentId = "p0",
        ),
        parts = listOf(Part.Text(id = id + "_p", sessionId = "s1", messageId = id, text = text)),
    )

    @Test
    fun `turn条目编码user身份与流式身份`() {
        // 最新在前：流式 assistant(0) / 完结 assistant(2) / user(3)
        val a1 = assistant("m_a1", streaming = true)
        val a2 = assistant("m_a2")
        val u1 = user("m_u1")
        val displayItems = listOf(0 to a1, 1 to a2, 2 to u1)
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = mapOf(0 to listOf(a1), 1 to listOf(a2)),
            streamingMsgId = "m_a1",
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
        )
        val turns = chat.entries.filterIsInstance<ChatEntry.Turn>()
        assertEquals(3, turns.size)
        val streaming = turns.first { it.displayIndex == 0 }
        assertFalse(streaming.isUser); assertTrue(streaming.isStreaming)
        val done = turns.first { it.displayIndex == 1 }
        assertFalse(done.isUser); assertFalse(done.isStreaming)
        val u = turns.first { it.displayIndex == 2 }
        assertTrue(u.isUser); assertFalse(u.isStreaming)
    }

    @Test
    fun `身份字段参与equals保持entry稳定性`() {
        val t1 = ChatEntry.Turn(displayIndex = 0, key = "k", isUser = true, isStreaming = false)
        val t2 = ChatEntry.Turn(displayIndex = 0, key = "k", isUser = true, isStreaming = false)
        assertEquals(t1, t2)
        val t3 = ChatEntry.Turn(displayIndex = 0, key = "k", isUser = false, isStreaming = true)
        assertFalse(t1 == t3)
    }
}
