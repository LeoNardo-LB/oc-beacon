package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.CreateSessionOpts
import dev.leonardo.octether.domain.model.MessageWithParts
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.model.SessionStatus
import dev.leonardo.octether.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.OutputStream
import javax.inject.Singleton

@Singleton
class FakeSessionRepository @Inject constructor() : SessionRepository {

    val sessionsState = MutableStateFlow<List<Session>>(emptyList())
    val statusesState = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val currentAgentFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val currentModelFlow = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())

    var createSessionResult: Result<Session> = Result.success(
        Session(
            id = "new-session",
            title = "New Session",
            time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis())
        )
    )
    var deleteSessionResult: Result<Unit> = Result.success(Unit)
    var switchSessionResult: Result<Unit> = Result.success(Unit)
    var getSessionResult: Result<Session> = Result.success(
        Session(
            id = "test-session",
            title = "Test Session",
            time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis())
        )
    )
    var abortResult: Result<Unit> = Result.success(Unit)
    var renameResult: Result<Unit> = Result.success(Unit)
    var forkResult: Result<Session> = Result.success(
        Session(
            id = "forked-session",
            title = "Forked Session",
            time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis())
        )
    )
    var archiveResult: Result<Session> = Result.success(
        Session(
            id = "test-session",
            title = "Test Session",
            time = Session.Time(
                created = System.currentTimeMillis(),
                updated = System.currentTimeMillis(),
                archived = System.currentTimeMillis()
            )
        )
    )
    var unarchiveResult: Result<Session> = Result.success(
        Session(
            id = "test-session",
            title = "Test Session",
            time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis())
        )
    )
    var shareResult: Result<Session> = Result.success(
        Session(
            id = "test-session",
            title = "Test Session",
            share = Session.Share(url = "https://share.example/test"),
            time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis())
        )
    )
    var unshareResult: Result<Unit> = Result.success(Unit)
    var compactResult: Result<Unit> = Result.success(Unit)
    var exportResult: Result<Unit> = Result.success(Unit)
    var importResult: Result<Session> = Result.success(
        Session(
            id = "imported-session",
            title = "Imported Session",
            time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis())
        )
    )
    var deleteMessageResult: Result<Boolean> = Result.success(true)
    var deleteMessagePartResult: Result<Boolean> = Result.success(true)
    var listMessagesResult: Result<List<MessageWithParts>> = Result.success(emptyList())
    var fetchStatusesResult: Result<Map<String, SessionStatus>> = Result.success(emptyMap())

    val abortCalls = mutableListOf<Pair<String, String>>()
    val renameCalls = mutableListOf<Triple<String, String, String>>()
    val createdSessions = mutableListOf<Pair<String, CreateSessionOpts>>()

    // ============ State Observations ============

    override fun getSessionsFlow(serverId: String): Flow<List<Session>> = sessionsState

    override fun getSessionStatusesFlow(serverId: String): Flow<Map<String, SessionStatus>> = statusesState

    // ============ CRUD ============

    override suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session> {
        createdSessions.add(serverId to opts)
        return createSessionResult
    }

    override suspend fun deleteSession(serverId: String, sessionId: String): Result<Unit> = deleteSessionResult

    override suspend fun switchSession(sessionId: String): Result<Unit> = switchSessionResult

    override suspend fun getSession(serverId: String, sessionId: String): Result<Session> = getSessionResult

    // ============ Session Lifecycle ============

    override suspend fun abort(serverId: String, sessionId: String, directory: String?): Result<Unit> {
        abortCalls.add(serverId to sessionId)
        return abortResult
    }

    override suspend fun rename(serverId: String, sessionId: String, title: String): Result<Unit> {
        renameCalls.add(Triple(serverId, sessionId, title))
        return renameResult
    }

    override suspend fun fork(serverId: String, sessionId: String): Result<Session> = forkResult

    // ============ Archive ============

    override suspend fun archive(serverId: String, sessionId: String): Result<Session> = archiveResult

    override suspend fun unarchive(serverId: String, sessionId: String): Result<Session> = unarchiveResult

    // ============ Share / Export ============

    override suspend fun shareSession(serverId: String, sessionId: String): Result<Session> = shareResult

    override suspend fun unshareSession(serverId: String, sessionId: String): Result<Unit> = unshareResult

    override suspend fun compactSession(
        serverId: String,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Result<Unit> = compactResult

    override suspend fun exportSessionToStream(
        serverId: String,
        sessionId: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ): Result<Unit> = exportResult

    // ============ Import ============

    override suspend fun importSession(serverId: String, shareUrl: String): Result<Session> = importResult

    // ============ Message Operations ============

    override suspend fun deleteMessage(serverId: String, sessionId: String, messageId: String): Result<Boolean> =
        deleteMessageResult

    override suspend fun deleteMessagePart(
        serverId: String,
        sessionId: String,
        messageId: String,
        partIndex: Int
    ): Result<Boolean> = deleteMessagePartResult

    override suspend fun listMessages(serverId: String, sessionId: String, limit: Int): Result<List<MessageWithParts>> =
        listMessagesResult

    // ============ Current Agent/Model ============

    override fun getCurrentAgentFlow(serverId: String): Flow<Map<String, String>> = currentAgentFlow

    override fun getCurrentModelFlow(serverId: String): Flow<Map<String, Pair<String, String>>> = currentModelFlow

    // ============ Write Operations ============

    override fun setSessions(serverId: String, sessions: List<Session>) {
        sessionsState.value = sessions
    }

    // ============ Session Status Sync ============

    override suspend fun fetchSessionStatuses(serverId: String, directory: String?): Result<Map<String, SessionStatus>> =
        fetchStatusesResult
}
