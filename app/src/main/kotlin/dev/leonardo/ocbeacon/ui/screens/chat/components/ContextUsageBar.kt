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
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * 从 StepFinish parts 计算上下文窗口使用率。
 *
 * @param parts 当前会话消息中的所有 parts。
 * @param contextLimit 模型的上下文窗口上限（token）。0 = 未知。
 * @return 使用率 0f..1f。contextLimit 为 0 或未找到 token 时返回 0f。
 */
fun calculateContextUsage(parts: List<Part>, contextLimit: Int): Float {
    if (contextLimit <= 0) return 0f

    var totalTokens = 0
    for (part in parts) {
        if (part is Part.StepFinish) {
            val tokens = part.tokens ?: continue
            totalTokens += tokens.total ?: (tokens.input + tokens.output + tokens.reasoning)
        }
    }

    if (totalTokens <= 0) return 0f
    return (totalTokens.toFloat() / contextLimit.toFloat()).coerceIn(0f, 1f)
}

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
 * @param usageRatio 来自 [calculateContextUsage] 的使用率 0f..1f。
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
