package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 堆积/TODO 常驻抽屉纯函数单测（2026-08-22 设计定案 grilling Q4-Q17）。
 */
class PendingDrawerSnapTest {

    @Test
    fun `anchorsPx 收起=标题栏固定高 其余按容器比例`() {
        val a = PendingDrawerAnchors.anchorsPx(containerHeightPx = 1000f, headerPx = 132f)
        assertEquals(3, a.size)
        assertEquals(132f, a[PendingDrawerAnchors.SNAP_COLLAPSED], 0.01f)
        assertEquals(300f, a[PendingDrawerAnchors.SNAP_MID], 0.01f)
        assertEquals(600f, a[PendingDrawerAnchors.SNAP_FULL], 0.01f)
    }

    @Test
    fun `nearestSnapIndex 各锚点邻域吸附正确`() {
        val anchors = floatArrayOf(132f, 300f, 600f)
        assertEquals(PendingDrawerAnchors.SNAP_COLLAPSED, nearestSnapIndex(132f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_MID, nearestSnapIndex(300f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_FULL, nearestSnapIndex(600f, anchors))
        // 中点偏移：靠近哪档吸哪档
        assertEquals(PendingDrawerAnchors.SNAP_COLLAPSED, nearestSnapIndex(200f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_MID, nearestSnapIndex(240f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_MID, nearestSnapIndex(430f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_FULL, nearestSnapIndex(470f, anchors))
        // 拖出上下界（coerceIn 之外的防御值）
        assertEquals(PendingDrawerAnchors.SNAP_COLLAPSED, nearestSnapIndex(0f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_FULL, nearestSnapIndex(1000f, anchors))
    }

    @Test
    fun `nearestSnapIndex 等距平局取更低档`() {
        val anchors = floatArrayOf(100f, 300f, 600f)
        // 200 距 100/300 均为 100——minBy 语义取先者（收起档）
        assertEquals(PendingDrawerAnchors.SNAP_COLLAPSED, nearestSnapIndex(200f, anchors))
    }

    @Test
    fun `pendingDrawerVisible 双空隐藏其余可见`() {
        assertFalse(pendingDrawerVisible(0, 0, todoCapable = true))
        assertFalse(pendingDrawerVisible(0, 0, todoCapable = false))
        assertTrue(pendingDrawerVisible(1, 0, todoCapable = false))
        assertTrue(pendingDrawerVisible(0, 2, todoCapable = true))
        // 无能力但 SSE 已有数据（视为有能力的老语义由调用方归一）
        assertFalse(pendingDrawerVisible(0, 2, todoCapable = false))
    }

    @Test
    fun `档位常量自洽`() {
        assertEquals(3, PendingDrawerAnchors.SNAP_COUNT)
        assertEquals(0.30f, PendingDrawerAnchors.FRACTIONS[PendingDrawerAnchors.SNAP_MID], 0.0001f)
        assertEquals(0.60f, PendingDrawerAnchors.FRACTIONS[PendingDrawerAnchors.SNAP_FULL], 0.0001f)
    }
}
