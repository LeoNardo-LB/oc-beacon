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
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 文件树面板：将工作区渲染为扁平化、按深度缩进的列表。
 * 目录可通过 [onToggleExpand] 展开/折叠；子目录
 * 在首次展开时懒加载。
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
                // #149：testTag 供 androidTest 唯一定位（文案随 locale 变化，
                // onNodeWithText 中文断言在 en 测试环境匹配 0 节点 → 注入失败）
                modifier = Modifier.testTag("file_tree_show_ignored"),
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
 * 文件树中的单行。
 * - 目录以其路径调用 [onToggleExpand]，并显示展开/折叠箭头。
 * - 文件以其路径调用 [onOpenFile]。
 * - 当 [isLoading] 为 true（正在拉取子目录）时，一个小型加载指示器替代箭头。
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
        // 目录/文件图标 — 目录使用 FolderOpen/Folder 显示展开状态。
        // 加载状态会略微降低图标透明度（没有单独的加载指示器）。
        Icon(
            imageVector = when {
                isDirectory && isExpanded -> Icons.Filled.FolderOpen
                isDirectory -> Icons.Filled.Folder
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                // #137（D2-L49）：裸 alpha 0.4f → AlphaTokens（数值最接近 FAINT 0.35）
                alpha = if (isLoading) AlphaTokens.FAINT else 1f
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
