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
     * 滚动方向预组合——速度自适应窗口（2026-08-20 第二轮滚动卡顿修复）。
     *
     * 背景：08-14 设定固定 PREFETCH_AHEAD=6 时，长 assistant 消息还是
     * 13 万字符整 item——fling 会整气泡跳过，宽窗是必要的。0faa6984
     * 块级分片后单个 item 已缩到 ~5000 字符，宽窗的原始理由消失；
     * 而预取在**主线程**执行（foundation 1.11.2 AndroidPrefetchScheduler：
     * view.post + Choreographer deadline），预算感知粒度 = 整个 item 的
     * subcompose——开始后不可中断。120Hz 设备帧预算仅 8.33ms，慢速拖动
     * 时 edge 每跨一个 item 就调度 6 个重 chunk 预组合 → 后续连续数帧
     * 各被单个 chunk 组合超支 → "新消息临近顿一下"（症状①）与长消息
     * 内滚动卡顿（症状③，chunk 边界 = item 边界）。
     *
     * 方案：按滚动速度（onScroll 帧间位移/wall-clock dt 的 EMA）分档——
     * 慢速拖动只需 1 个 ahead（下一 item 进视口前有整段拖动时间完成组合），
     * fling 高速段保留 6 个宽窗（防整气泡跳过——铁律 7 仍然有效）。
     * schedulePrefetch 内部去重（已组合 item 重复调度为 no-op）。
     */
    private companion object {
        /** fling 高速段窗口（保持 08-14 铁律 7 的防跳过覆盖量） */
        const val PREFETCH_AHEAD_FLING = 6

        /** 快速拖动窗口 */
        const val PREFETCH_AHEAD_FAST_DRAG = 3

        /** 慢速拖动窗口（120Hz 帧预算下的预算保护） */
        const val PREFETCH_AHEAD_SLOW_DRAG = 1

        /** 判定为 fling 的速度阈值（px/s）——SafeFlingBehavior 限速后典型 5k-30k */
        const val VELOCITY_FLING = 6000f

        /** 判定为快速拖动的速度阈值（px/s） */
        const val VELOCITY_FAST_DRAG = 2500f

        /** 速度 EMA 平滑系数 */
        const val EMA_ALPHA = 0.25f

        /** onScroll 调用间隔超过此值（ns）视为新手势/停顿——EMA 重置 */
        const val CALL_GAP_RESET_NS = 200_000_000L
    }

    /** 跳转目标 lazy index（-1 = 无）；jumpToMessage 设置 */
    var pendingIndex: Int = -1

    /** 目标预组合完成回调（index + 主轴尺寸 px）——scrollToDisplayItem await */
    var onCompleted: ((index: Int, mainAxisSize: Int) -> Unit)? = null

    private var lastScheduledJump = -1
    private var lastPredicted = -1

    /** 滚动速度 EMA（px/s，绝对值） */
    private var velocityEma = 0f
    private var lastOnScrollNanos = 0L

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        val ahead = updateVelocity(delta)

        // 滚动方向预测预组合。
        // vertical：delta<0 = 内容上移 = 向底部/向后（reverseLayout 更高 index = 更旧消息）
        val vis = layoutInfo.visibleItemsInfo
        if (vis.isNotEmpty()) {
            val total = layoutInfo.totalItemsCount
            if (delta < 0) {
                // 向更旧消息方向（reverseLayout: vis.last() = 视觉顶部 = 最高 index）
                val edge = vis.last().index
                if (edge != lastPredicted) {
                    lastPredicted = edge
                    val end = minOf(edge + 1 + ahead, total)
                    for (i in (edge + 1) until end) {
                        schedulePrefetch(i) { }
                    }
                }
            } else if (delta > 0) {
                // 向更新消息方向（reverseLayout: vis.first() = 视觉底部 = 最低 index）
                val edge = vis.first().index
                if (edge != lastPredicted) {
                    lastPredicted = edge
                    val start = maxOf(edge - ahead, 0)
                    for (i in (edge - 1) downTo start) {
                        schedulePrefetch(i) { }
                    }
                }
            }
        }
        maybeScheduleJump(layoutInfo)
    }

    /**
     * 帧间速度估计 + 分档窗口。
     *
     * onScroll 在滚动期间每帧（measure 阶段）调用一次，delta 为本帧位移——
     * 用 wall-clock 差分求瞬时速度再 EMA 平滑。间隔异常（>200ms，滚动
     * 停止后的残留调用/新手势）时重置，避免新手势首跨携带上一手势的
     * 高速 EMA。
     */
    private fun updateVelocity(delta: Float): Int {
        val now = System.nanoTime()
        if (lastOnScrollNanos > 0) {
            val dt = now - lastOnScrollNanos
            if (dt in 1_000_000L..100_000_000L) {
                val v = kotlin.math.abs(delta) / (dt / 1_000_000_000f)
                velocityEma += EMA_ALPHA * (v - velocityEma)
            } else if (dt > CALL_GAP_RESET_NS) {
                velocityEma = 0f
            }
        }
        lastOnScrollNanos = now
        return when {
            velocityEma >= VELOCITY_FLING -> PREFETCH_AHEAD_FLING
            velocityEma >= VELOCITY_FAST_DRAG -> PREFETCH_AHEAD_FAST_DRAG
            else -> PREFETCH_AHEAD_SLOW_DRAG
        }
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

    /** 复位（下次跳转重新调度；速度状态一并清零） */
    fun reset() {
        pendingIndex = -1
        lastScheduledJump = -1
        onCompleted = null
        velocityEma = 0f
        lastOnScrollNanos = 0L
    }
}
