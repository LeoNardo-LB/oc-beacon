package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ExpandRevealCompensator 状态机回归（#收起留白 2026-08-29）。
 *
 * 缝隙：纯逻辑类直接驱动 onMeasure——真机 [DEBUG-evcol] 迹线的逐帧重放。
 */
class ExpandRevealCompensatorTest {

    private fun compensator() = ExpandRevealCompensator()

    @Test
    fun `收起链末帧落在竞态门 hold 时不得冻结在旧展开高度`() {
        val c = compensator()
        // 首测：展开态全高 1129（真机迹线原值）
        assertEquals(1129 to 0, c.onMeasure(1129, shiftApplied = true))
        // 收起动画 chain 帧（report=上一遍 reveal，inject=递延增量）
        assertEquals(1129 to -76, c.onMeasure(1053, shiftApplied = true))
        assertEquals(1053 to -52, c.onMeasure(1001, shiftApplied = true))
        // 直落收起尾（pending 累计 -803）
        assertEquals(1001 to -675, c.onMeasure(326, shiftApplied = true))
        // ★ 回归点：动画末帧同帧重测（shift 未落地）——上报必须等于真实高度 326，
        // 旧实现返回 1129（展开全高）→ 动画已停、无失效源 → 永久空白。
        assertEquals(326 to 0, c.onMeasure(326, shiftApplied = false))
        // 位移落地后的收尾遍：正常 settle 到真实高度
        assertEquals(326 to 0, c.onMeasure(326, shiftApplied = true))
        // 之后再展开：从 326 基准正常配对，无漂移
        assertEquals(326 to 300, c.onMeasure(626, shiftApplied = true))
        assertEquals(626 to 0, c.onMeasure(626, shiftApplied = true))
    }

    @Test
    fun `展开侧竞态门保持基准裁剪不揭示（2026-08-27 裁决不回归）`() {
        val c = compensator()
        assertEquals(199 to 0, c.onMeasure(199, shiftApplied = true))
        // 展开 +300：chain 注入，等待位移
        assertEquals(199 to 300, c.onMeasure(499, shiftApplied = true))
        // 同帧重测（shift 未落地）：+Δ 揭示先于位移 = 跳变 → 必须钳在基准 199
        assertEquals(199 to 0, c.onMeasure(499, shiftApplied = false))
        // 位移落地：settle 到真实高度
        assertEquals(499 to 0, c.onMeasure(499, shiftApplied = true))
    }

    @Test
    fun `首测零高（收起态新组合）不短路仍走通用配对`() {
        val c = compensator()
        assertEquals(0 to 0, c.onMeasure(0, shiftApplied = true))
        // 展开动画首帧：从 0 基准配对增长（2026-08-27 -18px 漂移回归防线）
        val (report, inject) = c.onMeasure(300, shiftApplied = true)
        assertEquals(0, report)
        assertEquals(300, inject)
        // 位移落地后揭示
        assertEquals(300 to 0, c.onMeasure(300, shiftApplied = true))
    }
}