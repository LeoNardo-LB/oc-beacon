package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #420:CardExpandClock 契约——单一时钟同帧配对的账本数学。
 *
 * 核心不变量:Σ(advance 返回的 δ) == 最终上报高度 − 初始上报高度
 * (位移先行、揭示配对;守恒 = 视口零净跳动)。
 */
class CardExpandClockTest {

    private fun clock(expanded: Boolean = false) =
        CardExpandClock(if (expanded) 1f else 0f)

    @Test
    fun coldStartCollapsedReportsZero() {
        val c = clock(expanded = false)
        assertEquals(0, c.onMeasure(800))
        assertEquals(800, c.lastMeasuredH)
    }

    @Test
    fun coldStartExpandedReportsFull() {
        val c = clock(expanded = true)
        assertEquals(800, c.onMeasure(800))
    }

    @Test
    fun expandConservation() {
        val c = clock(expanded = false)
        c.onMeasure(1000)
        var dispatched = 0f
        var reported = 0
        for (step in 1..10) {
            val f = step / 10f
            dispatched += c.advance(f)
            reported = c.onMeasure(1000)
        }
        assertEquals(1000, reported)
        assertEquals(1000f, dispatched, 0.5f)
    }

    @Test
    fun collapseConservation() {
        val c = clock(expanded = true)
        c.onMeasure(1000)
        var dispatched = 0f
        var reported = 1000
        for (step in 9 downTo 0) {
            val f = step / 10f
            dispatched += c.advance(f)
            reported = c.onMeasure(1000)
        }
        assertEquals(0, reported)
        assertEquals(-1000f, dispatched, 0.5f)
    }

    @Test
    fun totalDerivativeCoversMidFlightGrowth() {
        val c = clock(expanded = false)
        c.onMeasure(1000)
        assertEquals(500f, c.advance(0.5f), 0.5f)
        assertEquals(500, c.onMeasure(1000))
        // 本帧 advance 用旧 H(1000)——增长 1000→1200 尚未被 measure 发现
        assertEquals(0.6f * 1000 - 500, c.advance(0.6f), 0.5f)
        assertEquals((0.6f * 1200).toInt(), c.onMeasure(1200))
        // 下一帧 advance 吸收增长分量(一帧滞后)
        assertEquals(0.7f * 1200 - (0.6f * 1200).toInt(), c.advance(0.7f), 0.5f)
    }

    @Test
    fun reversalMidFlightKeepsLedgerContinuous() {
        val c = clock(expanded = false)
        c.onMeasure(600)
        c.advance(0.5f); c.onMeasure(600)
        var dispatched = c.advance(0.2f)
        dispatched += c.advance(0f)
        assertEquals(0f - 300f, dispatched, 0.5f)
        assertEquals(0, c.onMeasure(600))
    }

    @Test
    fun snapStopsAnimationAndNextMeasurePlacesFull() {
        val c = clock(expanded = false)
        c.onMeasure(500)
        c.animating = true
        c.beginEpisode()
        c.snap(1f)
        assertEquals(1f, c.fraction, 0.001f)
        assertEquals(false, c.animating)
        // snap 后下一次 measure 全量揭示(取消时内容即时就位,位移由用户手势吸收)
        assertEquals(500, c.onMeasure(500))
    }

    @Test
    fun episodeAccountingResets() {
        val c = clock(expanded = false)
        c.onMeasure(1000)
        c.beginEpisode()
        c.recordDisplacement(60f)
        c.recordDisplacement(60f)
        assertEquals(120f, c.episodeDisplacement, 0.5f)
        c.beginEpisode()
        assertEquals(0f, c.episodeDisplacement, 0.5f)
    }
}
