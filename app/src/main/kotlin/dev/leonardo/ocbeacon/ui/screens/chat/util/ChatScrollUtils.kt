package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/**
 * 【死代码——全仓零引用，保留待清理（KT1a 台账标注）】
 * 动画自动滚动到底部。原用于发送后的跟随；该职责现由
 * ChatScrollController/forceScrollTick 路径承担，本函数无调用点。
 * 同文件 [snapToBottom] 仍在使用（FAB 即时吸附）。
 *
 * 使用 reverseLayout=true 时，"底部" = 第 0 项。
 */
internal suspend fun LazyListState.smoothScrollToBottom() {
    snapToBottom()
}

/**
 * 显式用户操作（FAB 点击）时的即时吸附到底部——**收敛锚定**语义。
 *
 * #256 根因链（2026-08-28 真机 ScrollDiag 取证）：snapToBottom 能瞬间到达
 * idx=0/off=0 真底部，但视口附近 item 的**延迟测量**（Markdown 异步解析
 * +194/+466、shell 卡正文 +55——渐进渲染架构：item 组合时占位、解析完成后
 * 涨高）把视口推离底部。固定周期强推是猜时间窗口的补丁。
 *
 * 收敛锚定（本实现）：显式跳底 = **钉住意图**——逐帧采样视口内容高度签名，
 * 任何变化（延迟测量收敛中）立即重锚定到底；连续稳定帧即判定收敛退出。
 * 用户拖动接管（isScrollInProgress）立即让位。事件驱动、无固定周期猜测、
 * 收敛即停（稳态零持续开销）；绝对帧数上限仅为防御性兜底。
 */
internal suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(0)
    var lastSig = -1L
    var stableFrames = 0
    var frames = 0
    while (frames < 60) { // 绝对上限兜底（约 1s@60fps）；正常 3-8 帧收敛
        frames++
        withFrameNanos { } // 等一帧：布局与延迟测量提交后再采样
        if (layoutInfo.totalItemsCount == 0) return
        // 收敛判定 = 双条件：在底（idx=0 且 offset<100）**且** 内容签名稳定。
        // 延迟测量推离会使 atBottom=false → stableFrames 清零 → 不会在错误
        // 位置提前退出（这是初版「仅签名稳定」判定的漏洞，实测 idx=2 off=227
        // 提前退出复现）。
        val atBottomNow = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset < 100
        val sig = layoutInfo.visibleItemsInfo.fold(layoutInfo.totalItemsCount.toLong()) { acc, vi ->
            acc * 31 + vi.size
        }
        if (atBottomNow && sig == lastSig) {
            stableFrames++
            if (stableFrames >= 4) return // 在底且连续 4 帧稳定 = 测量收敛
        } else {
            stableFrames = 0
        }
        // 被延迟测量推离（或尚未到底）→ 重锚定
        if (!atBottomNow) {
            scrollToItem(0)
        }
        lastSig = sig
    }
}
