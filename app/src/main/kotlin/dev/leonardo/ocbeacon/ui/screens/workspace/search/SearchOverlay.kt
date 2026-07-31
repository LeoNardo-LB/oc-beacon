package dev.leonardo.ocbeacon.ui.screens.workspace.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import dev.leonardo.ocbeacon.ui.screens.workspace.WorkspacePanel
import dev.leonardo.ocbeacon.ui.theme.DiffAdded
import dev.leonardo.ocbeacon.ui.theme.DiffRemoved
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * Search results body displayed below [SearchTopBar] (spec §6.4).
 *
 * Renders file-tree results (server search via findFiles) or git-changes
 * results (client filter) depending on [activePanel].
 */
@Composable
fun SearchOverlay(
    activePanel: WorkspacePanel,
    query: String,
    fileResults: List<String>,
    gitChanges: List<VcsChange>,
    isLoading: Boolean,
    hasSearched: Boolean,
    errorMessageRes: Int?,
    onOpenFile: (String) -> Unit,
    onOpenGitDiff: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
        when {
            errorMessageRes != null -> SearchErrorState(messageRes = errorMessageRes)
            isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            activePanel == WorkspacePanel.FILE_TREE -> FileSearchResultsList(
                results = fileResults, hasSearched = hasSearched, onOpenFile = onOpenFile
            )
            activePanel == WorkspacePanel.GIT_CHANGES -> GitSearchResultsList(
                changes = gitChanges, query = query, onOpenDiff = onOpenGitDiff
            )
        }
    }
}

@Composable
private fun FileSearchResultsList(
    results: List<String>,
    hasSearched: Boolean,
    onOpenFile: (String) -> Unit
) {
    when {
        !hasSearched -> EmptyHint(R.string.workspace_search_hint)
        results.isEmpty() -> EmptyHint(R.string.workspace_search_no_results)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it }) { path ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("workspace_search_result")
                        .clickable { onOpenFile(path) }
                        .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = ellipsizeMiddle(path, maxLength = 60),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = SpacingTokens.MD.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GitSearchResultsList(
    changes: List<VcsChange>,
    query: String,
    onOpenDiff: (String) -> Unit
) {
    if (changes.isEmpty()) {
        val hintRes = if (query.isBlank()) R.string.workspace_search_hint
        else R.string.workspace_search_no_git_match
        EmptyHint(hintRes)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(changes, key = { it.file }) { change ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("workspace_search_result")
                    .clickable { onOpenDiff(change.file) }
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(status = change.status)
                Text(
                    text = ellipsizeMiddle(change.file, maxLength = 56),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = SpacingTokens.MD.dp)
                        .weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "+${change.additions} -${change.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: VcsStatus) {
    val bg = when (status) {
        VcsStatus.ADDED -> DiffAdded
        VcsStatus.DELETED -> DiffRemoved
        VcsStatus.MODIFIED -> MaterialTheme.colorScheme.tertiary
    }
    val letter = when (status) {
        VcsStatus.ADDED -> "A"
        VcsStatus.DELETED -> "D"
        VcsStatus.MODIFIED -> "M"
    }
    Surface(color = bg, shape = ShapeTokens.extraSmall, modifier = Modifier.size(20.dp)) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(2.dp)
        )
    }
}

@Composable
private fun EmptyHint(messageRes: Int) {
    Box(Modifier.fillMaxSize().padding(SpacingTokens.LG.dp), Alignment.Center) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchErrorState(messageRes: Int) {
    Box(Modifier.fillMaxSize().padding(SpacingTokens.LG.dp), Alignment.Center) {
        Text(
            text = stringResource(messageRes),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Middle-ellipsis: app/src/.../User.kt. Used for long file paths in result lists. */
internal fun ellipsizeMiddle(path: String, maxLength: Int): String {
    if (path.length <= maxLength) return path
    val keepEach = (maxLength - 3) / 2
    return path.take(keepEach) + "..." + path.takeLast(keepEach)
}
