package dev.leonardo.octether.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.ServerConfig
import dev.leonardo.octether.ui.theme.AlphaTokens
import kotlinx.coroutines.delay

/**
 * Full-screen error UI shown when the server is unreachable.
 *
 * Displays a CloudOff icon, the server name, a status message with retry
 * countdown, and (when the countdown expires) a retry button. Below the
 * main section, lists other available servers the user can switch to.
 *
 * The countdown is driven by [LaunchedEffect] with a 1-second delay loop.
 * When [retryCountdown] > 0 a [LinearProgressIndicator] and countdown
 * text are shown; when it reaches 0 a retry [Button] appears instead.
 *
 * @param serverName    Display name of the current (unreachable) server
 * @param statusMessage Description of the connection error
 * @param retryCountdown Seconds remaining before auto-retry; 0 shows the retry button
 * @param otherServers  List of other known servers the user may switch to
 * @param onRetryClick  Called when the user taps the retry button
 * @param onSwitchServer Called when the user selects another server from the list
 * @param modifier      Modifier for the root layout
 */
@Composable
fun ConnectionErrorScreen(
    serverName: String,
    statusMessage: String,
    retryCountdown: Int,
    otherServers: List<ServerConfig>,
    onRetryClick: () -> Unit,
    onSwitchServer: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var secondsRemaining by remember(retryCountdown) {
        mutableIntStateOf(retryCountdown)
    }

    // Countdown loop: decrement every second while > 0
    LaunchedEffect(secondsRemaining) {
        if (secondsRemaining > 0) {
            delay(1000L)
            secondsRemaining--
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Error icon ──
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = stringResource(R.string.connection_error_title),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Server name ──
            Text(
                text = serverName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Status message ──
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Countdown progress or retry button ──
            if (secondsRemaining > 0) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(0.6f),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.connection_error_retrying_in, secondsRemaining),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = AlphaTokens.MUTED,
                    ),
                )
            } else {
                Button(onClick = onRetryClick) {
                    Text(stringResource(R.string.retry))
                }
            }

            // ── Other servers section ──
            if (otherServers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))

                HorizontalDivider()

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.connection_error_switch_server),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = AlphaTokens.MEDIUM,
                    ),
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                otherServers.forEach { server ->
                    Surface(
                        onClick = { onSwitchServer(server) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = AlphaTokens.FAINT,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = server.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = server.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
