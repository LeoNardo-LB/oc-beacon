package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #378 CompactionFolder 配对纯函数——compactionId 幂等折叠 + 孤儿事件自建。
 */
class CompactionFolderTest {

    private fun start(cid: String, seq: Long, time: Long = 100) =
        SseEvent.CompactionStarted(sessionId = "s1", compactionId = cid, sourceCommandId = "cmd-1", seq = seq, time = time)

    private fun summary(cid: String, text: String, seq: Long, time: Long = 150) =
        SseEvent.CompactionSummary(sessionId = "s1", compactionId = cid, summaryText = text, seq = seq, time = time)

    private fun finished(cid: String, error: String? = null, seq: Long, time: Long = 200) =
        SseEvent.CompactionFinished(sessionId = "s1", compactionId = cid, error = error, seq = seq, time = time)

    private fun bound(cid: String, messageId: String, seq: Long, time: Long = 160) =
        SseEvent.CompactionSurfaceBound(sessionId = "s1", compactionId = cid, messageId = messageId, seq = seq, time = time)
   
    @Test
    fun `start builds entry and replay keeps later facts`() {
        var states = CompactionFolder.onStarted(emptyList(), start("c1", 10))
        states = CompactionFolder.onSummary(states, summary("c1", "全文", 11))
        states = CompactionFolder.onFinished(states, finished("c1", seq = 12))
        // 重放 start：原位替换但已到达事实（摘要/终态）不回退
        states = CompactionFolder.onStarted(states, start("c1", 10))
        assertEquals(1, states.size)
        assertEquals("全文", states[0].summaryText)
        assertEquals(200L, states[0].finishedAt)
    }

    @Test
    fun `full lifecycle folds to single finished entry with surface binding`() {
        var states = CompactionFolder.onStarted(emptyList(), start("c1", seq = 5390))
        states = CompactionFolder.onSummary(states, summary("c1", "## 摘要", seq = 5391))
        states = CompactionFolder.onSurfaceBound(states, bound("c1", "seq-5392", seq = 5392))
        states = CompactionFolder.onFinished(states, finished("c1", seq = 5393))

        assertEquals(listOf(CompactionEntry(
            compactionId = "c1", sourceCommandId = "cmd-1", seq = 5390, startedAt = 100,
            summaryText = "## 摘要", messageId = "seq-5392", finishedAt = 200L, error = null,
        )), states)
        assertTrue(states[0].isFinished)
    }

    @Test
    fun `failed compaction keeps error text`() {
        var states = CompactionFolder.onStarted(emptyList(), start("c1", 10))
        states = CompactionFolder.onFinished(states, finished("c1", error = "boom", seq = 12))
        assertTrue(states.single().isFailed)
        assertEquals("boom", states.single().error)
    }

    @Test
    fun `orphan summary without start self-builds entry`() {
        val states = CompactionFolder.onSummary(emptyList(), summary("c9", "孤儿摘要", seq = 30))
        assertEquals(1, states.size)
        assertEquals("孤儿摘要", states[0].summaryText)
        assertEquals(30L, states[0].seq)
    }

    @Test
    fun `orphan finish without start self-builds terminal entry`() {
        val states = CompactionFolder.onFinished(emptyList(), finished("c8", seq = 40))
        val e = states.single()
        assertTrue(e.isFinished)
        assertNull(e.summaryText)
    }

    @Test
    fun `multiple compactions insert by seq ascending`() {
        var states = CompactionFolder.onStarted(emptyList(), start("c2", 500))
        states = CompactionFolder.onStarted(states, start("c1", 100))
        states = CompactionFolder.onStarted(states, start("c3", 900))
        assertEquals(listOf("c1", "c2", "c3"), states.map { it.compactionId })
    }

    @Test
    fun `replay whole sequence is fully idempotent`() {
        fun full(s: List<CompactionEntry>) = s
            .let { CompactionFolder.onStarted(it, start("c1", 10)) }
            .let { CompactionFolder.onSummary(it, summary("c1", "X", 11)) }
            .let { CompactionFolder.onSurfaceBound(it, bound("c1", "seq-12", 12)) }
            .let { CompactionFolder.onFinished(it, finished("c1", seq = 13)) }
        val once = full(emptyList())
        val twice = full(once)
        assertEquals(once, twice)
    }
}
