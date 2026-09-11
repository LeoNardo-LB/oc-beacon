package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #394：跳转高亮键与 Lazy item key 同源回归。
 *
 * 失效根因 = 高亮曾用 rawMessages[displayIndex+1] 推导 assistant 的 t_ 键，而渲染用
 * turnGroups[rawIndex].first() —— 两处公式漂移导致 assistant 目标永不命中高亮。
 */
class ChatEntryKeyTest {

    private fun user(id: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "s", time = TimeInfo(0L)),
        parts = emptyList(),
    )

    private fun assistant(id: String) = ChatMessage(
        message = Message.Assistant(
            id = id, sessionId = "s", time = TimeInfo(0L), parentId = "",
        ),
        parts = emptyList(),
    )

    @Test
    fun `user 目标取消息自身 id 键`() {
        val turn = listOf(user("u1"), assistant("a1"))
        assertEquals("u_u1", chatEntryKey(mapOf(0 to turn), 0, turn[0]))
    }

    @Test
    fun `assistant 目标取该轮首条消息 id 键`() {
        val turn = listOf(user("u1"), assistant("a1"))
        assertEquals("t_u1", chatEntryKey(mapOf(0 to turn), 0, turn[1]))
    }

    @Test
    fun `无 turn 组时 assistant 退化为自身 id`() {
        val msg = assistant("a9")
        assertEquals("t_a9", chatEntryKey(emptyMap(), 3, msg))
    }
}
