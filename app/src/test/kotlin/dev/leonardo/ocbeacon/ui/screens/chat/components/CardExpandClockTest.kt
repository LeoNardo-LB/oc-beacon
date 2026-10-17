package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #420:CardExpandClock 契约——单一时钟同帧配对的账本数学。
 *
 * #544 C2(2026-10-17):动画相 advance/吸收账本(absorb/absorbedPx/
 * primeLedger)与 closedLoopCommand(#420-#425 闭环派发机件)随直接
 * dispatchRawDelta 路径退役而清剿——本文件仅存活语义:上报公式(f·H,
 * ε 预热窗零上报)、snap 取消、episode 记账重置、#422 几何缓存窗、
 * #430 稳态配对账本。
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

    /**
     * #427 收起位移根因（用户实测：收起大卡整体对话上移一小段）：ε 预热相位的
     * 上报高度必须为 0——report=(fraction×H) 在 H=20k 级时 ε=0.001 仍上报 ~20px
     * 布局残高（批次十三标定 H≈738px 时 0.7px 判零视觉）。该残高在「预热在树
     * →展开 →收起 →预热回树」循环的 ±H 配对账本里不对称 → 每循环净漂 ~ε·H。
     * 语义：warmup 相位（fraction ≤ WARMUP_FRACTION）组合保温但零布局足迹。
     */
    @Test
    fun warmupPhaseReportsZeroRegardlessOfHeight() {
        val c = clock(expanded = false)
        c.warmup() // fraction = 0.001
        assertEquals(0, c.onMeasure(20_340))
        assertEquals(0, c.lastReportedH)
        assertEquals(20_340, c.lastMeasuredH)
        // 常规动画相位不受影响（fraction 高于 warmup 窗口照常上报）
        c.driveTo(0.5f)
        assertEquals(10_170, c.onMeasure(20_340))
    }

    /**
     * #427 收起位移终局根因：配对派发的消费残差重试指令（纯决策）。
     * 真机取证（两会话复现）：展开 dispatch 需 +20352 实消费 +14190、收起
     * 需 −20352 实消费 −14458——跨锚点测量竞态下两次欠消费残差不等
     * (6162−5894=268)，开环单发把差值漏成视口净漂（每周期恒 −268px）。
     * 决策：同相位（渲染前、程序化豁免内）把未消费余量作为下一发指令重试；
     * 消费为零（物理边缘/布局未就绪）则停止——不是渲染后补偿，是补完配对。
     */
    @Test
    fun pairedDispatchResidualRetryDecision() {
        // 全额消费 → 无需重试
        assertEquals(null, PairedDispatch.nextCommand(20_352f, 20_352f, tries = 0))
        // 欠消费 → 重试余量
        assertEquals(6_162f, PairedDispatch.nextCommand(20_352f, 14_190f, tries = 0)!!, 0.5f)
        // 收起方向对称
        assertEquals(-5_894f, PairedDispatch.nextCommand(-20_352f, -14_458f, tries = 0)!!, 0.5f)
        // 亚像素残差 → 结束（整数守恒量化阈）
        assertEquals(null, PairedDispatch.nextCommand(20_352f, 20_351.6f, tries = 0))
        // 零消费（物理不可约边缘）→ 停止，余量由布局吸收
        assertEquals(null, PairedDispatch.nextCommand(20_352f, 0f, tries = 0))
        // 有界重试：上限后放弃（防不可收敛死循环）
        assertEquals(null, PairedDispatch.nextCommand(20_352f, 14_190f, tries = 4))
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

    /** #424 连点竞态:滚动取消标志按 episode 重置,不跨集泄漏。 */
    @Test
    fun userScrollCancelFlagResetsPerEpisode() {
        val c = clock(expanded = false)
        c.beginEpisode()
        c.userScrollCancelled = true
        c.beginEpisode()
        assertEquals(false, c.userScrollCancelled)
    }

    // ============ #430 稳态配对账本(episode 外迟到增长) ============

    /**
     * #430 核心契约:稳态(非动画相)report 变化量逐笔入账,取走即清——
     * 分批表格逐组落地/asyncParse 完成的每个增量都成为待配对位移,
     * 由 pre-draw flush 派发(反向滚动)对冲 reverseLayout 锚定的上顶。
     */
    @Test
    fun steadyLedgerAccumulatesPostEpisodeGrowth() {
        val c = clock(expanded = true)
        c.animating = true
        c.beginEpisode() // 集开始:rebase
        c.animating = false
        // 集后第一次 measure:静默建基线(集内增长已由 episode dispatch 配对)
        c.noteSteadyReport(278)
        assertEquals(0f, c.takeSteadyPending())
        // 分批表格:组1 +312、组2 +288
        c.noteSteadyReport(278 + 312)
        c.noteSteadyReport(278 + 312 + 288)
        assertEquals(600f, c.takeSteadyPending())
        assertEquals(0f, c.takeSteadyPending()) // 取走即清
    }

    /**
     * #430 收缩方向:负增量(内容塌缩/图片回收)同样入账,flush 派发负向位移
     * (贴底不可消费时残量由布局吸收——与引擎既有语义一致)。
     */
    @Test
    fun steadyLedgerPairsNegativeGrowth() {
        val c = clock(expanded = true)
        c.noteSteadyReport(590)
        c.noteSteadyReport(560)
        assertEquals(-30f, c.takeSteadyPending())
    }

    /**
     * #430 rebase 协议:episode 派发点/收起点/取消点调用——丢弃未派发增量并
     * 重建基线,防 episode 自身的高度跳变(Phase A 落地/收起塌缩)被双配对。
     */
    @Test
    fun steadyRebaseDropsPendingAndRebasesBaseline() {
        val c = clock(expanded = true)
        c.noteSteadyReport(100)
        c.noteSteadyReport(250) // pending = 150
        c.steadyRebase()
        assertEquals(0f, c.takeSteadyPending())
        // rebase 后第一次 report 静默建基线(当前值整体视作已配对)
        c.noteSteadyReport(400)
        assertEquals(0f, c.takeSteadyPending())
        // 之后的增量恢复记账
        c.noteSteadyReport(430)
        assertEquals(30f, c.takeSteadyPending())
    }

    /**
     * #430 折叠/ε 预热窗:report 恒 0(warmup 零布局足迹),内容实侧增长
     * (分批组在预热期落地)不产生配对义务——展开 episode 会一次性配对全高。
     */
    @Test
    fun steadyLedgerSilentDuringWarmupAndCollapsed() {
        val c = clock(expanded = false)
        c.warmup() // fraction = ε,report 恒 0
        c.noteSteadyReport(0)
        c.noteSteadyReport(0) // 实侧 H 在涨但 report=0
        assertEquals(0f, c.takeSteadyPending())
        // 收起态(f=0):即使 report 非 0(防御)也清零
        c.driveTo(0f)
        c.noteSteadyReport(0)
        assertEquals(0f, c.takeSteadyPending())
    }

    /**
     * #430 取消路径:snap(用户滚动打断)同步 rebase——位置归 snap/用户所有,
     * 集内已入账的幕布期增量不得在集后补发(与手势竞态)。
     */
    @Test
    fun snapRebasesSteadyLedger() {
        val c = clock(expanded = false)
        c.warmup()
        c.noteSteadyReport(0)
        c.driveTo(1f)
        c.noteSteadyReport(500) // 幕布期增长入账
        c.snap(1f) // 用户滚动打断
        assertEquals(0f, c.takeSteadyPending())
    }

    /**
     * #430 修正:欠账 rebase——dispatch 瞬时 0 消费(transient:增长晚一帧落地)
     * 时残量=目标−实消费成为欠账;落地帧 report==ledger 不冲销欠账,由 flush
     * 补派(容量随增长出现)。真机 19:34 复现:2852px 增长裸上顶 = 旧协议把
     * 该增长静默吸进基线的洞。
     */
    @Test
    fun debtRebaseKeepsUnconsumedRemainderAsPending() {
        val c = clock(expanded = true)
        c.beginEpisode() // hold=true, rebase
        // settle 期 report=0(ε 窗)→ 基线 0
        c.noteSteadyReport(0)
        // dispatch:目标 H=2852,实消费 0(增长未落地)
        c.steadyRebaseAfterEpisodeDispatch(targetRep = 2852, consumed = 0f)
        c.steadyHold = false
        // 增长落地帧:report==ledger → d=0,欠账保留
        c.noteSteadyReport(2852)
        assertEquals(2852f, c.takeSteadyPending())
        // 后续增量(分批继续)逐笔记账
        c.noteSteadyReport(3152)
        assertEquals(300f, c.takeSteadyPending())
    }

    /**
     * #430:全额消费(预热命中/容量充足)→ 欠账 0,steady 静默(零开销)。
     */
    @Test
    fun debtRebaseZeroWhenFullyConsumed() {
        val c = clock(expanded = true)
        c.beginEpisode()
        c.noteSteadyReport(0)
        c.steadyRebaseAfterEpisodeDispatch(targetRep = 36612, consumed = 36612f)
        c.noteSteadyReport(36612)
        assertEquals(0f, c.takeSteadyPending())
    }
}
