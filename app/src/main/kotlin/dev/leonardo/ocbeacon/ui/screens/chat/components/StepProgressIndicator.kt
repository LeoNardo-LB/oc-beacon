package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.domain.model.StepProgressInfo
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Step progress indicator showing current step number, agent, and model.
 * Uses Material3 indeterminate LinearProgressIndicator.
 */
@Composable
fun StepProgressIndicator(
    stepInfo: StepProgressInfo,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Step ${stepInfo.step}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (stepInfo.agent.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stepInfo.agent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                )
            }
            if (stepInfo.model.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stepInfo.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
