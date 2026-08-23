package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AppMotion
import dev.leonardo.ocbeacon.util.DateFormatters

/**
 * 压缩进行中气泡——2026-08-16（用户设计）：单独的透明但带轮廓的
 * 气泡，复用标准消息容器（MessageBubble，与合成通知卡片同构：Transparent
 * + 1dp outline 轮廓）；标题栏 = 时间 + 「正在压缩上下文」+ 呼吸 Compress
 * 图标（labelSuffix 状态槽）；内容栏 = 进度条。
 *
 * 驱动：V1 由服务器 compaction.started 三件套；V2 由 SessionActionsDelegate
 * 本地置态（服务器只发单个 session.compacted 完成事件）。
 */
@Composable
fun CompactionBanner(
    state: CompactionStateInfo,
    modifier: Modifier = Modifier
) {
    if (!state.isActive) return

    val transition = rememberInfiniteTransition(label = "compaction_pulse")
    val alpha by transition.animateFloat(
        initialValue = AlphaTokens.MEDIUM,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(AppMotion.BREATH_CYCLE),
            repeatMode = RepeatMode.Reverse
        ),
        label = "compaction_alpha"
    )
    // 进行中态无事件时间戳——气泡渲染时刻（标题栏时间规范与消息一致）
    val nowMs = remember { System.currentTimeMillis() }

    MessageBubble(
        alignEnd = false,
        containerColor = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MEDIUM)),
        label = if (state.reason.isNotBlank()) {
            stringResource(R.string.chat_compressing_context, state.reason)
        } else {
            stringResource(R.string.chat_compressing_context_plain)
        },
        timeMs = nowMs,
        labelSuffix = {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Compress,
                contentDescription = stringResource(R.string.a11y_icon_compress),
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { this.alpha = alpha },
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        modifier = modifier,
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
