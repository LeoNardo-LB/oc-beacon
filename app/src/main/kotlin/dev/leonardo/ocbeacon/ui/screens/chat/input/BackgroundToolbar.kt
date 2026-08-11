package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 转后台工具栏——当有前台 subagent 运行时显示在输入栏上方。
 *
 * 对应 TUI 的 ctrl+b（"Background blocking session tools"）：
 * 一键将当前会话所有前台 subagent 批量转为后台执行，主会话立即恢复交互。
 *
 * M3 组件：状态文本 + SuggestionChip（"转为后台"）——零自定义。
 * 出现/消失动画由调用方 AnimatedVisibility 驱动。
 */
@Composable
internal fun BackgroundToolbar(
    text: String,
    onBackgroundSession: () -> Unit,
    onOpenBackground: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.FAINT),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(start = SpacingTokens.SM.dp, end = SpacingTokens.XS.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
    ) {
        // 左侧：图标 + 状态文本（点击打开后台面板查看详情）
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenBackground)
                .padding(vertical = SpacingTokens.XS.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Layers,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 右侧：转为后台操作
        SuggestionChip(
            onClick = onBackgroundSession,
            label = { Text(stringResource(R.string.background_toolbar_action)) }
        )
    }
}
