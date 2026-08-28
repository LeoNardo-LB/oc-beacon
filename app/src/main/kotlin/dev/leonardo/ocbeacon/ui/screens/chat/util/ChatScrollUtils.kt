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
    repeat(3) {
        delay(120)
        scroll { scrollBy(-10_000f) }
    }
}

/**
 * 显式用户操作（FAB 点击）时的即时吸附到底部。
 *
 * #256 勘误（2026-08-28 真机 ScrollDiag 取证）：snapToBottom 能瞬间到达
 * idx=0/off=0 真底部，但**视口附近 item 的延迟测量随后集中爆发**（shell 卡
 * 正文异步测量 +55、长 Markdown 异步解析 +194/+466——滚到底后新 item 进入
 * 视口才完成解析/测量）把视口推离底部 → isAtBottom=false → FAB 复现，
 * 用户感知「FAB 拉不到最低，还差一小段」。原 while(canScrollBackward) 兜底
 * 在到达底部时条件即 false，**一次强推都没执行**。
 * 修复：到底后 3×120ms 周期性强推钉底（已在底时 scrollBy 负向无空间 =
 * no-op 零副作用；RESIZE 爆发窗口 30-70ms 内被强推拉回）。
 */
internal suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(0)
    repeat(3) {
        delay(120)
        scroll { scrollBy(-10_000f) }
    }
}
