package dev.leonardo.octether.ui.screens.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.ui.theme.AppMotion

/** Breathing circle loading indicator — single circle that pulses smoothly. */
@Composable
internal fun BreathingCircleIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "breathing_circle")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(AppMotion.BREATH_CYCLE, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(AppMotion.BREATH_CYCLE, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_alpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(color, CircleShape)
        )
    }
}
