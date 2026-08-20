package dev.leonardo.ocbeacon.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrameStatsWindow 单测（2026-08-20 第三轮性能检测系统）。
 */
class FrameStatsWindowTest {

    @Test
    fun jankClassificationAgainstBudget() {
        val w = FrameStatsWindow(capacity = 10, frameBudgetMs = 8.33)
        assertFalse(w.record(5.0))
        assertTrue(w.record(8.34))
        assertTrue(w.record(20.0))
        assertEquals(2L, w.totalJank)
    }

    @Test
    fun percentilesOnSimpleWindow() {
        val w = FrameStatsWindow(capacity = 10, frameBudgetMs = 100.0)
        doubleArrayOf(2.0, 4.0, 6.0, 8.0, 10.0).forEach { w.record(it) }
        val s = w.snapshot()
        assertEquals(6.0, s.p50, 0.001)
        assertEquals(10.0, s.p95, 0.6) // 插值介于 9.2-10
        assertEquals(10.0, s.max, 0.001)
        assertEquals(0.0, s.overBudgetPct, 0.001)
        assertEquals(5, s.frames)
    }

    @Test
    fun ringBufferEvictionKeepsLatest() {
        val w = FrameStatsWindow(capacity = 3, frameBudgetMs = 100.0)
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0).forEach { w.record(it) }
        val s = w.snapshot()
        assertEquals(3, s.frames)
        assertEquals(5.0, s.max, 0.001)
        assertEquals(4.0, s.p50, 0.001) // 窗口 = [3,4,5]
    }

    @Test
    fun overBudgetPctOverWindow() {
        val w = FrameStatsWindow(capacity = 100, frameBudgetMs = 8.33)
        repeat(60) { w.record(9.0) }
        repeat(40) { w.record(5.0) }
        assertEquals(60.0, w.snapshot().overBudgetPct, 0.001)
    }

    @Test
    fun emptyWindowSnapshotIsZeroed() {
        val w = FrameStatsWindow(capacity = 5, frameBudgetMs = 8.33)
        val s = w.snapshot()
        assertEquals(0, s.frames)
        assertEquals(0.0, s.p50, 0.001)
        assertEquals(0L, w.totalJank)
    }
}