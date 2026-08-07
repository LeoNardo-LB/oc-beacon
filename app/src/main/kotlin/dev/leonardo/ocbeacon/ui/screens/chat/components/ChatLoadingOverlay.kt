package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator

/**
 * 统一加载蒙版 —— 不透明 surface + 居中 PulsingDots。
 * 覆盖消息区与输入栏（两处共用同一状态），掩盖蒙版后内容同时出现。
 */
@Composable
fun ChatLoadingOverlay(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
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
