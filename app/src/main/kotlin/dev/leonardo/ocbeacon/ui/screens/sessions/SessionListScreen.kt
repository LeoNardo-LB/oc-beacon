package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.sessions.components.ContentSearchFilterChips
import dev.leonardo.ocbeacon.ui.screens.sessions.components.DeleteSessionDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.NewSessionQuickDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.OpenProjectDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.RenameSessionDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionListEmptyState
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionListErrorState
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionListLoadingState
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionSearchBar
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionTreeList
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TagPickerDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateToChat: (sessionId: String, openTerminal: Boolean, jumpToMessageId: String?) -> Unit,
    onNavigateToNewChat: (directory: String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val content by viewModel.contentState.collectAsStateWithLifecycle()
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    val recentDirectoryCount by viewModel.recentDirectoryCount.collectAsStateWithLifecycle()
    val isAmoled = isAmoledTheme()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 对话框状态
    var showRenameDialog by remember { mutableStateOf(false) }
var showMoreMenu by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    // #115（D2-L25）：renameText 输入态 saveable——重建后不丢重命名输入
    // #115（D2-L25）：TextFieldValue 需显式 Saver（默认 Saver 不支持——
    // 直接 rememberSaveable 会 IllegalArgumentException 崩溃）
    var renameText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showOpenProject by remember { mutableStateOf(false) }
    var showQuickNewSession by remember { mutableStateOf(false) }

    // 会话分类选择器状态
    var showCategoryPicker by remember { mutableStateOf(false) }
    var assignSessionId by remember { mutableStateOf("") }
    var assignTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val sessionTags by viewModel.sessionTags.collectAsStateWithLifecycle()
    val sessionTagAssignments by viewModel.sessionTagAssignments.collectAsStateWithLifecycle()
    val tagFilters by viewModel.tagFilters.collectAsStateWithLifecycle()
    val favoriteSessionIds by viewModel.favoriteSessionIds.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    // #272：BM25 内容命中（FTS5 本地检索）——搜索词非空时聚合展示
    val contentHits by viewModel.contentHits.collectAsStateWithLifecycle()
    // #272/Q6c：内容检索过滤（角色 + 时间范围，chip 单选）
    val searchRole by viewModel.searchRole.collectAsStateWithLifecycle()
    val searchTimeRange by viewModel.searchTimeRange.collectAsStateWithLifecycle()
    // #271：drain 同步状态（长按菜单详情区数据源）
    val syncStates by viewModel.syncStates.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { 2 })
    val currentViewMode by viewModel.viewMode.collectAsStateWithLifecycle()

// 组合阶段消费待标记会话：返回列表时同步标记已读（渲染前 combine 重算完成，
// 消除 popBackStack 先渲染旧状态导致的一帧红点闪烁）。consume 保证只处理一次。
viewModel.consumePendingReadSessionId()

    // 进入屏幕时预加载 MCP 服务器 — 用户滑到 MCP 标签页时无加载延迟。
    LaunchedEffect(Unit) {
        viewModel.loadMcpServers()
    }

    LaunchedEffect(Unit) {
        viewModel.mcpError.collect { errorMessage ->
            snackbarHostState.showSnackbar(errorMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = shell.serverName.ifEmpty { stringResource(R.string.sessions_title) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // 以下入口仅在会话页（page 0）显示，设置页右上角保持干净
                    if (pagerState.currentPage == 0) {
                        // 收藏筛选：仅显示收藏会话（选中态 = 星标实心高亮）
                        IconButton(onClick = { viewModel.toggleFavoritesOnly() }) {
                            Icon(
                                if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(R.string.favorites_title),
                                tint = if (favoritesOnly) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        // 新建会话
                        IconButton(onClick = {
                            // 若已有会话，先显示快速对话框；
                            // 否则直接进入完整目录浏览器。
                            if (content.sessions.isNotEmpty()) {
                                showQuickNewSession = true
                            } else {
                                showOpenProject = true
                            }
                        }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.sessions_new),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        // 更多菜单（最右）：视图切换 + 一键已读
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                // 切换查看模式：最近 <-> 目录（视图模式 FOLDER 沿旧枚举名）
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(
                                            if (currentViewMode == SessionViewMode.RECENT) R.string.sessions_view_folders
                                            else R.string.sessions_view_recent
                                        ))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (currentViewMode == SessionViewMode.RECENT) Icons.Default.Folder
                                            else Icons.AutoMirrored.Filled.List,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.toggleViewMode()
                                    },
                                )
                                // 一键已读：消除所有小红点
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mark_all_read)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.DoneAll, contentDescription = null)
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.markAllSessionsRead()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        scope.launch { pagerState.scrollToPage(0) }
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            stringResource(R.string.sessions_title),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        scope.launch { pagerState.scrollToPage(1) }
                    },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding),
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(0)
            ),
        ) { page ->
            when (page) {
                0 -> {
                    PullToRefreshBox(
                        isRefreshing = shell.isRefreshing,
                        onRefresh = { viewModel.refreshSessions() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = SpacingTokens.LG.dp)
                        ) {
                            SessionSearchBar(
                                isAmoled = isAmoled,
                                categories = sessionTags,
                                categoryFilter = tagFilters,
                                onCategoryToggle = { viewModel.toggleCategoryFilter(it) },
                                onClearFilters = { viewModel.clearCategoryFilters() },
                                onSearch = { query ->
                                    viewModel.setSearchQuery(query)
                                    viewModel.loadSessions()
                                },
                                onClearSearch = {
                                    viewModel.clearSearchQuery()
                                    viewModel.loadSessions()
                                },
                            )

                            // #272：内容命中聚合区（FTS5 BM25 本地检索，纯本地）。
                            // Q6c：过滤激活（角色/时间任一非空）时即使 0 命中也保留本区——
                            // 否则过滤后无结果会把过滤 chips 一并藏掉，用户无法切回「全部」。
                            val searchFiltersActive = searchRole != null || searchTimeRange != null
                            if (!content.searchQuery.isNullOrBlank() && (contentHits.isNotEmpty() || searchFiltersActive)) {
                                val titles = content.sessions.associate { it.id to (it.title ?: it.id) }
                                // B1 链：命中组携带跳转目标 messageId（rank 最优）——点击即定位该消息
                                val groups: List<Triple<String, Int, Pair<String?, String>>> =
                                    contentHits.groupBy { it.sessionId }
                                        .map { (sid, hits) ->
                                            val best = dev.leonardo.ocbeacon.ui.screens.sessions.ContentHitNavigation.jumpTarget(hits)
                                            Triple(sid, hits.size, (best?.second to hits.first().snippet))
                                        }
                                        .sortedByDescending { it.second }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = SpacingTokens.SM.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.search_content_hits),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    // #272/Q6c：角色 + 时间过滤 chips（切换即重查）
                                    ContentSearchFilterChips(
                                        role = searchRole,
                                        timeRange = searchTimeRange,
                                        onRoleChange = { viewModel.setSearchRole(it) },
                                        onTimeRangeChange = { viewModel.setSearchTimeRange(it) },
                                    )
                                    if (contentHits.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.search_content_no_hits),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }
                                    groups.forEach { group ->
                                        val sid = group.first
                                        val count = group.second
                                        val (messageId, snippet) = group.third
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onNavigateToChat(sid, false, messageId) }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = titles[sid] ?: ("…" + sid.takeLast(10)),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                )
                                                Text(
                                                    text = snippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                )
                                            }
                                            Text(
                                                text = stringResource(R.string.search_content_hit_count, count),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            when {
                                shell.isLoading && content.treeNodes.isEmpty() && content.searchQuery.isNullOrBlank() -> {
                                    SessionListLoadingState()
                                }
                                shell.error != null && content.treeNodes.isEmpty() -> {
                                    SessionListErrorState(
                                        message = shell.error,
                                        onRetry = { viewModel.loadSessions() }
                                    )
                                }
                                content.treeNodes.isEmpty() -> {
                                    SessionListEmptyState()
                                }
                                else -> {
                                    SessionTreeList(
                                        viewModel = viewModel,
                                        treeNodes = content.treeNodes,
                                        currentViewMode = currentViewMode,
                                        favoriteSessionIds = favoriteSessionIds,
                                        snackbarHostState = snackbarHostState,
                                        scope = scope,
                                        onNavigateToChat = { id -> onNavigateToChat(id, false, null) },
                                        onNavigateToNewChat = onNavigateToNewChat,
                                        onRename = { sessionId, currentTitle ->
                                            renameSessionId = sessionId
                                            renameText = TextFieldValue(
                                                text = currentTitle,
                                                selection = TextRange(0, currentTitle.length)
                                            )
                                            showRenameDialog = true
                                        },
                                        onDelete = { sessionId, title ->
                                            deleteSessionId = sessionId
                                            deleteSessionTitle = title
                                            showDeleteDialog = true
                                        },
                                        onAssignTags = { sessionId, currentTagIds ->
                                            assignSessionId = sessionId
                                            assignTagIds = currentTagIds
                                            showCategoryPicker = true
                                        },
                                        // #271：同步状态透传（长按菜单「History Sync」区）
                                        syncStates = syncStates,
                                        onRequestSync = { sessionId -> viewModel.requestHistorySync(sessionId) },
                                        onCancelSync = { sessionId -> viewModel.cancelHistorySync(sessionId) },
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    ServerSettingsContent(
                        mcpServers = viewModel.mcpServers.collectAsStateWithLifecycle().value,
                        mcpLoading = viewModel.mcpLoading.collectAsStateWithLifecycle().value,
                        mcpInitialLoading = viewModel.mcpInitialLoading.collectAsStateWithLifecycle().value,
                        onToggleMcp = viewModel::toggleMcpServer,
                        tags = sessionTags,
                        tagAssignments = sessionTagAssignments,
                        sessions = content.sessions,
                        onAddTag = { tag ->
                            viewModel.addSessionTag(tag.name, tag.color, tag.icon, id = tag.id)
                        },
                        onUpdateTag = viewModel::updateSessionTag,
                        onDeleteTag = viewModel::removeSessionTag,
                        onRemoveTagAssignment = viewModel::removeSessionTagAssignment,
                        permissionSwitchSupported = viewModel.serverCapabilities.collectAsStateWithLifecycle().value.permissionSwitchSupported,
                        permissionDefault = viewModel.permissionDefault.collectAsStateWithLifecycle().value,
                        onSetPermissionDefault = viewModel::setPermissionDefault,
                        agentPresetSupported = viewModel.serverCapabilities.collectAsStateWithLifecycle().value.agentPresetSupported,
                        agentPresets = viewModel.agentPresetsList.collectAsStateWithLifecycle().value,
                        agentPresetDefault = viewModel.agentPresetDefault.collectAsStateWithLifecycle().value,
                        onSetAgentPresetDefault = viewModel::setAgentPresetDefault,
                    )
                }
            }
        }
    }

    // 打开项目对话框
    if (showOpenProject) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = emptyList(),
            initialDirectory = content.prefillDirectory,
            onSelect = { directory ->
                showOpenProject = false
                onNavigateToNewChat(directory)
            },
            onDismiss = { showOpenProject = false }
        )
    }

    // 快速新建会话对话框（最近目录）
    if (showQuickNewSession) {
        NewSessionQuickDialog(
            sessions = content.sessions,
            limit = recentDirectoryCount,
            onSelectDirectory = { directory ->
                showQuickNewSession = false
                onNavigateToNewChat(directory)
            },
            onBrowse = {
                showQuickNewSession = false
                showOpenProject = true
            },
            onDismiss = { showQuickNewSession = false }
        )
    }

    // 重命名对话框
    if (showRenameDialog) {
        RenameSessionDialog(
            text = renameText,
            onTextChange = { renameText = it },
            onDismiss = { showRenameDialog = false },
            onConfirm = {
                viewModel.renameSession(renameSessionId, renameText.text)
                showRenameDialog = false
            },
        )
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        DeleteSessionDialog(
            sessionTitle = deleteSessionTitle,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteSession(deleteSessionId)
                showDeleteDialog = false
            },
        )
    }

    // 标签分配对话框（复选框多选 + 新建自动勾选）
    if (showCategoryPicker) {
        TagPickerDialog(
            tags = sessionTags,
            selectedTagIds = assignTagIds,
            onConfirm = { tagIds ->
                showCategoryPicker = false
                viewModel.assignTags(assignSessionId, tagIds)
            },
            onDismiss = { showCategoryPicker = false },
            onCreateTag = { name, color, icon ->
                val newId = "tag_${System.currentTimeMillis()}"
                viewModel.addSessionTag(name, color, icon, id = newId)
                newId
            },
        )
    }
}
