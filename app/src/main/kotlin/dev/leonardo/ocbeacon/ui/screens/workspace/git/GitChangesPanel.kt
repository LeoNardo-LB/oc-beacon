package dev.leonardo.ocbeacon.ui.screens.workspace.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import dev.leonardo.ocbeacon.ui.screens.workspace.WorkspaceUiState
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * Git 变更面板：渲染工作区变更并带各状态计数。
 *
 * 镜像 [dev.leonardo.ocbeacon.ui.screens.workspace.tree.FileTreePanel] 的
 * 状态结构（加载中 / 非 git 仓库 / 错误 / 空 / 列表），每项变更显示一个
 * [GitChangeItem]，以 [HorizontalDivider] 分隔。
 *
 * Phase 1 范围：仅列表 + 计数。点击查看 diff 已通过 [onOpenDiff] 接通，
 * DiffView 承接差异渲染。
 */
@Composable
fun GitChangesPanel(
    uiState: WorkspaceUiState,
    onRefresh: () -> Unit,
    onOpenDiff: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val changes = uiState.gitChanges
    val stats = remember(changes) {
        changes.groupingBy { it.status }.eachCount()
    }
    val statsText = stringResource(
        R.string.workspace_git_stats,
        changes.size,
        stats[VcsStatus.MODIFIED] ?: 0,
        stats[VcsStatus.ADDED] ?: 0,
        stats[VcsStatus.DELETED] ?: 0
    )

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.SM.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.workspace_refresh)
                )
            }
            Text(
                text = statsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            uiState.gitLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag("git_changes_loading"))
            }

            uiState.isNonGit -> GitChangesEmptyState(
                message = stringResource(R.string.workspace_git_not_a_repo)
            )

            uiState.gitError != null -> GitChangesErrorState(
                error = uiState.gitError,
                onRetry = onRefresh
            )

            changes.isEmpty() -> GitChangesEmptyState(
                message = stringResource(R.string.workspace_git_working_tree_clean)
            )

            else -> LazyColumn {
                items(changes, key = { it.file }) { change ->
                    GitChangeItem(
                        change = change,
                        onClick = { onOpenDiff(change.file) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GitChangesEmptyState(message: String) {
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

@Composable
private fun GitChangesErrorState(
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
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.workspace_retry))
            }
        }
    }
}
