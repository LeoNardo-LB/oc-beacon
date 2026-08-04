package dev.leonardo.ocbeacon.ui.screens.sessions

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨屏幕信号：用户发送消息时 ChatViewModel 设置它；
 * 返回时 SessionListViewModel 消费它以将列表滚动回顶部。
 *
 * 以 Hilt singleton 形式保存在内存中。不跨进程死亡持久化，
 * 这可以接受，因为典型流程（发送 -> 返回）从不会杀死进程。
 * 选择它而非 SavedStateHandle，是因为 Hilt 注入的 SavedStateHandle
 * 和 NavBackStackEntry.savedStateHandle 实际上是不同实例，
 * 通过 SavedStateHandle 的跨组件通信会失效。
 */
@Singleton
class SessionScrollSignal @Inject constructor() {
    @Volatile
    private var pendingScrollToTop = false

    /** 由 ChatViewModel 在用户发送消息时调用。 */
    fun requestScrollToTop() {
        pendingScrollToTop = true
    }

    /** 返回时由 SessionListViewModel 调用；返回一次 true 后重置。 */
    fun consumeScrollToTop(): Boolean {
        val should = pendingScrollToTop
        pendingScrollToTop = false
        return should
    }
}
