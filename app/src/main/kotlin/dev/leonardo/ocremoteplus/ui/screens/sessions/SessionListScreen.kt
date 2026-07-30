package dev.leonardo.ocremoteplus.ui.screens.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremoteplus.R
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import dev.leonardo.ocremoteplus.ui.components.amoledOutlinedTextFieldColors
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import dev.leonardo.ocremoteplus.ui.components.amoledDialogParams
import dev.leonardo.ocremoteplus.ui.components.DialogButtons
import dev.leonardo.ocremoteplus.ui.components.DialogButtonRole
import dev.leonardo.ocremoteplus.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocremoteplus.ui.theme.ButtonTokens
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.DirectoryTreeNode
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.SessionCategoryPickerDialog
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.SessionCategoryStyle
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.SessionRow
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.TreeNode
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.isAmoledTheme
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.OpenProjectDialog
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.NewSessionQuickDialog
import dev.leonardo.ocremoteplus.ui.theme.AlphaTokens
import dev.leonardo.ocremoteplus.ui.theme.ShapeTokens
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem

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

    // Dialog states
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf(TextFieldValue("")) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showOpenProject by remember { mutableStateOf(false) }
    var showQuickNewSession by remember { mutableStateOf(false) }
    var showBaseDirDialog by remember { mutableStateOf(false) }

    // Session category picker state
    var showCategoryPicker by remember { mutableStateOf(false) }
    var assignSessionId by remember { mutableStateOf("") }
    var assignCategoryId by remember { mutableStateOf<String?>(null) }

    val categories by viewModel.sessionCategories.collectAsStateWithLifecycle()
    val categoryFilter by viewModel.categoryFilter.collectAsStateWithLifecycle()
    val favoriteSessionIds by viewModel.favoriteSessionIds.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { 2 })
    val currentViewMode by viewModel.viewMode.collectAsStateWithLifecycle()

    // Preload MCP servers on screen entry — no loading delay when user swipes
    // to the MCP tab. Also caches error handling for the entire lifetime.
    var mcpLoadedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadMcpServers()
        mcpLoadedOnce = true
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
                    // Cross-server favorites entry
                    IconButton(onClick = onNavigateToFavorites) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "收藏",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // Only on sessions page (page 0)
                    if (pagerState.currentPage == 0) {
                        // Toggle view mode: recent <-> folders
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
                        // New session
                        IconButton(onClick = {
                            // If there are known sessions, show the quick dialog first;
                            // otherwise go straight to the full directory browser.
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
            // Search bar — always visible, independent of list state
            var searchInput by rememberSaveable { mutableStateOf("") }
            val searchJob = remember { mutableStateOf<Job?>(null) }

            Column(modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
            ) {
                    OutlinedTextField(
                    value = searchInput,
                    onValueChange = { newQuery ->
                        searchInput = newQuery
                        searchJob.value?.cancel()
                        searchJob.value = scope.launch {
                            delay(300)
                            viewModel.setSearchQuery(newQuery)
                            viewModel.loadSessions()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.a11y_icon_search))
                    },
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            IconButton(onClick = {
                                searchInput = ""
                                viewModel.clearSearchQuery()
                                viewModel.loadSessions()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessions_clear_search))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.search_sessions)) },
                    singleLine = true,
                    colors = if (isAmoled) {
                        amoledOutlinedTextFieldColors()
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }
                    )

                // Category filter chips
                if (categories.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = categoryFilter == null,
                            onClick = { viewModel.setCategoryFilter(null) },
                            label = { Text("全部") },
                        )
                        categories.forEach { category ->
                            FilterChip(
                                selected = categoryFilter == category.id,
                                onClick = { viewModel.setCategoryFilter(category.id) },
                                label = { Text(category.name) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = SessionCategoryStyle.icon(category.icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = SessionCategoryStyle.color(category.color),
                                    )
                                },
                            )
                        }
                    }
                }

                when {
                    uiState.isLoading && uiState.treeNodes.isEmpty() && uiState.searchQuery.isNullOrBlank() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            PulsingDotsIndicator(dotSize = 12.dp, dotSpacing = 8.dp)
                        }
                    }
                    uiState.error != null && uiState.treeNodes.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = stringResource(R.string.a11y_icon_warning),
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = uiState.error ?: stringResource(R.string.session_unknown_error),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Button(onClick = { viewModel.loadSessions() }) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }
                    uiState.treeNodes.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.sessions_empty_directory),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                    val untitledLabel = stringResource(R.string.session_untitled)
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

                    // Scroll to top only when returning from a session where the user
                    // sent a message. Observe the NavBackStackEntry lifecycle (ON_RESUME)
                    // instead of a composition-keyed effect: Compose Navigation may keep
                    // this composable in composition while the user is in Chat, so a
                    // Unit-keyed LaunchedEffect would not reliably re-fire on return. A
                    // lifecycle observer catches the resume transition in both cases.
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
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(uiState.treeNodes, key = { _, node -> node.id }) { index, node ->
                            when (node) {
                                is TreeNode.Directory -> {
                                    DirectoryTreeNode(
                                        node = node,
                                        onClick = { viewModel.toggleDirectory(node.path) },
                                        onCopyPath = { path ->
                                            viewModel.copyToClipboard(path, context)
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
                                        },
                                        onNewSession = { directory ->
                                            onNavigateToNewChat(directory)
                                        },
                                    )
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
                                            onNavigateToChat(node.id, false)
                                        },
                                        onRename = {
                                            renameSessionId = node.id
                                            val title = node.session.session.title ?: ""
                                            renameText = TextFieldValue(
                                                text = title,
                                                selection = TextRange(0, title.length)
                                            )
                                            showRenameDialog = true
                                        },
                                        onDelete = {
                                            deleteSessionId = node.id
                                            deleteSessionTitle = node.session.session.title ?: untitledLabel
                                            showDeleteDialog = true
                                        },
                                        onCopyId = { id ->
                                            viewModel.copyToClipboard(id, context)
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
                                        },
                                        onAssignCategory = {
                                            assignSessionId = node.id
                                            assignCategoryId = node.session.category?.id
                                            showCategoryPicker = true
                                        },
                                        isFavorite = node.id in favoriteSessionIds,
                                        onToggleFavorite = {
                                            viewModel.toggleFavorite(node.session.session)
                                        },
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = AlphaTokens.FAINT
                                        )
                                    )
                                }
                            }
                        }

                        // Load more indicator at the bottom
                        if (viewModel.isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
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

    // Open Project dialog
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

    // Quick new-session dialog (recent directories)
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

    // Base directory selector dialog
    if (showBaseDirDialog) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = emptyList(),
            onSelect = { directory ->
                showBaseDirDialog = false
                viewModel.setBaseDirectory(directory)
            },
            onDismiss = { showBaseDirDialog = false }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        val renameParams = amoledDialogParams()
        val renameFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            renameFocusRequester.requestFocus()
        }
        BasicAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = renameParams.containerColor,
                tonalElevation = renameParams.tonalElevation,
                border = renameParams.border,
                shape = renameParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.session_rename),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.session_rename_title)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(renameFocusRequester)
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showRenameDialog = false },
                            Triple(stringResource(R.string.session_rename_button), DialogButtonRole.Primary) {
                                viewModel.renameSession(renameSessionId, renameText.text)
                                showRenameDialog = false
                            },
                        )
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        val deleteParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = deleteParams.containerColor,
                tonalElevation = deleteParams.tonalElevation,
                border = deleteParams.border,
                shape = deleteParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.session_delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.session_delete_confirm, deleteSessionTitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showDeleteDialog = false },
                            Triple(stringResource(R.string.delete), DialogButtonRole.Danger) {
                                viewModel.deleteSession(deleteSessionId)
                                showDeleteDialog = false
                            },
                        )
                    )
                }
            }
        }
    }

    // Session category picker dialog (assign / create / delete)
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
