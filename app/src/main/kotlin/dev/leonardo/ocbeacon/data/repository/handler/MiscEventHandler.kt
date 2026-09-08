package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.CommandFeedback
import dev.leonardo.ocbeacon.domain.model.CommandFeedbackFolder
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处理杂项事件：todos、PTY、workspace、file、MCP、command、installation、worktree。
 * 管理：todos
 */
@Singleton
class MiscEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "MiscEventHandler"
    }

    private val _todos = MutableStateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>>(emptyMap())
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> = _todos.asStateFlow()

    /** #285：DSH 命令注册表变更（commands/change 全局帧）——消费端重载命令列表。
     *  replay=1：晚订阅的会话（后开的 Chat）也能收到最近一次变更补载。 */
    private val _commandsChanged = MutableSharedFlow<Unit>(replay = 1)
    val commandsChanged: SharedFlow<Unit> = _commandsChanged.asSharedFlow()

    /**
     * #323：斜杠命令执行反馈行（sessionId → 有序卡态列表；seq 升序＝消息列表
     * 插入序）。commandId 配对原位更新由 [CommandFeedbackFolder] 纯函数承担
     * （run 建卡、done 同卡终态化）；durable——历史重放同路径折叠，SessionDeleted
     * 级联清（退出会话不清，#252 shell 卡同款裁决：转录可见性跨进入退出保持）。
     */
    private val _commandFeedback = MutableStateFlow<Map<String, List<CommandFeedback>>>(emptyMap())
    val commandFeedback: StateFlow<Map<String, List<CommandFeedback>>> = _commandFeedback.asStateFlow()

    /** REST hydrate（进会话补首屏 todo，2026-08-20）；与 SSE 路径同型幂等覆盖。 */
    fun setTodos(sessionId: String, todos: List<SseEvent.TodoUpdated.Todo>) {
        _todos.update { it + (sessionId to todos) }
    }

    /**
     * #365：本地受理占位（受理即知）——commands/execute 受理成功即追加，
     * 不等服务器事件；command/run 到达后由 Folder.onRun 同名占位原位升级。
     */
    fun recordLocalAcceptance(sessionId: String, name: String, args: String?) {
        _commandFeedback.update { all ->
            all + (sessionId to CommandFeedbackFolder.onLocalAcceptance(
                all[sessionId].orEmpty(), name, args, System.currentTimeMillis(),
            ))
        }
    }

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.TodoUpdated -> { _todos.update { it + (event.sessionId to event.todos) }; true }
            is SseEvent.CommandsChanged -> { _commandsChanged.tryEmit(Unit); true } // #285：全局注册表通知
            // #323：command/run|done → 反馈行卡态（配对纯函数折叠，本类只做容器写）
            is SseEvent.CommandRunStarted -> {
                _commandFeedback.update { all ->
                    all + (event.sessionId to CommandFeedbackFolder.onRun(all[event.sessionId].orEmpty(), event))
                }
                true
            }
            is SseEvent.CommandDone -> {
                _commandFeedback.update { all ->
                    all + (event.sessionId to CommandFeedbackFolder.onDone(all[event.sessionId].orEmpty(), event))
                }
                true
            }
            is SseEvent.PtyCreated -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "PTY created: ${event.id}"); true }
            is SseEvent.PtyUpdated -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "PTY updated: ${event.id}"); true }
            is SseEvent.PtyDeleted -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "PTY deleted: ${event.id}"); true }
            is SseEvent.WorkspaceReady -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "Workspace ready: ${event.workspaceId}"); true }
            is SseEvent.WorkspaceFailed -> { AppLogger.w(TAG, "Workspace failed: ${event.workspaceId}"); true }
            is SseEvent.FileEdited -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "File edited: ${event.path}"); true }
            is SseEvent.McpToolsChanged -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "MCP tools changed: ${event.server}"); true }
            is SseEvent.CommandExecuted -> {
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Command executed: ${event.name}")
                // 注意：会话状态重置为 Idle 由 EventDispatcher 处理（跨 handler 关注点）
                true
            }
            is SseEvent.FileWatcherUpdated -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "File watcher updated: ${event.path}"); true }
            is SseEvent.InstallationUpdated -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "Installation updated: ${event.version}"); true }
            is SseEvent.InstallationUpdateAvailable -> { AppLogger.i(TAG, "Update available: ${event.version}"); true }
            is SseEvent.WorktreeReady -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "Worktree ready: ${event.path}"); true }
            is SseEvent.WorktreeFailed -> { AppLogger.w(TAG, "Worktree failed: ${event.path}"); true }
            is SseEvent.LspUpdated -> { /* 移动端不需要 LSP 事件 */ true }
            else -> false
        }
    }

    fun clearForSession(sessionId: String) {
        _todos.update { it - sessionId }
        _commandFeedback.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _todos.update { it - sessionIds }
        _commandFeedback.update { it - sessionIds }
    }

    fun clearAll() {
        _todos.value = emptyMap()
        _commandFeedback.value = emptyMap()
    }
}
