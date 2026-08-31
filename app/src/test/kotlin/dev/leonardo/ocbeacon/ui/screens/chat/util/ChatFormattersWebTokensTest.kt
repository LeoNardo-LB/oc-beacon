package dev.leonardo.ocbeacon.ui.screens.chat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * webFormatTokens（Web dsh-client-ui-conversation client.js formatTokens 对齐）：
 * 517 / 12.2K / 517K / 1.2M；<1000 原数；缩放值 >=100 无小数、<100 一位小数。
 */
class ChatFormattersWebTokensTest {

    @Test
    fun `web tokens under thousand are raw integers`() {
        assertEquals("0", webFormatTokens(0))
        assertEquals("517", webFormatTokens(517))
        assertEquals("999", webFormatTokens(999))
    }

    @Test
    fun `web tokens thousand scaling matches web examples`() {
        assertEquals("1K", webFormatTokens(1000))
        assertEquals("12.2K", webFormatTokens(12200))
        assertEquals("517K", webFormatTokens(517000))
        assertEquals("999K", webFormatTokens(999000))
    }

    @Test
    fun `web tokens million scaling matches web examples`() {
        assertEquals("1M", webFormatTokens(1000000))
        assertEquals("1.2M", webFormatTokens(1200000))
        assertEquals("125M", webFormatTokens(125000000))
    }

    @Test
    fun `web tokens rounding is half up like Math round`() {
        assertEquals("1.1K", webFormatTokens(1099))
        assertEquals("1.5K", webFormatTokens(1500))
        assertEquals("2K", webFormatTokens(1999))
    }
}