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
        expandPaired: Boolean = false,
        anchorAboveReveal: Boolean = false,
        expandPairedPos: Boolean? = null,
    ) = reserveReleasePlan(reserved, trueHeight, fii, fiso, scrolling, growthIndex, expandPaired, expandPairedPos, anchorAboveReveal)

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
            ReserveReleasePlan(delta = 66, scrollPaired = true, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066),
        )
    }

    @Test
    fun `横幅区浅滑位锚在增长项下方也配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 21, growthIndex = 7),
        )
    }

    @Test
    fun `贴底原点免配对只放帽`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 5),
        )
    }

    @Test
    fun `读历史位锚在增长项上方免配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, fii = 9, growthIndex = 7),
        )
    }

    @Test
    fun `增长项不可见免配对保守放帽`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, growthIndex = null),
        )
    }

    // ============ #540 方向 A：展开配对窗（expandPaired 通道——流式降级路径展开增长恒配对） ============

    @Test
    fun `540 展开窗内贴底原点恒配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 5, expandPaired = true),
        )
    }

    @Test
    fun `540 展开窗内读历史位恒配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, fii = 9, growthIndex = 7, expandPaired = true),
        )
    }

    @Test
    fun `540 展开窗内增长项不可见恒配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, growthIndex = null, expandPaired = true),
        )
    }

    @Test
    fun `540 展开窗内收缩规划回改配对`() {
        assertEquals(
            ReserveReleasePlan(delta = -100, scrollPaired = true, hatDeltaNet = -100),
            plan(reserved = 1000, trueHeight = 900, expandPaired = true),
        )
    }

    @Test
    fun `540 展开窗内手势进行中仍持帽不释放`() {
        assertNull(plan(reserved = 1000, trueHeight = 1066, scrolling = true, expandPaired = true))
    }

    @Test
    fun `540 窗外收缩仍不回改（存量语义不变）`() {
        assertNull(plan(reserved = 1000, trueHeight = 900, expandPaired = false))
    }

    @Test
    fun `540 二轮修订 锚在揭示缘上方 窗内正增长否决配对（增长在锚下方）`() {
        // 用户复测定罪「输出中展开震」：阅读位高于被展开卡时，+H 在锚下方——
        // 锚所见内容不动，配对位移=自造推动 → 免配对释放（帽放行无滚动写）
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = false, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, expandPaired = true, anchorAboveReveal = true),
        )
    }

    @Test
    fun `540 二轮修订 否决只作用正向 窗内收缩不受影响`() {
        // 负向（收起）走负向分支自己的塌缩几何判定——锚在缘上方恰是镜像格
        assertEquals(
            ReserveReleasePlan(delta = -100, scrollPaired = true, hatDeltaNet = -100),
            plan(reserved = 1000, trueHeight = 900, expandPaired = true, anchorAboveReveal = true),
        )
    }

    @Test
    fun `540 二轮修订 未触发否决时窗内正增长照常恒配对`() {
        assertEquals(
            ReserveReleasePlan(delta = 66, scrollPaired = true, hatDeltaNet = 66),
            plan(reserved = 1000, trueHeight = 1066, expandPaired = true, anchorAboveReveal = false),
        )
    }
    // ============ #549 Fix B：正向窗配对动画门槛 ============

    @Test
    fun `549B settle 后窗尾巴正向回落锚规则 - 贴底原点免派发`() {
        // emu-r11/r427 定罪：驱动全 settle（窗未关）时帽把流式增长升格离底让位，
        // 内容短于视口被钳回 (0,0) 逐帧重发。expandPairedPos=false → 贴底原点
        //（fii=0, fiso=5）免派发（回流 StreamingAnchorRule 语义）。
        val p = plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 5,
            expandPaired = true, expandPairedPos = false)
        assertEquals(ReserveReleasePlan(delta = 66, scrollPaired = false, hatDeltaNet = 66), p)
    }

    @Test
    fun `549B 动画中驱动正向恒配对 - 离底让位保持`() {
        val p = plan(reserved = 1000, trueHeight = 1066, fii = 0, fiso = 5,
            expandPaired = true, expandPairedPos = true)
        assertEquals(ReserveReleasePlan(delta = 66, scrollPaired = true, hatDeltaNet = 66), p)
    }

    @Test
    fun `549B 负向例外不受 expandPairedPos 影响 - 收起集帽回落仍走窗语义`() {
        // delta<0 的窗内例外只看 expandPaired（防收起结算卡死）——expandPairedPos=false 不得掐死
        val p = plan(reserved = 1066, trueHeight = 1000,
            expandPaired = true, expandPairedPos = false)
        assertEquals(ReserveReleasePlan(delta = -66, scrollPaired = true, hatDeltaNet = -66), p)
    }
}
