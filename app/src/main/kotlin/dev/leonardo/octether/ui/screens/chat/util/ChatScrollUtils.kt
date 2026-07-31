package dev.leonardo.octether.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay

/**
 * Animated auto-scroll to bottom. Used for after-send follow.
 *
 * With reverseLayout=true, "bottom" = item 0.
 * Retries up to 48ms (3×16ms) to handle complex Markdown layout delays.
 */
internal suspend fun LazyListState.smoothScrollToBottom() {
    scrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}

/**
 * Instant snap to bottom for explicit user actions (FAB click).
 */
internal suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}
