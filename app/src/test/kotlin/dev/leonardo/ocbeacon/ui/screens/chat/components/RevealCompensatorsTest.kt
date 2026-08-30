package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #258 换道手术配套单测：流式补偿状态机（DeferredRevealCompensator）的配对
 * 语义与滚动守卫。
 *
 * 运输层（PreRenderShiftChannel→request-position）只改变注入的**投递方式**，
 * 状态机的「增长遍上报基准 → 遍首应用 → 揭示遍全量配对」契约不变——
 * 这里锁住该契约，防止未来运输层再动时配对语义被无声破坏。
 *
 * 2026-08-30：ExpandRevealCompensator（tap 展开家族）随用户裁决「回归原生
 * 展开/收起」整体退役——本文件只保留流式家族用例。
 */
class RevealCompensatorsTest {

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
