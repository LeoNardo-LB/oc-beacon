package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
// 2026-09-10（用户裁决⑤优化）：内容命中逐行呈现——角色标签 + [..] 命中段高亮
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.widthIn
import dev.leonardo.ocbeacon.data.local.ContentSearchFilterValues
import dev.leonardo.ocbeacon.data.local.ContentSearchHit
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
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import dev.leonardo.ocbeacon.service.ServerLinkState
import dev.leonardo.ocbeacon.ui.components.dsh.DshTokenDialog
import dev.leonardo.ocbeacon.ui.components.ServerLinkBanner
import dev.leonardo.ocbeacon.ui.extension.LocalServerUiSlots
import dev.leonardo.ocbeacon.ui.extension.SessionListHeaderSlotHost
import dev.leonardo.ocbeacon.ui.screens.sessions.components.AgentPresetContentDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.AgentPresetCopyDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.AgentPresetDeleteConfirmDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.ContentSearchFilterMenu
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
    // #311 Task3：新建会话对话框条目（快照→对话框状态映射，VM 单源三态）
    val newSessionDialogEntries by viewModel.newSessionDialogEntries.collectAsStateWithLifecycle()
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
    // #317：DSH 0.1.2 token 输入（TokenNeeded 横幅入口）
    var showDshTokenDialog by remember { mutableStateOf(false) }
    var dshTokenExchangePending by remember { mutableStateOf(false) }

    // 会话分类选择器状态
    var showCategoryPicker by remember { mutableStateOf(false) }
    var assignSessionId by remember { mutableStateOf("") }
    var assignTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val sessionTags by viewModel.sessionTags.collectAsStateWithLifecycle()
    // #324②：preset 管理对话框状态
    var pendingViewPreset by remember { mutableStateOf<AgentPreset?>(null) }
    var pendingCopyPreset by remember { mutableStateOf<AgentPreset?>(null) }
    var pendingDeletePreset by remember { mutableStateOf<AgentPreset?>(null) }
    val sessionTagAssignments by viewModel.sessionTagAssignments.collectAsStateWithLifecycle()
    val tagFilters by viewModel.tagFilters.collectAsStateWithLifecycle()
    val favoriteSessionIds by viewModel.favoriteSessionIds.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    // #272：BM25 内容命中（FTS5 本地检索）——搜索词非空时聚合展示
    val contentHits by viewModel.contentHits.collectAsStateWithLifecycle()
    // #322：DSH 服务器历史命中（session/search 全历史；非 DSH 恒 null）
    val serverSearch by viewModel.serverSearch.collectAsStateWithLifecycle()
    // #272/Q6c：内容检索过滤（角色 + 时间范围，chip 单选）
    val searchRole by viewModel.searchRole.collectAsStateWithLifecycle()
    val searchTimeRange by viewModel.searchTimeRange.collectAsStateWithLifecycle()
    // #271：drain 同步状态（长按菜单详情区数据源）
    val syncStates by viewModel.syncStates.collectAsStateWithLifecycle()
    // #391 切片9：适配器声明的界面插槽（通用屏幕的渲染先决条件）
    val sessionUiSlots by viewModel.uiSlots.collectAsStateWithLifecycle()

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

    // #267：写操作错误 snackbar 面（原非空列表下 _error 无可视面）——哨兵映射本地化
    val disconnectedMsg = stringResource(R.string.server_link_disconnected_message)
    // #311：归档失败哨兵同款映射
    val archiveFailedMsg = stringResource(R.string.session_archive_failed)
    LaunchedEffect(Unit) {
        viewModel.error.collect { msg ->
            if (!msg.isNullOrBlank()) {
                val text = when (msg) {
                    SessionListViewModel.ERROR_SERVER_DISCONNECTED -> disconnectedMsg
                    SessionListViewModel.ERROR_ARCHIVE_FAILED -> archiveFailedMsg
                    else -> msg
                }
                snackbarHostState.showSnackbar(text)
                viewModel.consumeError()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                // #267：断连常驻细条幅（恢复自动消失）
                val serverLinkState by viewModel.serverLinkState.collectAsStateWithLifecycle()
                // #317：token 待输入优先于一般断连横幅（给出路而非干等重连）
                val dshTokenNeeded by viewModel.dshTokenNeeded.collectAsStateWithLifecycle()
                if (serverLinkState != ServerLinkState.Connected) {
                    // #391 切片9：类型私有横幅经 SESSION_LIST_HEADER 插槽渲染——通用屏幕只
                    // 提供断连上下文与出路回调；两级门禁：适配器声明先决 + 贡献方能力过滤。
                    if (ServerUiSlot.SESSION_LIST_HEADER in sessionUiSlots) {
                        LocalServerUiSlots.current.Render(
                            slot = ServerUiSlot.SESSION_LIST_HEADER,
                            caps = viewModel.serverCapabilities.collectAsStateWithLifecycle().value,
                            host = SessionListHeaderSlotHost(
                                tokenNeeded = dshTokenNeeded,
                                onEnterToken = { showDshTokenDialog = true },
                            ),
                        )
                    }
                    if (!dshTokenNeeded) ServerLinkBanner()
                }
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
            }
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

                            val titles = content.sessions.associate { it.id to (it.title ?: it.id) }

                            // #355：已归档会话不展示于检索结果（三面统一：标题命中列表
                            // 本就只搜主列表；此处再滤内容命中与服务器命中两区）。
                            val archivedIds = content.archivedSessions.map { it.session.id }.toSet()
                            val visibleContentHits = if (archivedIds.isEmpty()) contentHits else {
                                contentHits.filter { it.sessionId !in archivedIds }
                            }

                            // #322：DSH 服务器历史命中区（session/search 全历史会话命中）。
                            // 呈现裁决：本地 FTS 已覆盖的会话不在此重复（本地行有消息级跳转，
                            // 服务器行只有会话级跳转——wire 无 messageId 锚点，如实呈现不伪造跳转）。
                            val serverRows = SessionSearchMerge.serverRows(serverSearch, visibleContentHits, archivedIds)
                            if (!content.searchQuery.isNullOrBlank() && serverRows.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = SpacingTokens.SM.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.search_server_hits),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    serverRows.forEach { hit ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onNavigateToChat(hit.sessionId, false, null) }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = titles[hit.sessionId] ?: ("…" + hit.sessionId.takeLast(10)),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                )
                                                Text(
                                                    text = hit.snippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                )
                                            }
                                        }
                                    }
                                    if (serverSearch?.hasMore == true) {
                                        Text(
                                            text = stringResource(R.string.search_server_more),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            // #272：内容命中聚合区（FTS5 BM25 本地检索，纯本地）。
                            // Q6c：过滤激活（角色/时间任一非空）时即使 0 命中也保留本区——
                            // 否则过滤后无结果会把过滤 chips 一并藏掉，用户无法切回「全部」。
                            val searchFiltersActive = searchRole != null || searchTimeRange != null
                            if (!content.searchQuery.isNullOrBlank() && (visibleContentHits.isNotEmpty() || searchFiltersActive)) {
                                // 2026-09-10（用户裁决⑤）：逐命中行——每条命中独立呈现
                                //（角色标签 + 高亮摘要 + 各自跳转 messageId）；会话分组保归属。
                                // 原「每会话仅取 first().snippet」在 BM25 短文档偏置下
                                //（user 提示短、rank 恒靠前）把同会话 AI 命中折叠不可见
                                //——「全部」过滤只剩人类消息的根因。
                                val groups: List<Pair<String, List<ContentSearchHit>>> =
                                    visibleContentHits.groupBy { it.sessionId }
                                        .map { (sid, hits) ->
                                            // 按消息去重：FTS 行级命中（同消息多 part/
                                            // 同步路径重复 partId）折叠为每消息一行
                                            sid to hits
                                                .sortedBy { it.rank ?: Double.MAX_VALUE }
                                                .distinctBy { it.messageId }
                                        }
                                        .sortedByDescending { it.second.size }
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
                                    // #355（用户裁决）：筛选改标准列表样式（DropdownMenu
                                    // 单选列表——「筛选不要 tag 形式」）；选项语义与原 chips 共源
                                    ContentSearchFilterMenu(
                                        role = searchRole,
                                        timeRange = searchTimeRange,
                                        onRoleChange = { viewModel.setSearchRole(it) },
                                        onTimeRangeChange = { viewModel.setSearchTimeRange(it) },
                                    )
                                    if (visibleContentHits.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.search_content_no_hits),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }
                                    groups.forEach { (sid, hits) ->
                                        // 会话头行：标题 + 命中计数（归属可扫读）
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = titles[sid] ?: ("…" + sid.takeLast(10)),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                text = stringResource(R.string.search_content_hit_count, hits.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        // 逐命中行：角色标签 + 高亮摘要；点击跳该条消息
                                        hits.forEach { hit ->
                                            val isUser = hit.role == ContentSearchFilterValues.ROLE_USER
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onNavigateToChat(sid, false, hit.messageId) }
                                                    .padding(vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        if (isUser) R.string.chat_label_user else R.string.chat_label_agent,
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isUser) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.tertiary
                                                    },
                                                    modifier = Modifier.widthIn(min = 32.dp).padding(end = 8.dp),
                                                )
                                                // FTS snippet() 以 [..] 标记命中段——高亮渲染
                                                HighlightedHitSnippet(
                                                    snippet = hit.snippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
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
                                        // #267：断连快速失败哨兵 → 本地化文案（其余原样透传）
                                        message = if (shell.error == SessionListViewModel.ERROR_SERVER_DISCONNECTED) {
                                            stringResource(R.string.server_link_disconnected_message)
                                        } else {
                                            shell.error
                                        },
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
                                        // #311 归档：已归档折叠区数据（workspace 快照集合驱动）+ 归档动作
                                        archivedSessions = content.archivedSessions,
                                        onArchive = { sessionId -> viewModel.archiveSession(sessionId) },
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
                        permissionSwitchSupported = (ServerFeatures.PERMISSION_SWITCH in viewModel.serverCapabilities.collectAsStateWithLifecycle().value),
                        permissionDefault = viewModel.permissionDefault.collectAsStateWithLifecycle().value,
                        onSetPermissionDefault = viewModel::setPermissionDefault,
                        permissionDefaultBlocked = viewModel.permissionDefaultBlocked.collectAsStateWithLifecycle().value,
                        agentPresetSupported = (ServerFeatures.AGENT_PRESET in viewModel.serverCapabilities.collectAsStateWithLifecycle().value),
                        agentPresets = viewModel.agentPresetsList.collectAsStateWithLifecycle().value,
                        agentPresetDefault = viewModel.agentPresetDefault.collectAsStateWithLifecycle().value,
                        onSetAgentPresetDefault = viewModel::setAgentPresetDefault,
                        agentPresetDefaultBlocked = viewModel.agentPresetDefaultBlocked.collectAsStateWithLifecycle().value,
                        // #324②：preset 管理动作（对话框宿主在本层下方对话框群）
                        agentPresetAuthorable = viewModel.agentPresetAuthorable.collectAsStateWithLifecycle().value,
                        onViewAgentPreset = { preset ->
                            pendingViewPreset = preset
                            viewModel.readAgentPreset(preset.id)
                        },
                        onCopyAgentPreset = { preset -> pendingCopyPreset = preset },
                        onDeleteAgentPreset = { preset -> pendingDeletePreset = preset },
                        // #391 切片9：SERVER_SETTINGS 插槽两级门禁（声明 + 能力位）
                        serverCapabilities = viewModel.serverCapabilities.collectAsStateWithLifecycle().value,
                        serverUiSlots = sessionUiSlots,
                    )
                }
            }
        }
    }

    // #317：DSH 0.1.2 token 输入对话框（交换成功自动关闭；被拒留窗示错）
    val dshTokenExchange by viewModel.dshTokenExchange.collectAsStateWithLifecycle()
    LaunchedEffect(dshTokenExchange) {
        when (dshTokenExchange) {
            SessionListViewModel.DshTokenExchangeState.Exchanging -> dshTokenExchangePending = true
            SessionListViewModel.DshTokenExchangeState.Idle ->
                if (dshTokenExchangePending) {
                    dshTokenExchangePending = false
                    showDshTokenDialog = false
                }
            SessionListViewModel.DshTokenExchangeState.Rejected -> Unit
        }
    }
    if (showDshTokenDialog) {
        DshTokenDialog(
            exchanging = dshTokenExchange == SessionListViewModel.DshTokenExchangeState.Exchanging,
            rejected = dshTokenExchange == SessionListViewModel.DshTokenExchangeState.Rejected,
            onSubmit = viewModel::submitDshToken,
            onDismiss = {
                viewModel.dismissDshTokenDialog()
                showDshTokenDialog = false
            },
        )
    }
    // #324②：preset 管理三对话框（组成查看/复制/删除确认）
    val agentPresetDoc by viewModel.agentPresetDocument.collectAsStateWithLifecycle()
    if (pendingViewPreset != null) {
        agentPresetDoc?.let { doc ->
            AgentPresetContentDialog(document = doc, onDismiss = {
                pendingViewPreset = null
                viewModel.closeAgentPresetDocument()
            })
        }
    }
    pendingCopyPreset?.let { preset ->
        AgentPresetCopyDialog(
            preset = preset,
            onConfirm = { newId, name ->
                viewModel.copyAgentPreset(preset.id, newId, name)
                pendingCopyPreset = null
            },
            onDismiss = { pendingCopyPreset = null },
        )
    }
    pendingDeletePreset?.let { preset ->
        AgentPresetDeleteConfirmDialog(
            preset = preset,
            onConfirm = { viewModel.deleteAgentPreset(preset.id) },
            onDismiss = { pendingDeletePreset = null },
        )
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

    // 快速新建会话对话框（#311 Task3：workspace 真建模条目——V012 快照 /
    // V011 listProjects 回退 / 非 DSH 最近目录；连接语义在 VM）。
    // 批 3（§三-3）：DSH 预设 roster 在场时对话框内联预设选择行（同屏一步
    // 选工作区+预设）；opencode 面 roster 恒空表行不渲染。
    if (showQuickNewSession) {
        val dialogAgentPresets by viewModel.agentPresetsList.collectAsStateWithLifecycle()
        NewSessionQuickDialog(
            entries = newSessionDialogEntries,
            limit = recentDirectoryCount,
            onSelectEntry = { entry, presetId ->
                showQuickNewSession = false
                viewModel.connectWorkspaceEntry(entry, presetId)
            },
            onBrowse = {
                showQuickNewSession = false
                showOpenProject = true
            },
            onDismiss = { showQuickNewSession = false },
            agentPresets = dialogAgentPresets,
        )
    }

    // #311 Task3：对话框连接语义导航事件（复用/新建跳转 + 目录懒建）
    LaunchedEffect(Unit) {
        viewModel.newSessionNavigation.collect { nav ->
            when (nav) {
                is SessionListViewModel.NewSessionNavigation.ToSession -> {
                    // 与 SessionTreeList 行点击同款：先记已读水位，再跳转
                    viewModel.onSessionOpened(nav.sessionId)
                    onNavigateToChat(nav.sessionId, false, null)
                }
                is SessionListViewModel.NewSessionNavigation.ToDirectory ->
                    onNavigateToNewChat(nav.directory)
            }
        }
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

/**
 * 2026-09-10（用户裁决⑤优化1）：内容命中摘要——FTS snippet() 的 [..] 命中段
 * 以背景色高亮（原中括号裸文本标记改为视觉高亮）；LIKE 降级路径无标记纯文本。
 */
@Composable
private fun HighlightedHitSnippet(
    snippet: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val annotated = remember(snippet) { buildHighlightedSnippet(snippet, highlight) }
    Text(
        text = annotated,
        style = style,
        color = color,
        maxLines = 2,
    )
}

/** 解析 FTS snippet 的 [命中] 标记对 → 背景色 SpanStyle（未配对标记按原文保留）。 */
private fun buildHighlightedSnippet(snippet: String, highlight: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < snippet.length) {
            val open = snippet.indexOf('[', i)
            if (open < 0) {
                append(snippet.substring(i))
                break
            }
            val close = snippet.indexOf(']', open + 1)
            if (close < 0) {
                append(snippet.substring(i))
                break
            }
            if (open > i) append(snippet.substring(i, open))
            withStyle(SpanStyle(background = highlight)) {
                append(snippet.substring(open + 1, close))
            }
            i = close + 1
        }
    }
