package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #192 v5 spec §4 JVM：D3 暂停语义 / 两 FAB 独立 / 初始态。
 */
class ChatFabVisibilityStateTest {

    // ---- D3：手动隐藏（peek 驻留）优先于「在底部自动隐藏」 ----

    @Test
    fun peekPersistsEvenAtBottom() {
        assertTrue(ChatFabVisibilityState.bottomFabComposed(hidden = true, isAtBottom = true))
    }

    @Test
    fun peekPersistsWhenScrolledAway() {
        assertTrue(ChatFabVisibilityState.bottomFabComposed(hidden = true, isAtBottom = false))
    }

    @Test
    fun notHidden_atBottom_notComposed() {
        assertFalse(ChatFabVisibilityState.bottomFabComposed(hidden = false, isAtBottom = true))
    }

    @Test
    fun notHidden_scrolledAway_composed() {
        assertTrue(ChatFabVisibilityState.bottomFabComposed(hidden = false, isAtBottom = false))
    }

    // ---- 恢复后自动显隐回归（D3 后半句：复位后原语义生效） ----

    @Test
    fun restoreResumesAutoHideLogic() {
        val s = ChatFabVisibilityState()
        s.hideBottomFab()
        s.showBottomFab()
        assertFalse(ChatFabVisibilityState.bottomFabComposed(s.bottomFabHidden, isAtBottom = true))
        assertTrue(ChatFabVisibilityState.bottomFabComposed(s.bottomFabHidden, isAtBottom = false))
    }

    // ---- 两 FAB 独立 ----

    @Test
    fun twoFabsIndependent() {
        val s = ChatFabVisibilityState()
        s.hideMenuFab()
        assertTrue(s.menuFabHidden)
        assertFalse(s.bottomFabHidden) // 左 FAB 不受影响
        s.hideBottomFab()
        assertTrue(s.bottomFabHidden)
        assertTrue(s.menuFabHidden)
        s.showMenuFab()
        assertFalse(s.menuFabHidden)
        assertTrue(s.bottomFabHidden) // 反向也不受影响
    }

    // ---- 初始态 ----

    @Test
    fun initialState_bothVisible() {
        val s = ChatFabVisibilityState()
        assertFalse(s.bottomFabHidden)
        assertFalse(s.menuFabHidden)
    }

    @Test
    fun hideShowRoundTrip() {
        val s = ChatFabVisibilityState()
        s.hideBottomFab(); s.showBottomFab()
        s.hideMenuFab(); s.showMenuFab()
        assertEquals(false, s.bottomFabHidden)
        assertEquals(false, s.menuFabHidden)
    }
}
