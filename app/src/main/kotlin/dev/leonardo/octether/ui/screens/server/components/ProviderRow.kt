package dev.leonardo.octether.ui.screens.server.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.components.AmoledDefaultBorder
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.screens.server.ProviderToggle
import dev.leonardo.octether.ui.theme.ShapeTokens

@Composable
internal fun ProviderRow(
    provider: ProviderToggle,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    showConnect: Boolean,
    canDisconnect: Boolean,
    isSaving: Boolean,
    isAmoled: Boolean,
    showSource: Boolean
) {
    Card(
        shape = ShapeTokens.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = if (isAmoled) AmoledDefaultBorder else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.providerName,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = provider.providerId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                )
                if (showSource) {
                    provider.source?.let { src ->
                        Text(
                            text = when (src) {
                                "env" -> stringResource(R.string.server_settings_provider_source_env)
                                "api" -> stringResource(R.string.server_settings_provider_source_api)
                                "config" -> stringResource(R.string.server_settings_provider_source_config)
                                "custom" -> stringResource(R.string.server_settings_provider_source_custom)
                                else -> stringResource(R.string.server_settings_provider_source_other)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                if (showConnect) {
                    OutlinedButton(onClick = onConnect, enabled = !isSaving) {
                        Text(stringResource(R.string.connect))
                    }
                } else if (canDisconnect) {
                    OutlinedButton(onClick = onDisconnect, enabled = !isSaving) {
                        Text(stringResource(R.string.disconnect))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.server_settings_provider_env_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                    )
                }
            }
        }
    }
}
