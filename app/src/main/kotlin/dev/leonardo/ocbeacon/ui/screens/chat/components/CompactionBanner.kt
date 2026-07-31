package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AppMotion

/**
 * Banner showing context compaction in progress.
 * Displays "Compressing context..." with an animated indicator and reason.
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

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = AlphaTokens.FAINT)
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Compress,
                contentDescription = stringResource(R.string.a11y_icon_compress),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { this.alpha = alpha },
                tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.reason.isNotBlank()) "Compressing context: ${state.reason}" else "Compressing context...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
