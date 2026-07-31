package dev.leonardo.ocbeacon.ui.screens.sessions

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-screen signal: ChatViewModel sets it when the user sends a message;
 * SessionListViewModel consumes it on return to scroll the list back to top.
 *
 * Held in memory as a Hilt singleton. Not persisted across process death,
 * which is acceptable since the typical flow (send -> back) never kills the process.
 * Chosen over SavedStateHandle because Hilt's injected SavedStateHandle and the
 * NavBackStackEntry.savedStateHandle turned out to be distinct instances, breaking
 * cross-component communication via SavedStateHandle.
 */
@Singleton
class SessionScrollSignal @Inject constructor() {
    @Volatile
    private var pendingScrollToTop = false

    /** Called by ChatViewModel when the user sends a message. */
    fun requestScrollToTop() {
        pendingScrollToTop = true
    }

    /** Called by SessionListViewModel on return; returns true once then resets. */
    fun consumeScrollToTop(): Boolean {
        val should = pendingScrollToTop
        pendingScrollToTop = false
        return should
    }
}
