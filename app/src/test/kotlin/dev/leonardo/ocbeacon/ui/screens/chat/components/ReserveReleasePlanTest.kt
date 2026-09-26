package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #437 引擎①「一帧缓冲帽」释放决策回归（z3 锚 index 语义：横幅区浅滑位配对、
 * 读历史位免配对、贴底原点免配对、手势持帽、单调不回改）。
 */
class ReserveReleasePlanTest {

    private fun plan(
        reserved: Int,
        trueHeight: Int,
        fii: Int = 7,
        fiso: Int = 900,
        scrolling: Boolean = false,
        growthIndex: Int? = 7,
    ) = reserveReleasePlan(reserved, trueHeight, fii, fiso, scrolling, growthIndex)

    @Test
    fun `未初始化不释放`() {
        assertNull(plan(reserved = -1, trueHeight = 500))
    }

    @Test
    fun `无增量不释放且收缩不回改`() {
        assertNull(plan(reserved = 1000, trueHeight = 1000))
        assertNull(plan(reserved = 1000, trueHeight = 900))
    }

    @Test
    fun `手势进行中持帽不释放`() {
        assertNull(plan(reserved = 1000, trueHeight = 1066, scrolling = true))
    }

    @Test
    fun `锚等于增长项配对滚动`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true),
            plan(reserved = 1000, trueHeight = 1066),
        )
    }

    @Test
    fun `横幅区浅滑位锚在增长项下方也配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true),
            plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 21, growthIndex = 7),
        )
    }

    @Test
    fun `贴底原点免配对只放帽`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false),
            plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 5),
        )
    }

    @Test
    fun `读历史位锚在增长项上方免配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false),
            plan(reserved = 1000, trueHeight = 1066, fii = 9, growthIndex = 7),
        )
    }

    @Test
    fun `增长项不可见免配对保守放帽`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false),
            plan(reserved = 1000, trueHeight = 1066, growthIndex = null),
        )
    }
}
