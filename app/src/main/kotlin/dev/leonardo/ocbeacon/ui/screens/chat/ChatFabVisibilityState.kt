package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 双 FAB 会话级隐藏状态（#192，spec 2026-08-23-fab-swipe-hide-design）。
 *
 * 归属：每 ChatViewModel（= 每导航入口）一份——主/子会话独立记忆（D2）；
 * 纯内存不落盘：离开会话随 VM 弹出复位、进程重启复位（D1）。
 */
internal class ChatFabVisibilityState {

    var bottomFabHidden by mutableStateOf(false)
        private set

    var menuFabHidden by mutableStateOf(false)
        private set

    fun hideBottomFab() {
        bottomFabHidden = true
    }

    fun showBottomFab() {
        bottomFabHidden = false
    }

    fun hideMenuFab() {
        menuFabHidden = true
    }

    fun showMenuFab() {
        menuFabHidden = false
    }

    companion object {
        /** 左 FAB（跳到底部）应渲染的槽位——D3：手动隐藏优先于「滚离底部自动出现」。 */
        fun bottomFabSlot(hidden: Boolean, isAtBottom: Boolean): FabSlot = when {
            hidden -> FabSlot.EDGE_TAB
            isAtBottom -> FabSlot.NONE
            else -> FabSlot.FAB
        }

        /** 右 FAB（菜单）应渲染的槽位（无 isAtBottom 参与）。 */
        fun menuFabSlot(hidden: Boolean): FabSlot =
            if (hidden) FabSlot.EDGE_TAB else FabSlot.FAB
    }
}

/** FAB 区域渲染槽位：FAB 本体 / 边缘拉杆 / 空（在底部自动隐藏）。 */
internal enum class FabSlot { FAB, EDGE_TAB, NONE }
