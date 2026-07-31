package dev.leonardo.octether.data.repository

import android.util.Log
import dev.leonardo.octether.data.api.message.MessageApi
import dev.leonardo.octether.data.api.session.SessionApi
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.domain.model.CreateSessionOpts
import dev.leonardo.octether.domain.model.MessageWithParts
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.model.SessionStatus
import dev.leonardo.octether.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SessionRepository].
 * Bridges domain interface with EventDispatcher (state) and domain APIs (network).
 *
 * Phase 3: compiled but not yet wired to UseCases. Phase 4 will migrate
 * ViewModel direct calls to go through this repository.
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val sessionApi: SessionApi,
    private val messageApi: MessageApi,
    private val eventDispatcher: EventDispatcher,
    private val serverRepo: ServerDataStore
) : SessionRepository {

    // ============ State Observations ============

    override fun getSessionsFlow(serverId: String): Flow<List<Session>> {
        // Combine server→session mapping with the global sessions list so that
        // changes to either trigger a re-emission.
        return combine(
            eventDispatcher.serverSessions,
            eventDispatcher.sessions
        ) { mapping, allSessions ->
            val sessionIds = mapping[serverId] ?: emptySet()
            if (sessionIds.isEmpty()) emptyList()
            else allSessions.filter { it.id in sessionIds }
        }
            .catch { e ->
                Log.e("SessionRepository", "Error in getSessionsFlow", e)
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
                Log.e("SessionRepository", "Error in getSessionStatusesFlow", e)
                emit(emptyMap())
            }
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
        // Switching is a UI/navigation concern — no server-side API call needed.
        // The session's data is already tracked by EventDispatcher.
        Unit
    }

    override suspend fun getSession(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.getSession(conn, sessionId)
    }

    // ============ Session Lifecycle ============

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

    // ============ Archive ============

    override suspend fun archive(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.updateSessionFields(conn, sessionId, mapOf("archived" to true))
    }

    override suspend fun unarchive(serverId: String, sessionId: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.updateSessionFields(conn, sessionId, mapOf("archived" to false))
    }

    // ============ Share / Export ============

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

    // ============ Import ============

    override suspend fun importSession(serverId: String, shareUrl: String): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.importSession(conn, shareUrl)
    }

    // ============ Message Operations ============

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
        limit: Int
    ): Result<List<MessageWithParts>> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.listMessages(conn, sessionId, limit)
    }

    // ============ Private Helpers ============

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }

    // ============ Current Agent/Model (SSE session.next) ============

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

    // ============ Write Operations (State Updates) ============

    override fun setSessions(serverId: String, sessions: List<Session>) {
        eventDispatcher.setSessions(serverId, sessions)
    }

    // ============ Session Status Sync ============

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
