package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #262 PreRenderExpandMachine 配对契约（取代 ExpandRevealCompensatorTest 的守门位，
 * spec：docs/specs/2026-08-30-expand-prerender-design.md §7）。
 *
 * 守恒不变量：
 * 1. 未位移的几何永不被上报（withhold：report < real，差额经运输层入队）；
 * 2. 揭示仅在 shiftSettled 后（竞态门：位移未落地保持裁剪）；
 * 3. version 在每次入队决策时自增（measure 订阅 → 消费遍重测）；
 * 4. 展开侧布局恒终态：reveal 后 report == real；
 * 5. 收起缓动：report == fraction × real，零入队（位移由动画循环承担）。
 */
class PreRenderExpandMachineTest {

    private fun machine(): PreRenderExpandMachine = PreRenderExpandMachine()

    // ── 启动指令 ──

    @Test
    fun `beginExpand 缓存命中走 Immediate`() {
        val m = machine()
        val start = m.beginExpand(350)
        assertTrue(start is PreRenderExpandMachine.ExpandStart.Immediate)
        assertEquals(350, (start as PreRenderExpandMachine.ExpandStart.Immediate).deltaPx)
    }

    @Test
    fun `beginExpand 无缓存走 MeasureFirst`() {
        val m = machine()
        assertEquals(
            PreRenderExpandMachine.ExpandStart.MeasureFirst,
            m.beginExpand(-1),
        )
    }

    // ── MEASURING 配对 ──

    @Test
    fun `首次展开测量遍持旧高并入队全量`() {
        val m = machine()
        m.beginExpand(-1)
        m.enterMeasuring()
        val d = m.onMeasure(contentH = 200, shiftSettled = true)
        assertEquals(0, d.reportContentH)          // 未位移不上报
        assertEquals(200, d.enqueueDeltaPx)        // 全量入队
        assertFalse(d.revealedNow)
        assertEquals(1, m.version)                 // 入队决策自增（重测契约）
    }

    @Test
    fun `空高度首测不短路不误入队`() {
        val m = machine()
        m.beginExpand(-1)
        m.enterMeasuring()
        val d = m.onMeasure(contentH = 0, shiftSettled = true)
        assertEquals(0, d.reportContentH)
        assertEquals(0, d.enqueueDeltaPx)
        assertEquals(0, m.version)
    }

    @Test
    fun `位移未落地保持裁剪（竞态门）`() {
        val m = machine()
        m.beginExpand(-1)
        m.enterMeasuring()
        m.onMeasure(contentH = 200, shiftSettled = true) // 入队
        val d = m.onMeasure(contentH = 200, shiftSettled = false) // 同帧重测插队
        assertEquals(0, d.reportContentH)
        assertEquals(0, d.enqueueDeltaPx)
    }

    @Test
    fun `位移落地遍全量揭示并转 REVEALING`() {
        val m = machine()
        m.beginExpand(-1)
        m.enterMeasuring()
        m.onMeasure(contentH = 200, shiftSettled = true)
        val d = m.onMeasure(contentH = 200, shiftSettled = true)
        assertEquals(200, d.reportContentH)
        assertEquals(0, d.enqueueDeltaPx)
        assertTrue(d.revealedNow)
        assertEquals(PreRenderPhase.REVEALING, m.phase)
    }

    // ── 稳态增长配对（EXPANDED 期异步变高） ──

    @Test
    fun `稳态增长 withhold 并入队增量`() {
        val m = machine()
        m.enterExpanded()
        val first = m.onMeasure(contentH = 100, shiftSettled = true)
        assertEquals(100, first.reportContentH)
        val grown = m.onMeasure(contentH = 150, shiftSettled = true)
        assertEquals(100, grown.reportContentH)    // 增量未位移不上报
        assertEquals(50, grown.enqueueDeltaPx)
        val settled = m.onMeasure(contentH = 150, shiftSettled = true)
        assertEquals(150, settled.reportContentH)  // 落地后揭示
    }

    // ── 缓存预留（重入组合） ──

    @Test
    fun `重入组合内容未及缓存终高时按缓存占位`() {
        val m = machine()
        m.enterExpanded()
        val d = m.onMeasure(contentH = 80, shiftSettled = true, reservePx = 200)
        assertEquals(200, d.reportContentH)        // 预留地板：首帧即终高
        assertEquals(0, d.enqueueDeltaPx)
    }

    @Test
    fun `内容超过缓存时首测即实高，后续增长才配对`() {
        val m = machine()
        m.enterExpanded()
        val d = m.onMeasure(contentH = 260, shiftSettled = true, reservePx = 200)
        assertEquals(260, d.reportContentH)         // 稳态首测全量上报
        assertEquals(0, d.enqueueDeltaPx)           // 无既有几何 → 零配对
        val grown = m.onMeasure(contentH = 320, shiftSettled = true)
        assertEquals(260, grown.reportContentH)     // 首测之后的增长照常配对
        assertEquals(60, grown.enqueueDeltaPx)
    }

    // ── 收起缓动 ──

    @Test
    fun `CLOSING 上报随 fraction 收缩且零入队`() {
        val m = machine()
        m.enterExpanded()
        m.onMeasure(contentH = 200, shiftSettled = true)
        m.beginCollapse()
        assertEquals(PreRenderPhase.CLOSING, m.phase)
        // fraction 由动画循环逐帧写入——直接测决策函数对 fraction 的线性依赖
        assertEquals(200, m.onMeasure(200, true).reportContentH)   // 1f × 200
        m.fraction = 0.5f
        assertEquals(100, m.onMeasure(200, true).reportContentH)   // 0.5f × 200
        assertEquals(0, m.onMeasure(200, true).enqueueDeltaPx)     // 收起零入队
        m.fraction = 0f
        assertEquals(0, m.onMeasure(200, true).reportContentH)     // 0f × 200
        m.closeFinished()
        assertEquals(PreRenderPhase.IDLE, m.phase)
        assertEquals(0, m.onMeasure(0, true).reportContentH) // IDLE 零高度
    }

    // ── 中断与反向 ──

    @Test
    fun `MEASURING 中断收起直接回 IDLE`() {
        val m = machine()
        m.beginExpand(-1)
        m.enterMeasuring()
        m.onMeasure(200, true)
        m.abortMeasuring()
        assertEquals(PreRenderPhase.IDLE, m.phase)
    }

    @Test
    fun `揭示动画完成转 EXPANDED`() {
        val m = machine()
        m.enterRevealing()
        m.onRevealAnimationFinished()
        assertEquals(PreRenderPhase.EXPANDED, m.phase)
    }

    @Test
    fun `IDLE 态测量恒零（content 不组合的守恒对应）`() {
        val m = machine()
        val d = m.onMeasure(contentH = 999, shiftSettled = true)
        assertEquals(0, d.reportContentH)
        assertEquals(0, d.enqueueDeltaPx)
    }
}
