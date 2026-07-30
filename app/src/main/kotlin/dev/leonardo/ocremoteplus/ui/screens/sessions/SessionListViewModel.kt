package dev.leonardo.ocremoteplus.ui.screens.sessions

import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocremoteplus.BuildConfig
import dev.leonardo.ocremoteplus.ui.WhileSubscribed5s
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocremoteplus.data.dto.response.FileNodeDto
import dev.leonardo.ocremoteplus.data.dto.response.ServerPaths
import dev.leonardo.ocremoteplus.data.api.file.FileApi
import dev.leonardo.ocremoteplus.data.api.session.SessionApi
import dev.leonardo.ocremoteplus.data.api.system.SystemApi
import dev.leonardo.ocremoteplus.data.api.terminal.TerminalApi
import dev.leonardo.ocremoteplus.domain.model.ServerConnection
import dev.leonardo.ocremoteplus.data.repository.EventDispatcher
import dev.leonardo.ocremoteplus.data.repository.SessionStateService
import dev.leonardo.ocremoteplus.domain.model.Project
import dev.leonardo.ocremoteplus.domain.model.Session
import dev.leonardo.ocremoteplus.domain.model.SessionStatus
import dev.leonardo.ocremoteplus.domain.model.McpServerStatus
import dev.leonardo.ocremoteplus.domain.repository.DraftRepository
import dev.leonardo.ocremoteplus.domain.repository.McpRepository
import dev.leonardo.ocremoteplus.domain.usecase.DeleteSessionUseCase
import dev.leonardo.ocremoteplus.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.TreeNode
import dev.leonardo.ocremoteplus.ui.screens.sessions.components.buildTreeNodes
import dev.leonardo.ocremoteplus.ui.WhileSubscribed5s
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "SessionListViewModel"

enum class SessionViewMode { FOLDER, RECENT }

data class SessionListUiState(
    val treeNodes: List<TreeNode> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val baseDirectory: String? = null,
    val baseDirectories: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val prefillDirectory: String? = null,
    val searchQuery: String? = null,
)

data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle,
    val hasDraft: Boolean = false
)

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val eventDispatcher: EventDispatcher,
    private val sessionStateService: SessionStateService,
    private val sessionApi: SessionApi,
    private val fileApi: FileApi,
    private val systemApi: SystemApi,
    private val terminalApi: TerminalApi,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val draftRepository: DraftRepository,
    private val mcpRepository: McpRepository,
    private val scrollSignal: SessionScrollSignal
) : ViewModel() {

    companion object {
        /** Virtual path representing the Windows drive-picker root. */
        const val WINDOWS_DRIVES_ROOT = ":///drives"
        /** SavedStateHandle key written by ChatScreen when the user sends a message;
         * consumed by this ViewModel on return to scroll the list back to top. */
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

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })

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

    private val _error = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _baseDirectory = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _lastToggledDirectory = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow<String?>(null)
    private val _currentCursor = MutableStateFlow<String?>(null)
    private val _hasMorePages = MutableStateFlow(true)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _viewMode = MutableStateFlow(
        savedStateHandle.get<String>("viewMode")?.let {
            runCatching { SessionViewMode.valueOf(it) }.getOrNull()
        } ?: SessionViewMode.RECENT
    )
    val viewMode: StateFlow<SessionViewMode> = _viewMode.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val mcpServers: StateFlow<List<McpServerStatus>> = _mcpServers.asStateFlow()

    private val _mcpLoading = MutableStateFlow<String?>(null)
    val mcpLoading: StateFlow<String?> = _mcpLoading.asStateFlow()

    private val _mcpInitialLoading = MutableStateFlow(false)
    val mcpInitialLoading: StateFlow<Boolean> = _mcpInitialLoading.asStateFlow()

    private val _mcpError = MutableSharedFlow<String>()
    val mcpError: SharedFlow<String> = _mcpError.asSharedFlow()

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
        _viewMode
    ) { values ->
        val allSessions = values[0] as List<Session>
        val statuses = values[1] as Map<String, SessionStatus>
        val serverSessionMap = values[2] as Map<String, Set<String>>
        val lastUserMessageTime = values[3] as Map<String, Long>
        val isLoading = values[4] as Boolean
        val error = values[5] as String?
        val projects = values[6] as List<Project>
        val expandedPaths = values[7] as Set<String>
        val selectedIds = values[8] as Set<String>
        val baseDirectory = values[9] as String?
        val isRefreshing = values[10] as Boolean
        val lastToggledDirectory = values[11] as String?
        val searchQuery = values[12] as String?
        val viewMode = values[13] as SessionViewMode

        val serverSessionIds = serverSessionMap[serverId].orEmpty()

        val filteredSessions = allSessions
            .filter { it.id in serverSessionIds && it.parentId == null }
            .sortedByDescending { session ->
                lastUserMessageTime[session.id] ?: session.time.updated
            }

        val baseFilteredSessions = if (baseDirectory != null) {
            filteredSessions.filter { session ->
                val dir = session.directory.replace('\\', '/').trimEnd('/')
                dir.startsWith(baseDirectory)
            }
        } else {
            filteredSessions
        }

        // Client-side search: filter by directory path OR session title
        val searchedSessions = if (!searchQuery.isNullOrBlank()) {
            val query = searchQuery.lowercase()
            baseFilteredSessions.filter { session ->
                session.directory.lowercase().contains(query) ||
                    session.title?.lowercase()?.contains(query) == true
            }
        } else {
            baseFilteredSessions
        }

        val treeNodes = if (viewMode == SessionViewMode.RECENT) {
            // Recent mode: flat list of sessions sorted by update time, no directory grouping
            searchedSessions.map { session ->
                TreeNode.Session(
                    id = session.id,
                    session = SessionItem(
                        session = session,
                        status = statuses[session.id] ?: SessionStatus.Idle,
                        hasDraft = session.id in draftRepository.getDraftSessionIds()
                    )
                )
            }
        } else {
            buildTreeNodes(searchedSessions, expandedPaths, baseDirectory, statuses, draftRepository.getDraftSessionIds(), projects)
        }

        val prefillDirectory = if (lastToggledDirectory != null && lastToggledDirectory in expandedPaths)
            lastToggledDirectory
        else
            baseDirectory

        SessionListUiState(
            treeNodes = treeNodes,
            serverName = serverName,
            isLoading = isLoading,
            error = error,
            selectedIds = selectedIds,
            isSelectionMode = selectedIds.isNotEmpty(),
            baseDirectory = baseDirectory,
            baseDirectories = emptySet(),
            isRefreshing = isRefreshing,
            prefillDirectory = prefillDirectory,
            searchQuery = searchQuery,
        )
    }.stateIn(viewModelScope, WhileSubscribed5s, SessionListUiState())

    init {
        loadSessions()
    }

    /** True if the previous ChatScreen visit sent a message, so the list should
     * scroll back to top. Consumes (clears) the flag set via [KEY_SCROLL_TO_TOP]. */
    fun consumeScrollToTopOnReturn(): Boolean {
        return scrollSignal.consumeScrollToTop()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            resetPagination()
            try {
                val projects = fileApi.listProjects(conn)
                _projects.value = projects
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${projects.size} projects for multi-project session fetch")

                if (projects.isEmpty()) {
                    val sessions = sessionApi.listSessions(conn, search = _searchQuery.value)
                    eventDispatcher.setSessions(serverId, sessions)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions (no projects)")
                } else {
                    var totalSessions = 0
                    for (project in projects) {
                        try {
                            val sessions = sessionApi.listSessions(conn, directory = project.worktree, search = _searchQuery.value)
                            eventDispatcher.setSessions(serverId, sessions)
                            totalSessions += sessions.size
                            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions for project ${project.displayName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load sessions for project ${project.displayName}: ${e.message}")
                        }
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Total: loaded $totalSessions sessions across ${projects.size} projects for server $serverId")
                }
                // Sync session statuses from server via the unified FSM pipeline
                // (aggregate across project worktrees + absence=idle + incomplete-protection).
                sessionStateService.setServerId(serverId)
                sessionStateService.syncFromRest(_projects.value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sessions", e)
                _error.value = e.message ?: "Failed to load sessions"
            }             finally {
                if (_expandedPaths.value.isEmpty()) {
                    // Expand all directories by default on first load
                    val currentSessions = eventDispatcher.sessions.value
                    val base = _baseDirectory.value?.replace('\\', '/')?.trimEnd('/')
                    val dirs = mutableSetOf<String>()
                    for (s in currentSessions) {
                        val dir = s.directory.replace('\\', '/').trimEnd('/')
                        if (base != null && dir.startsWith(base)) {
                            val relative = dir.removePrefix(base).removePrefix("/")
                            if (relative.isNotEmpty()) {
                                dirs.add("$base/${relative.substringBefore('/')}")
                            }
                        }
                    }
                    _expandedPaths.value = dirs
                }
                _isLoading.value = false
            }
        }
    }

    fun refreshSessions() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                val projects = fileApi.listProjects(conn)
                _projects.value = projects
                if (projects.isEmpty()) {
                    val sessions = sessionApi.listSessions(conn, search = _searchQuery.value)
                    eventDispatcher.setSessions(serverId, sessions)
                } else {
                    for (project in projects) {
                        try {
                            val sessions = sessionApi.listSessions(conn, directory = project.worktree, search = _searchQuery.value)
                            eventDispatcher.setSessions(serverId, sessions)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to refresh sessions for project ${project.displayName}: ${e.message}")
                        }
                    }
                }
                // Sync session statuses from server via the unified FSM pipeline.
                sessionStateService.setServerId(serverId)
                sessionStateService.syncFromRest(_projects.value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh sessions", e)
                _error.value = e.message ?: "Failed to refresh sessions"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val result = deleteSessionUseCase(serverId, sessionId)
                if (result.isSuccess) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Deleted session $sessionId")
                    loadSessions()
                } else {
                    _error.value = "Failed to delete session"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
                _error.value = e.message ?: "Failed to delete session"
            }
        }
    }

    fun toggleSelection(sessionId: String) {
        _selectedIds.update { selected ->
            if (sessionId in selected) selected - sessionId else selected + sessionId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        val currentState = uiState.value
        val sessionIds = currentState.treeNodes
            .filterIsInstance<TreeNode.Session>()
            .map { it.id }
            .toSet()
        _selectedIds.value = sessionIds
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return@launch
            try {
                val results = coroutineScope {
                    ids.map { id ->
                        async { id to deleteSessionUseCase(serverId, id).isSuccess }
                    }.awaitAll()
                }
                val failed = results.filterNot { it.second }
                if (failed.isNotEmpty()) {
                    _error.value = "Failed to delete ${failed.size} session(s)"
                }
                clearSelection()
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete selected sessions", e)
                _error.value = e.message ?: "Failed to delete selected sessions"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                manageSessionUseCase.renameSession(serverId, sessionId, newTitle)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to '$newTitle'")
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                _error.value = e.message ?: "Failed to rename session"
            }
        }
    }

    // ============ Tree expand/collapse ============

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
        // Reset expanded paths so auto-expand recalculates for the new base
        _expandedPaths.value = emptySet()
    }

    val currentBaseDirectory: String? get() = _baseDirectory.value

    val searchQuery: String? get() = _searchQuery.value

    fun setSearchQuery(query: String) {
        _searchQuery.value = query.ifBlank { null }
    }

    fun clearSearchQuery() {
        _searchQuery.value = null
    }

    val currentCursor: String? get() = _currentCursor.value
    val hasMorePages: Boolean get() = _hasMorePages.value
    val isLoadingMore: Boolean get() = _isLoadingMore.value

    fun resetPagination() {
        _currentCursor.value = null
        _hasMorePages.value = true
        _isLoadingMore.value = false
    }

    /**
     * Load the next page of sessions using cursor-based pagination.
     * Called by UI when user scrolls near the bottom of the session list.
     */
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMorePages.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val cursor = _currentCursor.value
                val sessions = sessionApi.listSessions(
                    conn,
                    directory = _baseDirectory.value,
                    search = _searchQuery.value,
                    cursor = cursor,
                    limit = 50
                )
                if (sessions.isNotEmpty()) {
                    eventDispatcher.setSessions(serverId, sessions)
                    _currentCursor.value = sessions.last().id
                }
                if (sessions.size < 50) {
                    _hasMorePages.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load more sessions", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == SessionViewMode.FOLDER) SessionViewMode.RECENT else SessionViewMode.FOLDER
        savedStateHandle["viewMode"] = _viewMode.value.name
    }

    /**
     * Import a session from a share URL.
     * On success, reload the session list.
     */
    fun importSession(shareUrl: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val session = manageSessionUseCase.importSession(serverId, shareUrl)
                if (BuildConfig.DEBUG) Log.d(TAG, "Imported session ${session.id}")
                eventDispatcher.setSessions(serverId, listOf(session))
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import session", e)
                _error.value = e.message ?: "Failed to import session"
                onResult(false)
            }
        }
    }

    fun copyToClipboard(text: String, context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("label", text))
    }

    // ============ Directory browsing for Open Project (delegated to DirectoryManager) ============

    suspend fun getServerPaths(): ServerPaths = directoryManager.getServerPaths()

    val isWindowsServer: Boolean
        get() = directoryManager.isWindowsServer

    suspend fun getHomeDirectory(): String = directoryManager.getHomeDirectory()

    suspend fun listWindowsDrives(): List<FileNodeDto> = directoryManager.listWindowsDrives()

    suspend fun listDirectories(directory: String): List<FileNodeDto> =
        directoryManager.listDirectories(directory)

    suspend fun searchDirectories(query: String, directory: String): List<String> =
        directoryManager.searchDirectories(query, directory)

    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> =
        directoryManager.createDirectory(parentDirectory, folderName)

    fun loadMcpServers() {
        viewModelScope.launch {
            _mcpInitialLoading.value = true
            mcpRepository.getMcpServers()
                .onSuccess { _mcpServers.value = it }
                .onFailure {
                    _mcpError.emit(it.message ?: "Failed to load MCP servers")
                }
            _mcpInitialLoading.value = false
        }
    }

    fun toggleMcpServer(name: String) {
        if (_mcpLoading.value == name) return
        val server = _mcpServers.value.find { it.name == name } ?: return
        val connect = server.status != "connected"
        _mcpLoading.value = name

        viewModelScope.launch {
            mcpRepository.toggleMcpServer(name, connect)
                .onSuccess {
                    mcpRepository.getMcpServers()
                        .onSuccess { _mcpServers.value = it }
                }
                .onFailure {
                    _mcpError.emit("Failed to ${if (connect) "connect" else "disconnect"} $name")
                }
            _mcpLoading.value = null
        }
    }
}
