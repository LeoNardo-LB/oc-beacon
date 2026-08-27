package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #244 边界岛吞噬判定纯函数契约。
 *
 * 符号约定：dy>0 = 手指向下滑（向顶边/backward）；dy<0 = 向上滑（底边/forward）。
 */
class ScrollIslandConsumeTest {

    // —— 中段（双向皆可滚）：完全透明 ——

    @Test
    fun `mid content passes both directions through`() {
        assertEquals(0f, scrollIslandConsumeY(12f, true, true, true))
        assertEquals(0f, scrollIslandConsumeY(-30f, true, true, true))
        assertEquals(0f, scrollIslandConsumeY(-0.5f, true, true, true))
    }

    // —— 顶边封死向下压 ——

    @Test
    fun `at top downward push is consumed`() {
        assertEquals(8f, scrollIslandConsumeY(8f, false, true, true))
    }

    @Test
    fun `at top upward fling still passes`() {
        assertEquals(0f, scrollIslandConsumeY(-400f, false, true, true))
    }

    // —— 底边封死向上推 ——

    @Test
    fun `at bottom upward push is consumed`() {
        assertEquals(-15f, scrollIslandConsumeY(-15f, true, false, true))
    }

    @Test
    fun `at bottom downward drag still passes`() {
        assertEquals(0f, scrollIslandConsumeY(9f, true, false, true))
    }

    // —— 不可滚内容（maxValue<=0 ⇔ scrollable=false 或双 false）：完全透明 ——

    @Test
    fun `non-scrollable short content stays transparent`() {
        assertEquals(0f, scrollIslandConsumeY(20f, false, false, false))
        assertEquals(0f, scrollIslandConsumeY(-20f, false, false, false))
        assertEquals(0f, scrollIslandConsumeY(0f, false, false, false))
    }

    // —— 零位移零消耗 ——

    @Test
    fun `zero delta never consumes`() {
        assertEquals(0f, scrollIslandConsumeY(0f, false, true, true))
        assertEquals(0f, scrollIslandConsumeY(0f, true, false, true))
    }
}
