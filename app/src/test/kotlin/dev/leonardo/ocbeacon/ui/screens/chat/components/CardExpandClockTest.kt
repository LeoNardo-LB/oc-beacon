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
        // onMeasure 不洗账(指令账本归 advance 独有):报告 720,账本仍 600,欠账 120
        assertEquals((0.6f * 1200).toInt(), c.onMeasure(1200))
        // 下一帧 advance 的 δ telescoping 自动补齐欠账(240 = 增量 120 + 欠账 120)
        assertEquals((0.7f * 1200).toInt() - 600f, c.advance(0.7f), 0.5f)
    }

    /**
     * #420 追诊回归:content 渐进组合竞态(0→18→738 两步首测)的欠账必须在
     * H 就绪后的下一帧全额补 dispatch——否则永久上推 f_done·H px
     * (实测短文本 8px,长文本首测跨 100-300ms 时 200-380px,概率性)。
     */
    @Test
    fun progressiveCompositionDebtCompensatedNextFrame() {
        val c = clock(expanded = false)
        // 帧1:content 未组合(H=0)——advance δ=0 静默
        assertEquals(0f, c.advance(0.05f), 0.5f)
        // 帧1 measure:渐进组合第一步只测到 18px,report≈1(0.05·18)
        c.onMeasure(18)
        // 帧2 advance 仍用旧 H(18):δ=trunc(0.1·18)−0=1
        assertEquals(1f, c.advance(0.1f), 0.5f)
        // 帧2 measure:首测完成 H=738,report=73,账本仍 1 → 欠账 72(未洗账)
        assertEquals(73, c.onMeasure(738))
        // 帧3 advance:δ=trunc(0.2·738)−1=146(增量 73 + 欠账 72 全额补齐)
        assertEquals(146f, c.advance(0.2f), 0.5f)
        // 守恒:此后揭示到 738 时,Σdispatch == 738(欠账不流失)
        var dispatched = 146f + 1f
        var f = 0.2f
        while (f < 1f) {
            f += 0.1f
            dispatched += c.advance(f)
            c.onMeasure(738)
        }
        dispatched += c.advance(1f)
        assertEquals(738f, dispatched, 0.5f)
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
        // snap 自写账本(指令值)——防去洗账后后续 δ 暴冲
        assertEquals(500, c.lastReportedH)
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

    // ------------------------------------------------------------------
    // #422:tween 缓存窗口 + settle 计数 + episode 末复测语义
    // ------------------------------------------------------------------

    @Test
    fun tweeningDefaultsFalse() {
        assertEquals(false, clock().tweening)
    }

    @Test
    fun measureCountIncrementsPerRecord() {
        val c = clock()
        assertEquals(0, c.measureCount)
        c.recordMeasure()
        c.recordMeasure()
        assertEquals(2, c.measureCount)
    }

    @Test
    fun remeasureEpochBumpsOnRequest() {
        val c = clock()
        val before = c.remeasureEpoch
        c.requestRemeasure()
        assertEquals(before + 1, c.remeasureEpoch)
    }

    /**
     * #422 迟到增量守恒:settle 末 H1 → tween 至 1 → episode 末强制复测发现
     * H2(内容迟到增长)→ 追加一次 advance(f) 的 δ 补偿。不变量:
     * Σδ(tween) + δ(catch-up) == H2(最终上报),无净漂移。
     */
    @Test
    fun lateGrowthCatchUpConservation() {
        val c = clock(expanded = false)
        c.onMeasure(800) // settle 末:H1=800
        var dispatched = 0f
        for (step in 1..10) {
            val f = step / 10f
            dispatched += c.advance(f)
            c.onMeasure(800) // tween 期间缓存:H 不变
        }
        assertEquals(800, c.lastReportedH)
        // episode 末强制复测:内容迟到增长 800 → 1000
        val reportedAfterGrowth = c.onMeasure(1000)
        assertEquals(1000, reportedAfterGrowth)
        // catch-up:advance(1f) 以新 H 计算目标 → δ = 200
        val catchUp = c.advance(c.fraction)
        dispatched += catchUp
        assertEquals(200f, catchUp)
        assertEquals(1000f, dispatched, 0.5f)
        assertEquals(1000, (c.fraction * c.lastMeasuredH).toInt())
    }

    /**
     * #422 catch-up 门控语义:collapse(f=0)时内容高度变化不产生补偿位移
     * (content 已离树,report 恒 0)。
     */
    @Test
    fun lateGrowthNoCatchUpWhenCollapsed() {
        val c = clock(expanded = true)
        c.onMeasure(800)
        for (step in 9 downTo 0) {
            c.advance(step / 10f)
            c.onMeasure(800)
        }
        assertEquals(0f, c.fraction)
        // 离树后空测:H=0 → lastMeasuredH=0
        c.onMeasure(0)
        // f=0 → advance 目标恒 0,无位移
        assertEquals(0f, c.advance(c.fraction))
    }
}
