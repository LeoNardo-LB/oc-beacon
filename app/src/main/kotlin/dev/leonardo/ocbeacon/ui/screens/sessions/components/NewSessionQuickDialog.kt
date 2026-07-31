package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

internal data class RecentSessionDirectory(
    val directory: String,
    val name: String,
    val count: Int,
    val lastUsed: Long,
)

/**
 * Groups [sessions] by directory and returns the most recently used ones,
 * up to [limit]. Used to populate the quick new-session dialog.
 */
internal fun recentSessionDirectories(
    sessions: List<Session>,
    limit: Int = 20,
): List<RecentSessionDirectory> = sessions
    .groupBy { it.directory.replace('\\', '/').trimEnd('/') }
    .map { (directory, items) ->
        RecentSessionDirectory(
            directory = items.first().directory,
            name = directory.substringAfterLast('/').ifEmpty { directory },
            count = items.size,
            lastUsed = items.maxOf { it.time.updated },
        )
    }
    .sortedByDescending(RecentSessionDirectory::lastUsed)
    .take(limit)

/**
 * Quick-start dialog for creating a new session.
 * Shows unique project folders (grouped from existing sessions) sorted by most
 * recently used — one tap starts a session there. A "Browse…" row at the bottom
 * opens the full directory picker ([onBrowse]).
 *
 * Ported from upstream oc-remote v1.7.0, adapted to the AMOLED dialog token system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewSessionQuickDialog(
    sessions: List<Session>,
    limit: Int,
    onSelectDirectory: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dirEntries = remember(sessions, limit) { recentSessionDirectories(sessions, limit) }
    val params = amoledDialogParams(shape = ShapeTokens.largeMedium)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.sessions_new_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(dirEntries, key = { it.directory }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectDirectory(entry.directory) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = entry.directory.trimEnd('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${entry.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBrowse() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                    Text(
                        text = stringResource(R.string.sessions_open_other_project),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
