package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #136（D2-L55）：转后台 synthetic 提示识别——服务器模板变体匹配。
 * 单一硬编码模板在服务器改文案后静默失效；现为变体列表 + 可测辅助函数。
 */
class IsBackgroundMoveSyntheticTest {

    @Test
    fun `exact current server template matches`() {
        assertTrue(isBackgroundMoveSynthetic("User requested that active blocking work be moved to the background"))
    }

    @Test
    fun `server template with surrounding text matches`() {
        assertTrue(
            isBackgroundMoveSynthetic(
                "[system] User requested that active blocking work be moved to the background (conversation continues)"
            )
        )
    }

    @Test
    fun `known wording variant matches`() {
        assertTrue(isBackgroundMoveSynthetic("Active blocking work was moved to the background"))
    }

    @Test
    fun `unrelated synthetic text does not match`() {
        assertFalse(isBackgroundMoveSynthetic("The user cancelled the current operation"))
    }

    @Test
    fun `empty text does not match`() {
        assertFalse(isBackgroundMoveSynthetic(""))
    }

    @Test
    fun `multi-line text containing marker matches`() {
        assertTrue(
            isBackgroundMoveSynthetic(
                "User requested that active blocking work be moved to the background.\nThe agent will continue in background mode."
            )
        )
    }
}
