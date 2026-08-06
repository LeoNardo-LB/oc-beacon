package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处理会话生命周期事件：created、updated、deleted、diff、error、compacted。
 * 管理：sessions、serverSessions、sessionDiffs、vcsBranch、projectInfo
 *
 * 会话 STATUS 不再在此跟踪——[dev.leonardo.ocbeacon.data.repository.SessionStateService]
 * 是单一真相源。SessionStatus/SessionIdle 事件在此被确认
 *（以便 dispatcher 的注册表路由它们），但实际的 FSM 转移发生在
 * [dev.leonardo.ocbeacon.data.repository.EventDispatcher.forwardToSessionStateService]。
 */
@Singleton
class SessionEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "SessionEventHandler"
    }

    private val _serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val serverSessions: StateFlow<Map<String, Set<String>>> = _serverSessions.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _sessionDiffs = MutableStateFlow<Map<String, List<FileDiff>>>(emptyMap())
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> = _sessionDiffs.asStateFlow()

    private val _vcsBranch = MutableStateFlow<String?>(null)
    val vcsBranch: StateFlow<String?> = _vcsBranch.asStateFlow()

    private val _projectInfo = MutableStateFlow<Project?>(null)
    val projectInfo: StateFlow<Project?> = _projectInfo.asStateFlow()

    /** 跟踪每个会话最后一条用户消息的时间戳，用于稳定排序。 */
    private val _lastUserMessageTime = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastUserMessageTime: StateFlow<Map<String, Long>> = _lastUserMessageTime.asStateFlow()

    fun recordUserMessage(sessionId: String, time: Long) {
        _lastUserMessageTime.update { it + (sessionId to time) }
    }

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.ServerConnected -> { if (BuildConfig.DEBUG) AppLogger.d(TAG, "Server connected"); true }
            is SseEvent.ServerHeartbeat -> true
            is SseEvent.ServerInstanceDisposed -> {
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Server instance disposed: ${event.directory}"); true
            }
            is SseEvent.SessionCreated -> { handleSessionCreated(event, serverId); true }
            is SseEvent.SessionUpdated -> { handleSessionUpdated(event, serverId); true }
            is SseEvent.SessionDeleted -> { handleSessionDeleted(event); true }
            // 状态/idle 事件在此确认，但由 SessionStateService 经由
            // EventDispatcher.forwardToSessionStateService 处理——无本地状态需更新。
            is SseEvent.SessionStatus -> true
            is SseEvent.SessionIdle -> true
            is SseEvent.SessionDiff -> { handleSessionDiff(event); true }
            is SseEvent.SessionError -> { handleSessionError(event); true }
            is SseEvent.SessionCompacted -> {
                AppLogger.i(TAG, "Session ${event.sessionId} compacted"); true
            }
            is SseEvent.VcsBranchUpdated -> { _vcsBranch.value = event.branch; true }
            is SseEvent.ProjectUpdated -> { _projectInfo.value = event.info; true }
            else -> false
        }
    }

    private fun trackSession(serverId: String, sessionId: String) {
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionId))
        }
    }

    private fun handleSessionCreated(event: SseEvent.SessionCreated, serverId: String) {
        trackSession(serverId, event.info.id)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                current.toMutableList().apply { set(idx, event.info) }
            } else {
                (current + event.info).sortedByDescending { s -> s.time.updated }
            }
        }
    }

    private fun handleSessionUpdated(event: SseEvent.SessionUpdated, serverId: String) {
        AppLogger.i(TAG, "SessionUpdated: id=${event.info.id} title=${event.info.title}")
        trackSession(serverId, event.info.id)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                // 不要让陈旧的 SSE 恢复我们刚在本地清除的 revert。
                // 服务器在处理我们的新消息后最终会发送 revert=null，
                // 此时我们接受并清除该标志。
                val merged = if (event.info.id in locallyClearedReverts && event.info.revert != null) {
                    event.info.copy(revert = null)
                } else {
                    if (event.info.revert == null) locallyClearedReverts.remove(event.info.id)
                    event.info
                }
                current.toMutableList().apply { set(idx, merged) }
            } else {
                (current + event.info).sortedByDescending { s -> s.time.updated }
            }
        }
    }

    private fun handleSessionDeleted(event: SseEvent.SessionDeleted) {
        val sessionId = event.info.id
        _sessions.update { it.filter { s -> s.id != sessionId } }
        _sessionDiffs.update { it - sessionId }
    }

    private fun handleSessionDiff(event: SseEvent.SessionDiff) {
        _sessionDiffs.update { it + (event.sessionId to event.diff) }
    }

    private fun handleSessionError(event: SseEvent.SessionError) {
        AppLogger.e(TAG, "Session ${event.sessionId} error: ${event.error}")
    }

    // ============ 批量操作 ============

    fun setSessions(serverId: String, newSessions: List<Session>) {
        val sessionIds = newSessions.map { it.id }.toSet()
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionIds))
        }
        _sessions.update { current ->
            val updated = current.toMutableList()
            for (session in newSessions) {
                val idx = updated.indexOfFirst { it.id == session.id }
                if (idx >= 0) {
                    updated[idx] = session
                } else {
                    updated.add(session)
                }
            }
            updated.sortedByDescending { it.time.updated }
        }
    }

    fun clearForServer(serverId: String) {
        val sessionIds = _serverSessions.value[serverId] ?: emptySet()
        if (sessionIds.isEmpty()) {
            _serverSessions.update { it - serverId }
            return
        }
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Clearing state for server $serverId (${sessionIds.size} sessions)")
        _serverSessions.update { it - serverId }
        _sessions.update { it.filter { s -> s.id !in sessionIds } }
        _sessionDiffs.update { it - sessionIds }
        _lastUserMessageTime.update { it - sessionIds }
    }

    /**
     * 清除会话的 revert 状态。
     * 在用户 revert 后发送新消息时调用——服务器会消费 revert，
     * 但可能不会发送 `session.updated` SSE 事件通知客户端。
     * 这确保消息列表过滤器不再隐藏新消息。
     */
    /**
     * 本地已清除 revert 的会话（用户发送了新消息）。
     * 防止陈旧的 SessionUpdated SSE 事件在服务器确认已清除前
     * 恢复 revert。
     */
    private val locallyClearedReverts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun clearRevert(sessionId: String) {
        locallyClearedReverts.add(sessionId)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == sessionId }
            if (idx >= 0 && current[idx].revert != null) {
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Clearing revert for session $sessionId")
                current.toMutableList().apply { set(idx, current[idx].copy(revert = null)) }
            } else {
                current
            }
        }
    }

    fun setRevert(sessionId: String, messageId: String) {
        locallyClearedReverts.remove(sessionId)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Setting revert for session $sessionId msg=$messageId")
                current.toMutableList().apply {
                    set(idx, current[idx].copy(revert = Session.Revert(messageId = messageId)))
                }
            } else {
                current
            }
        }
    }

    fun clearAll() {
        _serverSessions.value = emptyMap()
        _sessions.value = emptyList()
        _sessionDiffs.value = emptyMap()
        _lastUserMessageTime.value = emptyMap()
        _vcsBranch.value = null
        _projectInfo.value = null
    }
}
