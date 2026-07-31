package dev.leonardo.octether.data.repository.handler

import android.util.Log
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles session lifecycle events: created, updated, deleted, diff, error, compacted.
 * Manages: sessions, serverSessions, sessionDiffs, vcsBranch, projectInfo
 *
 * Session STATUS is no longer tracked here — [dev.leonardo.octether.data.repository.SessionStateService]
 * is the single source of truth. SessionStatus/SessionIdle events are acknowledged here
 * (so the dispatcher's registry routes them) but the actual FSM transition happens in
 * [dev.leonardo.octether.data.repository.EventDispatcher.forwardToSessionStateService].
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

    /** Tracks the timestamp of the last user message per session for stable sort ordering. */
    private val _lastUserMessageTime = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastUserMessageTime: StateFlow<Map<String, Long>> = _lastUserMessageTime.asStateFlow()

    fun recordUserMessage(sessionId: String, time: Long) {
        _lastUserMessageTime.update { it + (sessionId to time) }
    }

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.ServerConnected -> { if (BuildConfig.DEBUG) Log.d(TAG, "Server connected"); true }
            is SseEvent.ServerHeartbeat -> true
            is SseEvent.ServerInstanceDisposed -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Server instance disposed: ${event.directory}"); true
            }
            is SseEvent.SessionCreated -> { handleSessionCreated(event, serverId); true }
            is SseEvent.SessionUpdated -> { handleSessionUpdated(event, serverId); true }
            is SseEvent.SessionDeleted -> { handleSessionDeleted(event); true }
            // Status/idle events are acknowledged here but processed by SessionStateService
            // via EventDispatcher.forwardToSessionStateService — no local state to update.
            is SseEvent.SessionStatus -> true
            is SseEvent.SessionIdle -> true
            is SseEvent.SessionDiff -> { handleSessionDiff(event); true }
            is SseEvent.SessionError -> { handleSessionError(event); true }
            is SseEvent.SessionCompacted -> {
                Log.i(TAG, "Session ${event.sessionId} compacted"); true
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
        Log.i(TAG, "SessionUpdated: id=${event.info.id} title=${event.info.title}")
        trackSession(serverId, event.info.id)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                // Don't let stale SSE restore a revert we just cleared locally.
                // The server will eventually send revert=null after processing
                // our new message, at which point we accept and clear the flag.
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
        Log.e(TAG, "Session ${event.sessionId} error: ${event.error}")
    }

    // ============ Batch Operations ============

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
        if (BuildConfig.DEBUG) Log.d(TAG, "Clearing state for server $serverId (${sessionIds.size} sessions)")
        _serverSessions.update { it - serverId }
        _sessions.update { it.filter { s -> s.id !in sessionIds } }
        _sessionDiffs.update { it - sessionIds }
        _lastUserMessageTime.update { it - sessionIds }
    }

    /**
     * Clear the revert state for a session.
     * Called when the user sends a new message after revert — the server
     * consumes the revert but may not send a `session.updated` SSE event
     * to notify the client. This ensures the message list filter stops
     * hiding new messages.
     */
    /**
     * Sessions whose revert was locally cleared (user sent a new message).
     * Prevents stale SessionUpdated SSE events from restoring the revert
     * before the server confirms it's cleared.
     */
    private val locallyClearedReverts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun clearRevert(sessionId: String) {
        locallyClearedReverts.add(sessionId)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == sessionId }
            if (idx >= 0 && current[idx].revert != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Clearing revert for session $sessionId")
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Setting revert for session $sessionId msg=$messageId")
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
