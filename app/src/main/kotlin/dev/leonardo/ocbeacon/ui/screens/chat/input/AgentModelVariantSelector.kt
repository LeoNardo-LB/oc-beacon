package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * Agent / Model / Variant 选择器行，带附件按钮。
 */
@Composable
internal fun AgentModelVariantSelector(
    modelLabel: String,
    selectedProviderId: String?,
    agents: List<AgentInfo>,
    selectedAgent: String,
    variantNames: List<String>,
    selectedVariant: String?,
    onModelClick: () -> Unit,
    onAgentSelect: (String) -> Unit,
    onCycleVariant: () -> Unit,
    onAttach: () -> Unit
) {
    if (modelLabel.isEmpty() && agents.size <= 1) return

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // agent/model/variant 的可滚动区域，保证回形针始终可见
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            // Agent 选择器 —— 单个按钮，点击循环切换
            // 固定宽度：所有 agent 名称以不可见方式渲染以预留最大宽度
            if (agents.size > 1) {
                val agentColorValue = agentColor(selectedAgent, agents)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(ShapeTokens.smallMedium)
                        .background(agentColorValue.copy(alpha = AlphaTokens.FAINT))
                        .clickable {
                            val currentIndex = agents.indexOfFirst { it.name == selectedAgent }
                            val nextIndex = (currentIndex + 1) % agents.size
                            onAgentSelect(agents[nextIndex].name)
                        }
                        .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp)
                ) {
                    // 所有 agent 名称的不可见幽灵文本 —— 将宽度固定为最宽者
                    agents.forEach { agent ->
                        Text(
                            text = agent.name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Transparent
                        )
                    }
                    // 带强调色的可见标签
                    Text(
                        text = selectedAgent.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = agentColorValue
                    )
                }
            }

            // 模型选择器 —— 第二位
            if (modelLabel.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(ShapeTokens.smallMedium)
                        .clickable { onModelClick() }
                        .padding(horizontal = SpacingTokens.XS.dp, vertical = SpacingTokens.XS.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
                ) {
                    if (selectedProviderId != null) {
                        ProviderIcon(
                            providerId = selectedProviderId,
                            size = 13.dp,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                        )
                    }
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                    )
                    Icon(
                        Icons.Default.UnfoldMore,
                        contentDescription = stringResource(R.string.a11y_icon_model_variant),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    )
                }
            }

            // Variant 循环按钮（思考强度）—— 第三位
            if (variantNames.isNotEmpty()) {
                Text(
                    text = selectedVariant?.replaceFirstChar { it.uppercase() }
                        ?: stringResource(R.string.chat_default_variant),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedVariant != null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    },
                    modifier = Modifier
                        .clip(ShapeTokens.smallMedium)
                        .clickable { onCycleVariant() }
                        .padding(horizontal = SpacingTokens.XS.dp, vertical = SpacingTokens.XS.dp)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // 附件按钮（回形针）—— 始终可见，固定在右侧，与发送按钮对齐
            IconButton(
                onClick = onAttach,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.chat_attach),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                )
            }
        }
    }
}
