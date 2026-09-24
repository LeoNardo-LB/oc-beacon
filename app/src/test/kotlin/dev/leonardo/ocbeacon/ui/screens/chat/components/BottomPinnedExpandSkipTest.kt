package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #432 贴底免派发判定:严格贴底(fii==0 ∧ offset==0)时展开集跳过位移派发。
 * 用户主诉「展开时跳转到其他地方」:贴底端 dispatch +H 把视口推离贴底,
 * 正在看的最新回复被推出屏。半贴底(fii==0 offset>0)布局语义未取证不启用。
 */
class BottomPinnedExpandSkipTest {

    @Test
    fun strictBottomPinnedSkips() {
        assertTrue(bottomPinnedExpandSkip(fii = 0, fiso = 0))
    }

    @Test
    fun halfBottomDoesNotSkip() {
        assertFalse(bottomPinnedExpandSkip(fii = 0, fiso = 424))
    }

    @Test
    fun midListDoesNotSkip() {
        assertFalse(bottomPinnedExpandSkip(fii = 7, fiso = 212))
        assertFalse(bottomPinnedExpandSkip(fii = 14, fiso = 0))
    }
}