package dev.leonardo.ocbeacon.ui.screens.workspace.tree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.isDirectory
import dev.leonardo.ocbeacon.ui.screens.workspace.FileTreeNode
import dev.leonardo.ocbeacon.ui.screens.workspace.WorkspaceUiState
import dev.leonardo.ocbeacon.ui.screens.workspace.flattenTree
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * File tree panel: renders the workspace as a flattened, depth-indented list.
 * Directories can be expanded/collapsed via [onToggleExpand]; sub-directory
 * children are lazily loaded on first expansion.
 */
@Composable
fun FileTreePanel(
    uiState: WorkspaceUiState,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onOpenFile: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val emptyDirectoryMessage = stringResource(R.string.workspace_empty_directory)
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(SpacingTokens.SM.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onRefreshRoot) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.workspace_refresh))
            }
            FilterChip(
                selected = uiState.showIgnored,
                onClick = onToggleShowIgnored,
                label = { Text(stringResource(R.string.workspace_show_ignored)) },
                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) }
            )
        }
        when {
            uiState.rootLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag("file_tree_loading"))
            }

            uiState.rootError != null -> FileTreeErrorState(
                error = uiState.rootError,
                onRetry = onRefreshRoot
            )

            uiState.rootNodes.isEmpty() -> FileTreeEmptyState(message = emptyDirectoryMessage)

            else -> {
                val flattened = remember(
                    uiState.rootNodes,
                    uiState.expandedDirs,
                    uiState.showIgnored
                ) {
                    flattenTree(uiState.rootNodes, uiState.expandedDirs, uiState.showIgnored)
                }
                LazyColumn {
                    items(flattened, key = { it.first.node.path }) { (treeNode, depth) ->
                        FileTreeItem(
                            treeNode = treeNode,
                            depth = depth,
                            isExpanded = treeNode.node.path in uiState.expandedDirs,
                            isLoading = treeNode.node.path in uiState.loadingDirs,
                            onOpenFile = onOpenFile,
                            onToggleExpand = onToggleExpand
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single row in the file tree.
 * - Directories invoke [onToggleExpand] with their path and show an expand/collapse arrow.
 * - Files invoke [onOpenFile] with their path.
 * - When [isLoading] is true (sub-directory being fetched), a small spinner replaces the arrow.
 */
@Composable
fun FileTreeItem(
    treeNode: FileTreeNode,
    depth: Int,
    isExpanded: Boolean,
    isLoading: Boolean,
    onOpenFile: (String) -> Unit,
    onToggleExpand: (String) -> Unit
) {
    val isDirectory = treeNode.node.isDirectory()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isDirectory) onToggleExpand(treeNode.node.path)
                else onOpenFile(treeNode.node.path)
            }
            .padding(start = (depth * SpacingTokens.LG).dp)
            .padding(vertical = SpacingTokens.SM.dp, horizontal = SpacingTokens.MD.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Folder/file icon — folders use FolderOpen/Folder to show expand state.
        // Loading state dims the icon slightly (no separate spinner).
        Icon(
            imageVector = when {
                isDirectory && isExpanded -> Icons.Filled.FolderOpen
                isDirectory -> Icons.Filled.Folder
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (isLoading) 0.4f else 1f
            )
        )
        Spacer(Modifier.width(SpacingTokens.SM.dp))
        Text(
            text = treeNode.node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FileTreeErrorState(
    error: Int,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp)
        ) {
            Text(
                text = stringResource(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.workspace_retry)) }
        }
    }
}

@Composable
private fun FileTreeEmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
