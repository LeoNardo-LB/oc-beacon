package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextStatsTest {

    private fun userMsg(id: String, textLen: Int) = MessageWithParts(
        Message.User(
            id = id,
            sessionId = "s",
            time = TimeInfo(created = 0)
        ),
        listOf(Part.Text("p-$id", "s", id, "x".repeat(textLen)))
    )

    private fun assistantMsg(id: String, textLen: Int = 0, toolOutputLen: Int = 0) = MessageWithParts(
        Message.Assistant(
            id = id,
            sessionId = "s",
            time = TimeInfo(created = 0),
            parentId = "p-$id"
        ),
        buildList {
            if (textLen > 0) add(Part.Text("t-$id", "s", id, "y".repeat(textLen)))
            if (toolOutputLen > 0) add(
                Part.Tool(
                    "tl-$id", "s", id, "c-$id", "read",
                    ToolState.Completed(output = "z".repeat(toolOutputLen))
                )
            )
        }
    )

    @Test
    fun `breakdown estimates tokens as chars divided by 4`() {
        // 用户文本 40 字符 -> 10 tokens
        val msgs = listOf(userMsg("u1", 40))
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val userSeg = b.segments.first { it.role == BreakdownRole.USER }
        assertEquals(10, userSeg.estimatedTokens)
    }

    @Test
    fun `other absorbs difference from real input`() {
        val msgs = listOf(userMsg("u1", 40))  // 10 tokens 用户
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val otherSeg = b.segments.first { it.role == BreakdownRole.OTHER }
        assertEquals(90, otherSeg.estimatedTokens)  // 100 - 10
    }

    @Test
    fun `other is zero when estimate exceeds input`() {
        val msgs = listOf(userMsg("u1", 4000))  // 1000 tokens
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val otherSeg = b.segments.firstOrNull { it.role == BreakdownRole.OTHER }
        // other = max(0, 100 - 1000) = 0 -> 被过滤掉
        assertNull(otherSeg)
    }

    @Test
    fun `percent is tokens over real input`() {
        val msgs = listOf(userMsg("u1", 40))
        val b = estimateContextBreakdown(msgs, realInput = 200)
        val userSeg = b.segments.first { it.role == BreakdownRole.USER }
        assertEquals(0.05f, userSeg.percent, 0.001f)  // 10/200
    }

    @Test
    fun `percents normalized when estimate exceeds input`() {
        // user 4000 字符=1000 tok, assistant 3640 字符=910 tok, tool 29200 字符=7300 tok
        // estimated = 9210, realInput = 1958 -> 分母 = estimated = 9210
        val msgs = listOf(
            userMsg("u1", 4000),
            assistantMsg("a1", textLen = 3640, toolOutputLen = 29200)
        )
        val b = estimateContextBreakdown(msgs, realInput = 1958)

        // 没有 other 分段（estimated > input）
        assertNull(b.segments.firstOrNull { it.role == BreakdownRole.OTHER })

        // 每个分段 ≤ 1.0
        for (seg in b.segments) {
            assert(seg.percent <= 1.0f) { "${seg.role} percent ${seg.percent} > 1.0" }
        }

        // 所有分段之和约为 ~1.0（100%）
        val total = b.segments.sumOf { it.percent.toDouble() }
        assertEquals(1.0, total, 0.01)
    }

    @Test
    fun `countMessages splits user and assistant`() {
        val msgs = listOf(userMsg("u1", 1), assistantMsg("a1"), assistantMsg("a2"))
        val c = countMessages(msgs.map { it.info })
        assertEquals(1, c.user)
        assertEquals(2, c.assistant)
    }

    @Test
    fun `cacheHitRate is cacheRead over total reads`() {
        assertEquals(0.5f, cacheHitRate(cacheRead = 50, input = 50)!!, 0.001f)
    }

    @Test
    fun `cacheHitRate returns 0 when no cache`() {
        assertEquals(0f, cacheHitRate(cacheRead = 0, input = 100)!!, 0.001f)
    }

    @Test
    fun `cacheHitRate returns null when both zero`() {
        assertNull(cacheHitRate(cacheRead = 0, input = 0))
    }
}
