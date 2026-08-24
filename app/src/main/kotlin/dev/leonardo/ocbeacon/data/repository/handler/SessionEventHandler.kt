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

    /** 2026-08-15：已压缩会话 id 集合（SessionCompacted 事件累积）——UI 监听触发刷新。 */
    /** #217 R3 修复（2026-08-24）：Set → per-session 压缩计数。原 Set 判变在同会话
     * 第二次压缩时不发射 → ChatViewModel 刷新/通知双双跳过（真机 round 3 实证
     * 全程零 UI）。计数单调递增，每次 SessionCompacted 都触发下游。 */
    private val _compactedSessions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val compactedSessions: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _compactedSessions.asStateFlow()
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
                AppLogger.i(TAG, "Session ${event.sessionId} compacted")
                // 2026-08-15：通知 UI 刷新——压缩后服务器把历史替换为 compaction
                // 消息 + 摘要，不刷新的话压缩卡片要重进会话才显示（用户实测
                // "点击压缩无任何反馈"成因之一）。
                _compactedSessions.update {
                    it + (event.sessionId to ((it[event.sessionId] ?: 0L) + 1L))
                }
                true
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
        // #152：per-event INFO 补 DEBUG 门控（#40 残留漏网——SessionUpdated 高频触发）
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "SessionUpdated: id=${event.info.id} title=${event.info.title}")
        trackSession(serverId, event.info.id)
        // #134（D2-L54）：locallyClearedReverts.remove 是副作用——原实现位于
        // _sessions.update lambda 内，CAS 重试会重复执行。移出 lambda：
        // 服务器确认 revert=null 即清除标志（幂等操作，语义不变）。
        if (event.info.revert == null) locallyClearedReverts.remove(event.info.id)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                // 不要让陈旧的 SSE 恢复我们刚在本地清除的 revert。
                // 服务器在处理我们的新消息后最终会发送 revert=null，
                // 此时我们接受并清除该标志。
                val merged = if (event.info.id in locallyClearedReverts && event.info.revert != null) {
                    event.info.copy(revert = null)
                } else {
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
        // 2026-08-16（F6 泄漏清理）：_serverSessions（会话归属服务器映射）同步
        // 清理——原实现只清 _sessions/_sessionDiffs，删除的会话 id 永驻映射
        //（无害但随时间无限增长）。
        // 2026-08-25 修复（列表全空 bug·用户报告）：原 values.removeAll
        // { it.contains(sessionId) } 谓词作用于 Set 元素本身——只要服务器集合
        // 包含被删会话，**整台服务器的会话 id 集合被整体移除**（非仅删一个）→
        // 列表过滤 id in serverSessionIds 全空 → 任何 session.deleted SSE 后
        // 会话列表变 Empty directory（实测复现：删除任一会话即触发）。
        // 正确语义：从每个集合中移除该 id；顺手清理空集合（F6 防泄漏意图保留）。
        _serverSessions.update { map ->
            map.mapValues { (_, v) -> v - sessionId }
                .filterValues { it.isNotEmpty() }
        }
        // #96（L-2 泄漏补漏）：服务器确认删除的会话，per-session 缓存
        // 必须同步清理——原实现漏清 _lastUserMessageTime 与
        // locallyClearedReverts（#89 的 clearForSession 只在退出会话时调用，
        // 服务器 SessionDeleted 路径未接入）→ 删除会话后条目永久残留。
        _lastUserMessageTime.update { it - sessionId }
        locallyClearedReverts.remove(sessionId)
    }

    private fun handleSessionDiff(event: SseEvent.SessionDiff) {
        _sessionDiffs.update { it + (event.sessionId to event.diff) }
    }

    private fun handleSessionError(event: SseEvent.SessionError) {
        AppLogger.e(TAG, "Session ${event.sessionId} error: ${event.error}")
        // 2026-08-15（research/11 P1）：error 产生未读——对齐官方 Web
        //（notification.tsx:366-397：session.error 计入未读且 unseenHasError
        // 区分）。挂后台会话失败时用户有感知。sessionId 可空（协议防御）——
        // 空则跳过。
        event.sessionId?.let { sid -> onSessionError?.invoke(sid, event.error) }
    }

    /** 2026-08-15（research/11 P1）：error 未读回调（EventDispatcher 装配 → UnreadBadgeService）。 */
    @Volatile
    var onSessionError: ((sessionId: String, error: String) -> Unit)? = null

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
     * 释放单会话状态（内存泄漏修复 #89）：会话退出时由 EventDispatcher.releaseSessionData 调用。
     * 只清理 per-session 缓存（_sessionDiffs/_lastUserMessageTime/locallyClearedReverts）——
     * **不删除 _sessions/_serverSessions 中的会话元数据**（列表级共享数据，
     * 2026-08-14 修复：过度清理导致返回会话列表后 item 消失，刷新/重启才恢复）。
     * 会话元数据删除仅由服务器 SessionDeleted 事件（handleSessionDeleted）驱动。
     */
    /** 2026-08-15（research/11 P1）：session.next.moved——更新缓存会话的 directory
     *  （对齐官方 TUI sync.tsx:300-314 增量更新；分组随 sessionsFlow 重算）。 */
    fun updateSessionDirectory(sessionId: String, location: String, subdirectory: String?) {
        val newDir = buildString {
            append(location)
            if (!subdirectory.isNullOrEmpty()) {
                if (isNotEmpty() && !endsWith("/")) append("/")
                append(subdirectory)
            }
        }
        _sessions.update { current ->
            current.map { s ->
                if (s.id == sessionId && s.directory != newDir) s.copy(directory = newDir) else s
            }
        }
    }

    fun clearForSession(sessionId: String) {
        _sessionDiffs.update { it - sessionId }
        _lastUserMessageTime.update { it - sessionId }
        locallyClearedReverts.remove(sessionId)
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
