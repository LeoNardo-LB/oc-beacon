package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #429 L1-v2：[tableGroupBounds] 行组边界机械不变量（JVM 纯逻辑）。 */
class TableGroupBoundsTest {

    @Test
    fun `空表无组`() {
        assertTrue(tableGroupBounds(0).isEmpty())
    }

    @Test
    fun `首组 8 行其余 12 行`() {
        val b = tableGroupBounds(61)
        assertEquals(0..7, b[0])
        assertEquals(8..19, b[1])
        assertEquals(20..31, b[2])
        assertEquals(56..60, b.last())
        assertEquals(6, b.size)
    }

    @Test
    fun `组无缝连续覆盖全部行`() {
        val b = tableGroupBounds(45)
        var expected = 0
        b.forEach { gr ->
            assertEquals(expected, gr.first)
            expected = gr.last + 1
        }
        assertEquals(45, expected)
    }

    @Test
    fun `小于阈值由调用方走原路径`() {
        // 阈值本身是常量契约
        assertEquals(20, TABLE_VIRTUALIZE_MIN_ROWS)
    }
}
