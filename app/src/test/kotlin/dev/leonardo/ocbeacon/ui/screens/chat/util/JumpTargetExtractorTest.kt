package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JumpTargetExtractorTest {

    // ---- ChatMessage 辅助（findNearestUserIndexBefore 测试用） ----

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

    // ---- extractJumpTargets(MessageWithParts)（Room 全量数据源） ----

    private fun userMsgWithParts(id: String, text: String, created: Long = 0L) = MessageWithParts(
        info = Message.User(id = id, sessionId = "s1", role = "user", time = TimeInfo(created = created)),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "s1", messageId = id, text = text))
    )

    private fun syntheticMsgWithParts(id: String, text: String, created: Long = 0L) = MessageWithParts(
        info = Message.User(id = id, sessionId = "s1", role = "synthetic", time = TimeInfo(created = created)),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "s1", messageId = id, text = text))
    )

    @Test
    fun `extractJumpTargets returns user messages sorted ascending with sequential labels`() {
        // Room userMessages 返回降序（created DESC），验证内部升序排列
        val msgs = listOf(
            userMsgWithParts("u3", "third", 3000),
            userMsgWithParts("u2", "second", 2000),
            userMsgWithParts("u1", "first", 1000),
        )
        val targets = extractJumpTargets(msgs)
        assertEquals(3, targets.size)
        assertEquals(listOf("Q1", "Q2", "Q3"), targets.map { it.label })
        assertEquals(listOf("u1", "u2", "u3"), targets.map { it.msgId })
        assertEquals("first", targets[0].preview)
        assertEquals(1000L, targets[0].timestampMs)
    }

    @Test
    fun `extractJumpTargets excludes synthetic as double insurance`() {
        // SQL 层已 role='user' 过滤；此处验证纯函数双保险
        val msgs = listOf(
            userMsgWithParts("u1", "real", 1000),
            syntheticMsgWithParts("syn1", "injected", 2000),
        )
        val targets = extractJumpTargets(msgs)
        assertEquals(1, targets.size)
        assertEquals("u1", targets[0].msgId)
    }

    @Test
    fun `skips shell user messages with no text and no summary`() {
        // 2026-08-12 空壳修复：服务器历史遗留/已删除消息（Room 有记录但无 parts
        // 且无 summary.body）——直接跳过，不显示 "(无文本)" 占位
        val shell = MessageWithParts(
            info = Message.User(id = "u1", sessionId = "s1", role = "user", time = TimeInfo(created = 1000)),
            parts = emptyList(),
        )
        val targets = extractJumpTargets(listOf(shell), "(无文本)")
        assertEquals(0, targets.size)
    }

    @Test
    fun `uses summary body as preview fallback`() {
        // 无 Part.Text 但有 summary.body（Room payload 的 User 消息摘要）→ 降级
        val withSummary = MessageWithParts(
            info = Message.User(
                id = "u1",
                sessionId = "s1",
                role = "user",
                time = TimeInfo(created = 1000),
                summary = Message.User.UserSummary(body = "从 summary 提取的文本"),
            ),
            parts = emptyList(),
        )
        val targets = extractJumpTargets(listOf(withSummary))
        assertEquals(1, targets.size)
        assertEquals("从 summary 提取的文本", targets[0].preview)
    }

    @Test
    fun `labels stay sequential after filtering shells`() {
        // 过滤空壳后编号连续（Q1、Q2……不跳号）
        val msgs = listOf(
            userMsgWithParts("u1", "first", 1000),
            MessageWithParts(
                info = Message.User(id = "shell2", sessionId = "s1", role = "user", time = TimeInfo(created = 2000)),
                parts = emptyList(),
            ),
            userMsgWithParts("u3", "third", 3000),
        )
        val targets = extractJumpTargets(msgs)
        assertEquals(2, targets.size)
        assertEquals(listOf("Q1", "Q2"), targets.map { it.label })
        assertEquals(listOf("u1", "u3"), targets.map { it.msgId })
    }

    @Test
    fun `extractJumpTargets empty for no user messages`() {
        assertEquals(emptyList<JumpTarget>(), extractJumpTargets(emptyList()))
    }

    // ---- findNearestUserIndexBefore（不变） ----

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
