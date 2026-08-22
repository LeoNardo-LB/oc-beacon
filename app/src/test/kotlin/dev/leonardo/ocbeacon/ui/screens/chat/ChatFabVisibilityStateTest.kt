package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #192 spec §4 JVM：D3 暂停语义 / 两 FAB 独立 / 恢复后自动显隐回归。
 */
class ChatFabVisibilityStateTest {

    // ---- D3：手动隐藏优先于「滚离底部自动出现」 ----

    @Test
    fun hiddenSuspendsAutoShow_evenWhenScrolledAway() {
        assertEquals(FabSlot.EDGE_TAB, ChatFabVisibilityState.bottomFabSlot(hidden = true, isAtBottom = false))
    }

    @Test
    fun hiddenSuspendsAutoShow_evenAtBottom_stillEdgeTab() {
        // 隐藏期在底部也不消失拉杆（恢复入口必须恒在）
        assertEquals(FabSlot.EDGE_TAB, ChatFabVisibilityState.bottomFabSlot(hidden = true, isAtBottom = true))
    }

    @Test
    fun notHidden_atBottom_noFab() {
        assertEquals(FabSlot.NONE, ChatFabVisibilityState.bottomFabSlot(hidden = false, isAtBottom = true))
    }

    @Test
    fun notHidden_scrolledAway_fabShows() {
        assertEquals(FabSlot.FAB, ChatFabVisibilityState.bottomFabSlot(hidden = false, isAtBottom = false))
    }

    // ---- 恢复后自动显隐回归（D3 后半句） ----

    @Test
    fun restoreResumesAutoShowLogic() {
        val s = ChatFabVisibilityState()
        s.hideBottomFab()
        s.showBottomFab()
        assertEquals(FabSlot.FAB, ChatFabVisibilityState.bottomFabSlot(s.bottomFabHidden, isAtBottom = false))
        assertEquals(FabSlot.NONE, ChatFabVisibilityState.bottomFabSlot(s.bottomFabHidden, isAtBottom = true))
    }

    // ---- 两 FAB 独立 ----

    @Test
    fun twoFabsIndependent() {
        val s = ChatFabVisibilityState()
        s.hideMenuFab()
        assertEquals(true, s.menuFabHidden)
        assertEquals(false, s.bottomFabHidden) // 左 FAB 不受影响
        s.hideBottomFab()
        assertEquals(true, s.bottomFabHidden)
        assertEquals(true, s.menuFabHidden)
        s.showMenuFab()
        assertEquals(false, s.menuFabHidden)
        assertEquals(true, s.bottomFabHidden) // 反向也不受影响
    }

    @Test
    fun menuSlot_ignoresScroll() {
        assertEquals(FabSlot.FAB, ChatFabVisibilityState.menuFabSlot(hidden = false))
        assertEquals(FabSlot.EDGE_TAB, ChatFabVisibilityState.menuFabSlot(hidden = true))
    }

    // ---- 初始态 ----

    @Test
    fun initialState_bothVisible() {
        val s = ChatFabVisibilityState()
        assertEquals(false, s.bottomFabHidden)
        assertEquals(false, s.menuFabHidden)
    }
}
