package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionListViewModel
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionViewMode
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 会话树形列表（LazyColumn）——含分页加载、滚动恢复和目录/会话节点渲染。
 */
@Composable
internal fun SessionTreeList(
    viewModel: SessionListViewModel,
    treeNodes: List<TreeNode>,
    currentViewMode: SessionViewMode,
    favoriteSessionIds: Set<String>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    scope: CoroutineScope,
    onNavigateToChat: (String) -> Unit,
    onNavigateToNewChat: (String) -> Unit,
    onRename: (sessionId: String, currentTitle: String) -> Unit,
    onDelete: (sessionId: String, title: String) -> Unit,
    onAssignTags: (sessionId: String, currentTagIds: Set<String>) -> Unit,
    // #271：同步状态透传（长按菜单同步详情区）
    syncStates: Map<String, dev.leonardo.ocbeacon.data.local.SessionSyncEntity> = emptyMap(),
    onRequestSync: (String) -> Unit = {},
    onCancelSync: (String) -> Unit = {},
) {
    val context = LocalContext.current
    // #106 lint 清偿：复制提示 hoist（两处 lambda 共用；context 仍供剪贴板）
    val copiedToClipboardMsg = stringResource(R.string.menu_copied_to_clipboard)
    val untitledLabel = stringResource(R.string.session_untitled)
    // #177：堆积队列计数（详情对话框「继续发送堆积消息」入口）
    val pendingCounts by viewModel.pendingCounts.collectAsState()
    // #276：能力位（DSH 无 session.delete——详情对话框删除动作隐藏）
    val serverCapabilities by viewModel.serverCapabilities.collectAsState()
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && viewModel.hasMorePages && !viewModel.isLoadingMore) {
            viewModel.loadMore()
        }
    }

    // 仅当从用户发送过消息的会话返回时滚动到顶部。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                viewModel.consumeScrollToTopOnReturn()
            ) {
                scope.launch { listState.scrollToItem(0) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = SpacingTokens.XS.dp)
    ) {
        itemsIndexed(treeNodes, key = { _, node -> node.id }) { index, node ->
            when (node) {
                is TreeNode.Directory -> {
                    DirectoryTreeNode(
                        node = node,
                        onClick = { viewModel.toggleDirectory(node.path) },
                        onCopyPath = { path ->
                            viewModel.copyToClipboard(path, context)
                            scope.launch { snackbarHostState.showSnackbar(copiedToClipboardMsg) }
                        },
                        onNewSession = { directory ->
                            // 进入会话前标记：返回列表时回到顶部
                            viewModel.requestScrollToTopOnReturn()
                            onNavigateToNewChat(directory)
                        },                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                            alpha = AlphaTokens.FAINT
                        )
                    )
                }
                is TreeNode.Session -> {
                    val isRecentMode = currentViewMode == SessionViewMode.RECENT
                    SessionRow(
                        item = node.session,
                        showDirectory = isRecentMode,
                        onClick = {
                            // 进入会话前标记：返回列表时回到顶部（无论是否发过消息）+ 记录待标记已读
                            viewModel.requestScrollToTopOnReturn()
                            viewModel.onSessionOpened(node.id)
                            onNavigateToChat(node.id)
                        },
                        onRename = {
                            val title = node.session.session.title ?: ""
                            onRename(node.id, title)
                        },
                        onDelete = {
                            onDelete(node.id, node.session.session.title ?: untitledLabel)
                        },
                        onCopyId = { id ->
                            viewModel.copyToClipboard(id, context)
                            scope.launch { snackbarHostState.showSnackbar(copiedToClipboardMsg) }
                        },
                        onAssignCategory = {
                            onAssignTags(node.id, node.session.tags.map { it.id }.toSet())
                        },
                        pendingCount = pendingCounts[node.id] ?: 0,
                        onContinueQueue = { viewModel.continuePendingQueue(node.id) },
                        isFavorite = node.id in favoriteSessionIds,
                        onToggleFavorite = {
                            viewModel.toggleFavorite(node.session.session)
                        },
                        deleteSupported = serverCapabilities.sessionDeleteSupported,
                        syncState = syncStates[node.id],
                        onRequestSync = { onRequestSync(node.id) },
                        onCancelSync = { onCancelSync(node.id) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                            alpha = AlphaTokens.FAINT
                        )
                    )
                }
            }
        }

        // 底部的"加载更多"指示器
        if (viewModel.isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingTokens.LG.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
