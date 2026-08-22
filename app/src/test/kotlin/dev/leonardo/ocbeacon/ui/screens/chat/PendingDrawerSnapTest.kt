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
    fun `anchorsPx offset 锚点 全开0 收起=容器-handle`() {
        // offset 语义（第四轮重构）：Surface 固定高=80% 容器、底贴容器底；
        // 档位 = 自全开位置的下移量 = fullPx − 露出高度
        val a = PendingDrawerAnchors.anchorsPx(containerHeightPx = 1000f, handlePx = 44f)
        assertEquals(3, a.size)
        // 收起：800−44=756（只露 handle）
        assertEquals(756f, a[PendingDrawerAnchors.SNAP_COLLAPSED], 0.01f)
        // 半开：800−200=600（露 20% 容器）
        assertEquals(600f, a[PendingDrawerAnchors.SNAP_MID], 0.01f)
        // 全开：0（Surface 顶=容器底−80%）
        assertEquals(0f, a[PendingDrawerAnchors.SNAP_FULL], 0.01f)
    }

    @Test
    fun `nearestSnapIndex 各锚点邻域吸附正确（offset 递减序）`() {
        // offset 锚递减（收起 756 > 半开 600 > 全开 0）——nearestSnap 按距离，与序无关
        val anchors = floatArrayOf(756f, 600f, 0f)
        assertEquals(PendingDrawerAnchors.SNAP_COLLAPSED, nearestSnapIndex(756f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_MID, nearestSnapIndex(600f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_FULL, nearestSnapIndex(0f, anchors))
        // 中点偏移：靠近哪档吸哪档
        assertEquals(PendingDrawerAnchors.SNAP_COLLAPSED, nearestSnapIndex(700f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_MID, nearestSnapIndex(650f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_MID, nearestSnapIndex(340f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_FULL, nearestSnapIndex(260f, anchors))
        // 拖出上下界（coerceIn 之外的防御值）
        assertEquals(PendingDrawerAnchors.SNAP_COLLAPSED, nearestSnapIndex(900f, anchors))
        assertEquals(PendingDrawerAnchors.SNAP_FULL, nearestSnapIndex(-50f, anchors))
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
        assertEquals(0.20f, PendingDrawerAnchors.FRACTIONS[PendingDrawerAnchors.SNAP_MID], 0.0001f)
        assertEquals(0.80f, PendingDrawerAnchors.FRACTIONS[PendingDrawerAnchors.SNAP_FULL], 0.0001f)
    }
}
