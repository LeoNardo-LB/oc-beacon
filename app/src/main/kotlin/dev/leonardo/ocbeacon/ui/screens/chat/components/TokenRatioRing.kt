package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlin.math.min

/**
 * Token 占比圆环——统计栏内展示 input/output token 比例（2026-08-14 恢复）。
 *
 * 两段弧：输入（primary 色）与输出（tertiary 色）按 token 数占比绘制；
 * 无数据（total<=0）时渲染为浅色占位圆环（数据未到/流式中）。
 */
@Composable
internal fun TokenRatioRing(
    inputTokens: Int,
    outputTokens: Int,
    reasoningTokens: Int,
    modifier: Modifier = Modifier,
) {
    val total = inputTokens + outputTokens + reasoningTokens
    val inputFraction = if (total > 0) inputTokens.toFloat() / total else 0f
    val outputFraction = if (total > 0) outputTokens.toFloat() / total else 0f
    val reasoningFraction = if (total > 0) reasoningTokens.toFloat() / total else 0f

    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val tertiary = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
    val onSurface = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val placeholder = onSurface.copy(alpha = AlphaTokens.FAINT)

    Canvas(modifier = modifier.size(12.dp)) {
        val strokeWidth = 2.dp.toPx()
        val inset = strokeWidth / 2f
        val arcSize = Size(
            width = size.width - strokeWidth,
            height = size.height - strokeWidth
        )
        val topLeft = Offset(inset, inset)

        if (total <= 0) {
            // 无数据占位：完整浅色圆环
            drawArc(
                color = placeholder,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            return@Canvas
        }

        var start = -90f
        // 输出（tertiary）——用户最关心的回复量
        drawArc(
            color = tertiary,
            startAngle = start,
            sweepAngle = outputFraction * 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        start += outputFraction * 360f
        // 推理（tertiary 淡化）
        if (reasoningFraction > 0f) {
            drawArc(
                color = tertiary.copy(alpha = 0.45f),
                startAngle = start,
                sweepAngle = reasoningFraction * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            start += reasoningFraction * 360f
        }
        // 输入（primary）
        drawArc(
            color = primary,
            startAngle = start,
            sweepAngle = inputFraction * 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
    }
}
