package dev.leonardo.ocbeacon.ui.screens.chat.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BusyIndicatorSmoother] 单测（2026-08-17 修复：busy 指示闪烁）。
 *
 * 覆盖三条核心契约：
 * 1. true 立即传导（busy 或 sending 任一为 true）
 * 2. false 需持续稳定 releaseDelayMs 才传导
 * 3. 释放等待期间又变 true → 挂起的 false 不传导
 */
class BusyIndicatorSmootherTest {

    private val delayMs = 2_500L

    @Test
    fun `busy true 传递立即`() {
        val s = BusyIndicatorSmoother(delayMs)
        // 初始 false
        assertFalse(s.update(busy = false, sending = false, nowMs = 0))
        // busy 上升沿：立即 true
        assertTrue(s.update(busy = true, sending = false, nowMs = 10))
        // 持续 busy：保持 true
        assertTrue(s.update(busy = true, sending = false, nowMs = 5_000))
    }

    @Test
    fun `sending true 也立即传导`() {
        val s = BusyIndicatorSmoother(delayMs)
        assertTrue(s.update(busy = false, sending = true, nowMs = 0))
        assertTrue(s.update(busy = false, sending = true, nowMs = 1_000))
    }

    @Test
    fun `false 需稳定 delay 后才传导`() {
        val s = BusyIndicatorSmoother(delayMs)
        s.update(busy = true, sending = false, nowMs = 0)
        // 下降沿 t=100：挂起释放，保持 true
        assertTrue(s.update(busy = false, sending = false, nowMs = 100))
        assertEquals(delayMs, s.remainingMs(nowMs = 100))
        // 未到期（t=100+2499）：仍 true
        assertTrue(s.update(busy = false, sending = false, nowMs = 2_599))
        // 到期（t=100+2500）：释放为 false
        assertFalse(s.update(busy = false, sending = false, nowMs = 2_600))
        // 释放后 remaining 无挂起
        assertEquals(-1L, s.remainingMs(nowMs = 2_600))
        // 已释放后保持 false
        assertFalse(s.update(busy = false, sending = false, nowMs = 10_000))
    }

    @Test
    fun `释放等待期间又变 true 则不传导 false`() {
        val s = BusyIndicatorSmoother(delayMs)
        s.update(busy = true, sending = false, nowMs = 0)
        // 下降沿 t=100，释放定于 t=2600
        assertTrue(s.update(busy = false, sending = false, nowMs = 100))
        // t=2000 又变 true（FSM 复活 Busy）：取消挂起，立即 true
        assertTrue(s.update(busy = true, sending = false, nowMs = 2_000))
        assertEquals(-1L, s.remainingMs(nowMs = 2_000))
        // 原定释放点已过但 busy 在保持：仍 true
        assertTrue(s.update(busy = true, sending = false, nowMs = 3_000))
        // 新下降沿 t=3000 → 释放点 t=5500；旧挂起不得提前生效
        assertTrue(s.update(busy = false, sending = false, nowMs = 3_000))
        assertTrue(s.update(busy = false, sending = false, nowMs = 5_499))
        assertFalse(s.update(busy = false, sending = false, nowMs = 5_500))
    }

    @Test
    fun `busy 到 sending 接力无缝`() {
        // POST 完成（sending 下降）与 FSM 置 Busy（busy 上升）的组合缝隙：
        // sending=false 先到、busy=true 未到 → 保持 true 等 busy 接管
        val s = BusyIndicatorSmoother(delayMs)
        s.update(busy = false, sending = true, nowMs = 0)
        // sending 下降沿 t=100
        assertTrue(s.update(busy = false, sending = false, nowMs = 100))
        // 缝隙期（t=500）busy 到达 → 持续 true
        assertTrue(s.update(busy = true, sending = false, nowMs = 500))
        assertTrue(s.update(busy = true, sending = false, nowMs = 4_000))
    }

    @Test
    fun `从未置位时保持 false 不产生指示`() {
        // 冷启动/会话空闲：输入始终 false → 不应因释放延迟意外显示
        val s = BusyIndicatorSmoother(delayMs)
        assertFalse(s.update(busy = false, sending = false, nowMs = 0))
        assertEquals(-1L, s.remainingMs(nowMs = 0))
        assertFalse(s.update(busy = false, sending = false, nowMs = 10_000))
    }

    @Test
    fun `发送失败回 idle 在 delay 后正常释放`() {
        // POST 失败：isSending 下降且 isBusy 永不到达 → delay 后释放（不粘死）
        val s = BusyIndicatorSmoother(delayMs)
        s.update(busy = false, sending = true, nowMs = 0)
        assertTrue(s.update(busy = false, sending = false, nowMs = 200))
        assertFalse(s.update(busy = false, sending = false, nowMs = 200 + delayMs))
    }
}
