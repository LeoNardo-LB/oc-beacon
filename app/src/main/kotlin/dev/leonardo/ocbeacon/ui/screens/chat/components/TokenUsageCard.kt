package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * Card displaying token usage statistics for the current session.
 */
@Composable
fun TokenUsageCard(
    inputTokens: Int,
    outputTokens: Int,
    reasoningTokens: Int,
    cacheReadTokens: Int,
    cacheWriteTokens: Int,
    totalCost: Double,
    modifier: Modifier = Modifier
) {
    // #215 批1：容器统一——裸 Surface(surfaceContainerLow + small 8dp) →
    // AmoledSurface 统一语言（surface + tonal 1dp + smallMedium 6dp，AMOLED 纯黑+边框）
    AmoledSurface(
        isAmoledDark = isAmoledTheme(),
        normalColor = MaterialTheme.colorScheme.surface,
        normalTonalElevation = 1.dp,
        shape = ShapeTokens.smallMedium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: total tokens + cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.chat_token_usage_total,
                        inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
                if (totalCost > 0) {
                    Text(
                        text = String.format("$%.4f", totalCost),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Token breakdown rows
            TokenRow(stringResource(R.string.chat_token_input), inputTokens)
            TokenRow(stringResource(R.string.chat_token_output), outputTokens)
            if (reasoningTokens > 0) {
                TokenRow(stringResource(R.string.chat_token_reasoning), reasoningTokens)
            }
            if (cacheReadTokens > 0 || cacheWriteTokens > 0) {
                TokenRow(stringResource(R.string.chat_token_cache_read), cacheReadTokens)
                TokenRow(stringResource(R.string.chat_token_cache_write), cacheWriteTokens)
            }
        }
    }
}

@Composable
private fun TokenRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
        )
        Text(
            text = String.format("%,d", value),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
