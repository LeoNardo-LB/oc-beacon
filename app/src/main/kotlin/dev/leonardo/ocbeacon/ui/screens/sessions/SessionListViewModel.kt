package dev.leonardo.ocbeacon.ui.screens.sessions

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.ui.navigation.routes.safeDecodeParam
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.DeleteSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.runBlocking
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
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    companion object {
        /** 表示 Windows 盘符选择器根的虚拟路径。 */
        const val WINDOWS_DRIVES_ROOT = ":///drives"
        /** ChatScreen 在用户发送消息时写入的 SavedStateHandle key；
         * 此 ViewModel 在返回时消费它以将列表滚动回顶部。 */
        const val KEY_SCROLL_TO_TOP = "session_list_scroll_to_top"
    }

    val serverId: String = safeDecodeParam(savedStateHandle.get<String>("serverId") ?: "")

    // 服务器配置异步从数据源解析（密码/用户名/URL 不再经导航参数传递）。
    // runBlocking(Dispatchers.IO)：本地 Room 读取毫秒级，保证 directoryManager/mcpRepository eager 初始化。
    private val serverConfig: dev.leonardo.ocbeacon.domain.model.ServerConfig? =
        runBlocking(Dispatchers.IO) { serverRepository.getServer(serverId) }
    val serverName: String = serverConfig?.displayName ?: ""

    internal val conn = serverConfig?.let {
        ServerConnection.from(it.url, it.username, it.password)
    } ?: ServerConnection.from("", "", null)

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

    /** 仅显示收藏会话（本服务器内置标签筛选）。 */
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    /** 切换"仅收藏"筛选。 */
    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    /** 全局标签列表（按服务器划分），用于选择器 / 过滤 chip。 */
    val sessionTags: StateFlow<List<Tag>> = settingsRepository.sessionTags(serverId)
        .stateIn(viewModelScope, WhileSubscribed5s, emptyList())

    /** 按服务器划分的 sessionId → tagIds 分配（含内置收藏标签），供设置页标签管理逐会话解除使用。 */
    val sessionTagAssignments: StateFlow<Map<String, List<String>>> =
        settingsRepository.sessionTagAssignments(serverId)
            .stateIn(viewModelScope, WhileSubscribed5s, emptyMap())

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
        settingsRepository.sessionTagAssignments(serverId),
        _categoryFilter,
        sessionTags,
        _favoritesOnly,
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

    /** 替换指定会话上的用户标签集（保留内置收藏标签）。 */
    fun assignTags(sessionId: String, tagIds: Set<String>) {
        viewModelScope.launch { settingsRepository.setSessionTags(serverId, sessionId, tagIds) }
    }

    /** 切换当前服务器上某会话的收藏状态（基于内置收藏标签）。 */
    fun toggleFavorite(session: Session) {
        viewModelScope.launch {
            settingsRepository.toggleFavorite(serverId, session.id)
        }
    }

    /** 移除某会话在当前服务器上的某个标签分配（不删除标签本身）。 */
    fun removeSessionTagAssignment(sessionId: String, tagId: String) {
        viewModelScope.launch { settingsRepository.removeSessionTagAssignment(serverId, sessionId, tagId) }
    }

    /**
     * 新建一个用户标签，使用调用方预生成的 [id]（用于 TagPickerDialog 创建后立即勾选）。
     *
     * 返回 [id] 以便调用方把它加入本地选择集合。
     */
    fun addSessionTag(name: String, color: String, icon: String, id: String): String {
        viewModelScope.launch {
            settingsRepository.addSessionTag(
                serverId,
                Tag(
                    id = id,
                    name = name,
                    color = color,
                    icon = icon,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        return id
    }

    /** 更新一个已存在的用户标签（按 id 替换）。 */
    fun updateSessionTag(tag: Tag) {
        viewModelScope.launch { settingsRepository.updateSessionTag(serverId, tag) }
    }

    /** 按 id 删除一个用户标签（并原子清理所有分配）。 */
    fun removeSessionTag(tagId: String) {
        viewModelScope.launch { settingsRepository.removeSessionTag(serverId, tagId) }
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

    suspend fun listWindowsDrives(): Flow<FileNode> = directoryManager.listWindowsDrives()

    suspend fun listDirectories(directory: String): List<FileNode> =
        directoryManager.listDirectories(directory)

    suspend fun searchDirectories(query: String, directory: String): List<String> =
        directoryManager.searchDirectories(query, directory)

    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> =
        directoryManager.createDirectory(parentDirectory, folderName)
}
