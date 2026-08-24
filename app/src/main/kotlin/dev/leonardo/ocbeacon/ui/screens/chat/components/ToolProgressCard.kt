package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AppMotion
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * 展示实时工具执行进度的卡片。
 * 显示工具名称、状态文本和一个动画进度指示器。
 */
@Composable
fun ToolProgressCard(
    toolInfo: ToolProgressInfo,
    modifier: Modifier = Modifier
) {
    // #215 批1：容器统一——M3 Card(surfaceVariant 淡底 + shapes.small) →
    // AmoledSurface 统一语言（surface + tonal 1dp + smallMedium 6dp，AMOLED 纯黑+边框）
    AmoledSurface(
        isAmoledDark = isAmoledTheme(),
        normalColor = MaterialTheme.colorScheme.surface,
        normalTonalElevation = 1.dp,
        shape = ShapeTokens.smallMedium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 动画图标
            val transition = rememberInfiniteTransition(label = "tool_progress_rotation")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(AppMotion.PULSE_CYCLE, easing = AppMotion.StandardEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "tool_icon_rotation"
            )
            Icon(
                imageVector = if (toolInfo.status == "started") Icons.Default.Sync else Icons.Default.Build,
                contentDescription = stringResource(R.string.a11y_icon_build),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 工具名 + 进度
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = toolInfo.title ?: toolInfo.tool,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    if (toolInfo.progress != null) {
                        Text(
                            text = toolInfo.progress,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 不确定进度条
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}
