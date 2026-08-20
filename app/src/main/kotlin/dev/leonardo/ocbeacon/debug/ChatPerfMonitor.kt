package dev.leonardo.ocbeacon.debug

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * 开发用常驻性能监测器（2026-08-20 第三轮——应用内可观测性建设）。
 *
 * 动机：两轮滚动卡顿调查都依赖外挂工具（gfxinfo 快照合并 / perfetto 配置
 * 推送 / 脚本重铺），每次观测成本高且无法覆盖日常开发中的偶现卡顿。
 * 本监测器常驻 App（仅 debug 构建接线；release 永不 attach，零开销），
 * 提供三层观测：
 * 1. HUD（[PerfHud]）——fps/p50/p95/超预算%/jank 累计，滚动时实时可见
 * 2. jank 事件日志——单帧超预算 2 倍时输出 FrameMetrics 相位分解
 *   （输入/动画/布局/绘制/同步/交换/GPU 哪个阶段吃掉了时间）+ 最近
 *   [mark] 标记（滚动起止/分片提交等上下文），进 AppLogger（Diagnostics
 *   屏可见）——这就是"一边测试一边找原因"的数据源
 * 3. [hud] State——程序化消费（后续接 benchmark 断言）
 *
 * 帧预算从 display.refreshRate 推导（120Hz→8.33ms）——不用 gfxinfo 的
 * 16.7ms 固定口径（高刷设备漏报，第二轮调查的起点教训）。
 */
internal class ChatPerfMonitor(
    refreshRateHz: Float,
    private val statsWindow: FrameStatsWindow = FrameStatsWindow(
        capacity = 720,
        frameBudgetMs = 1000.0 / refreshRateHz.toDouble(),
    ),
) {
    private val frameBudgetMs = 1000.0 / refreshRateHz.toDouble()

    /** HUD 数据（500ms 节流更新——排序不在帧路径高频执行） */
    private val _hud = mutableStateOf(HudData())
    val hud: State<HudData> = _hud

    /** 上下文标记环（最近 6 条，jank 日志携带） */
    private val markers = ArrayDeque<Marker>(6)

    private var lastJankLogNanos = 0L
    private var lastHudUpdateNanos = 0L

    // 稳态采样（2026-08-20）：慢拖场景 60% 帧稳定超预算但达不到 2x jank 门槛，
    // 这批帧恰是残余卡顿主体——每 2s 输出窗口摘要 + 期间最差帧的相位分解
    private var lastSteadyLogNanos = 0L
    private var worstSinceSteadyMs = 0.0
    private var worstPhases = DoubleArray(7)

    // 2026-08-20 观察者效应批次：平台有重复 FrameMetrics 事件前科
    //（b/206956036，JankStats 源码内有去重补丁）——按 vsync 时间戳去重；
    // dropCount 记账（回调第 3 参：两次回调间被丢弃的帧报告数——量化丢样）
    private var lastVsyncNanos = -1L
    private var droppedReports = 0L

    data class HudData(
        val fpsEstimate: Int = 0,
        val p50Ms: Double = 0.0,
        val p95Ms: Double = 0.0,
        val overBudgetPct: Double = 0.0,
        val totalJank: Long = 0,
        val windowFrames: Int = 0,
        val droppedReports: Long = 0,
    )

    data class Marker(val atNanos: Long, val tag: String)

    /** 打上下文标记（滚动起止/分片提交/页面切换……jank 日志会带上）。 */
    fun mark(tag: String) {
        synchronized(markers) {
            if (markers.size == 6) markers.removeFirst()
            markers.addLast(Marker(System.nanoTime(), tag))
        }
    }

    private val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, dropCount ->
        droppedReports += dropCount
        onFrame(frameMetrics)
    }

    fun attach(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching { window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
    }

    fun detach(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
    }

    private fun onFrame(metrics: FrameMetrics) {
        val vsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        if (vsync > 0) {
            if (vsync == lastVsyncNanos) return // 平台重复事件（b/206956036）
            lastVsyncNanos = vsync
        }
        val totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (totalNs <= 0) return
        val totalMs = totalNs / 1_000_000.0
        val jank = statsWindow.record(totalMs)

        // 追踪期间最差帧及其相位（稳态采样数据源）
        if (totalMs > worstSinceSteadyMs) {
            worstSinceSteadyMs = totalMs
            worstPhases[0] = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) / 1e6
            worstPhases[1] = metrics.getMetric(FrameMetrics.ANIMATION_DURATION) / 1e6
            worstPhases[2] = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6
            worstPhases[3] = metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1e6
            worstPhases[4] = metrics.getMetric(FrameMetrics.SYNC_DURATION) / 1e6
            worstPhases[5] = metrics.getMetric(FrameMetrics.GPU_DURATION) / 1e6
            worstPhases[6] = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION) / 1e6
        }

        val now = System.nanoTime()
        if (now - lastHudUpdateNanos > 500_000_000L) {
            lastHudUpdateNanos = now
            val s = statsWindow.snapshot()
            _hud.value = HudData(
                fpsEstimate = if (s.p50 > 0) (1000.0 / s.p50).toInt() else 0,
                p50Ms = s.p50,
                p95Ms = s.p95,
                overBudgetPct = s.overBudgetPct,
                totalJank = statsWindow.totalJank,
                windowFrames = s.frames,
                droppedReports = droppedReports,
            )
        }

        // jank 事件：超预算 2 倍才记 + 250ms 限频——稳态慢滚（大量预算内超支）
        // 不淹没有价值的尖刺事件，日志自身开销也可控
        if (jank && totalMs > frameBudgetMs * 2 && now - lastJankLogNanos > 250_000_000L) {
            lastJankLogNanos = now
            logJankBreakdown(metrics, totalMs)
        }

        // 稳态采样：窗口超预算占比 >25% 时每 2s 一条（解剖"稳定 9ms"之谜）
        if (now - lastSteadyLogNanos > 2_000_000_000L) {
            val s = statsWindow.snapshot()
            if (s.frames >= 30 && s.overBudgetPct > 25.0) {
                lastSteadyLogNanos = now
                fun f(v: Double) = String.format("%.1f", v)
                AppLogger.w(
                    TAG,
                    "STEADY p50=" + f(s.p50) + " p95=" + f(s.p95) + " over=" + f(s.overBudgetPct) + "%" +
                        " worst=" + f(worstSinceSteadyMs) + "ms[" +
                        "in=" + f(worstPhases[0]) + " anim=" + f(worstPhases[1]) +
                        " lay=" + f(worstPhases[2]) + " draw=" + f(worstPhases[3]) +
                        " sync=" + f(worstPhases[4]) + " gpu=" + f(worstPhases[5]) +
                        " swap=" + f(worstPhases[6]) + "]",
                )
            }
            worstSinceSteadyMs = 0.0
        }
    }

    private fun logJankBreakdown(metrics: FrameMetrics, totalMs: Double) {
        fun ns(id: Int): Double = metrics.getMetric(id) / 1_000_000.0
        val input = ns(FrameMetrics.INPUT_HANDLING_DURATION)
        val anim = ns(FrameMetrics.ANIMATION_DURATION)
        val layout = ns(FrameMetrics.LAYOUT_MEASURE_DURATION)
        val draw = ns(FrameMetrics.DRAW_DURATION)
        val sync = ns(FrameMetrics.SYNC_DURATION)
        val gpu = ns(FrameMetrics.GPU_DURATION)
        val swap = ns(FrameMetrics.SWAP_BUFFERS_DURATION)
        val ctx = synchronized(markers) { markers.joinToString(" | ") { it.tag } }
        fun f(v: Double) = String.format("%.1f", v)
        AppLogger.w(
            TAG,
            "JANK " + f(totalMs) + "ms (budget " + f(frameBudgetMs) +
                ") input=" + f(input) + " anim=" + f(anim) +
                " layout=" + f(layout) + " draw=" + f(draw) +
                " sync=" + f(sync) + " gpu=" + f(gpu) + " swap=" + f(swap) +
                (if (ctx.isEmpty()) "" else " ctx=[" + ctx + "]"),
        )
    }

    private companion object {
        const val TAG = "PerfMon"
    }
}