package dev.leonardo.octether.domain.repository

import dev.leonardo.octether.domain.model.CreateSessionOpts
import dev.leonardo.octether.domain.model.MessageWithParts
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * Repository interface for session management.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface SessionRepository {

    // ============ State Observations ============

    /**
     * Observe sessions for a specific server connection.
     * Phase 3 impl: delegates to EventDispatcher.sessions filtered by serverId.
     */
    fun getSessionsFlow(serverId: String): Flow<List<Session>>

    /**
     * Observe session statuses for a specific server connection.
     * Delegates to EventDispatcher.sessionStatuses.
     */
    fun getSessionStatusesFlow(serverId: String): Flow<Map<String, SessionStatus>>

    // ============ CRUD ============

    /**
     * Create a new session on the specified server with the given options.
     * Returns the created [Session] on success.
     */
    suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session>

    /**
     * Delete a session by its ID.
     */
    suspend fun deleteSession(serverId: String, sessionId: String): Result<Unit>

    /**
     * Switch the active session.
     * Phase 3 impl: delegates to SessionApi or connection service.
     */
    suspend fun switchSession(sessionId: String): Result<Unit>

    /**
     * Get a single session by ID.
     */
    suspend fun getSession(serverId: String, sessionId: String): Result<Session>

    // ============ Session Lifecycle ============

    /**
     * Abort a running session.
     */
    suspend fun abort(serverId: String, sessionId: String, directory: String?): Result<Unit>

    /**
     * Rename a session.
     */
    suspend fun rename(serverId: String, sessionId: String, title: String): Result<Unit>

    /**
     * Fork a session, creating a new session from a message point.
     */
    suspend fun fork(serverId: String, sessionId: String): Result<Session>

    // ============ Archive ============

    /**
     * Archive a session.
     */
    suspend fun archive(serverId: String, sessionId: String): Result<Session>

    /**
     * Unarchive a session.
     */
    suspend fun unarchive(serverId: String, sessionId: String): Result<Session>

    // ============ Share / Export ============

    /**
     * Share a session, creating a shareable link.
     */
    suspend fun shareSession(serverId: String, sessionId: String): Result<Session>

    /**
     * Unshare a session, removing the shareable link.
     */
    suspend fun unshareSession(serverId: String, sessionId: String): Result<Unit>

    /**
     * Summarize (compact) a session to reduce context.
     */
    suspend fun compactSession(serverId: String, sessionId: String, providerId: String, modelId: String): Result<Unit>

    /**
     * Stream session export JSON directly to an OutputStream.
     * @param onProgress called with bytes written so far.
     */
    suspend fun exportSessionToStream(
        serverId: String,
        sessionId: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ): Result<Unit>

    // ============ Import ============

    /**
     * Import a session from a share URL.
     */
    suspend fun importSession(serverId: String, shareUrl: String): Result<Session>

    // ============ Message Operations ============

    /**
     * Delete a message from a session.
     */
    suspend fun deleteMessage(serverId: String, sessionId: String, messageId: String): Result<Boolean>

    /**
     * Delete a specific part from a message by index.
     */
    suspend fun deleteMessagePart(serverId: String, sessionId: String, messageId: String, partIndex: Int): Result<Boolean>

    /**
     * List messages in a session.
     */
    suspend fun listMessages(serverId: String, sessionId: String, limit: Int): Result<List<MessageWithParts>>

    // ============ Current Agent/Model (SSE session.next) ============

    /**
     * Observe the current agent name map (sessionId → agent name) from SSE session.next events.
     */
    fun getCurrentAgentFlow(serverId: String): Flow<Map<String, String>>

    /**
     * Observe the current model map (sessionId → Pair(providerId, modelId)) from SSE session.next events.
     */
    fun getCurrentModelFlow(serverId: String): Flow<Map<String, Pair<String, String>>>

    // ============ Write Operations (State Updates) ============

    /**
     * Inject REST-loaded sessions into the state holder.
     * Used when REST fallback loads session info before SSE delivers it.
     */
    fun setSessions(serverId: String, sessions: List<Session>)

    // ============ Session Status Sync ============

    /**
     * Fetch all session statuses from the server via REST.
     * Used as a fallback when SSE events may have been missed.
     * @return Map of sessionId → [SessionStatus].
     */
    suspend fun fetchSessionStatuses(serverId: String, directory: String? = null): Result<Map<String, SessionStatus>>
}
