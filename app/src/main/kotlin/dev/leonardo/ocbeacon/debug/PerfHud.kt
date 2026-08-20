package dev.leonardo.ocbeacon.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 性能 HUD（2026-08-20 第三轮；仅 debug 构建由 MainActivity 在 debug_perf
 * intent extra 开启时组合——release 树中不存在本节点）。
 *
 * 观察者效应控制：单 Text 行 + 静态背景，500ms 才重绘一次（数据源节流），
 * 无动画无 pointerInput（不拦截下方触摸）。故意不用 Canvas 图表——
 * 图表每帧重绘会污染被测帧。
 */
@Composable
internal fun PerfHud(hud: State<ChatPerfMonitor.HudData>, modifier: Modifier = Modifier) {
    val d = hud.value
    // 预算超支色阶：<30% 绿 / 30-60% 黄 / >60% 红（直觉读法）
    val color = when {
        d.overBudgetPct > 60 -> Color(0xFFFF5252)
        d.overBudgetPct > 30 -> Color(0xFFFFD54F)
        else -> Color(0xFF69F0AE)
    }
    Column(
        modifier = modifier
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "~" + d.fpsEstimate + "fps  p50 " + fmt(d.p50Ms) + "  p95 " + fmt(d.p95Ms) + "ms",
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = "over " + fmt(d.overBudgetPct) + "%  jank " + d.totalJank + " (" + d.windowFrames + "f)",
            color = Color(0xB3FFFFFF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun fmt(v: Double): String = String.format("%.1f", v)