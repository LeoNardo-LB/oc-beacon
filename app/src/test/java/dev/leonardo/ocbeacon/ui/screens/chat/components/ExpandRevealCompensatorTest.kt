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
        assertEquals(1129 to 0, c.onMeasure(1129, shiftApplied = true))
        assertEquals(1129 to -76, c.onMeasure(1053, shiftApplied = true))
        assertEquals(1053 to -52, c.onMeasure(1001, shiftApplied = true))
        assertEquals(1001 to -675, c.onMeasure(326, shiftApplied = true))
        // ★ 回归点：动画末帧同帧重测（shift 未落地）——上报必须等于真实高度，
        // 旧实现返回旧展开高 → 动画已停、无失效源 → 永久空白。
        assertEquals(326 to 0, c.onMeasure(326, shiftApplied = false))
        assertEquals(326 to 0, c.onMeasure(326, shiftApplied = true))
        assertEquals(326 to 300, c.onMeasure(626, shiftApplied = true))
        assertEquals(626 to 0, c.onMeasure(626, shiftApplied = true))
    }

    @Test
    fun `展开侧竞态门保持基准裁剪不揭示（2026-08-27 裁决不回归）`() {
        val c = compensator()
        assertEquals(199 to 0, c.onMeasure(199, shiftApplied = true))
        assertEquals(199 to 300, c.onMeasure(499, shiftApplied = true))
        assertEquals(199 to 0, c.onMeasure(499, shiftApplied = false))
        assertEquals(499 to 0, c.onMeasure(499, shiftApplied = true))
    }

    @Test
    fun `锚定在底展开走配对注入（贴底不往上顶 2026-08-30 裁决）`() {
        val c = compensator()
        // 贴底展开不再透传（旧契约撤销）：配对注入经 drain pre-shift 预移视窗
        // = 上方内容固定、向下生长揭示（视口脱离贴底由通道负责）。
        // 首测贴底：全量上报 + 等量预移（首帧即可见且上方固定，无隐身帧）
        assertEquals(332 to 332, c.onMeasure(332, shiftApplied = true, anchoredAtBottom = true))
        assertEquals(332 to 79, c.onMeasure(411, shiftApplied = true, anchoredAtBottom = true))
        // 竞态门：展开侧保持基准裁剪（揭示先于位移防跳变，2026-08-27 裁决不回归；
        // 基准 = 首测上报高 332，故 hold report=332）
        assertEquals(332 to 0, c.onMeasure(411, shiftApplied = false, anchoredAtBottom = true))
        assertEquals(411 to 0, c.onMeasure(411, shiftApplied = true, anchoredAtBottom = true))
    }

    @Test
    fun `锚定在底收起仍透传（上方承担位移，列表尾不露空白）`() {
        val c = compensator()
        assertEquals(332 to 332, c.onMeasure(332, shiftApplied = true, anchoredAtBottom = true))
        assertEquals(200 to 0, c.onMeasure(200, shiftApplied = true, anchoredAtBottom = true))
        assertEquals(150 to 0, c.onMeasure(150, shiftApplied = false, anchoredAtBottom = true))
    }

    @Test
    fun `首测零高（收起态新组合）不短路仍走通用配对`() {
        val c = compensator()
        assertEquals(0 to 0, c.onMeasure(0, shiftApplied = true))
        val (report, inject) = c.onMeasure(300, shiftApplied = true)
        assertEquals(0, report)
        assertEquals(300, inject)
        assertEquals(300 to 0, c.onMeasure(300, shiftApplied = true))
    }
}