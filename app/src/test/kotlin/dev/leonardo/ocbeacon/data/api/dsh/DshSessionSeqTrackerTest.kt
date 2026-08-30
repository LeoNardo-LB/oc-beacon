package dev.leonardo.ocbeacon.data.api.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DshSessionSeqTracker 测试（backlog #276 步骤⑤；本地已应用 seq 水位表）。
 */
class DshSessionSeqTrackerTest {

    @Test
    fun `applied seq is monotonic max per session`() {
        val tracker = DshSessionSeqTracker()
        tracker.applied("s1", 10L)
        tracker.applied("s1", 7L) // 乱序/重放旧事件不回退水位
        tracker.applied("s1", 12L)
        tracker.applied("s2", 3L)
        assertEquals(12L, tracker.get("s1"))
        assertEquals(3L, tracker.get("s2"))
        assertEquals(mapOf("s1" to 12L, "s2" to 3L), tracker.snapshot())
    }

    @Test
    fun `unknown session has null watermark`() {
        assertNull(DshSessionSeqTracker().get("nope"))
    }

    @Test
    fun `remove clears per-session watermark`() {
        val tracker = DshSessionSeqTracker()
        tracker.applied("s1", 5L)
        tracker.remove("s1")
        assertNull(tracker.get("s1"))
        assertEquals(emptyMap<String, Long>(), tracker.snapshot())
    }

    @Test
    fun `snapshot is a detached copy`() {
        val tracker = DshSessionSeqTracker()
        tracker.applied("s1", 1L)
        val snap = tracker.snapshot()
        tracker.applied("s1", 2L)
        assertEquals(1L, snap["s1"]) // 快照不被后续推进污染
    }
}
