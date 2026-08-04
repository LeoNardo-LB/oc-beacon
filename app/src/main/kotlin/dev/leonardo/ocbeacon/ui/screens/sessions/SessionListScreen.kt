package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.sessions.components.DeleteSessionDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.NewSessionQuickDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.OpenProjectDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.RenameSessionDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionCategoryPickerDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionListEmptyState
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionListErrorState
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionListLoadingState
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionSearchBar
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionTreeList
import dev.leonardo.ocbeacon.ui.screens.sessions.components.isAmoledTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateToNewChat: (directory: String) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToFavorites: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recentDirectoryCount by viewModel.recentDirectoryCount.collectAsStateWithLifecycle()
    val isAmoled = isAmoledTheme()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 对话框状态
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf(TextFieldValue("")) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showOpenProject by remember { mutableStateOf(false) }
    var showQuickNewSession by remember { mutableStateOf(false) }

    // 会话分类选择器状态
    var showCategoryPicker by remember { mutableStateOf(false) }
    var assignSessionId by remember { mutableStateOf("") }
    var assignCategoryId by remember { mutableStateOf<String?>(null) }

    val categories by viewModel.sessionCategories.collectAsStateWithLifecycle()
    val categoryFilter by viewModel.categoryFilter.collectAsStateWithLifecycle()
    val favoriteSessionIds by viewModel.favoriteSessionIds.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { 2 })
    val currentViewMode by viewModel.viewMode.collectAsStateWithLifecycle()

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
                        text = uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // 跨服务器收藏入口
                    IconButton(onClick = onNavigateToFavorites) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "收藏",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // 仅在会话页（page 0）
                    if (pagerState.currentPage == 0) {
                        // 切换查看模式：最近 <-> 文件夹
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                if (currentViewMode == SessionViewMode.RECENT) Icons.Default.Folder
                                else Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(
                                    if (currentViewMode == SessionViewMode.RECENT) R.string.sessions_view_folders
                                    else R.string.sessions_view_recent
                                ),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        // 新建会话
                        IconButton(onClick = {
                            // 若已有会话，先显示快速对话框；
                            // 否则直接进入完整目录浏览器。
                            if (uiState.sessions.isNotEmpty()) {
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
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshSessions() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                        ) {
                            SessionSearchBar(
                                isAmoled = isAmoled,
                                categories = categories,
                                categoryFilter = categoryFilter,
                                onCategoryFilterChange = { viewModel.setCategoryFilter(it) },
                                onSearch = { query ->
                                    viewModel.setSearchQuery(query)
                                    viewModel.loadSessions()
                                },
                                onClearSearch = {
                                    viewModel.clearSearchQuery()
                                    viewModel.loadSessions()
                                },
                            )

                            when {
                                uiState.isLoading && uiState.treeNodes.isEmpty() && uiState.searchQuery.isNullOrBlank() -> {
                                    SessionListLoadingState()
                                }
                                uiState.error != null && uiState.treeNodes.isEmpty() -> {
                                    SessionListErrorState(
                                        message = uiState.error,
                                        onRetry = { viewModel.loadSessions() }
                                    )
                                }
                                uiState.treeNodes.isEmpty() -> {
                                    SessionListEmptyState()
                                }
                                else -> {
                                    SessionTreeList(
                                        viewModel = viewModel,
                                        treeNodes = uiState.treeNodes,
                                        currentViewMode = currentViewMode,
                                        favoriteSessionIds = favoriteSessionIds,
                                        snackbarHostState = snackbarHostState,
                                        scope = scope,
                                        onNavigateToChat = { id -> onNavigateToChat(id, false) },
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
                                        onAssignCategory = { sessionId, currentCategoryId ->
                                            assignSessionId = sessionId
                                            assignCategoryId = currentCategoryId
                                            showCategoryPicker = true
                                        },
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
            initialDirectory = uiState.prefillDirectory,
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
            sessions = uiState.sessions,
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

    // 会话分类选择器对话框（分配 / 创建 / 删除）
    if (showCategoryPicker) {
        SessionCategoryPickerDialog(
            categories = categories,
            assignedCategoryId = assignCategoryId,
            onAssign = { categoryId ->
                showCategoryPicker = false
                if (categoryId != null) {
                    viewModel.assignCategory(assignSessionId, categoryId)
                } else {
                    viewModel.unassignCategory(assignSessionId)
                }
            },
            onCreateCategory = { name, color, icon ->
                viewModel.addCategory(name, color, icon)
            },
            onDeleteCategory = { categoryId ->
                viewModel.removeCategory(categoryId)
            },
            onDismiss = { showCategoryPicker = false },
        )
    }
}
