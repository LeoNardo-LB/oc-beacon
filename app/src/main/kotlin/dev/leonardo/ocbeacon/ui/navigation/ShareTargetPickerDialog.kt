package dev.leonardo.ocbeacon.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.Session
import java.text.SimpleDateFormat
import java.util.*
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * Dialog shown when images are shared into the app via ACTION_SEND.
 * Lists recent sessions from servers that have SSE data loaded,
 * grouped by server. User taps a session to open it with the shared image(s).
 */
@Composable
internal fun ShareTargetPickerDialog(
    servers: List<ServerConfig>,
    sessions: List<Session>,
    serverSessions: Map<String, Set<String>>,
    imageCount: Int,
    onSelectSession: (server: ServerConfig, session: Session) -> Unit,
    onNewSession: (server: ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    // Build list of (server, session) pairs, sorted by most recently updated session
    data class PickerItem(val server: ServerConfig, val session: Session)

    val items = remember(servers, sessions, serverSessions) {
        val result = mutableListOf<PickerItem>()
        for (server in servers) {
            val sessionIds = serverSessions[server.id] ?: continue
            val serverSessionList = sessions
                .filter { it.id in sessionIds && !it.isArchived && it.parentId == null }
                .sortedByDescending { it.time.updated }
                .take(15)
            for (session in serverSessionList) {
                result.add(PickerItem(server, session))
            }
        }
        result.sortedByDescending { it.session.time.updated }
    }

    // Servers that have sessions loaded (for the "New session" option)
    val activeServers = remember(servers, serverSessions) {
        servers.filter { serverSessions.containsKey(it.id) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.7f),
            shape = ShapeTokens.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.share_send_image_to),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (imageCount == 1)
                                stringResource(R.string.image_count_single)
                            else
                                stringResource(R.string.image_count_multiple, imageCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )

                if (items.isEmpty()) {
                    // No connected servers / sessions
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = stringResource(R.string.share_no_connected_servers),
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                            )
                            Text(
                                text = stringResource(R.string.share_no_connected_servers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                            )
                            Text(
                                text = stringResource(R.string.share_connect_first),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                    }
                } else {
                    // Session list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(items, key = { "${it.server.id}/${it.session.id}" }) { item ->
                            val projectName = item.session.directory
                                .trimEnd('/')
                                .substringAfterLast('/')
                                .ifEmpty { null }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSession(item.server, item.session) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = stringResource(R.string.a11y_icon_open_chat),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    // Session title
                                    Text(
                                        text = item.session.title ?: stringResource(R.string.session_untitled),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // Project + server info
                                    val subtitle = buildString {
                                        if (projectName != null) append(projectName)
                                        if (activeServers.size > 1) {
                                            if (isNotEmpty()) append(" · ")
                                            append(item.server.displayName)
                                        }
                                    }
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                // Date
                                Text(
                                    text = dateFormat.format(Date(item.session.time.updated)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                )
                            }
                        }
                    }
                }

                // "New session" buttons per active server
                if (activeServers.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                    )

                    for (server in activeServers) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNewSession(server) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.a11y_icon_add),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                            )
                            Text(
                                text = if (activeServers.size > 1)
                                    stringResource(R.string.sessions_new_on_server, server.displayName)
                                else
                                    stringResource(R.string.sessions_new_short),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
