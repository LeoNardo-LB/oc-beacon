package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * #224：V1 压缩消息归一化（分割线形态统一）。
 */
class CompactionNormalizerTest {

    private fun compactionMessage(
        completed: Long? = 1000L,
        error: Message.Assistant.ErrorInfo? = null,
        text: String = "## Objective\n- 测试摘要",
    ) = MessageWithParts(
        info = Message.Assistant(
            id = "m1",
            sessionId = "s1",
            role = "assistant",
            time = TimeInfo(created = 900, completed = completed),
            parentId = "",
            agent = "compaction",
            error = error,
        ),
        parts = listOf(
            Part.StepStart(id = "p0", sessionId = "s1", messageId = "m1", snapshot = "x"),
            Part.Reasoning(id = "p1", sessionId = "s1", messageId = "m1", text = "thinking"),
            Part.Text(id = "p2", sessionId = "s1", messageId = "m1", text = text),
            Part.StepFinish(id = "p3", sessionId = "s1", messageId = "m1"),
        )
    )

    @Test
    fun `completed v1 compaction collapses to single Part_Compaction`() {
        val out = CompactionNormalizer.normalize(compactionMessage())
        assertEquals(1, out.parts.size)
        val part = out.parts[0] as Part.Compaction
        assertEquals("## Objective\n- 测试摘要", part.summary)
        assertFalse(part.failed)
        assertEquals("m1", part.messageId)
    }

    @Test
    fun `error compaction maps to failed divider`() {
        val out = CompactionNormalizer.normalize(
            compactionMessage(error = Message.Assistant.ErrorInfo(name = "APIError"))
        )
        val part = out.parts[0] as Part.Compaction
        assertTrue(part.failed)
    }

    @Test
    fun `inflight compaction stays untouched`() {
        val out = CompactionNormalizer.normalize(compactionMessage(completed = null))
        assertEquals(4, out.parts.size)
    }

    @Test
    fun `non-compaction assistant and user pass through`() {
        val normal = MessageWithParts(
            info = Message.Assistant(
                id = "m2", sessionId = "s1", role = "assistant",
                time = TimeInfo(created = 1, completed = 2), parentId = "", agent = "build",
            ),
            parts = listOf(Part.Text(id = "t", sessionId = "s1", messageId = "m2", text = "hi"))
        )
        assertSame(normal, CompactionNormalizer.normalize(normal))
    }

    @Test
    fun `blank text summary passes through`() {
        val out = CompactionNormalizer.normalize(compactionMessage(text = "   "))
        assertEquals(4, out.parts.size)
    }
}
