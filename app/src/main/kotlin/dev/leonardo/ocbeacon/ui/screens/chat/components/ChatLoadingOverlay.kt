package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.ui.screens.chat.util.OVERLAY_FADE_IN_MS

/**
 * 统一加载蒙版 —— 不透明 surface + 居中 PulsingDots，含淡入淡出动画与触摸拦截。
 *
 * 覆盖消息区与输入栏（两处共用同一 [visible] 状态），掩盖期间内容不可交互。
 * - 淡入 / 淡出均 [OVERLAY_FADE_IN_MS]（300ms）对称时长；淡入用 M3 decelerate 系，淡出用 M3 accelerate 系。
 * - 蒙版期间消费触摸事件，防止点穿到底下输入栏（弹键盘 / 发消息）。
 */
@Composable
fun ChatLoadingOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(OVERLAY_FADE_IN_MS.toInt(), easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(OVERLAY_FADE_IN_MS.toInt(), easing = FastOutLinearInEasing)),
    ) {
        Surface(
            modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                PulsingDotsIndicator()
            }
        }
    }
}
