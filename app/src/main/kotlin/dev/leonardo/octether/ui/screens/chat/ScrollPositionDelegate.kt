package dev.leonardo.octether.ui.screens.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owns the saved chat scroll position used to restore the list after returning from
 * FileViewer / sub-session navigation.
 *
 * Extracted in Phase 3 Task 1b. Zero dependencies — a pure state holder.
 *
 * Compose [mutableStateOf] state is read by ChatScreen through ChatViewModel facade getters.
 * Snapshot reads are tracked correctly through the property indirection, so recomposition
 * behaviour is identical to the previous inline declaration.
 *
 * NOTE: [savedMessageId] is currently never assigned — it is preserved verbatim from the
 * pre-refactor ChatViewModel as pre-existing dead state (not introduced by this change).
 */
internal class ScrollPositionDelegate {

    /** Saved scroll position for restoring after sub-session navigation. */
    var savedMessageId by mutableStateOf<String?>(null)
        private set

    /** Raw LazyColumn index at save time — used for direct restoration without index arithmetic. */
    var savedLazyIndex by mutableStateOf(0)
        private set

    var savedScrollOffset by mutableStateOf(0)
        private set

    /** Key of the first visible item at save time — used to verify restore accuracy. */
    var savedFirstVisibleKey by mutableStateOf<String?>(null)
        private set

    /**
     * Incremented each time [saveScrollPosition] is called, and again by
     * [bumpScrollRestoreIfPending] when ChatScreen resumes with a pending restore.
     * ChatScreen observes this via LaunchedEffect to reliably restore scroll position
     * after FileViewer / sub-session navigation. Using rememberLazyListState(initial...)
     * is unreliable because `remember` caches the initial state on first composition and
     * ignores new values on recomposition, causing scroll to sometimes restore and sometimes not.
     */
    var scrollRestoreVersion by mutableStateOf(0)
        private set

    /**
     * True when a scroll position has been saved (via [saveScrollPosition]) but not yet
     * restored. Used by [bumpScrollRestoreIfPending] to re-trigger restoration ONLY when
     * returning from a navigation that actually saved a position (FileViewer / sub-session),
     * avoiding spurious restores on plain background→foreground transitions that would
     * disturb the user's current browsing position.
     */
    private var hasPendingScrollRestore = false

    /** Public read-only flag: true when scroll position needs to be restored on return. */
    val pendingScrollRestore: Boolean get() = hasPendingScrollRestore

    fun clearPendingScrollRestore() {
        hasPendingScrollRestore = false
    }

    fun saveScrollPosition(lazyIndex: Int, offset: Int, firstVisibleKey: String? = null) {
        savedLazyIndex = lazyIndex
        savedScrollOffset = offset
        savedFirstVisibleKey = firstVisibleKey
        scrollRestoreVersion++
        hasPendingScrollRestore = true
    }

    /**
     * Re-triggers scroll position restoration on ON_RESUME, but only when a save is pending
     * (i.e. the user is returning from FileViewer or a sub-session). Plain background→foreground
     * transitions are ignored so the user's current browsing position is not disturbed.
     */
    fun bumpScrollRestoreIfPending() {
        if (hasPendingScrollRestore) {
            scrollRestoreVersion++
        }
    }
}

