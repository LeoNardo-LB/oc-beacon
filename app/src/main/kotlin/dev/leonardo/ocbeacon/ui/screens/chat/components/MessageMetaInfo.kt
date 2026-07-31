package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Displays metadata below an assistant message: model name, duration, token count.
 */
@Composable
fun MessageMetaInfo(
    modelName: String?,
    durationMs: Long?,
    inputTokens: Int?,
    outputTokens: Int?,
    modifier: Modifier = Modifier
) {
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
    val parts = buildList {
        modelName?.let { add(it) }
        durationMs?.let { add(formatMetaDuration(it)) }
        inputTokens?.let { i -> outputTokens?.let { o ->
            add(stringResource(R.string.chat_meta_tokens, i + o))
        } }
    }
    if (parts.isEmpty()) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor
                )
            }
            Text(
                text = part,
                style = MaterialTheme.typography.labelSmall,
                color = metaColor
            )
        }
    }
}

private fun formatMetaDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m ${((ms % 60_000) / 1000)}s"
    }
}
