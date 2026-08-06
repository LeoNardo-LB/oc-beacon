package dev.leonardo.ocbeacon.ui.screens.sessions

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.dto.response.FileNodeDto
import dev.leonardo.ocbeacon.data.dto.response.ServerPaths
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.DeleteSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    internal val eventDispatcher: EventDispatcher,
    internal val sessionStateService: SessionStateRepository,
    internal val sessionApi: SessionApi,
    internal val fileApi: FileApi,
    private val systemApi: SystemApi,
    private val terminalApi: TerminalApi,
    internal val manageSessionUseCase: ManageSessionUseCase,
    internal val deleteSessionUseCase: DeleteSessionUseCase,
    private val draftRepository: DraftRepository,
    internal val mcpRepository: McpRepository,
    private val scrollSignal: SessionScrollSignal,
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        /** 表示 Windows 盘符选择器根的虚拟路径。 */
        const val WINDOWS_DRIVES_ROOT = ":///drives"
        /** ChatScreen 在用户发送消息时写入的 SavedStateHandle key；
         * 此 ViewModel 在返回时消费它以将列表滚动回顶部。 */
        const val KEY_SCROLL_TO_TOP = "session_list_scroll_to_top"
    }

    val serverUrl: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverUrl") ?: "", "UTF-8"
    )
    private val username: String = URLDecoder.decode(
        savedStateHandle.get<String>("username") ?: "", "UTF-8"
    )
    private val password: String = URLDecoder.decode(
        savedStateHandle.get<String>("password") ?: "", "UTF-8"
    )
    val serverName: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverName") ?: "", "UTF-8"
    )
    val serverId: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverId") ?: "", "UTF-8"
    )

    internal val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })

    private val directoryManager = DirectoryManager(
        fileApi = fileApi,
        sessionApi = sessionApi,
        systemApi = systemApi,
        terminalApi = terminalApi,
        deleteSessionUseCase = deleteSessionUseCase,
        conn = conn,
        serverId = serverId,
    )

    init { mcpRepository.setConnection(conn) }

    // ============ 内部状态（部分由扩展函数访问） ============

    internal val _isLoading = MutableStateFlow(true)
    internal val _error = MutableStateFlow<String?>(null)
    internal val _projects = MutableStateFlow<List<Project>>(emptyList())
    internal val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    internal val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    internal val _baseDirectory = MutableStateFlow<String?>(null)
    internal val _isRefreshing = MutableStateFlow(false)
    private val _lastToggledDirectory = MutableStateFlow<String?>(null)
    internal val _searchQuery = MutableStateFlow<String?>(null)
    internal val _currentCursor = MutableStateFlow<String?>(null)
    internal val _hasMorePages = MutableStateFlow(true)
    internal val _isLoadingMore = MutableStateFlow(false)

    private val _viewMode = MutableStateFlow(
        savedStateHandle.get<String>("viewMode")?.let {
            runCatching { SessionViewMode.valueOf(it) }.getOrNull()
        } ?: SessionViewMode.RECENT
    )
    val viewMode: StateFlow<SessionViewMode> = _viewMode.asStateFlow()

    /** 选中的分类过滤 id，null 表示"全部"。 */
    private val _categoryFilter = MutableStateFlow<String?>(null)
    val categoryFilter: StateFlow<String?> = _categoryFilter.asStateFlow()

    /** 全局分类列表，用于选择器 / 过滤 chip。 */
    val sessionCategories: StateFlow<List<SessionCategory>> = settingsRepository.sessionCategories()
        .stateIn(viewModelScope, WhileSubscribed5s, emptyList())

    /** 当前服务器的已收藏会话 id（驱动 SessionRow 中的星标切换）。 */
    val favoriteSessionIds: StateFlow<Set<String>> = settingsRepository.favoriteSessionIds(serverId)
        .stateIn(viewModelScope, WhileSubscribed5s, emptySet())

    // ============ MCP 状态 ============

    internal val _mcpServers = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val mcpServers: StateFlow<List<McpServerStatus>> = _mcpServers.asStateFlow()

    internal val _mcpLoading = MutableStateFlow<String?>(null)
    val mcpLoading: StateFlow<String?> = _mcpLoading.asStateFlow()

    internal val _mcpInitialLoading = MutableStateFlow(false)
    val mcpInitialLoading: StateFlow<Boolean> = _mcpInitialLoading.asStateFlow()

    internal val _mcpError = MutableSharedFlow<String>()
    val mcpError: SharedFlow<String> = _mcpError.asSharedFlow()

    // ============ 聚合 UI 状态 ============

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SessionListUiState> = combine(
        eventDispatcher.sessions,
        sessionStateService.statusFlow,
        eventDispatcher.serverSessions,
        eventDispatcher.lastUserMessageTime,
        _isLoading,
        _error,
        _projects,
        _expandedPaths,
        _selectedIds,
        _baseDirectory,
        _isRefreshing,
        _lastToggledDirectory,
        _searchQuery,
        _viewMode,
        settingsRepository.sessionCategoryAssignments(serverId),
        _categoryFilter,
        sessionCategories
    ) { values ->
        buildSessionListUiState(values, serverId, serverName, draftRepository)
    }.stateIn(viewModelScope, WhileSubscribed5s, SessionListUiState())

    /** 快速新建会话对话框中显示的最近目录最大数量。 */
    val recentDirectoryCount: StateFlow<Int> = getSettingsFlowUseCase()
        .map { it.recentDirectoryCount }
        .stateIn(viewModelScope, WhileSubscribed5s, 20)

    init {
        loadSessions()
    }

    // ============ 滚动 / 分类 / 收藏 ============

    /** 若上次进入 ChatScreen 时发送了消息，列表应滚动回顶部。 */
    fun consumeScrollToTopOnReturn(): Boolean = scrollSignal.consumeScrollToTop()

    /** 进入 ChatScreen 前标记：返回列表时滚动回顶部（无论是否发过消息）。 */
    fun requestScrollToTopOnReturn() = scrollSignal.requestScrollToTop()

    /** 设置分类过滤（传 null 清除）。 */
    fun setCategoryFilter(categoryId: String?) {
        _categoryFilter.value = categoryId
    }

    /** 将会话分配到当前服务器的某个分类。 */
    fun assignCategory(sessionId: String, categoryId: String) {
        viewModelScope.launch { settingsRepository.assignSessionCategory(serverId, sessionId, categoryId) }
    }

    /** 切换当前服务器上某会话的收藏状态（基于内置收藏标签）。 */
    fun toggleFavorite(session: Session) {
        viewModelScope.launch {
            settingsRepository.toggleFavorite(serverId, session.id)
        }
    }

    /** 移除某会话在当前服务器上的分类分配。 */
    fun unassignCategory(sessionId: String) {
        viewModelScope.launch { settingsRepository.unassignSessionCategory(serverId, sessionId) }
    }

    /** 新建一个全局分类。 */
    fun addCategory(name: String, color: String, icon: String) {
        viewModelScope.launch {
            settingsRepository.addSessionCategory(
                SessionCategory(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    color = color,
                    icon = icon,
                )
            )
        }
    }

    /** 按 id 删除一个全局分类。 */
    fun removeCategory(categoryId: String) {
        viewModelScope.launch { settingsRepository.removeSessionCategory(categoryId) }
    }

    // ============ 树形展开/收起 ============

    fun toggleDirectory(path: String) {
        val normalized = path.replace('\\', '/')
        _lastToggledDirectory.value = normalized
        _expandedPaths.update { paths ->
            if (normalized in paths) paths - normalized
            else paths + normalized
        }
    }

    fun setBaseDirectory(directory: String?) {
        _baseDirectory.value = directory?.replace('\\', '/')?.trimEnd('/')
        // 重置展开路径，让自动展开基于新 base 重新计算
        _expandedPaths.value = emptySet()
    }

    val currentBaseDirectory: String? get() = _baseDirectory.value

    // ============ 搜索 ============

    val searchQuery: String? get() = _searchQuery.value

    fun setSearchQuery(query: String) {
        _searchQuery.value = query.ifBlank { null }
    }

    fun clearSearchQuery() {
        _searchQuery.value = null
    }

    // ============ 分页属性（只读） ============

    val currentCursor: String? get() = _currentCursor.value
    val hasMorePages: Boolean get() = _hasMorePages.value
    val isLoadingMore: Boolean get() = _isLoadingMore.value

    // ============ 视图模式 ============

    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == SessionViewMode.FOLDER) SessionViewMode.RECENT else SessionViewMode.FOLDER
        savedStateHandle["viewMode"] = _viewMode.value.name
    }

    // ============ 用于"打开项目"的目录浏览（委托给 DirectoryManager） ============

    suspend fun getServerPaths(): ServerPaths = directoryManager.getServerPaths()

    val isWindowsServer: Boolean
        get() = directoryManager.isWindowsServer

    suspend fun getHomeDirectory(): String = directoryManager.getHomeDirectory()

    suspend fun listWindowsDrives(): Flow<FileNodeDto> = directoryManager.listWindowsDrives()

    suspend fun listDirectories(directory: String): List<FileNodeDto> =
        directoryManager.listDirectories(directory)

    suspend fun searchDirectories(query: String, directory: String): List<String> =
        directoryManager.searchDirectories(query, directory)

    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> =
        directoryManager.createDirectory(parentDirectory, folderName)
}
