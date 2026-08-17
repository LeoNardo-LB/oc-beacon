package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

// 2026-08-17 上下文占用口径修正（ACP：input+cache.read）：删除
// calculateContextUsage(parts, contextLimit)——无生产调用点（显示唯一来源
// 是 ChatTopBar，基于 lastContextTokens/contextWindow），系早期实现遗留。

/**
 * 根据使用率返回进度条的颜色。
 * - <70%：primary
 * - 70-90%：tertiary
 * - >90%：error
 */
@Composable
fun contextUsageColor(ratio: Float) = when {
    ratio >= 0.9f -> MaterialTheme.colorScheme.error
    ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/**
 * 以进度条形式展示上下文窗口使用率的 composable。
 *
 * @param usageRatio 使用率 0f..1f。
 * @param modifier 可选 modifier。
 */
@Composable
fun ContextUsageBar(
    usageRatio: Float,
    modifier: Modifier = Modifier
) {
    if (usageRatio <= 0f) return

    val percentage = (usageRatio * 100).toInt()
    val color = contextUsageColor(usageRatio)
    val trackColor = color.copy(alpha = AlphaTokens.FAINT)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_context_usage, percentage),
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        LinearProgressIndicator(
            progress = { usageRatio },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = color,
            trackColor = trackColor,
        )
    }
}
