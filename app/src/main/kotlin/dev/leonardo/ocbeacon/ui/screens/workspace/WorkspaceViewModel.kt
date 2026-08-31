package dev.leonardo.ocbeacon.ui.screens.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.api.dsh.DshApiError
import dev.leonardo.ocbeacon.data.api.dsh.DshRpcErrorCode
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.isDirectory
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.usecase.FindFilesUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetVcsStatusUseCase
import dev.leonardo.ocbeacon.domain.usecase.ListDirectoryUseCase
import dev.leonardo.ocbeacon.ui.navigation.routes.ServerRouteParams
import dev.leonardo.ocbeacon.ui.navigation.routes.safeDecodeParam
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
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDirectory: ListDirectoryUseCase,
    private val getVcsStatus: GetVcsStatusUseCase,
    private val findFiles: FindFilesUseCase,
    /** #276：能力位来源（serverType 维度——DSH 下 vcs/文件搜索/文件读全 false）。 */
    private val serverConfigRepository: ServerConfigRepository
) : ViewModel() {

    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = safeDecodeParam(savedStateHandle.get<String>(WorkspaceNav.PARAM_DIRECTORY).orEmpty())

    private val _uiState = MutableStateFlow(WorkspaceUiState(directory = directory))
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    // #98（M-13）：LRU 有界（原仅 refreshRoot 清理——深层目录浏览累积无界）。
    // LinkedHashMap 访问序淘汰，容量对齐 DirectoryManager.dirCache 标杆。
    private val dirCache = object : LinkedHashMap<String, List<FileNode>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileNode>>): Boolean {
            return size > DIR_CACHE_MAX
        }
    }
    // #98（M-13）：完成的 Job 引用原永不清理——invokeOnCompletion 即时移除
    //（in-flight 的取消/去重语义保留）。
    private val loadJobs = mutableMapOf<String, Job>()

    private companion object {
        const val DIR_CACHE_MAX = 200
    }
    private var searchJob: Job? = null
    /** #134（D2-L33）：git 状态加载共享 job——prefetch 与完整加载互斥（in-flight 保护）。 */
    private var gitLoadJob: Job? = null

    private val _dirLoadEvents = MutableSharedFlow<DirectoryLoadResult>()
    val dirLoadEvents: SharedFlow<DirectoryLoadResult> = _dirLoadEvents.asSharedFlow()

    init {
        if (serverId.isBlank()) {
            _uiState.update { it.copy(rootError = R.string.workspace_error_server_config_missing, rootLoading = false) }
        } else {
            loadDirectory("")
            // #276：能力位先行——配置加载后才决定是否发起 git 预取（DSH 无 vcs 域，
            // 白跑一次 RPC 还会把空 git 面板暴露成可切换入口）。
            viewModelScope.launch {
                val config = serverConfigRepository.getServer(serverId)
                val caps = config?.let {
                    dev.leonardo.ocbeacon.domain.model.ServerCapabilities.of(it.serverType, it.apiVersion)
                }
                if (caps != null) {
                    _uiState.update {
                        it.copy(
                            vcsSupported = caps.vcsSupported,
                            fileSearchSupported = caps.fileSearchSupported,
                            fileReadSupported = caps.fileReadSupported,
                        )
                    }
                }
                if (caps?.vcsSupported != false) {
                    prefetchGitCount()
                }
            }
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
        val job = viewModelScope.launch {
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
                        // #276 终验 V4：DSH host.listDirectory 无类型判别（协议级
                        // 补偿）——条目缺省全按 directory 可展开（DshApiClient 映射），
                        // 对非目录路径的展开失败（闭集错误码 directory-unreadable）
                        // 即单次探测信号：节点转标 file 叶并随树缓存（不再可展开，
                        // 避免重复探测）。仅此错误码转标——真实目录的瞬时网络/服务
                        // 失败不误降级。
                        if (e is DshApiError && e.code == DshRpcErrorCode.DirectoryUnreadable) {
                            _uiState.update { state ->
                                state.copy(rootNodes = state.rootNodes.demoteToFile(path))
                            }
                        }
                        _dirLoadEvents.tryEmit(DirectoryLoadResult(path, emptyList(), e.message))
                    }
                }
        }
        // #98（M-13）：完成（含取消/失败）即移除引用，防 Job 永驻
        job.invokeOnCompletion { loadJobs.remove(path) }
        loadJobs[path] = job
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
        // #276 能力位门控：DSH 无 vcs 域（UI 入口已隐藏，此处兜底防程序化切换）
        if (!_uiState.value.vcsSupported) return
        // #134（D2-L33）：取消在跑的 prefetch——完整加载与其结果互斥，避免双发 VCS status
        gitLoadJob?.cancel()
        _uiState.update { it.copy(gitLoading = true, gitError = null, isNonGit = false) }
        gitLoadJob = viewModelScope.launch {
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
        // #134（D2-L33）：in-flight 保护——完整加载进行中（或已有 prefetch）不重复发请求
        if (gitLoadJob?.isActive == true) return
        gitLoadJob = viewModelScope.launch {
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
