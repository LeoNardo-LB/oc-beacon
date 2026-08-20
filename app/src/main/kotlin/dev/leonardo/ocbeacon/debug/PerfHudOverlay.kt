package dev.leonardo.ocbeacon.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicReference

/**
 * 性能 HUD 独立悬浮窗（2026-08-20 观察者效应批次）。
 *
 * 动机（调研结论 /tmp/perf-round3/research.md）：同窗口 Compose HUD 会参与
 * 被测窗口的帧成本（重组/重绘都在同一 FrameMetrics 流里）——测量污染。
 * 本类把 HUD 放进 **独立 overlay window**：自己的帧流，与被测窗口完全隔离，
 * 且用纯 View 直绘（无 Compose 运行时开销），500ms 节流刷新。
 *
 * 权限：SYSTEM_ALERT_WINDOW（MIUI 需用户在系统设置授权一次）。未授权时
 * 调用方应回退到同窗口 Compose HUD（[PerfHud]）。
 */
internal class PerfHudOverlay(
    private val context: Context,
) {
    private var view: LinearLayout? = null
    private var line1: TextView? = null
    private var line2: TextView? = null
    private val wm get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)

    @SuppressLint("InflateParams")
    fun show() {
        if (!isAvailable() || view != null) return
        val dp = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            val pad = (8 * dp).toInt()
            setPadding(pad, (4 * dp).toInt(), pad, (4 * dp).toInt())
        }
        fun tv() = TextView(context).apply {
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
        }
        line1 = tv().also { container.addView(it) }
        line2 = tv().also { container.addView(it) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * dp).toInt()
            y = (90 * dp).toInt()
        }
        runCatching {
            wm.addView(container, params)
            view = container
        }
    }

    fun update(d: ChatPerfMonitor.HudData) {
        val color = when {
            d.overBudgetPct > 60 -> 0xFFFF5252.toInt()
            d.overBudgetPct > 30 -> 0xFFFFD54F.toInt()
            else -> 0xFF69F0AE.toInt()
        }
        line1?.text = "~" + d.fpsEstimate + "fps  p50 " + f(d.p50Ms) + "  p95 " + f(d.p95Ms) + "ms"
        line1?.setTextColor(color)
        line2?.text = "over " + f(d.overBudgetPct) + "%  jank " + d.totalJank +
            " (" + d.windowFrames + "f" + (if (d.droppedReports > 0) " drop" + d.droppedReports else "") + ")"
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
    }

    private fun f(v: Double) = String.format("%.1f", v)
}