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
 * Agent / Model / Variant selector row with attach button.
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
        // Scrollable area for agent/model/variant so paperclip always stays visible
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            // Agent selector — single button, tap to cycle
            // Fixed width: all agent names rendered invisible to reserve max width
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
                    // Invisible ghost texts for all agent names — fixes width to the widest
                    agents.forEach { agent ->
                        Text(
                            text = agent.name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Transparent
                        )
                    }
                    // Visible label with accent color
                    Text(
                        text = selectedAgent.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = agentColorValue
                    )
                }
            }

            // Model selector — SECOND
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

            // Variant cycle button (thinking effort) — THIRD
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
            // Attach button (paperclip) — always visible, pinned right, aligned with Send button
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
