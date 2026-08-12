package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 紧凑标签（2026-08-12 用户要求：与输入组件的 agent 选择器样式一致——
 * 不使用 M3 Chip（32dp 高偏大），采用紧凑 Box 标签：
 * clip(小圆角) + 淡色底 + labelSmall 文字。
 *
 * 使用点：agent 徽章（统计栏/后台面板）、QUEUED 状态徽章。
 */
@Composable
internal fun CompactTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontSize: Int = 12,
    shape: RoundedCornerShape = ShapeTokens.smallMedium,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = fontWeight,
                fontSize = fontSize.sp
            ),
            color = contentColor
        )
    }
}

/**
 * agent 名称标签（与输入组件 AgentModelVariantSelector 同款视觉）。
 * agentColor 提供语义色（不同 agent 不同色相）。
 */
@Composable
internal fun AgentTag(
    agent: String,
    tagColor: Color,
    modifier: Modifier = Modifier,
) {
    CompactTag(
        text = agent.replaceFirstChar { it.uppercase() },
        containerColor = tagColor.copy(alpha = AlphaTokens.FAINT),
        contentColor = tagColor,
        modifier = modifier
    )
}
