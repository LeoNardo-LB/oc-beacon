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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

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
    // 堆积消息级联删除（2026-08-20）：deleteSession 成功后清空该会话队列
    private val pendingMessageRepository: dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository,
) : SessionRepository {

    // ============ listMessages 在途去重（#91，2026-08-18） ============
    // 会话进入瞬间多链并发拉同一窗口（初始加载 / SSE 重连 backfill / reconcile /
    // L3 校验），实测同 (sessionId, cursor) 精确重复请求成对出现（22ms 内 8 次、
    // 20s 内 30 次）。GET 幂等——相同参数的并发调用共享同一在途结果，完成后
    // 立即移除（不缓存：消息流是活数据，缓存会引入陈旧窗口）。
    private val inFlightListMessages =
        ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Result<MessagePage>>>()

    private suspend fun listMessagesDeduped(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String?,
    ): Result<MessagePage> {
        val key = "$serverId|$sessionId|$limit|" + (before ?: "-")
        while (true) {
            val existing = inFlightListMessages[key]
            if (existing != null) return existing.await()
            val deferred = kotlinx.coroutines.CompletableDeferred<Result<MessagePage>>()
            val winner = inFlightListMessages.putIfAbsent(key, deferred)
            if (winner != null) return winner.await()
            try {
                val result = listMessagesDirect(serverId, sessionId, limit, before)
                deferred.complete(result)
                return result
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
                throw t
            } finally {
                inFlightListMessages.remove(key, deferred)
            }
        }
    }

    private suspend fun listMessagesDirect(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String?,
    ): Result<MessagePage> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        if (BuildConfig.DEBUG) AppLogger.d("NetTrace", "listMessages REQUEST server=$serverId sid=${sessionId.take(12)} limit=$limit before=${before?.take(16)}")
        messageApi.listMessages(conn, sessionId, limit, before).also {
            if (BuildConfig.DEBUG) AppLogger.d("NetTrace", "listMessages RESPONSE server=$serverId sid=${sessionId.take(12)} msgs=${it.messages.size} (limit=$limit)")
        }
    }

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

    override suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.createSession(
            conn = conn,
            title = opts.title,
            parentId = opts.parentId,
            directory = opts.directory
        )
    }

    override suspend fun getSessionTodos(
        serverId: String,
        sessionId: String,
    ): Result<List<dev.leonardo.ocbeacon.domain.model.SseEvent.TodoUpdated.Todo>> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.getSessionTodos(conn, sessionId).map { item ->
            dev.leonardo.ocbeacon.domain.model.SseEvent.TodoUpdated.Todo(
                content = item.content,
                status = item.status,
                priority = item.priority,
            )
        }
    }.onSuccess { todos ->
        // hydrate 回填（SSE todo.updated 后续增量覆盖，同型幂等）
        eventDispatcher.hydrateTodos(sessionId, todos)
    }

    override suspend fun deleteSession(serverId: String, sessionId: String): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.deleteSession(conn, sessionId)
        // 堆积消息级联删除（2026-08-20）：REST 删除成功即清队列（SSE
        // SessionDeleted 路径也有兜底，双保险幂等）
        runCatching { pendingMessageRepository.deleteForSession(sessionId) }
    }

    override suspend fun getSession(serverId: String, sessionId: String): Result<Session> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.getSession(conn, sessionId)
    }

    // ============ 会话生命周期 ============

    override suspend fun abort(serverId: String, sessionId: String, directory: String?): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.abortSession(conn, sessionId, directory)
    }

    override suspend fun rename(serverId: String, sessionId: String, title: String): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.updateSession(conn, sessionId, title)
    }

    override suspend fun fork(serverId: String, sessionId: String): Result<Session> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.forkSession(conn, sessionId)
    }

    // ============ 归档 ============

    override suspend fun archive(serverId: String, sessionId: String): Result<Session> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.updateSessionFields(conn, sessionId, mapOf("archived" to true))
    }

    override suspend fun unarchive(serverId: String, sessionId: String): Result<Session> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.updateSessionFields(conn, sessionId, mapOf("archived" to false))
    }

    // ============ 分享 / 导出 ============

    override suspend fun shareSession(serverId: String, sessionId: String): Result<Session> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.shareSession(conn, sessionId)
    }

    override suspend fun unshareSession(serverId: String, sessionId: String): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.unshareSession(conn, sessionId)
    }

    override suspend fun compactSession(
        serverId: String,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.summarizeSession(conn, sessionId, providerId, modelId)
    }

    override suspend fun exportSessionToStream(
        serverId: String,
        sessionId: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        messageApi.exportSessionToStream(conn, sessionId, outputStream, onProgress)
    }

    // ============ 导入 ============

    override suspend fun importSession(serverId: String, shareUrl: String): Result<Session> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.importSession(conn, shareUrl)
    }

    // ============ 消息操作 ============

    override suspend fun deleteMessage(
        serverId: String,
        sessionId: String,
        messageId: String
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        messageApi.deleteMessage(conn, sessionId, messageId)
    }

    override suspend fun deleteMessagePart(
        serverId: String,
        sessionId: String,
        messageId: String,
        partIndex: Int
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        messageApi.deleteMessagePart(conn, sessionId, messageId, partIndex)
    }

    override suspend fun listMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String?,
    ): Result<MessagePage> = listMessagesDeduped(serverId, sessionId, limit, before)

    override suspend fun getMessage(
        serverId: String,
        sessionId: String,
        messageId: String,
    ): Result<dev.leonardo.ocbeacon.domain.model.MessageWithParts> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        messageApi.getMessage(conn, sessionId, messageId)
    }

    override suspend fun getApiVersion(serverId: String): dev.leonardo.ocbeacon.domain.model.ApiVersion =
        serverRepo.getServer(serverId)?.apiVersion
            ?: dev.leonardo.ocbeacon.domain.model.ApiVersion.UNKNOWN

    // ============ 私有辅助方法 ============

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password, config.apiVersion)
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

    override suspend fun fetchSessionStatuses(serverId: String, directory: String?): Result<Map<String, SessionStatus>> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        val rawStatuses = sessionApi.fetchSessionStatus(conn, directory = directory).getOrThrow()
        // 2026-08-16（状态误杀修复）：未知 type 不再翻译成 Idle——V2ApiClient
        // 已把 running/busy 归一化为 "busy"，此处未知值意味着服务器出现了新枚举
        //（backlog #70 type 完整枚举未确认），语义未知时跳过该条目（走「缺失」
        // 路径，由 SessionStateService 的新鲜度护栏保护），绝不把「服务器说活跃」
        // 翻译成 Idle。明确 "idle"（V1）保持 Idle 映射。
        rawStatuses.mapNotNull { (sid, info) ->
            val status = when (info.type) {
                "busy" -> SessionStatus.Busy
                "retry" -> SessionStatus.Retry(
                    attempt = info.attempt ?: 0,
                    message = info.message ?: "",
                    next = info.next ?: 0L
                )
                "idle" -> SessionStatus.Idle
                else -> {
                    AppLogger.w("SessionRepository", "fetchSessionStatuses: unknown type='${info.type}' for $sid, skipping (not mapping to Idle)")
                    null
                }
            }
            if (status != null) sid to status else null
        }.toMap()
    }
}
