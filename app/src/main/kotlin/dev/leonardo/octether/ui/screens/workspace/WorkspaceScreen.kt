package dev.leonardo.octether.ui.screens.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.screens.viewer.FileViewerOverlay
import dev.leonardo.octether.ui.screens.viewer.FileViewerParams
import dev.leonardo.octether.ui.screens.viewer.FileViewerSource
import dev.leonardo.octether.ui.screens.workspace.git.GitChangesPanel
import dev.leonardo.octether.ui.screens.workspace.search.SearchOverlay
import dev.leonardo.octether.ui.screens.workspace.search.SearchTopBar
import dev.leonardo.octether.ui.screens.workspace.tree.FileTreePanel

@Composable
fun WorkspaceRoute(
    viewModel: WorkspaceViewModel = hiltViewModel(),
    serverId: String,
    sessionId: String,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var fileViewerRequest by remember { mutableStateOf<FileViewerParams?>(null) }
    WorkspaceScreen(
        uiState = uiState,
        onBack = onBack,
        onSwitchPanel = viewModel::switchPanel,
        onRefreshRoot = viewModel::refreshRoot,
        onToggleShowIgnored = viewModel::toggleShowIgnored,
        onToggleExpand = viewModel::toggleExpand,
        onRefreshGit = viewModel::loadGitChanges,
        onOpenFile = { filePath ->
            fileViewerRequest = FileViewerParams(
                serverId = serverId,
                sessionId = sessionId,
                filePath = filePath,
                directory = uiState.directory,
                source = FileViewerSource.LIVE
            )
        },
        onOpenGitDiff = { filePath ->
            fileViewerRequest = FileViewerParams(
                serverId = serverId,
                sessionId = sessionId,
                filePath = filePath,
                directory = uiState.directory,
                source = FileViewerSource.GIT_DIFF
            )
        },
        // Phase 2: Search
        onEnterSearch = viewModel::enterSearch,
        onExitSearch = viewModel::exitSearch,
        onSearchQueryChange = viewModel::updateSearchQuery
    )

    fileViewerRequest?.let { params ->
        FileViewerOverlay(
            params = params,
            onDismiss = { fileViewerRequest = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    uiState: WorkspaceUiState,
    onBack: () -> Unit,
    onSwitchPanel: (WorkspacePanel) -> Unit,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onToggleExpand: (String) -> Unit,
    onRefreshGit: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenGitDiff: (String) -> Unit,
    // Phase 2: Search
    onEnterSearch: () -> Unit,
    onExitSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit
) {
    // Intercept system back when in search mode to exit search instead of leaving the screen
    BackHandler(enabled = uiState.isSearchMode) {
        onExitSearch()
    }

    Scaffold(
        topBar = {
            Crossfade(targetState = uiState.isSearchMode, label = "search_topbar") { isSearch ->
                if (isSearch) {
                    SearchTopBar(
                        query = uiState.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onBack = onExitSearch,
                        onClear = { onSearchQueryChange("") }
                    )
                } else {
                    WorkspaceTopBar(
                        uiState = uiState,
                        onBack = onBack,
                        onSwitchPanel = onSwitchPanel,
                        onSearch = onEnterSearch
                    )
                }
            }
        }
    ) { padding ->
        if (uiState.isSearchMode) {
            val filteredGitChanges = if (uiState.currentPanel == WorkspacePanel.GIT_CHANGES) {
                uiState.gitChanges.filter {
                    uiState.searchQuery.isBlank() || it.file.contains(uiState.searchQuery, ignoreCase = true)
                }
            } else emptyList()
            SearchOverlay(
                activePanel = uiState.currentPanel,
                query = uiState.searchQuery,
                fileResults = uiState.fileSearchResults,
                gitChanges = filteredGitChanges,
                isLoading = uiState.searchLoading,
                hasSearched = uiState.hasSearched,
                errorMessageRes = uiState.searchError,
                onOpenFile = { onOpenFile(it); onExitSearch() },
                onOpenGitDiff = { onOpenGitDiff(it); onExitSearch() },
                modifier = Modifier.padding(padding)
            )
        } else {
            when (uiState.currentPanel) {
                WorkspacePanel.FILE_TREE -> FileTreePanel(
                    uiState = uiState,
                    onRefreshRoot = onRefreshRoot,
                    onToggleShowIgnored = onToggleShowIgnored,
                    onOpenFile = onOpenFile,
                    onToggleExpand = onToggleExpand,
                    modifier = Modifier.padding(padding)
                )
                WorkspacePanel.GIT_CHANGES -> GitChangesPanel(
                    uiState = uiState,
                    onRefresh = onRefreshGit,
                    onOpenDiff = onOpenGitDiff,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    uiState: WorkspaceUiState,
    onBack: () -> Unit,
    onSwitchPanel: (WorkspacePanel) -> Unit,
    onSearch: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = basename(uiState.directory),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.directory.isNotBlank()) {
                    Text(
                        text = uiState.directory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("back_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        },
        actions = {
            // Phase 2: 🔍 search button (spec §6.1 order: [🔍][📁/🔀])
            IconButton(
                onClick = onSearch,
                modifier = Modifier.testTag("workspace_search_button")
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.a11y_icon_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Toggle button: switches between FILE_TREE and GIT_CHANGES panels.
            // Non-git repos only show the Folder icon (no toggle available).
            when (uiState.currentPanel) {
                WorkspacePanel.FILE_TREE -> {
                    if (uiState.isNonGit) {
                        // No git → static folder icon, no toggle
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = stringResource(R.string.a11y_icon_toggle_directory),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        // FILE_TREE active → show Git icon to switch to GIT_CHANGES
                        IconButton(
                            onClick = { onSwitchPanel(WorkspacePanel.GIT_CHANGES) },
                            modifier = Modifier.testTag("panel_toggle")
                        ) {
                            BadgedBox(
                                badge = {
                                    val count = uiState.gitChangeCount
                                    if (count != null && count > 0) {
                                        Badge { Text("$count") }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.CompareArrows,
                                    contentDescription = stringResource(R.string.a11y_icon_git_changes),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                WorkspacePanel.GIT_CHANGES -> {
                    // GIT_CHANGES active → show Folder icon to switch back to FILE_TREE
                    IconButton(
                        onClick = { onSwitchPanel(WorkspacePanel.FILE_TREE) },
                        modifier = Modifier.testTag("panel_toggle")
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = stringResource(R.string.a11y_icon_toggle_directory),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

/** Returns the last path segment, or "/" for empty/root paths.
 *  Handles both POSIX (/) and Windows (\) separators. */
private fun basename(path: String): String {
    if (path.isBlank()) return "/"
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return "/"
    return dev.leonardo.octether.util.PathUtils.fileName(trimmed).ifBlank { trimmed }
}
