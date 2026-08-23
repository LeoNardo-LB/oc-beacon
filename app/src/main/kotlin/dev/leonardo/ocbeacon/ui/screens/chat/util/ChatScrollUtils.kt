package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay

/**
 * 【死代码——全仓零引用，保留待清理（KT1a 台账标注）】
 * 动画自动滚动到底部。原用于发送后的跟随；该职责现由
 * ChatScrollController/forceScrollTick 路径承担，本函数无调用点。
 * 同文件 [snapToBottom] 仍在使用（FAB 即时吸附）。
 *
 * 使用 reverseLayout=true 时，"底部" = 第 0 项。
 * 最多重试 48ms（3×16ms）以应对复杂 Markdown 布局的延迟。
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
 * 显式用户操作（FAB 点击）时的即时吸附到底部。
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
