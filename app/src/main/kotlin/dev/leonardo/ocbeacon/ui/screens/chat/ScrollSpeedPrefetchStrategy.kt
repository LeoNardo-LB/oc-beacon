package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.foundation.lazy.layout.PrefetchRequest

/**
 * 滚动速度自适应预组合策略（2026-08-13 引入；原名 JumpPrefetchStrategy，
 * 2026-08-21 跳转职责移除后 #200 F13 更名以名实对齐）。
 *
 * 原理：LazyListPrefetchStrategy 是 Compose 官方的"视口外预组合"机制——
 * schedulePrefetch(index) 在帧空闲期对指定 item 执行 precomposition +
 * premeasure（**不显示**）。预组合完成后 item 的内容树已构建、尺寸已知；
 * 滚动到视口时 apply 即显示（"fully formed UI appears on screen instantly"）
 * → **目标进入视口零渲染过程，物理上无闪烁**。
 *
 * 2026-08-21 卫生清理（D-9/#11-4）：原「跳转目标预组合」职责（pendingIndex/
 * onCompleted/maybeScheduleJump——2026-08-13 因预测量尺寸污染 item 布局禁用，
 * 后由跳转状态机 + 透明门控取代）与零调用的 reset() 一并移除。本策略仅保留
 * 滚动方向预测预组合（速度自适应窗口）。
 */
@OptIn(ExperimentalFoundationApi::class)
class ScrollSpeedPrefetchStrategy : LazyListPrefetchStrategy {

    /**
     * 机制级根因修复（2026-08-29，用户「反复滚动 FATAL」）：调度器 no-op——
     * 预组合请求整体丢弃，pausable 预组合物理上不再发生。
     *
     * 崩溃栈（crash buffer 完整取证）：ArrayIndexOutOfBoundsException(-2) @
     * IntStack.peek2 ← GapComposer.end ← performPausableComposition ←
     * AndroidPrefetchScheduler——即框架 prefetch 的 pausable 预组合内部缺陷
     * （issuetracker 331365999 家族，K-9/Thunderbird 11.0 同签名滚动崩溃）。
     * item 内容侧危险形态已清（return@Box 加固），本 override 使崩溃栈
     * **不可达**：无调度 → 无预组合 → 无 pausable 恢复。
     *
     * 代价：放弃滚动方向预组合的平滑收益（本策略 onScroll 的分档窗口随之
     * 停用）——重 chunk 进视口改为即时组合，理论上 fling 段有顿挫风险，
     * 真机 A/B 若不可接受，恢复路径 = 删除本 override 即回原策略。
     * 恢复条件：Compose 升级跨过 pausable prefetch 缺陷修复版本。
     */
    override val prefetchScheduler: PrefetchScheduler = NoPrefetchScheduler

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

        /** 慢速拖动窗口（2026-08-21 A/B 复评：改为 0——F5 重组修复后慢拖残余
         * 尖刺与预取窗口无关联（0 vs 1 无差异），且分片后 edge 预取组合对
         * 慢拖帧预算是净负担；详见 backlog 慢拖尖刺 A/B 条目） */
        const val PREFETCH_AHEAD_SLOW_DRAG = 0

        /** 判定为 fling 的速度阈值（px/s）——SafeFlingBehavior 限速后典型 5k-30k */
        const val VELOCITY_FLING = 6000f

        /** 判定为快速拖动的速度阈值（px/s） */
        const val VELOCITY_FAST_DRAG = 2500f

        /** 速度 EMA 平滑系数 */
        const val EMA_ALPHA = 0.25f

        /** onScroll 调用间隔超过此值（ns）视为新手势/停顿——EMA 重置 */
        const val CALL_GAP_RESET_NS = 200_000_000L
    }

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
        // 2026-08-21：跳转目标预组合已移除（见类注释）——无操作
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        // 无嵌套列表——忽略
    }

    private object NoPrefetchScheduler : PrefetchScheduler {
        /** 丢弃全部预组合请求（含嵌套预组合）——见 [getPrefetchScheduler] 注。 */
        override fun schedulePrefetch(request: PrefetchRequest) = Unit
    }

}
