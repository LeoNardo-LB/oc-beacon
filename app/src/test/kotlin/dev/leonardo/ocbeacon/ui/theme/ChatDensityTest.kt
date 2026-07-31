package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDensityTest {

    @Test
    fun `Normal body font size is 14sp`() {
        assertEquals(14.sp, ChatDensity.Normal.typography.bodyFontSize)
    }

    @Test
    fun `Normal body line height is 22sp`() {
        assertEquals(22.sp, ChatDensity.Normal.typography.bodyLineHeight)
    }

    @Test
    fun `Compact body font size is 13sp`() {
        assertEquals(13.sp, ChatDensity.Compact.typography.bodyFontSize)
    }

    @Test
    fun `Normal h1 is body plus 4`() {
        assertEquals(18.sp, ChatDensity.Normal.typography.h1.fontSize)
    }

    @Test
    fun `Normal h6 equals body size`() {
        assertEquals(ChatDensity.Normal.typography.bodyFontSize,
            ChatDensity.Normal.typography.h6.fontSize)
    }

    @Test
    fun `Compact h1 is body plus 4`() {
        assertEquals(17.sp, ChatDensity.Compact.typography.h1.fontSize)
    }

    @Test
    fun `Normal code equals table font size`() {
        assertEquals(
            ChatDensity.Normal.typography.codeFontSize,
            ChatDensity.Normal.typography.tableFontSize
        )
    }

    @Test
    fun `Normal table cell equals code block spacing`() {
        assertEquals(
            ChatDensity.Normal.spacing.tableCell,
            ChatDensity.Normal.spacing.codeBlock
        )
    }

    @Test
    fun `Headings are strictly descending in Normal`() {
        val t = ChatDensity.Normal.typography
        assert(t.h1.fontSize > t.h2.fontSize)
        assert(t.h2.fontSize > t.h3.fontSize)
        assert(t.h3.fontSize > t.h4.fontSize)
        assert(t.h4.fontSize > t.h5.fontSize)
        assert(t.h5.fontSize >= t.h6.fontSize)
    }
}
