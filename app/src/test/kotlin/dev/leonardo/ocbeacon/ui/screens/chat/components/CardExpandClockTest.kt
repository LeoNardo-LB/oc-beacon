package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * #425 语义重写:飞行中 H 增长(1000→1200)由「目标重算」自然覆盖——
     * 指令 targetRep−账本 含增长欠量,不再依赖跨帧洗账。
     */
    @Test
    fun totalDerivativeCoversMidFlightGrowth() {
        val c = clock(expanded = false)
        c.onMeasure(1000)
        c.animating = true
        c.primeLedger()
        c.absorb(500f) // 已展开到 500
        // H 增长为 1200 后,目标 0.6 的指令 = 720−500 = 220(增量 120 + 欠账 120 同帧)
        assertEquals(220, closedLoopCommand(targetRep = 720, absorbed = 500))
        c.absorb(220f)
        assertEquals(720, c.absorbedPx)
    }

    /**
     * #425 语义重写:渐进组合竞态(0→18→738 两步首测)在吸收账本下按构造
     * 免疫——settle 期间 onMeasure 只更新 H(上报账本),tween 指令以就绪
     * 后的 H 一次性计算,首测跨帧不再产生「永久上推」欠账。
     */
    @Test
    fun progressiveCompositionDebtCompensatedNextFrame() {
        val c = clock(expanded = false)
        c.animating = true
        c.primeLedger()
        // settle 期渐进首测:H 18 → 738,账本不动(上报恒 0,零视觉)
        assertEquals(0, c.onMeasure(18))
        assertEquals(0, c.onMeasure(738))
        assertEquals(738, c.lastMeasuredH)
        // tween 首帧:目标 0.2 → 指令 147(全部增量一次算清,无历史欠账)
        assertEquals(147, closedLoopCommand(targetRep = 147, absorbed = 0))
        var f = 0.2f
        c.absorb(147f)
        while (f < 1f) {
            f += 0.1f
            val target = (f * 738).toInt()
            val cmd = closedLoopCommand(targetRep = target, absorbed = c.absorbedPx)
            c.absorb(cmd.toFloat()) // 假设全消费
        }
        c.absorb((738 - c.absorbedPx).toFloat())
        // 守恒:Σabsorb == 最终账本 == H(欠账不流失)
        assertEquals(738, c.absorbedPx)
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
     * #425 语义重写:迟到增量守恒——tween 末账本 800,episode 末复测发现
     * H2=1000;catch-up 指令(目标−账本+反馈)吸收 200,随后稳定态上报 H2。
     * 不变量:Σabsorb == H2,无净漂移。
     */
    @Test
    fun lateGrowthCatchUpConservation() {
        val c = clock(expanded = false)
        c.onMeasure(800) // settle 末:H1=800
        c.animating = true
        c.primeLedger()
        for (step in 1..10) {
            val cmd = closedLoopCommand(targetRep = step * 80, absorbed = c.absorbedPx)
            c.absorb(cmd.toFloat())
        }
        assertEquals(800, c.absorbedPx)
        // episode 末强制复测:内容迟到增长 800 → 1000(上报乐观=f·H)
        c.driveTo(1f)
        assertEquals(1000, c.onMeasure(1000))
        assertEquals(1000, c.lastMeasuredH)
        // catch-up:指令以新 H 计算,吸收差值 = 200
        val catchUp = closedLoopCommand(targetRep = 1000, absorbed = 800)
        assertEquals(200, catchUp)
        c.absorb(catchUp.toFloat())
        c.animating = false
        c.driveTo(1f)
        assertEquals(1000, c.onMeasure(1000))
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

    // ===== #424 闭环位置恢复判定 =====

    /** 上漂(实测高于锚)→ 正向修正 δ(与展开 dispatch 同号:内容下移回锚)。 */
    @Test
    fun endCorrectionPositiveWhenContentDriftedUp() {
        assertEquals(98f, episodeEndCorrection(934f, 836f, userScrollCancelled = false)!!, 0.01f)
    }

    /** 下漂 → 负向修正。 */
    @Test
    fun endCorrectionNegativeWhenContentDriftedDown() {
        assertEquals(-120f, episodeEndCorrection(800f, 920f, userScrollCancelled = false)!!, 0.01f)
    }

    /** 偏差 <1px → 0(免无意义 dispatch),非 null。 */
    @Test
    fun endCorrectionZeroBelowThreshold() {
        assertEquals(0f, episodeEndCorrection(934f, 934.5f, userScrollCancelled = false)!!, 0.01f)
    }

    /** 用户滚动取消 → 不修(阅读位置优先权铁律)。 */
    @Test
    fun endCorrectionSkippedWhenUserScrolled() {
        assertNull(episodeEndCorrection(934f, 500f, userScrollCancelled = true))
    }

    /** 坐标缺失(首组合同帧未挂位置)→ 不修。 */
    @Test
    fun endCorrectionSkippedWhenCoordinatesMissing() {
        assertNull(episodeEndCorrection(Float.NaN, 500f, userScrollCancelled = false))
        assertNull(episodeEndCorrection(934f, Float.NaN, userScrollCancelled = false))
    }

    /**
     * #424 连点竞态:反向 toggle 中途重定向(tween 未完即回摆再展开)——
     * 账本 telescoping 不变量 Σδ == 终report − 初report 必须成立,
     * 否则连点后视口累积漂移。
     */
    @Test
    fun reverseToggleLedgerTelescoping() {
        val c = clock(expanded = false)
        c.onMeasure(1000)
        var dispatched = 0f
        dispatched += c.advance(0.5f)   // 展开一半
        c.onMeasure(1000)
        dispatched += c.advance(0.2f)   // 中途回摆(连点反向)
        c.onMeasure(1000)
        dispatched += c.advance(0.8f)   // 再次展开
        c.onMeasure(1000)
        dispatched += c.advance(0.0f)   // 又收起
        c.onMeasure(1000)
        dispatched += c.advance(1.0f)   // 终态展开
        c.onMeasure(1000)
        assertEquals(1000f, dispatched, 0.5f)
        assertEquals(1000, (c.fraction * c.lastMeasuredH).toInt())
    }

    /** #424 连点竞态:滚动取消标志按 episode 重置,不跨集泄漏。 */
    @Test
    fun userScrollCancelFlagResetsPerEpisode() {
        val c = clock(expanded = false)
        c.beginEpisode()
        c.userScrollCancelled = true
        c.beginEpisode()
        assertEquals(false, c.userScrollCancelled)
    }

    // ===== #425 渲染前反馈闭环 =====

    /** 指令 = 目标 − 已吸收(账本重试式,全程单向)。 */
    @Test
    fun closedLoopCommandIsLedgerGap() {
        assertEquals(100, closedLoopCommand(targetRep = 500, absorbed = 400))
        assertEquals(-100, closedLoopCommand(targetRep = 400, absorbed = 500))
        assertEquals(0, closedLoopCommand(targetRep = 500, absorbed = 500))
    }

    /** 单帧指令钳制(残量集中释放防单帧暴冲)。 */
    @Test
    fun closedLoopCommandClamped() {
        assertEquals(300, closedLoopCommand(targetRep = 1000, absorbed = 0))
        assertEquals(-300, closedLoopCommand(targetRep = 0, absorbed = 1000))
    }

    /**
     * 吸收账本(观测用):部分消费不丢失——未消费残量经下帧 topErr 重试;
     * 账本累计 == Σconsumed。
     */
    @Test
    fun absorbLedgerTracksConsumedOnly() {
        val c = clock(expanded = false)
        c.onMeasure(1000)
        c.animating = true
        c.primeLedger()
        c.absorb(40f)
        assertEquals(40, c.absorbedPx)
        c.absorb(210f)
        assertEquals(250, c.absorbedPx)
        c.absorb(-100f)
        assertEquals(150, c.absorbedPx)
    }

    /**
     * 残量重试路径:帧1指令 100 只消费 40 → 账本 40;帧2目标 250,
     * 指令 = 250−40 = 210(增量 150 + 残量 60,不丢失)。
     */
    @Test
    fun unconsumedCommandRetriedViaLedger() {
        assertEquals(100, closedLoopCommand(targetRep = 100, absorbed = 0))
        assertEquals(210, closedLoopCommand(targetRep = 250, absorbed = 40))
    }

    /**
     * #425 死锁回归:上报恒为 f·H(乐观),与吸收账本解耦——上报若跟随
     * 消费,列表无增长空间,dispatch 恒消费 0(真机 218 帧 consumed=0)。
     */
    @Test
    fun onMeasureAlwaysReportsFractionHeight() {
        val c = clock(expanded = false)
        c.animating = true
        c.primeLedger()
        c.absorb(123f) // 吸收落后
        c.driveTo(0.5f)
        // 上报仍是 f·H = 500(乐观,列表空间由此生长)
        assertEquals(500, c.onMeasure(1000))
        assertEquals(123, c.absorbedPx)
    }
}
