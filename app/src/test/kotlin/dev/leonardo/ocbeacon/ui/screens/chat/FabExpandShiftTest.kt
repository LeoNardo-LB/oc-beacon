package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #194 D2 溢出量纯函数单测（全稳定量版）：边界（恰好够/溢出/拖到顶）+ token/边距联动。
 * 数值模型（houji @480dpi 实测量级）：折叠高 collapsed=120px、menuSpan=520px、
 * menuPad=24px（8dp）、顶边距=24px（8dp）、容器高 H=1954px。
 */
class FabExpandShiftTest {

    private val collapsed = 120f
    private val span = 520f
    private val pad = 24f
    private val margin = 24f
    private val h = 1954f

    private fun shift(offsetY: Float) = computeFabExpandShiftPx(
        collapsedPx = collapsed,
        menuSpanPx = span,
        containerPx = h,
        offsetYPx = offsetY,
        menuPadPx = pad,
        topMarginPx = margin,
    )

    /** 停底部原位（offsetY=0）：完全展开高 664 << 容器 1954，无溢出。 */
    @Test
    fun `enough space at rest - no shift`() {
        assertEquals(0f, shift(0f), 0f)
    }

    /** 临界点：expandedH − H − offsetY == 0（恰好达顶）→ 0（边界连续，无跳变）。 */
    @Test
    fun `exactly reaches top - zero not margin`() {
        // offsetY = expandedH − H = 664 − 1954 = −1290
        assertEquals(0f, shift(-1290f), 0f)
    }

    /** 溢出 X → shift = X + 顶边距（「顶到顶部」：items 顶缘贴容器顶 +8dp）。 */
    @Test
    fun `overflow pushes down by overflow plus margin`() {
        // offsetY=−1500：overflow = 664−1954+1500 = 210 → shift = 210+24
        assertEquals(210f + margin, shift(-1500f), 0.01f)
    }

    /** 拖到极限（D1 上限 offsetY≈−1810）：overflow = 520 → shift = 544（真机 E4d 场景）。 */
    @Test
    fun `dragged to top limit - full overflow`() {
        assertEquals(520f + margin, shift(-1810f), 0.01f)
    }

    /** 负溢出（空间富余）：clamp 到 0，不产生负下移。 */
    @Test
    fun `slack clamps to zero`() {
        assertEquals(0f, shift(-1000f), 0f)
    }

    /** menuPad 计入完全展开高：pad 增大 → 同位移下 shift 等量增大。 */
    @Test
    fun `menu pad included in expanded height`() {
        val base = computeFabExpandShiftPx(collapsed, span, h, -1500f, pad, margin)
        val bigger = computeFabExpandShiftPx(collapsed, span, h, -1500f, pad + 10f, margin)
        assertEquals(10f, bigger - base, 0.01f)
    }
}
