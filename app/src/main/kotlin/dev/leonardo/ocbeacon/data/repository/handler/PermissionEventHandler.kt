package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.logging.AppLogger

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
 * 处理权限事件：asked、replied。
 * 管理：permissions
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
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Permission event received: PermissionAsked(id=${event.id}, sessionId=${event.sessionId})")
                handlePermissionAsked(event)
                true
            }
            is SseEvent.PermissionReplied -> {
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Permission event received: PermissionReplied(requestId=${event.requestId}, sessionId=${event.sessionId})")
                handlePermissionReplied(event)
                true
            }
            else -> false
        }
    }

    private fun handlePermissionAsked(event: SseEvent.PermissionAsked) {
        // #136（D2-L53）：PermissionAsked 是待用户决定的 pending 请求，非"auto-approved"；
        // 正常流程事件降为 DEBUG（release 不再刷 INFO 日志）
        AppLogger.d(TAG, "Permission requested (pending): id=${event.id}, permission=${event.permission}, sessionId=${event.sessionId}")
        _permissions.update { current ->
            val sessionPerms = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            if (sessionPerms.any { it.id == event.id }) {
                current // 已存在，跳过重复
            } else {
                sessionPerms.add(event)
                current + (event.sessionId to sessionPerms)
            }
        }
    }

    private fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
        // #136（D2-L53）：PermissionReplied 表示请求已处理（从 pending 移除），非"auto-denied"；
        // 正常流程事件降为 DEBUG（release 不再刷 WARN 日志）
        AppLogger.d(TAG, "Permission resolved (removed from pending): requestId=${event.requestId}, sessionId=${event.sessionId}")
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
        // 仅由 SessionDeleted 级联调用。会话退出（releaseSessionData）不调用——
        // pending permissions 是服务器状态（2026-08-14 与 QuestionEventHandler 同批修复）。
        _permissions.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _permissions.update { it - sessionIds }
    }

    fun clearAll() {
        _permissions.value = emptyMap()
    }

    /**
     * 获取某会话的所有待处理权限，包括来自子会话的权限。
     * 这使父会话 UI 能显示子代理的权限请求。
     * 子会话权限用 [SseEvent.PermissionAsked.sourceSessionTitle] 标注。
     */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.PermissionAsked> {
        val currentPerms = _permissions.value[sessionId] ?: emptyList()

        // 查找子会话（parentId == sessionId 的会话）
        val childSessionIds = sessions
            .filter { it.parentId == sessionId }
            .map { it.id }
            .toSet()

        // 聚合所有子会话的权限，并用来源标题标注
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
