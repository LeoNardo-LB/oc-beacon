package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #437 扣留尾部状态机（2026-09-25 重写后语义：超龄揭示永久关闭——用户裁决
 * 「未闭合构造零输出，等闭合符号来了之后再整体输出」）。visible 恒 false、
 * 恒不锁高；仅保留扣留计时与复位语义供观测。
 */
class HeldTailAgingTest {

    @Test
    fun `扣留期不可见且不锁高`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held text", 40)
        t = 299
        s.update("held text more", 52)
        assertFalse(s.visible)
        assertEquals(-1, s.lockedHeightPx)
    }

    @Test
    fun `超龄后仍不可见`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 40)
        t = 300
        s.update("held", 40)
        t = 100_000
        s.update("held", 40)
        assertFalse(s.visible)
        assertEquals(-1, s.lockedHeightPx)
        assertEquals(100_000L, s.heldForMs) // 计时保留供观测
    }

    @Test
    fun `自然高照常观测`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 40)
        t = 300; s.update("held+", 96)
        assertEquals(96, s.lastNaturalHeightPx)
        assertEquals(-1, s.lockedHeightPx)
    }

    @Test
    fun `扣留清空复位并重新计时`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 40)
        t = 1_000; s.update("held", 40)
        assertEquals(1_000L, s.heldForMs)
        s.update("", 0)                     // 全部毕业
        assertFalse(s.visible)
        assertEquals(-1, s.lockedHeightPx)
        assertEquals(0L, s.heldForMs)
        t = 2_000; s.update("new held", 30) // 重新计时
        assertEquals(0L, s.heldForMs)
    }

    @Test
    fun `自定义阈值注入不再触发可见`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t }, revealAfterMs = 100, heightRefreshMs = 50)
        s.update("held", 10)
        t = 100; s.update("held", 10)
        t = 150; s.update("held+", 20)
        assertFalse(s.visible)
        assertEquals(-1, s.lockedHeightPx)
    }
}
