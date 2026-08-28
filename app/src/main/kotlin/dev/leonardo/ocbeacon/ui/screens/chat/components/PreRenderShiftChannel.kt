package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.logging.AppLogger
import java.util.WeakHashMap

/**
 * #258 换道手术（2026-08-29 用户裁决「渲染前计算的根因修复模式」）：
 * 统一渲染前视窗位移运输层——全 App 唯一的补偿注入入口。
 *
 * 崩溃根因（真机 FATAL「entered drag with non-zero pending scroll」）：
 * 旧通道 LazyListReflection.requestScrollShift 反射直写
 * LazyListState.scrollToBeConsumed，残量存活约一帧；用户 drag 起手经
 * onScroll（foundation-android 1.11.2 LazyListState.kt:492-501）时
 * checkPrecondition 见非零残量直接抛出。expand 家族（tap 展开动画逐帧
 * 注入、手指常在屏上）把竞态窗口放大到必现。**scrollToBeConsumed 直写
 * 已随本通道整体删除**——LazyListReflection 只保留 request-position 探测。
 *
 * 新通道（foundation-android 1.11.2 sources 源码级核销）：
 * - measure 块内只入队（[enqueue]，不触碰 LazyListState 任何内部字段）；
 * - 帧边界排空（[drain]，MonotonicFrameClock 回调相，早于当帧 measure 遍历）
 *   → requestPositionAndForgetLastKnownKey + measurementScopeInvalidator
 *   写「待定滚动位置」。该通道由 measure 遍首消费（LazyListMeasure 起始应用、
 *   LazyListState.kt:630 updateFromMeasureResult 回写应用后值），而 onScroll/
 *   drag 对它**无断言**——竞态滑过最坏一次一帧位置校正，无崩溃路径；
 * - #222 回写竞争不复发：注入发生在上一遍完全结束之后（帧界），待定位置
 *   下一遍遍首应用，遍末回写写回的即应用后值——无「测量中途注入被覆盖」窗口；
 * - 视觉时序与旧通道逐帧一致：帧 k 入队 → 帧 k+1 遍首生效 + 状态机全量
 *   揭示，未补偿几何永不被放置（渲染前语义保持，#241 硬约束不动）。
 *
 * 滚动语义（门 A 用户反馈修复）：**滚动中照常排空配对**——通道②对 drag 无
 * 断言，旧通道「滚动中注入=崩溃放大器」的前提已消失；状态机层唯一保留的
 * 裁剪是流式家族 holdReveal（#239 定案：SSE 增量滚动中裁剪、停滚补齐）。
 * expand 家族（默认展开卡的 markdown 迟到解析）滚动中即时配对到位——
 * 否则出现「滑完才展开/FAB 先停在近底再跳」的二次缺陷（V6 实证）。
 *
 * 应用确认门（[shiftSettled]）：入队/排空各推进一代计数；揭示方（补偿状态机）
 * 在未落地时保持裁剪——堵「同帧重测插队导致揭示先于位移」的跳变竞态。
 *
 * 降级阶梯（任何一级都无崩溃）：反射字段消失（Compose 升级）→
 * requestScrollToItemNoCancel 内部走官方 requestScrollToItem（守卫下互斥锁
 * 路径不触发）；目标 offset 为负（列表顶部大收缩）→ 钳 0（等价旧通道
 * 「边界钳制兜底」语义，轻微软化）；排空注入异常 → 日志+放弃本帧增量
 * （代计数照常落地，揭示恢复——宁推挤不卡裁剪）。
 */
internal object PreRenderShiftChannel {

    /** 每列表累计器（弱键随 LazyListState 生命周期回收；仅主线程 measure/帧回调访问，无锁）。 */
    private val pending = WeakHashMap<LazyListState, FloatArray>()

    /** 注入代计数 [已入队, 已落地]：揭示方据此判断位移是否落地（竞态门）。 */
    private val generations = WeakHashMap<LazyListState, LongArray>()

    /** measure 块内调用：入队本遍补偿增量（正=内容生长视窗下移，负=收缩上移）。 */
    fun enqueue(state: LazyListState, deltaPx: Float) {
        pending.getOrPut(state) { floatArrayOf(0f) }[0] += deltaPx
        generations.getOrPut(state) { longArrayOf(0L, 0L) }[0]++
    }

    /**
     * true = 无未落地注入（补偿状态机可安全揭示）；false = 有增量已入队但
     * 尚未在 measure 遍首应用——揭示方保持裁剪，杜绝「揭示先于位移」。
     */
    fun shiftSettled(state: LazyListState): Boolean {
        val g = generations[state] ?: return true
        return g[0] == g[1]
    }

    /**
     * 帧边界调用（LaunchedEffect + withFrameNanos 循环，挂 ChatMessageList）：
     * 排空累计增量 → 一次 request-position 注入，下一遍 measure 遍首应用。
     * 滚动中照常排空（见类头「滚动语义」）。
     */
    fun drain(state: LazyListState) {
        val g = generations.getOrPut(state) { longArrayOf(0L, 0L) }
        val acc = pending[state] ?: return
        val total = acc[0]
        if (total == 0f) return
        acc[0] = 0f
        val baseOffset = state.firstVisibleItemScrollOffset
        val targetOffset = (baseOffset + total).toInt().coerceAtLeast(0)
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG && targetOffset != (baseOffset + total).toInt()) {
            AppLogger.w("PreRenderShift", "clamp: target=" + (baseOffset + total).toInt() + " -> 0")
        }
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            AppLogger.d(
                "PreRenderShift",
                "drain total=" + total.toInt() + " idx=" + state.firstVisibleItemIndex +
                    " off=" + baseOffset + " -> " + targetOffset
            )
        }
        try {
            LazyListReflection.requestScrollToItemNoCancel(
                state,
                state.firstVisibleItemIndex,
                targetOffset,
            )
        } catch (t: Throwable) {
            // 降级终点：放弃本帧增量（推挤一帧），绝不崩溃。
            AppLogger.w("PreRenderShift", "drain inject failed: " + t.message)
        } finally {
            // 代计数落地（成功/钳 0/放弃一律算「已解决」）：揭示恢复，宁推挤不卡裁剪。
            g[1] = g[0]
        }
    }
}
