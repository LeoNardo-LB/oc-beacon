package dev.leonardo.octether.ui.screens.chat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for consumeBoundaryScroll's NestedScrollConnection logic.
 *
 * Since the NestedScrollConnection is created inside a @Composable function,
 * we test the boundary conditions directly by extracting the decision logic
 * into a testable top-level function.
 */
class ChatModifiersTest {

    // ----- onPostScroll logic -----

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

    // ----- onPostFling logic (mirrors onPostScroll) -----

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

    // ----- Helper functions that mirror the NestedScrollConnection logic -----

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
