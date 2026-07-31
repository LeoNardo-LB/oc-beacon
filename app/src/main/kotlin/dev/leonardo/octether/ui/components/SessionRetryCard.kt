package dev.leonardo.octether.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.ui.theme.AlphaTokens
import kotlinx.coroutines.delay

/**
 * Compact card showing retry status inside a chat session.
 *
 * Displays a [CircularProgressIndicator] with attempt text, a
 * [LinearProgressIndicator] for attempt progress, an optional countdown
 * timer, and an optional error message truncated to 80 characters.
 *
 * @param attempt         Current retry attempt (1-based).
 * @param maxAttempts     Maximum retry attempts (default 3).
 * @param countdownSeconds Seconds until next retry; null hides countdown.
 * @param errorMessage    Optional error description (truncated to 80 chars).
 * @param modifier        Modifier for the root card.
 */
@Composable
fun SessionRetryCard(
    attempt: Int,
    maxAttempts: Int = 3,
    countdownSeconds: Int?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    var remainingSeconds by remember(countdownSeconds) {
        mutableIntStateOf(countdownSeconds ?: 0)
    }

    // Countdown loop: decrement every second while > 0
    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
    }

    // Truncate error message to 80 characters
    val displayError = errorMessage?.let {
        if (it.length > 80) it.take(80) + "…" else it
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Top row: spinner + attempt label + countdown ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )

                Text(
                    text = "Attempt $attempt of $maxAttempts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )

                if (remainingSeconds > 0) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${remainingSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(
                            alpha = AlphaTokens.MUTED,
                        ),
                    )
                }
            }

            // ── Attempt progress bar ──
            LinearProgressIndicator(
                progress = {
                    if (maxAttempts > 0) attempt.toFloat() / maxAttempts.toFloat() else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onErrorContainer,
                trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(
                    alpha = AlphaTokens.FAINT,
                ),
            )

            // ── Error message ──
            if (displayError != null) {
                Text(
                    text = displayError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(
                        alpha = AlphaTokens.MEDIUM,
                    ),
                    maxLines = 2,
                )
            }
        }
    }
}
