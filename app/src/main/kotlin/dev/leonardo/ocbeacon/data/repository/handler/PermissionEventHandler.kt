package dev.leonardo.ocbeacon.data.repository.handler

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles permission events: asked, replied.
 * Manages: permissions
 */
@Singleton
class PermissionEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "PermissionEventHandler"
    }

    private val _permissions = MutableStateFlow<Map<String, List<SseEvent.PermissionAsked>>>(emptyMap())
    val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> = _permissions.asStateFlow()

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.PermissionAsked -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Permission event received: PermissionAsked(id=${event.id}, sessionId=${event.sessionId})")
                handlePermissionAsked(event)
                true
            }
            is SseEvent.PermissionReplied -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Permission event received: PermissionReplied(requestId=${event.requestId}, sessionId=${event.sessionId})")
                handlePermissionReplied(event)
                true
            }
            else -> false
        }
    }

    private fun handlePermissionAsked(event: SseEvent.PermissionAsked) {
        Log.i(TAG, "Permission auto-approved: id=${event.id}, permission=${event.permission}, sessionId=${event.sessionId}")
        _permissions.update { current ->
            val sessionPerms = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            if (sessionPerms.any { it.id == event.id }) {
                current // already exists, skip duplicate
            } else {
                sessionPerms.add(event)
                current + (event.sessionId to sessionPerms)
            }
        }
    }

    private fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
        Log.w(TAG, "Permission auto-denied: requestId=${event.requestId}, sessionId=${event.sessionId}")
        _permissions.update { current ->
            val sessionPerms = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionPerms != null) current + (event.sessionId to sessionPerms) else current
        }
    }

    fun removePermission(permissionId: String) {
        _permissions.update { current ->
            current.mapValues { (_, perms) -> perms.filter { it.id != permissionId } }
        }
    }

    fun setPermissions(sessionId: String, perms: List<SseEvent.PermissionAsked>) {
        _permissions.update { current ->
            if (perms.isEmpty()) current - sessionId else current + (sessionId to perms)
        }
    }

    fun clearForSession(sessionId: String) {
        _permissions.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _permissions.update { it - sessionIds }
    }

    fun clearAll() {
        _permissions.value = emptyMap()
    }

    /**
     * Get all pending permissions for a session, including permissions from child sessions.
     * This enables the parent session UI to display sub-agent permission requests.
     * Child session permissions are annotated with [SseEvent.PermissionAsked.sourceSessionTitle].
     */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.PermissionAsked> {
        val currentPerms = _permissions.value[sessionId] ?: emptyList()

        // Find child sessions (sessions whose parentId == sessionId)
        val childSessionIds = sessions
            .filter { it.parentId == sessionId }
            .map { it.id }
            .toSet()

        // Aggregate permissions from all child sessions, annotating with source title
        val childPerms = _permissions.value
            .filterKeys { it in childSessionIds }
            .entries
            .flatMap { (childId, perms) ->
                val childTitle = sessions.find { it.id == childId }?.title
                perms.map { perm ->
                    perm.copy(sourceSessionTitle = childTitle)
                }
            }

        return currentPerms + childPerms
    }
}
