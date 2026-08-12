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
 *    下一项——保持流式/滚动的预组合收益
 * 2. **跳转目标预组合**（[pendingIndex]）：jumpToMessage 设置目标 index 后，
 *    触发一次伪滚动 → schedulePrefetch(目标) → [onCompleted] 回调拿到
 *    **主轴尺寸**（定位直接用，无需目标进入视口测量）
 */
@OptIn(ExperimentalFoundationApi::class)
class JumpPrefetchStrategy : LazyListPrefetchStrategy {

    /** 跳转目标 lazy index（-1 = 无）；jumpToMessage 设置 */
    var pendingIndex: Int = -1

    /** 目标预组合完成回调（index + 主轴尺寸 px）——scrollToDisplayItem await */
    var onCompleted: ((index: Int, mainAxisSize: Int) -> Unit)? = null

    private var lastScheduledJump = -1
    private var lastPredicted = -1

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        // 滚动预测：滚动方向视口边缘的下一项（vertical：delta<0 = 内容上移 = 向底部/向后）
        val vis = layoutInfo.visibleItemsInfo
        if (vis.isNotEmpty()) {
            val predict = if (delta < 0) vis.last().index + 1 else vis.first().index - 1
            if (predict in 0 until layoutInfo.totalItemsCount && predict != lastPredicted) {
                lastPredicted = predict
                schedulePrefetch(predict) { }
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
        val idx = pendingIndex
        if (idx >= 0 && idx < layoutInfo.totalItemsCount && idx != lastScheduledJump) {
            lastScheduledJump = idx
            schedulePrefetch(idx) {
                // LazyListPrefetchResultScope 直接提供主轴尺寸（vertical：高度）
                onCompleted?.invoke(index, mainAxisSize)
            }
        }
    }

    /** 复位（下次跳转重新调度） */
    fun reset() {
        pendingIndex = -1
        lastScheduledJump = -1
        onCompleted = null
    }
}
