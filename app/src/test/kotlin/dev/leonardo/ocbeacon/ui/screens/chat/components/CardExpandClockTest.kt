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
