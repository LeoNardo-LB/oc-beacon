package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #427 P3 窗口范围纯函数（宿主组合级行为由真机验收覆盖——spec §Testing
 * Decisions；此函数是宿主内唯一的几何决策，独立成纯 seam 顺带单测）。
 */
class SliceWindowRangeTest {

    // 4 片，各 1000px：tops = [0, 1000, 2000, 3000, 4000]
    private val tops = intArrayOf(0, 1000, 2000, 3000, 4000)

    @Test
    fun viewportInsideFirstSliceSelectsNeighborhood() {
        // 宿主顶 y=500，视口 [0,2000]，余量 1000：扩展窗 root[-1000,3000]，
        // 片 3(root[3500,4500]) 不相交 → 0..2
        val r = visibleSliceRange(4, tops, hostTopY = 500f, viewportTop = 0f, viewportBottom = 2000f, marginPx = 1000f)
        assertEquals(0..2, r)
    }

    @Test
    fun scrolledDeepSelectsMiddleSlicesOnly() {
        // 宿主顶远在视口上方(y=-2500)，视口 [0,2000]，余量 1000：
        // 片 root 区间 s1[-1500,-500]/s2[-500,500]/s3[500,1500] 均交扩展窗[-1000,3000]
        val r = visibleSliceRange(4, tops, hostTopY = -2500f, viewportTop = 0f, viewportBottom = 2000f, marginPx = 1000f)
        assertEquals(1..3, r)
    }

    @Test
    fun marginExtendsOneScreenBeyondViewport() {
        // 余量 500：窗口 root[-500,2500] → 片 0,1,2 相交，片 3 不
        val r = visibleSliceRange(4, tops, hostTopY = 0f, viewportTop = 0f, viewportBottom = 2000f, marginPx = 500f)
        assertEquals(0..2, r)
    }

    @Test
    fun hostFarBelowViewportSelectsFirstSlices() {
        // 宿主整体在视口下方一个余量内：仅片 0 底缘进入扩展窗
        val r = visibleSliceRange(4, tops, hostTopY = 2400f, viewportTop = 0f, viewportBottom = 2000f, marginPx = 500f)
        assertEquals(0..0, r)
    }

    @Test
    fun completelyOutsideReturnsNullClampedEmpty() {
        // 宿主远在扩展窗外：无相交 → null（宿主保持空窗）
        val r = visibleSliceRange(4, tops, hostTopY = 6000f, viewportTop = 0f, viewportBottom = 2000f, marginPx = 500f)
        assertNull(r)
    }

    @Test
    fun nanPositionReturnsNull() {
        assertNull(visibleSliceRange(4, tops, Float.NaN, viewportTop = 0f, viewportBottom = 2000f, marginPx = 1000f))
    }

    @Test
    fun emptySlicesReturnNull() {
        assertNull(visibleSliceRange(0, intArrayOf(0), hostTopY = 0f, viewportTop = 2000f, viewportBottom = 2000f, marginPx = 1000f))
    }

    @Test
    fun zeroHeightTailSlicesNotSelected() {
        // 高度 0 的片（账本冷占位）不因零宽相交被选中——除非窗口真覆盖
        val topsZ = intArrayOf(0, 1000, 1000, 1000)
        val r = visibleSliceRange(3, topsZ, hostTopY = -2000f, viewportTop = 0f, viewportBottom = 100f, marginPx = 100f)
        assertNull(r)
    }
}
