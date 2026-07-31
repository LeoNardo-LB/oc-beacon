package dev.leonardo.ocbeacon.data.repository.handler

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles miscellaneous events: todos, PTY, workspace, file, MCP, command, installation, worktree.
 * Manages: todos
 */
@Singleton
class MiscEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "MiscEventHandler"
    }

    private val _todos = MutableStateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>>(emptyMap())
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> = _todos.asStateFlow()

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.TodoUpdated -> { _todos.update { it + (event.sessionId to event.todos) }; true }
            is SseEvent.PtyCreated -> { if (BuildConfig.DEBUG) Log.d(TAG, "PTY created: ${event.id}"); true }
            is SseEvent.PtyUpdated -> { if (BuildConfig.DEBUG) Log.d(TAG, "PTY updated: ${event.id}"); true }
            is SseEvent.PtyDeleted -> { if (BuildConfig.DEBUG) Log.d(TAG, "PTY deleted: ${event.id}"); true }
            is SseEvent.WorkspaceReady -> { if (BuildConfig.DEBUG) Log.d(TAG, "Workspace ready: ${event.workspaceId}"); true }
            is SseEvent.WorkspaceFailed -> { Log.w(TAG, "Workspace failed: ${event.workspaceId}"); true }
            is SseEvent.FileEdited -> { if (BuildConfig.DEBUG) Log.d(TAG, "File edited: ${event.path}"); true }
            is SseEvent.McpToolsChanged -> { if (BuildConfig.DEBUG) Log.d(TAG, "MCP tools changed: ${event.server}"); true }
            is SseEvent.CommandExecuted -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Command executed: ${event.name}")
                // Note: session status reset to Idle is handled by EventDispatcher (cross-handler concern)
                true
            }
            is SseEvent.FileWatcherUpdated -> { if (BuildConfig.DEBUG) Log.d(TAG, "File watcher updated: ${event.path}"); true }
            is SseEvent.InstallationUpdated -> { if (BuildConfig.DEBUG) Log.d(TAG, "Installation updated: ${event.version}"); true }
            is SseEvent.InstallationUpdateAvailable -> { Log.i(TAG, "Update available: ${event.version}"); true }
            is SseEvent.WorktreeReady -> { if (BuildConfig.DEBUG) Log.d(TAG, "Worktree ready: ${event.path}"); true }
            is SseEvent.WorktreeFailed -> { Log.w(TAG, "Worktree failed: ${event.path}"); true }
            is SseEvent.LspUpdated -> { /* LSP events not needed in mobile */ true }
            else -> false
        }
    }

    fun clearForSession(sessionId: String) {
        _todos.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _todos.update { it - sessionIds }
    }

    fun clearAll() {
        _todos.value = emptyMap()
    }
}
