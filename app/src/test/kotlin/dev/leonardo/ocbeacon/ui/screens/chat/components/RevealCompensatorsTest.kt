package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #258 换道手术配套单测：两族补偿状态机的配对语义与滚动守卫。
 *
 * 运输层（PreRenderShiftChannel→request-position）只改变注入的**投递方式**，
 * 状态机的「增长遍上报基准 → 遍首应用 → 揭示遍全量配对」契约不变——
 * 这里锁住该契约，防止未来运输层再动时配对语义被无声破坏。
 */
class RevealCompensatorsTest {

    // ---- ExpandRevealCompensator（tap 展开家族）----

    @Test
    fun expand_firstMeasure_reportsFull_neverInjects() {
        val c = ExpandRevealCompensator()
        assertEquals(120 to 0, c.onMeasure(120, shiftApplied = true))
    }

    @Test
    fun expand_growthPass_reportsBase_enqueuesDelta_revealsNextPass() {
        val c = ExpandRevealCompensator()
        c.onMeasure(100, shiftApplied = true)

        // 增长遍：真实 160 → 上报基准 100、注入 +60
        val v0 = c.version
        assertEquals(100 to 60, c.onMeasure(160, shiftApplied = true))
        assertEquals(v0 + 1, c.version)

        // 揭示遍：遍首已应用 +60 → 全量揭示 160、无新注入
        assertEquals(160 to 0, c.onMeasure(160, shiftApplied = true))
    }

    @Test
    fun expand_collapsePass_pairsNegativeDelta() {
        val c = ExpandRevealCompensator()
        c.onMeasure(200, shiftApplied = true)

        // 收起遍：真实 120 → 上报 200、注入 -80（视窗上移配对）
        assertEquals(200 to -80, c.onMeasure(120, shiftApplied = true))
        // 揭示遍：锚点已上移 → 全量揭示 120
        assertEquals(120 to 0, c.onMeasure(120, shiftApplied = true))
    }

    @Test
    fun expand_raceGate_unappliedShift_holdsClipped_thenReveals() {
        val c = ExpandRevealCompensator()
        c.onMeasure(100, shiftApplied = true)

        // 增长：上报基准、入队 80
        assertEquals(100 to 80, c.onMeasure(180, shiftApplied = true))

        // 同帧重测插队（位移未落地）：保持基准裁剪，绝不提前揭示
        assertEquals(100 to 0, c.onMeasure(180, shiftApplied = false))

        // 位移落地：全量揭示
        assertEquals(180 to 0, c.onMeasure(180, shiftApplied = true))
    }

    @Test
    fun expand_chainMultiFrame_revealsPreviousEnqueuesCurrent() {
        val c = ExpandRevealCompensator()
        c.onMeasure(0, shiftApplied = true)

        // 多帧动画链式：每遍揭示上一遍注入、递延本遍（spring 逐帧小增量）
        assertEquals(0 to 30, c.onMeasure(30, shiftApplied = true))
        assertEquals(30 to 20, c.onMeasure(50, shiftApplied = true))
        assertEquals(50 to 10, c.onMeasure(60, shiftApplied = true))
        assertEquals(60 to 0, c.onMeasure(60, shiftApplied = true))
    }

    // ---- DeferredRevealCompensator（SSE 流式家族）----

    @Test
    fun deferred_coldStart_reportsFull_noInject() {
        val c = DeferredRevealCompensator()
        val d = c.onMeasure(500, shouldCompensate = true)
        assertEquals(DeferredRevealCompensator.Decision(500, 0, false), d)
    }

    @Test
    fun deferred_growth_defersReveal_injectsDelta() {
        val c = DeferredRevealCompensator()
        c.onMeasure(500, shouldCompensate = true)

        // 增长 +72：上报已消费基准 500、注入 72
        assertEquals(DeferredRevealCompensator.Decision(500, 72, true), c.onMeasure(572, shouldCompensate = true))
        // 揭示遍：遍首已应用 → 全量
        assertEquals(DeferredRevealCompensator.Decision(572, 0, false), c.onMeasure(572, shouldCompensate = true))
    }

    @Test
    fun deferred_scrollHold_revealsPairedOnly_noInject() {
        val c = DeferredRevealCompensator()
        c.onMeasure(500, shouldCompensate = true)
        c.onMeasure(572, shouldCompensate = true) // 注入 72 挂账

        // 滚动中（holdReveal）：只揭示已配对部分 572（600 的增长被裁剪）、清账、不注入
        assertEquals(
            DeferredRevealCompensator.Decision(572, 0, false),
            c.onMeasure(600, shouldCompensate = true, holdReveal = true),
        )

        // 停滚后：新增长 640 相对配对基准 572 → 常规配对（572, 68, true）
        assertEquals(DeferredRevealCompensator.Decision(572, 68, true), c.onMeasure(640, shouldCompensate = true))
        assertEquals(DeferredRevealCompensator.Decision(640, 0, false), c.onMeasure(640, shouldCompensate = true))
    }

    @Test
    fun deferred_raceGate_unappliedShift_holdsClipped_thenReveals() {
        val c = DeferredRevealCompensator()
        c.onMeasure(500, shouldCompensate = true)
        // 增长 +72：注入挂账
        assertEquals(DeferredRevealCompensator.Decision(500, 72, true), c.onMeasure(572, shouldCompensate = true))

        // 同帧重测插队（位移未落地）：保持基准裁剪、不重入队
        assertEquals(
            DeferredRevealCompensator.Decision(500, 0, false),
            c.onMeasure(572, shouldCompensate = true, shiftApplied = false),
        )

        // 位移落地：全量揭示
        assertEquals(DeferredRevealCompensator.Decision(572, 0, false), c.onMeasure(572, shouldCompensate = true))
    }

    @Test
    fun deferred_notCompensating_revealsFull_clearsDebt() {
        val c = DeferredRevealCompensator()
        c.onMeasure(500, shouldCompensate = true)
        c.onMeasure(572, shouldCompensate = true) // 注入 72 挂账

        // 贴底/回底：补偿语境消失，全量揭示清欠（边界钳制兜底）
        assertEquals(DeferredRevealCompensator.Decision(600, 0, false), c.onMeasure(600, shouldCompensate = false))
    }

    @Test
    fun deferred_shrink_revealsFull_noInject() {
        val c = DeferredRevealCompensator()
        c.onMeasure(500, shouldCompensate = true)

        // 收缩：完全揭示、重置基准、无注入
        assertEquals(DeferredRevealCompensator.Decision(300, 0, false), c.onMeasure(300, shouldCompensate = true))
    }
}