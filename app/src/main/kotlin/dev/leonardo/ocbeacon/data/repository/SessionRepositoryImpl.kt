package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.CreateSessionOpts
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SessionRepository] 的实现。
 * 桥接领域接口与 EventDispatcher（状态）和领域 API（网络）。
 *
 * 阶段 3：已编译但尚未接入 UseCase。阶段 4 将把 ViewModel 的
 * 直接调用迁移为通过此 repository。
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val sessionApi: SessionApi,
    private val messageApi: MessageApi,
    private val eventDispatcher: EventDispatcher,
    private val serverRepo: ServerDataStore,
) : SessionRepository {

    // ============ 状态观察 ============

    override fun getSessionsFlow(serverId: String): Flow<List<Session>> {
        // 将 服务器→会话 映射与全局会话列表合并，使任一变更
        // 都触发重新发射。
        return combine(
            eventDispatcher.serverSessions,
            eventDispatcher.sessions
        ) { mapping, allSessions ->
            val sessionIds = mapping[serverId] ?: emptySet()
            if (sessionIds.isEmpty()) emptyList()
            else allSessions.filter { it.id in sessionIds }
        }
            .catch { e ->
                AppLogger.e("SessionRepository", "Error in getSessionsFlow", e)
                emit(emptyList())
            }
    }

    override fun getSessionStatusesFlow(serverId: String): Flow<Map<String, SessionStatus>> {
        return combine(
            eventDispatcher.serverSessions,
            eventDispatcher.sessionStatuses
        ) { mapping, statuses ->
            val sessionIds = mapping[serverId] ?: emptySet()
            statuses.filterKeys { it in sessionIds }
        }
            .catch { e ->
                AppLogger.e("SessionRepository", "Error in getSessionStatusesFlow", e)
                emit(emptyMap())
            }
    }

    override fun getServerSessionsFlow(): Flow<Map<String, Set<String>>> =
        eventDispatcher.serverSessions

    override fun getLastUserMessageTimeFlow(): Flow<Map<String, Long>> =
        eventDispatcher.lastUserMessageTime

    override fun getLastCompletedReplyTimeFlow(): Flow<Map<String, Long>> =
        eventDispatcher.lastCompletedReplyTime

    override suspend fun listSessions(
        serverId: String,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int
    ): List<Session> {
        val conn = resolveConnection(serverId)
        return sessionApi.listSessions(
            conn = conn,
            directory = directory,
            search = search,
            cursor = cursor,
            limit = limit
        )
    }

    // ============ CRUD ============

    override suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.createSession(
            conn = conn,
            title = opts.title,
            parentId = opts.parentId,
            directory = opts.directory
        )
    }

    override suspend fun deleteSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.deleteSession(conn, sessionId)
    }

    override suspend fun switchSession(sessionId: String): Result<Unit> = runCatching {
        // 切换是 UI/导航层面的关注点——无需服务端 API 调用。
        // 会话数据已由 EventDispatcher 跟踪。
        Unit
    }

    override suspend fun getSession(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.getSession(conn, sessionId)
    }

    // ============ 会话生命周期 ============

    override suspend fun abort(serverId: String, sessionId: String, directory: String?): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.abortSession(conn, sessionId, directory)
    }

    override suspend fun rename(serverId: String, sessionId: String, title: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.updateSession(conn, sessionId, title)
    }

    override suspend fun fork(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.forkSession(conn, sessionId)
    }

    // ============ 归档 ============

    override suspend fun archive(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.updateSessionFields(conn, sessionId, mapOf("archived" to true))
    }

    override suspend fun unarchive(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.updateSessionFields(conn, sessionId, mapOf("archived" to false))
    }

    // ============ 分享 / 导出 ============

    override suspend fun shareSession(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.shareSession(conn, sessionId)
    }

    override suspend fun unshareSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.unshareSession(conn, sessionId)
    }

    override suspend fun compactSession(
        serverId: String,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.summarizeSession(conn, sessionId, providerId, modelId)
    }

    override suspend fun exportSessionToStream(
        serverId: String,
        sessionId: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.exportSessionToStream(conn, sessionId, outputStream, onProgress)
    }

    // ============ 导入 ============

    override suspend fun importSession(serverId: String, shareUrl: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.importSession(conn, shareUrl)
    }

    // ============ 消息操作 ============

    override suspend fun deleteMessage(
        serverId: String,
        sessionId: String,
        messageId: String
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.deleteMessage(conn, sessionId, messageId)
    }

    override suspend fun deleteMessagePart(
        serverId: String,
        sessionId: String,
        messageId: String,
        partIndex: Int
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.deleteMessagePart(conn, sessionId, messageId, partIndex)
    }

    override suspend fun listMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String?,
    ): Result<MessagePage> = runCatching {
        val conn = resolveConnection(serverId)
        if (BuildConfig.DEBUG) AppLogger.d("NetTrace", "listMessages REQUEST server=$serverId sid=${sessionId.take(12)} limit=$limit before=${before?.take(16)}")
        messageApi.listMessages(conn, sessionId, limit, before).also {
            if (BuildConfig.DEBUG) AppLogger.d("NetTrace", "listMessages RESPONSE server=$serverId sid=${sessionId.take(12)} msgs=${it.messages.size} (limit=$limit)")
        }
    }

    // ============ 私有辅助方法 ============

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }

    // ============ 当前 Agent/Model（SSE session.next）============

    override fun getCurrentAgentFlow(serverId: String): Flow<Map<String, String>> =
        combine(
            eventDispatcher.serverSessions,
            eventDispatcher.currentAgent
        ) { mapping, agentMap ->
            val sessionIds = mapping[serverId] ?: emptySet()
            agentMap.filterKeys { it in sessionIds }
        }

    override fun getCurrentModelFlow(serverId: String): Flow<Map<String, Pair<String, String>>> =
        combine(
            eventDispatcher.serverSessions,
            eventDispatcher.currentModel
        ) { mapping, modelMap ->
            val sessionIds = mapping[serverId] ?: emptySet()
            modelMap.filterKeys { it in sessionIds }
        }

    // ============ 写操作（状态更新）============

    override fun setSessions(serverId: String, sessions: List<Session>) {
        eventDispatcher.setSessions(serverId, sessions)
    }

    // ============ 会话状态同步 ============

    override suspend fun fetchSessionStatuses(serverId: String, directory: String?): Result<Map<String, SessionStatus>> = runCatching {
        val conn = resolveConnection(serverId)
        val rawStatuses = sessionApi.fetchSessionStatus(conn, directory = directory).getOrThrow()
        rawStatuses.mapValues { (_, info) ->
            when (info.type) {
                "busy" -> SessionStatus.Busy
                "retry" -> SessionStatus.Retry(
                    attempt = info.attempt ?: 0,
                    message = info.message ?: "",
                    next = info.next ?: 0L
                )
                else -> SessionStatus.Idle
            }
        }
    }
}
