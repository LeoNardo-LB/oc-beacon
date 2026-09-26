package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #437 引擎①「一帧缓冲帽」释放决策回归：
 * 增长当帧帽保持旧高（增量不可见）→ pre-draw 单事务 {帽→真高 + 配对滚动} →
 * 下一 measure 同 pass 原子生效。决策门：手势持帽 / 贴底免配对 / 锚上方免配对。
 */
class ReserveReleasePlanTest {

    private fun plan(
        reserved: Int,
        trueHeight: Int,
        fii: Int = 7,
        fiso: Int = 900,
        scrolling: Boolean = false,
        anchorKey: Any? = "t_anchor",
        growthKey: Any? = "t_anchor",
    ) = reserveReleasePlan(reserved, trueHeight, fii, fiso, scrolling, anchorKey, growthKey)

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
    fun `锚等于增长源且非贴底配对滚动`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true),
            plan(reserved = 1000, trueHeight = 1066),
        )
    }

    @Test
    fun `贴底原点免配对只放帽`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false),
            plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 20),
        )
    }

    @Test
    fun `锚在增长源上方免配对只放帽`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false),
            plan(reserved = 1000, trueHeight = 1066, anchorKey = "t_older", growthKey = "t_anchor"),
        )
    }

    @Test
    fun `锚键缺失免配对保守放帽`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false),
            plan(reserved = 1000, trueHeight = 1066, anchorKey = null),
        )
    }
}
