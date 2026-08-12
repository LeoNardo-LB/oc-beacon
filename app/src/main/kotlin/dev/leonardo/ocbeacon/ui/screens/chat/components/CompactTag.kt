package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * 紧凑 SuggestionChip（2026-08-12 用户要求：M3 Chip 原生但紧凑）。
 *
 * M3 无官方 compact 变体——通过 `Modifier.height` 压缩高度
 * （默认 32dp → 24dp，M3 文档确认 Height 可 override），其余样式保持 M3 原生。
 * 使用点：agent 徽章（统计栏/后台面板）、QUEUED 状态徽章。
 */
@Composable
internal fun CompactSuggestionChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontSize: Int = 12,
) {
    SuggestionChip(
        onClick = {},
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = fontWeight,
                    fontSize = fontSize.sp
                )
            )
        },
        modifier = modifier.height(24.dp),
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = containerColor,
            labelColor = contentColor
        )
    )
}

/**
 * agent 名称标签（与输入组件 AgentModelVariantSelector 同款视觉语义）。
 * agentColor 提供语义色（不同 agent 不同色相）。
 */
@Composable
internal fun AgentTag(
    agent: String,
    tagColor: Color,
    modifier: Modifier = Modifier,
) {
    CompactSuggestionChip(
        text = agent.replaceFirstChar { it.uppercase() },
        containerColor = tagColor.copy(alpha = AlphaTokens.FAINT),
        contentColor = tagColor,
        modifier = modifier
    )
}
