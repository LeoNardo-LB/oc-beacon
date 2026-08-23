package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 双 FAB 会话级隐藏状态（#192，spec 2026-08-23-fab-swipe-hide-design）。
 *
 * 归属：每 ChatViewModel（= 每导航入口）一份——主/子会话独立记忆（D2）；
 * 纯内存不落盘：离开会话随 VM 弹出复位、进程重启复位（D1）。
 *
 * v5：隐藏语义 = Peek 驻留（FAB 贴边露 ~1/4 + 半透明），无独立拉杆组件。
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
        /**
         * D3：左 FAB（跳到底部）是否组合——手动隐藏（peek 驻留）优先，
         * 隐藏期不因「回到底部」消失；未隐藏时保留原在底部自动隐藏语义。
         */
        fun bottomFabComposed(hidden: Boolean, isAtBottom: Boolean): Boolean =
            hidden || !isAtBottom
    }
}
