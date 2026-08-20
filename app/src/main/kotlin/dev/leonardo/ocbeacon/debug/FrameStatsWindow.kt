package dev.leonardo.ocbeacon.debug

/**
 * 帧统计滚动窗口（纯 Kotlin，无 Android 依赖——可单测）。
 *
 * 2026-08-20 第三轮：开发用性能检测系统核心。设计约束：
 * - record() 每帧调用一次，O(1)，零分配（环形数组复用）
 * - 百分位只在 snapshot() 时对窗口副本排序（不在帧路径）
 * - jank 判定基于构造时传入的帧预算（120Hz = 8.33ms；gfxinfo 的
 *   16.7ms 默认口径在高刷设备漏报——本轮调查的起点教训）
 */
internal class FrameStatsWindow(
    private val capacity: Int = 720,
    private val frameBudgetMs: Double,
) {
    private val durations = DoubleArray(capacity)
    private var count = 0
    private var next = 0

    var totalJank: Long = 0
        private set

    fun record(durationMs: Double): Boolean {
        durations[next] = durationMs
        next = (next + 1) % capacity
        if (count < capacity) count++
        val jank = durationMs > frameBudgetMs
        if (jank) totalJank++
        return jank
    }

    fun snapshot(): Stats {
        if (count == 0) return Stats(0.0, 0.0, 0.0, 0.0, 0, 0.0)
        val copy = DoubleArray(count)
        var i = 0
        var idx = if (count == capacity) next else 0
        while (i < count) {
            copy[i] = durations[(idx + i) % capacity]
            i++
        }
        copy.sort()
        val over = copy.count { it > frameBudgetMs }
        return Stats(
            p50 = copy.percentile(0.50),
            p95 = copy.percentile(0.95),
            p99 = copy.percentile(0.99),
            max = copy.last(),
            frames = count,
            overBudgetPct = over * 100.0 / count,
        )
    }

    private fun DoubleArray.percentile(q: Double): Double {
        if (isEmpty()) return 0.0
        val pos = q * (size - 1)
        val lo = pos.toInt()
        val hi = kotlin.math.min(lo + 1, size - 1)
        return this[lo] + (this[hi] - this[lo]) * (pos - lo)
    }

    data class Stats(
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val max: Double,
        val frames: Int,
        val overBudgetPct: Double,
    )
}