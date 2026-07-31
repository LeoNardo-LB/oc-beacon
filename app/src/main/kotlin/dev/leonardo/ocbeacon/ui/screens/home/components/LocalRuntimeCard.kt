package dev.leonardo.ocbeacon.ui.screens.home.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.AmoledCard
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode
import dev.leonardo.ocbeacon.ui.screens.home.LocalRuntimeStatus

@Composable
internal fun LocalRuntimeCard(
    termuxInstalled: Boolean,
    runtimeStatus: LocalRuntimeStatus,
    statusMessage: String?,
    fixCommand: String?,
    needsOverlaySettings: Boolean,
    localServerConnected: Boolean,
    localServerConnecting: Boolean,
    localServerConnectionError: String?,
    showLocalServerSettings: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetup: () -> Unit,
    onCopyFixCommand: (String) -> Unit,
    onOpenTermuxOverlaySettings: () -> Unit,
    onOpenLocalSessions: () -> Unit,
    onOpenLocalServerSettings: () -> Unit,
    onOpenLocalLaunchOptions: () -> Unit,
    onInstallTermux: () -> Unit,
) {
    val isAmoled = LocalAmoledMode.current
    val cardContentColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    AmoledCard(
        isAmoledDark = isAmoled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val compactActive = runtimeStatus == LocalRuntimeStatus.Running &&
                localServerConnected &&
                localServerConnectionError.isNullOrBlank()

            // Header row with title and status chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_local_server_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = cardContentColor,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenLocalLaunchOptions) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(R.string.home_local_launch_options),
                            tint = cardContentColor,
                        )
                    }
                    if (showLocalServerSettings) {
                        IconButton(onClick = onOpenLocalServerSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = cardContentColor,
                            )
                        }
                    }
                }
            }

            // Description (hide when fully active to keep card compact)
            if (!compactActive) {
                Text(
                    text = stringResource(R.string.home_local_server_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = cardContentColor.copy(alpha = AlphaTokens.AMOLED),
                )
            }

            // Status / error message
            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (runtimeStatus == LocalRuntimeStatus.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        cardContentColor
                    },
                )
            }

            // Fix command copy button (for errors with a known fix)
            if (runtimeStatus == LocalRuntimeStatus.Error && !fixCommand.isNullOrBlank()) {
                Button(
                    onClick = { onCopyFixCommand(fixCommand) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonTokens.filledColors(),
                    border = ButtonTokens.amoledBorder(),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.a11y_icon_copy))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_local_copy_fix_command))
                }
            }

            if (runtimeStatus == LocalRuntimeStatus.Error && needsOverlaySettings) {
                Button(
                    onClick = onOpenTermuxOverlaySettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonTokens.filledColors(),
                    border = ButtonTokens.amoledBorder(),
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.a11y_icon_open_in_browser))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_local_open_termux_overlay_settings))
                }
            }

            // --- Action area based on status ---
            when {
                // Termux not installed — show install button
                !termuxInstalled -> {
                    Button(
                        onClick = onInstallTermux,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonTokens.filledColors(),
                        border = ButtonTokens.amoledBorder(),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.a11y_icon_download))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_local_install_termux))
                    }
                }

                // Needs setup — show setup command and Setup button
                runtimeStatus == LocalRuntimeStatus.NeedsSetup -> {
                    Text(
                        text = stringResource(R.string.home_local_setup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = cardContentColor.copy(alpha = AlphaTokens.AMOLED),
                    )
                    Button(
                        onClick = onSetup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonTokens.filledColors(),
                        border = ButtonTokens.amoledBorder(),
                    ) {
                        Icon(Icons.Default.Build, contentDescription = stringResource(R.string.a11y_icon_build), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_local_setup))
                    }

                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonTokens.filledColors(),
                        border = ButtonTokens.amoledBorder(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.a11y_icon_start), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_local_start))
                    }
                }

                // Running or Starting or Stopping — show stop button
                runtimeStatus == LocalRuntimeStatus.Running ||
                    runtimeStatus == LocalRuntimeStatus.Starting ||
                    runtimeStatus == LocalRuntimeStatus.Stopping -> {
                    val actionLabel = when (runtimeStatus) {
                        LocalRuntimeStatus.Starting -> stringResource(R.string.home_local_status_starting)
                        LocalRuntimeStatus.Stopping -> stringResource(R.string.home_local_status_stopping)
                        else -> stringResource(R.string.home_local_stop)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (localServerConnected) {
                            Button(
                                onClick = onOpenLocalSessions,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonTokens.filledColors(),
                                border = ButtonTokens.amoledBorder(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.a11y_icon_open_chat))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.home_local_open_sessions))
                            }
                        }

                        Button(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = runtimeStatus == LocalRuntimeStatus.Running,
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            if (runtimeStatus == LocalRuntimeStatus.Starting || runtimeStatus == LocalRuntimeStatus.Stopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(actionLabel)
                        }
                    }
                }

                // Stopped or Error — show start button
                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.a11y_icon_start), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_local_start))
                    }

                    Button(
                            onClick = onSetup,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Icon(Icons.Default.Build, contentDescription = stringResource(R.string.a11y_icon_build), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_local_setup))
                        }
                    }
                }
            }

            if (
                runtimeStatus != LocalRuntimeStatus.Running &&
                runtimeStatus != LocalRuntimeStatus.Starting &&
                runtimeStatus != LocalRuntimeStatus.Stopping &&
                localServerConnected
            ) {
                if (!compactActive) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT))
                }

                if (!localServerConnectionError.isNullOrBlank()) {
                    Text(
                        text = localServerConnectionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = onOpenLocalSessions,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonTokens.filledColors(),
                    border = ButtonTokens.amoledBorder(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.a11y_icon_open_chat))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_local_open_sessions))
                }
            }
        }
    }
}
