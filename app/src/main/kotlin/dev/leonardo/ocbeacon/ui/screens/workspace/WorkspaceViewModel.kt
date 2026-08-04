package dev.leonardo.ocbeacon.ui.screens.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.isDirectory
import dev.leonardo.ocbeacon.domain.usecase.FindFilesUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetVcsStatusUseCase
import dev.leonardo.ocbeacon.domain.usecase.ListDirectoryUseCase
import dev.leonardo.ocbeacon.ui.navigation.routes.ServerRouteParams
import dev.leonardo.ocbeacon.ui.navigation.routes.WorkspaceNav
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDirectory: ListDirectoryUseCase,
    private val getVcsStatus: GetVcsStatusUseCase,
    private val findFiles: FindFilesUseCase
) : ViewModel() {

    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = URLDecoder.decode(
        savedStateHandle.get<String>(WorkspaceNav.PARAM_DIRECTORY).orEmpty(), "UTF-8"
    )

    private val _uiState = MutableStateFlow(WorkspaceUiState(directory = directory))
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val dirCache = mutableMapOf<String, List<FileNode>>()
    private val loadJobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null

    private val _dirLoadEvents = MutableSharedFlow<DirectoryLoadResult>()
    val dirLoadEvents: SharedFlow<DirectoryLoadResult> = _dirLoadEvents.asSharedFlow()

    init {
        if (serverId.isBlank()) {
            _uiState.update { it.copy(rootError = R.string.workspace_error_server_config_missing, rootLoading = false) }
        } else {
            loadDirectory("")
            prefetchGitCount()
        }
    }

    fun loadDirectory(path: String) {
        if (serverId.isBlank()) return
        dirCache[path]?.let { return }
        loadJobs[path]?.cancel()
        if (path.isEmpty()) {
            _uiState.update { it.copy(rootLoading = true, rootError = null) }
        } else {
            _uiState.update { it.copy(loadingDirs = it.loadingDirs + path) }
        }
        loadJobs[path] = viewModelScope.launch {
            listDirectory(serverId, directory, path)
                .onSuccess { nodes ->
                    dirCache[path] = nodes
                    if (path.isEmpty()) {
                        _uiState.update {
                            it.copy(rootNodes = nodes.toTreeNodes(), rootLoading = false)
                        }
                    } else {
                        _uiState.update { state ->
                            state.copy(
                                rootNodes = state.rootNodes.withChildren(path, nodes.toTreeNodes()),
                                expandedDirs = state.expandedDirs + path,
                                loadingDirs = state.loadingDirs - path
                            )
                        }
                        _dirLoadEvents.tryEmit(DirectoryLoadResult(path, nodes, null))
                    }
                }
                .onFailure { e ->
                    if (path.isEmpty()) {
                        _uiState.update { it.copy(rootLoading = false, rootError = R.string.workspace_error_load_failed) }
                    } else {
                        _uiState.update { it.copy(loadingDirs = it.loadingDirs - path) }
                        _dirLoadEvents.tryEmit(DirectoryLoadResult(path, emptyList(), e.message))
                    }
                }
        }
    }

    fun toggleExpand(path: String) {
        val state = _uiState.value
        when {
            // 已展开 → 折叠
            path in state.expandedDirs ->
                _uiState.update { it.copy(expandedDirs = it.expandedDirs - path) }
            // 已缓存但未展开 → 展开（children 已从之前的加载进入树中）
            path in dirCache ->
                _uiState.update { it.copy(expandedDirs = it.expandedDirs + path) }
            // 未加载 → 触发异步加载
            else -> {
                _uiState.update { it.copy(loadingDirs = it.loadingDirs + path) }
                loadDirectory(path)
            }
        }
    }

    fun refreshRoot() {
        dirCache.clear()
        loadJobs.values.forEach { it.cancel() }
        _uiState.update { it.copy(expandedDirs = emptySet(), loadingDirs = emptySet()) }
        loadDirectory("")
    }

    fun switchPanel(p: WorkspacePanel) {
        _uiState.update { it.copy(currentPanel = p) }
        if (p == WorkspacePanel.GIT_CHANGES
            && _uiState.value.gitChanges.isEmpty()
            && !_uiState.value.isNonGit
            && !_uiState.value.gitLoading
        ) {
            loadGitChanges()
        }
    }

    fun loadGitChanges() {
        if (serverId.isBlank()) return
        _uiState.update { it.copy(gitLoading = true, gitError = null, isNonGit = false) }
        viewModelScope.launch {
            getVcsStatus(serverId, directory)
                .onSuccess { c ->
                    _uiState.update {
                        it.copy(gitChanges = c, gitLoading = false, gitChangeCount = c.size, isNonGit = false)
                    }
                }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    val nonGit = msg.contains("non-git", true) || msg.contains("not a git", true)
                    _uiState.update {
                        it.copy(gitLoading = false, isNonGit = nonGit, gitError = if (nonGit) null else R.string.workspace_error_load_failed)
                    }
                }
        }
    }

    private fun prefetchGitCount() {
        viewModelScope.launch {
            getVcsStatus(serverId, directory)
                .onSuccess { c -> _uiState.update { it.copy(gitChangeCount = c.size) } }
        }
    }

    fun toggleShowIgnored() {
        _uiState.update { it.copy(showIgnored = !it.showIgnored) }
    }

    // ============ Phase 2：搜索 ============

    fun enterSearch() {
        _uiState.update {
            it.copy(isSearchMode = true, searchQuery = "", fileSearchResults = emptyList(), hasSearched = false, searchError = null)
        }
    }

    fun exitSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchMode = false,
                searchQuery = "",
                fileSearchResults = emptyList(),
                hasSearched = false,
                searchLoading = false,
                searchError = null
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchFiles(query)
    }

    fun searchFiles(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(fileSearchResults = emptyList(), hasSearched = false, searchLoading = false, searchError = null)
            }
            return
        }
        _uiState.update { it.copy(searchLoading = true, searchError = null) }
        searchJob = viewModelScope.launch {
            delay(300)
            findFiles(serverId, directory, query.trim())
                .onSuccess { results ->
                    _uiState.update { it.copy(fileSearchResults = results, searchLoading = false, hasSearched = true) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(searchLoading = false, searchError = R.string.workspace_error_load_failed, hasSearched = true)
                    }
                }
        }
    }

    /** git 变更的客户端过滤（无网络调用）。 */
    fun filterGitChanges(query: String): List<VcsChange> {
        val changes = _uiState.value.gitChanges
        if (query.isBlank()) return changes
        return changes.filter { it.file.contains(query, ignoreCase = true) }
    }

    private fun List<FileNode>.toTreeNodes() =
        sortedWith(compareBy({ !it.isDirectory() }, { it.name.lowercase() }))
            .map { FileTreeNode(it, if (it.isDirectory()) null else emptyList()) }
}
