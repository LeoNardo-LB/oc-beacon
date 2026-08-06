package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.viewModelScope
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============ 会话加载 / 刷新 / 分页 ============

fun SessionListViewModel.loadSessions() {
    viewModelScope.launch {
        _isLoading.value = true
        _error.value = null
        resetPagination()
        try {
            val projects = fileApi.listProjects(conn)
            _projects.value = projects
            if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Loaded ${projects.size} projects for multi-project session fetch")

            if (projects.isEmpty()) {
                val sessions = sessionApi.listSessions(conn, search = _searchQuery.value)
                eventDispatcher.setSessions(serverId, sessions)
                if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Loaded ${sessions.size} sessions (no projects)")
            } else {
                var totalSessions = 0
                for (project in projects) {
                    try {
                        val sessions = sessionApi.listSessions(conn, directory = project.worktree, search = _searchQuery.value)
                        eventDispatcher.setSessions(serverId, sessions)
                        totalSessions += sessions.size
                        if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Loaded ${sessions.size} sessions for project ${project.displayName}")
                    } catch (e: Exception) {
                        AppLogger.w(TAG_SESSION_LIST_VM, "Failed to load sessions for project ${project.displayName}: ${e.message}")
                    }
                }
                if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Total: loaded $totalSessions sessions across ${projects.size} projects for server $serverId")
            }
            // 通过统一的 FSM 管线从服务器同步会话状态
            //（跨项目 worktree 聚合 + 缺失即 idle + 不完整保护）。
            sessionStateService.setServerId(serverId)
            sessionStateService.syncFromRest(_projects.value)
        } catch (e: Exception) {
            AppLogger.e(TAG_SESSION_LIST_VM, "Failed to load sessions", e)
            _error.value = e.message ?: "Failed to load sessions"
        } finally {
            if (_expandedPaths.value.isEmpty()) {
                // 首次加载时默认展开所有目录
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

fun SessionListViewModel.refreshSessions() {
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
                        AppLogger.w(TAG_SESSION_LIST_VM, "Failed to refresh sessions for project ${project.displayName}: ${e.message}")
                    }
                }
            }
            // 通过统一的 FSM 管线从服务器同步会话状态。
            sessionStateService.setServerId(serverId)
            sessionStateService.syncFromRest(_projects.value)
        } catch (e: Exception) {
            AppLogger.e(TAG_SESSION_LIST_VM, "Failed to refresh sessions", e)
            _error.value = e.message ?: "Failed to refresh sessions"
        } finally {
            _isRefreshing.value = false
        }
    }
}

fun SessionListViewModel.resetPagination() {
    _currentCursor.value = null
    _hasMorePages.value = true
    _isLoadingMore.value = false
}

/**
 * 使用基于游标的分页加载下一页会话。
 * 由 UI 在用户滚动到会话列表底部附近时调用。
 */
fun SessionListViewModel.loadMore() {
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
            AppLogger.e(TAG_SESSION_LIST_VM, "Failed to load more sessions", e)
        } finally {
            _isLoadingMore.value = false
        }
    }
}

// ============ 会话操作（删除 / 重命名 / 导入） ============

fun SessionListViewModel.deleteSession(sessionId: String) {
    viewModelScope.launch {
        try {
            val result = deleteSessionUseCase(serverId, sessionId)
            if (result.isSuccess) {
                if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Deleted session $sessionId")
                loadSessions()
            } else {
                _error.value = "Failed to delete session"
            }
        } catch (e: Exception) {
            AppLogger.e(TAG_SESSION_LIST_VM, "Failed to delete session", e)
            _error.value = e.message ?: "Failed to delete session"
        }
    }
}

fun SessionListViewModel.renameSession(sessionId: String, newTitle: String) {
    viewModelScope.launch {
        try {
            manageSessionUseCase.renameSession(serverId, sessionId, newTitle)
            if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Renamed session $sessionId to '$newTitle'")
            loadSessions()
        } catch (e: Exception) {
            AppLogger.e(TAG_SESSION_LIST_VM, "Failed to rename session", e)
            _error.value = e.message ?: "Failed to rename session"
        }
    }
}

/**
 * 从分享 URL 导入会话。
 * 成功后重新加载会话列表。
 */
fun SessionListViewModel.importSession(shareUrl: String, onResult: (Boolean) -> Unit) {
    viewModelScope.launch {
        try {
            val session = manageSessionUseCase.importSession(serverId, shareUrl)
            if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Imported session ${session.id}")
            eventDispatcher.setSessions(serverId, listOf(session))
            onResult(true)
        } catch (e: Exception) {
            AppLogger.e(TAG_SESSION_LIST_VM, "Failed to import session", e)
            _error.value = e.message ?: "Failed to import session"
            onResult(false)
        }
    }
}

fun SessionListViewModel.copyToClipboard(text: String, context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("label", text))
}

// ============ 批量选择 ============

fun SessionListViewModel.toggleSelection(sessionId: String) {
    _selectedIds.update { selected ->
        if (sessionId in selected) selected - sessionId else selected + sessionId
    }
}

fun SessionListViewModel.clearSelection() {
    _selectedIds.value = emptySet()
}

fun SessionListViewModel.selectAll() {
    val currentState = uiState.value
    val sessionIds = currentState.treeNodes
        .filterIsInstance<TreeNode.Session>()
        .map { it.id }
        .toSet()
    _selectedIds.value = sessionIds
}

fun SessionListViewModel.deleteSelected() {
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
            AppLogger.e(TAG_SESSION_LIST_VM, "Failed to delete selected sessions", e)
            _error.value = e.message ?: "Failed to delete selected sessions"
        }
    }
}
