package dev.leonardo.ocbeacon.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.AppMotion

/**
 * 边缘加载指示器，支持两种模式：
 * - **active**：无限脉冲动画（呼吸效果），alpha 固定为 0.9
 * - **progress**：基于 [progress] 值（0..1）的确定性缩放，alpha 随进度变化
 *
 * 渲染为 3dp 高的水平渐变条（透明 → primary → 透明）。
 *
 * 灵感来自上游 oc-remote v1.7.0 的 AppLoadingEdge。
 * 动画时长使用项目的 [AppMotion.BREATH_CYCLE] 令牌。
 */
@Composable
fun AppLoadingEdge(
    active: Boolean,
    progress: Float = if (active) 1f else 0f,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "app_loading_edge")
    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(AppMotion.BREATH_CYCLE, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "app_loading_edge_pulse",
    )
    val scale = if (active) pulse else progress.coerceIn(0f, 1f)
    val alpha = if (active) 0.9f else (progress * 1.8f).coerceIn(0f, 0.9f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                transformOrigin = TransformOrigin.Center
            }
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.35f to MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    0.5f to MaterialTheme.colorScheme.primary,
                    0.65f to MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                )
            )
    )
}
