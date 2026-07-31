package dev.leonardo.octether.ui.screens.chat.components

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
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.ui.theme.AlphaTokens

/**
 * Calculates the context window usage ratio from StepFinish parts.
 *
 * @param parts All parts from the current session's messages.
 * @param contextLimit The model's context window limit (tokens). 0 = unknown.
 * @return Usage ratio 0f..1f. Returns 0f if contextLimit is 0 or no tokens found.
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
 * Returns the color to use for the progress bar based on usage ratio.
 * - <70%: primary
 * - 70-90%: tertiary
 * - >90%: error
 */
@Composable
fun contextUsageColor(ratio: Float) = when {
    ratio >= 0.9f -> MaterialTheme.colorScheme.error
    ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/**
 * A composable that displays context window usage as a progress bar.
 *
 * @param usageRatio Usage ratio 0f..1f from [calculateContextUsage].
 * @param modifier Optional modifier.
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
