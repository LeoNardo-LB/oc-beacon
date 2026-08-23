package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #194 D2 溢出量纯函数单测：边界（恰好够/溢出/深溢出）+ 展开位移无关性 + 停放位移联动。
 * 数值模型：容器高 2000px、root 坐标系容器顶 T=1000（顶栏占上部）、菜单整体占位 460px。
 */
class FabExpandShiftTest {

    private val margin = 8f

    /** 停底部原位（offsetY=0）：菜单整体在容器内，无溢出。 */
    @Test
    fun `enough space at rest - no shift`() {
        val shift = computeFabExpandShiftPx(
            nodeBottomInRoot = 3000f,
            firstItemTopInRoot = 2540f,
            containerHeightPx = 2000f,
            offsetYPx = 0f,
            topMarginPx = margin,
        )
        assertEquals(0f, shift, 0f)
    }

    /** 临界点：items 顶缘恰好达容器顶（溢出 = 0）→ shift = 0（边界连续，无跳变）。 */
    @Test
    fun `exactly reaches top - zero not margin`() {
        val shift = computeFabExpandShiftPx(
            nodeBottomInRoot = 3000f,
            firstItemTopInRoot = 1000f,
            containerHeightPx = 2000f,
            offsetYPx = 0f,
            topMarginPx = margin,
        )
        assertEquals(0f, shift, 0f)
    }

    /** 溢出 X → shift = X + 顶边距（「顶到顶部」：items 顶缘贴容器顶 +8dp）。 */
    @Test
    fun `overflow pushes down by overflow plus margin`() {
        // 上拖 1600：nodeBottom = T(1000)+2000-1600 = 1400, firstItemTop = 1400-460 = 940
        // ⇒ items 顶缘越过容器顶 60px
        val shift = computeFabExpandShiftPx(
            nodeBottomInRoot = 1400f,
            firstItemTopInRoot = 940f,
            containerHeightPx = 2000f,
            offsetYPx = -1600f,
            topMarginPx = margin,
        )
        assertEquals(60f + margin, shift, 0.01f)
    }

    /** 拖到极限（D1 上限 ≈ 容器高 − 节点高 − 8dp）：溢出 = 顶到容器顶外的整段。 */
    @Test
    fun `dragged to top limit - full span overflow`() {
        // offsetY = -(2000 - 88 - 8) = -1904：nodeBottom = 1096, firstItemTop = 636
        // 溢出 = T(1000) - 636 = 364
        val shift = computeFabExpandShiftPx(
            nodeBottomInRoot = 1096f,
            firstItemTopInRoot = 636f,
            containerHeightPx = 2000f,
            offsetYPx = -1904f,
            topMarginPx = margin,
        )
        assertEquals(364f + margin, shift, 0.01f)
    }

    /** 展开/收起动画中途实测：nodeBottom 与 firstItemTop 同加 shift 分量，结果不变。 */
    @Test
    fun `shift invariance - mid animation measurement`() {
        val base = computeFabExpandShiftPx(
            nodeBottomInRoot = 1400f,
            firstItemTopInRoot = 940f,
            containerHeightPx = 2000f,
            offsetYPx = -1600f,
            topMarginPx = margin,
        )
        val midAnimation = computeFabExpandShiftPx(
            nodeBottomInRoot = 1400f + 123f,
            firstItemTopInRoot = 940f + 123f,
            containerHeightPx = 2000f,
            offsetYPx = -1600f,
            topMarginPx = margin,
        )
        assertEquals(base, midAnimation, 0.01f)
    }

    /** 负溢出（空间富余很多）：clamp 到 0，不产生负下移。 */
    @Test
    fun `slack clamps to zero`() {
        val shift = computeFabExpandShiftPx(
            nodeBottomInRoot = 3000f,
            firstItemTopInRoot = 2900f,
            containerHeightPx = 2000f,
            offsetYPx = 0f,
            topMarginPx = margin,
        )
        assertEquals(0f, shift, 0f)
    }
}
