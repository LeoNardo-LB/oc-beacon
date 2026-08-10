package dev.leonardo.ocbeacon.ui.screens.home.components

import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.ui.components.AmoledCard
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode
import dev.leonardo.ocbeacon.ui.theme.StatusConnected

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerCard(
    server: ServerConfig,
    isConnected: Boolean,
    isConnecting: Boolean,
    connectionError: String?,
    showServerSettings: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSessions: () -> Unit,
    onServerSettings: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isAmoled = LocalAmoledMode.current
    val cardContentColor = if (isConnected && !isAmoled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    AmoledCard(
        isAmoledDark = isAmoled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 头部行：名称、URL、状态、菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = cardContentColor
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cardContentColor.copy(alpha = AlphaTokens.MEDIUM)
                    )
                    // 版本标签行
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isConnected) {
                            Text(
                                text = stringResource(R.string.home_server_health_good),
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusConnected
                            )
                        } else if (isConnecting) {
                            Text(
                                text = stringResource(R.string.home_connecting),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        // API 版本徽章
                        if (server.apiVersion != dev.leonardo.ocbeacon.domain.model.ApiVersion.V1) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = when (server.apiVersion) {
                                    dev.leonardo.ocbeacon.domain.model.ApiVersion.V2 ->
                                        MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = "API v${if (server.apiVersion.isV2) "2" else "1"}" +
                                        (server.serverVersion?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (server.apiVersion) {
                                        dev.leonardo.ocbeacon.domain.model.ApiVersion.V2 ->
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showServerSettings) {
                        IconButton(onClick = onServerSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.server_settings_title))
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            border = if (isAmoled) AmoledDefaultBorder else null
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_edit)) },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.a11y_icon_edit))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.server_delete)) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.a11y_icon_delete))
                                }
                            )
                        }
                    }
                }
            }

            // 连接错误
            if (connectionError != null) {
                Text(
                    text = connectionError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 操作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isConnected) {
                    Button(
                        onClick = onOpenSessions,
                        modifier = Modifier.weight(1f),
                        colors = ButtonTokens.filledColors(),
                        border = ButtonTokens.amoledBorder()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.a11y_icon_open_chat), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.sessions_title), maxLines = 1)
                    }
                }
            }
            if (isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonTokens.filledColors(),
                        border = ButtonTokens.amoledBorder()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_icon_close), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_disconnect), maxLines = 1)
                    }
                }
            }
            if (!isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        enabled = !isConnecting,
                        colors = ButtonTokens.filledColors(),
                        border = ButtonTokens.amoledBorder()
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = if (isAmoled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.home_connecting))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.a11y_icon_start), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.home_connect))
                        }
                    }
                    // 连接中可取消：长时间连接（弱网/服务器启动慢）时
                    // 允许用户中止，避免只能等待超时。
                    if (isConnecting) {
                        Button(
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f),
                            colors = ButtonTokens.dangerColors(),
                            border = ButtonTokens.amoledBorder()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_icon_close), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cancel), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
