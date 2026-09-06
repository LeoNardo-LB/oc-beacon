package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionListViewModel
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionViewMode
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * #331 A2-r3：前插揭示裁决——head id 变化（有行前插到列表顶）且用户本在列表顶
 * （离开时/变更前首可见索引 0）→ 揭示（scrollToItem(0)）。
 *
 * 真机插桩取证（2026-09-06 /tmp/a2r3_probe2.log + a2r3_fix.log）：fork 行四 seam
 * （handler fold / 仓库发射 / VM combine / contentState）全部在场且列头位，但视口
 * 首行仍是旧头——LazyColumn 按键锚定把前插行留在视口之上；返回时 ON_RESUME 的
 * scrollToItem(0) 先于 WhileSubscribed5s 重订阅的新状态（~360ms）执行，随后数据
 * 变更重布局又把锚点拉回旧头（真机复核取证：decide 瞬间读到的是重布局中途的可见
 * 键，first=新行/idx=1 抖动）。
 *
 * 因此裁决只吃**无竞态**信号：head id（纯数据）+ 组合期读取的 firstVisibleItemIndex
 * （此刻新条目尚未布局，读到的是前次布局的稳定锚定位）。可见键类布局读数一律不用。
 */
internal fun shouldRevealPrependedHead(
    previousHeadId: String?,
    newHeadId: String?,
    wasAtTopWhenChanged: Boolean,
): Boolean = previousHeadId != null &&
    newHeadId != null &&
    previousHeadId != newHeadId &&
    wasAtTopWhenChanged

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
    // #311 归档：已归档行（workspace 快照集合 ∩ 会话缓存）+ 归档动作
    archivedSessions: List<SessionItem> = emptyList(),
    onArchive: (String) -> Unit = {},
) {
    val context = LocalContext.current
    // #106 lint 清偿：复制提示 hoist（两处 lambda 共用；context 仍供剪贴板）
    val copiedToClipboardMsg = stringResource(R.string.menu_copied_to_clipboard)
    val untitledLabel = stringResource(R.string.session_untitled)
    // #276：能力位（DSH 无 session.delete——详情对话框删除动作隐藏）
    val serverCapabilities by viewModel.serverCapabilities.collectAsState()
    // UI-B：preset id → name（详情对话框只读标签）
    val agentPresetNames by viewModel.agentPresetNames.collectAsState()
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

    // #331 A2-r3：前插揭示——离开列表期间新行（fork 即插/added 帧等）前插到 head
    // 时，LazyColumn 按键锚定的视口停在旧 head 上（新行在锚点之上不可见）。
    // head 变化 + 用户本在顶部 → 滚回 0 揭示；中部浏览不拉动。
    // prev 用 remember（非 saveable）：返回后首帧重新播种，避免把离开前的
    // head 误当「刚被前插的旧 head」。
    val revealPreviousHeadId = remember { mutableStateOf<String?>(null) }
    val headNodeId = treeNodes.firstOrNull()?.id
    // 组合期读取（新条目此时尚未布局 → 前次布局的稳定值，避开重布局竞态）
    val revealWasAtTop = listState.firstVisibleItemIndex == 0
    LaunchedEffect(headNodeId) {
        val previousHeadId = revealPreviousHeadId.value
        if (shouldRevealPrependedHead(previousHeadId, headNodeId, revealWasAtTop)) {
            listState.scrollToItem(0)
        }
        revealPreviousHeadId.value = headNodeId
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
                        isFavorite = node.id in favoriteSessionIds,
                        onToggleFavorite = {
                            viewModel.toggleFavorite(node.session.session)
                        },
                        deleteSupported = serverCapabilities.sessionDeleteSupported,
                        // #311：主列表行经 builder 已滤除归档集合——isArchived 恒 false；
                        // 能力位门控（非 DSH 无归档项/不可左滑）
                        archiveSupported = serverCapabilities.archiveSupported,
                        onArchive = { onArchive(node.id) },
                        agentPresetSupported = serverCapabilities.agentPresetSupported,
                        agentPresetNames = agentPresetNames,
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

        // #311 已归档折叠区（列表底部入口，自有形态）。仅能力位内（DSH）且存在
        // 归档会话时呈现；非 DSH 后端归档面整体隐藏（workspace 快照恒空 + 能力位
        // false）。
        //
        // 契约事实（2026-09-05 四重取证，详见 SessionRowMenu.kt）：DSH
        // workspace/archiveSession 为幂等 add-only——服务端 archivedSessionIds 无
        // 任何移除路径（全包 grep 无 unarchive，官方 web 客户端同无恢复入口），
        // 归档单向。故本区行菜单只留「详情」，无「取消归档」入口——后续勿在
        // 无 wire 动词时误加恢复入口。
        val archiveSupported = serverCapabilities.archiveSupported
        if (archiveSupported && archivedSessions.isNotEmpty()) {
            item(key = "archived_sessions_section") {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 区头（可折叠）：归档图标 + 已归档 (N) + 展开箭头
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(start = 28.dp, end = SpacingTokens.SM.dp, top = SpacingTokens.SM.dp, bottom = SpacingTokens.SM.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.session_archived_section_title, archivedSessions.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) {
                        archivedSessions.forEach { archivedItem ->
                            SessionRow(
                                item = archivedItem,
                                showDirectory = true,
                                onClick = {
                                    viewModel.requestScrollToTopOnReturn()
                                    viewModel.onSessionOpened(archivedItem.session.id)
                                    onNavigateToChat(archivedItem.session.id)
                                },
                                onRename = {
                                    val title = archivedItem.session.title ?: ""
                                    onRename(archivedItem.session.id, title)
                                },
                                onDelete = {
                                    onDelete(archivedItem.session.id, archivedItem.session.title ?: untitledLabel)
                                },
                                onCopyId = { id ->
                                    viewModel.copyToClipboard(id, context)
                                    scope.launch { snackbarHostState.showSnackbar(copiedToClipboardMsg) }
                                },
                                onAssignCategory = {
                                    onAssignTags(archivedItem.session.id, archivedItem.tags.map { it.id }.toSet())
                                },
                                isFavorite = archivedItem.session.id in favoriteSessionIds,
                                onToggleFavorite = {
                                    viewModel.toggleFavorite(archivedItem.session)
                                },
                                deleteSupported = serverCapabilities.sessionDeleteSupported,
                                isArchived = true,
                                archiveSupported = archiveSupported,
                                agentPresetSupported = serverCapabilities.agentPresetSupported,
                                agentPresetNames = agentPresetNames,
                                syncState = syncStates[archivedItem.session.id],
                                onRequestSync = { onRequestSync(archivedItem.session.id) },
                                onCancelSync = { onCancelSync(archivedItem.session.id) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = AlphaTokens.FAINT
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
