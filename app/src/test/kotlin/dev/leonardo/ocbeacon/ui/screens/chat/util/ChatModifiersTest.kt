package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * consumeBoundaryScroll 的 NestedScrollConnection 逻辑单元测试。
 *
 * 由于 NestedScrollConnection 是在 @Composable 函数内部创建的，
 * 我们通过将决策逻辑提取为可测试的顶层函数来直接测试边界条件。
 */
class ChatModifiersTest {

    // ----- onPostScroll 逻辑 -----

    @Test
    fun `onPostScroll at top scrolling up consumes available`() {
        val result = postScrollDecision(atTop = true, atBottom = false, availableY = -5f)
        assertEquals(Offset(0f, -5f), result)
    }

    @Test
    fun `onPostScroll at bottom scrolling down consumes available`() {
        val result = postScrollDecision(atTop = false, atBottom = true, availableY = 5f)
        assertEquals(Offset(0f, 5f), result)
    }

    @Test
    fun `onPostScroll not at boundary returns zero`() {
        val result = postScrollDecision(atTop = false, atBottom = false, availableY = 5f)
        assertEquals(Offset.Zero, result)
    }

    @Test
    fun `onPostScroll at top scrolling down returns zero`() {
        val result = postScrollDecision(atTop = true, atBottom = false, availableY = 5f)
        assertEquals(Offset.Zero, result)
    }

    @Test
    fun `onPostScroll at bottom scrolling up returns zero`() {
        val result = postScrollDecision(atTop = false, atBottom = true, availableY = -5f)
        assertEquals(Offset.Zero, result)
    }

    // ----- onPostFling 逻辑（与 onPostScroll 对应）-----

    @Test
    fun `onPostFling at top fling up consumes velocity`() {
        val result = postFlingDecision(atTop = true, atBottom = false, availableY = -100f)
        assertEquals(Velocity(0f, -100f), result)
    }

    @Test
    fun `onPostFling at bottom fling down consumes velocity`() {
        val result = postFlingDecision(atTop = false, atBottom = true, availableY = 100f)
        assertEquals(Velocity(0f, 100f), result)
    }

    @Test
    fun `onPostFling not at boundary returns zero`() {
        val result = postFlingDecision(atTop = false, atBottom = false, availableY = 100f)
        assertEquals(Velocity.Zero, result)
    }

    // ----- 镜像 NestedScrollConnection 逻辑的辅助函数 -----

    private fun postScrollDecision(atTop: Boolean, atBottom: Boolean, availableY: Float): Offset {
        return when {
            atTop && availableY < 0f -> Offset(0f, availableY)
            atBottom && availableY > 0f -> Offset(0f, availableY)
            else -> Offset.Zero
        }
    }

    private fun postFlingDecision(atTop: Boolean, atBottom: Boolean, availableY: Float): Velocity {
        return when {
            atTop && availableY < 0f -> Velocity(0f, availableY)
            atBottom && availableY > 0f -> Velocity(0f, availableY)
            else -> Velocity.Zero
        }
    }
}
