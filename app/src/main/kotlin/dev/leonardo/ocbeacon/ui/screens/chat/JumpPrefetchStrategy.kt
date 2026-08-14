package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope

/**
 * 跳转预组合策略（终极解法 2026-08-13）。
 *
 * 原理：LazyListPrefetchStrategy 是 Compose 官方的"视口外预组合"机制——
 * schedulePrefetch(index) 在帧空闲期对指定 item 执行 precomposition +
 * premeasure（**不显示**）。预组合完成后 item 的内容树已构建、尺寸已知；
 * 滚动到视口时 apply 即显示（"fully formed UI appears on screen instantly"）
 * → **目标进入视口零渲染过程，物理上无闪烁**。
 *
 * 本策略同时承担两种职责：
 * 1. **滚动预测**（替代默认策略/cacheWindow）：按滚动方向预组合视口边缘
 *    前方一个窗口（[PREFETCH_AHEAD] 项）——保持流式/滚动的预组合收益，
 *    覆盖 fling 距离避免长气泡跳过
 * 2. **跳转目标预组合**（[pendingIndex]）：jumpToMessage 设置目标 index 后，
 *    触发一次伪滚动 → schedulePrefetch(目标) → [onCompleted] 回调拿到
 *    **主轴尺寸**（定位直接用，无需目标进入视口测量）
 */
@OptIn(ExperimentalFoundationApi::class)
class JumpPrefetchStrategy : LazyListPrefetchStrategy {

    /**
     * 滚动方向预组合的 item 数量。
     *
     * 替代旧 `LazyLayoutCacheWindow(1.5f, 1.5f)`（~1.5 视口 ≈ 5-8 条消息）。
     * 值太小（如原 1）→ fling 快速滚动时长消息来不及组合 → 空白/跳过（铁律 7）。
     * schedulePrefetch 内部去重（已组合的 item 重复调度为 no-op），
     * 多余调度不造成浪费——每帧 edge 变化才会重新调度窗口。
     */
    private companion object {
        const val PREFETCH_AHEAD = 6
    }

    /** 跳转目标 lazy index（-1 = 无）；jumpToMessage 设置 */
    var pendingIndex: Int = -1

    /** 目标预组合完成回调（index + 主轴尺寸 px）——scrollToDisplayItem await */
    var onCompleted: ((index: Int, mainAxisSize: Int) -> Unit)? = null

    private var lastScheduledJump = -1
    private var lastPredicted = -1

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        // 滚动方向预测预组合。
        // vertical：delta<0 = 内容上移 = 向底部/向后（reverseLayout 更高 index = 更旧消息）
        //
        // 2026-08-14 修复 fling 跳过长气泡：原实现仅预组合滚动方向 1 项——
        // 快速 fling 时长 assistant 气泡（重型 Markdown 解析 + 多工具卡片）
        // 来不及在进入视口前完成组合 → 空白/跳过，需慢速回滚才能看到。
        // 改为预组合一个窗口（PREFETCH_AHEAD 项），覆盖 fling 距离。
        // 这替代了旧 cacheWindow(1.5f, 1.5f) 的预组合覆盖量（铁律 7: ahead 太小 →
        // fling 高速滚动时视口瞬间滚出已组合区域 → 新 item 被迫即时组合 → 跳过）。
        val vis = layoutInfo.visibleItemsInfo
        if (vis.isNotEmpty()) {
            val total = layoutInfo.totalItemsCount
            if (delta < 0) {
                // 向更旧消息方向（reverseLayout: vis.last() = 视觉顶部 = 最高 index）
                val edge = vis.last().index
                if (edge != lastPredicted) {
                    lastPredicted = edge
                    val end = minOf(edge + 1 + PREFETCH_AHEAD, total)
                    for (i in (edge + 1) until end) {
                        schedulePrefetch(i) { }
                    }
                }
            } else if (delta > 0) {
                // 向更新消息方向（reverseLayout: vis.first() = 视觉底部 = 最低 index）
                val edge = vis.first().index
                if (edge != lastPredicted) {
                    lastPredicted = edge
                    val start = maxOf(edge - PREFETCH_AHEAD, 0)
                    for (i in (edge - 1) downTo start) {
                        schedulePrefetch(i) { }
                    }
                }
            }
        }
        maybeScheduleJump(layoutInfo)
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        maybeScheduleJump(layoutInfo)
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        // 无嵌套列表——忽略
    }

    private fun LazyListPrefetchScope.maybeScheduleJump(layoutInfo: LazyListLayoutInfo) {
        // 2026-08-13 禁用：预组合的 premeasure 尺寸会污染 item 布局
        //（214 vs 最终 331——实测微调 residual=-117 错位）。预组合收益
        //（内容树预热）< 代价（尺寸污染）。跳转目标由"进入视口自然渲染
        // + 透明门控"处理——JumpPrefetchStrategy 仅保留滚动方向预测。
        // 保留 pendingIndex 复位逻辑供调用方（未来修复后重新启用）。
        @Suppress("UNUSED_EXPRESSION")
        pendingIndex
    }

    /** 复位（下次跳转重新调度） */
    fun reset() {
        pendingIndex = -1
        lastScheduledJump = -1
        onCompleted = null
    }
}
