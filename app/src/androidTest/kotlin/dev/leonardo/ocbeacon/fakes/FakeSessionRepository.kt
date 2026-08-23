package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.CreateSessionOpts
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
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
    val serverSessionsFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val lastUserMessageTimeFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastCompletedReplyTimeFlow = MutableStateFlow<Map<String, Long>>(emptyMap())

    var createSessionResult: Result<Session> = Result.success(
        Session(
            id = "new-session",
            title = "New Session",
            time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis())
        )
    )
    var deleteSessionResult: Result<Unit> = Result.success(Unit)
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
    var listMessagesResult: Result<MessagePage> = Result.success(MessagePage(emptyList(), null))
    var fetchStatusesResult: Result<Map<String, SessionStatus>> = Result.success(emptyMap())

    val interruptCalls = mutableListOf<Pair<String, String>>()
    val renameCalls = mutableListOf<Triple<String, String, String>>()
    val createdSessions = mutableListOf<Pair<String, CreateSessionOpts>>()

    // ============ 状态观察 ============

    override fun getSessionsFlow(serverId: String): Flow<List<Session>> = sessionsState

    override fun getSessionStatusesFlow(serverId: String): Flow<Map<String, SessionStatus>> = statusesState

    // ============ CRUD ============

    override suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session> {
        createdSessions.add(serverId to opts)
        return createSessionResult
    }

    override suspend fun deleteSession(serverId: String, sessionId: String): Result<Unit> = deleteSessionResult


    override suspend fun getSession(serverId: String, sessionId: String): Result<Session> = getSessionResult

    // 2026-08-16（androidTest 源集修复）：接口既有成员缺失实现的补齐
    override suspend fun getMessage(
        serverId: String,
        sessionId: String,
        messageId: String,
    ): Result<dev.leonardo.ocbeacon.domain.model.MessageWithParts> = Result.failure(UnsupportedOperationException("fake"))

    override suspend fun getApiVersion(serverId: String): dev.leonardo.ocbeacon.domain.model.ApiVersion =
        dev.leonardo.ocbeacon.domain.model.ApiVersion.UNKNOWN

    // ============ 会话生命周期 ============

    override suspend fun interrupt(serverId: String, sessionId: String, directory: String?): Result<Unit> {
        interruptCalls.add(serverId to sessionId)
        return abortResult
    }

    override suspend fun rename(serverId: String, sessionId: String, title: String): Result<Unit> {
        renameCalls.add(Triple(serverId, sessionId, title))
        return renameResult
    }

    override suspend fun fork(serverId: String, sessionId: String): Result<Session> = forkResult

    // ============ 归档 ============

    override suspend fun archive(serverId: String, sessionId: String): Result<Session> = archiveResult

    override suspend fun unarchive(serverId: String, sessionId: String): Result<Session> = unarchiveResult

    // ============ 分享 / 导出 ============

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

    // ============ 导入 ============

    override suspend fun importSession(serverId: String, shareUrl: String): Result<Session> = importResult

    // ============ 消息操作 ============

    override suspend fun deleteMessage(serverId: String, sessionId: String, messageId: String): Result<Boolean> =
        deleteMessageResult

    override suspend fun deleteMessagePart(
        serverId: String,
        sessionId: String,
        messageId: String,
        partIndex: Int
    ): Result<Boolean> = deleteMessagePartResult

    override suspend fun listMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String?,
    ): Result<MessagePage> = listMessagesResult

    // ============ 当前 Agent/Model ============

    override fun getCurrentAgentFlow(serverId: String): Flow<Map<String, String>> = currentAgentFlow

    override fun getCurrentModelFlow(serverId: String): Flow<Map<String, Pair<String, String>>> = currentModelFlow

    // ============ 写操作 ============

    override fun setSessions(serverId: String, sessions: List<Session>) {
        sessionsState.value = sessions
    }

    // ============ 会话状态同步 ============

    override suspend fun fetchSessionStatuses(serverId: String, directory: String?): Result<Map<String, SessionStatus>> =
        fetchStatusesResult

    // ============ 服务器会话映射 / 最近消息时间 / 会话列表 ============

    override fun getServerSessionsFlow(): Flow<Map<String, Set<String>>> = serverSessionsFlow

    override fun getLastUserMessageTimeFlow(): Flow<Map<String, Long>> = lastUserMessageTimeFlow

    override fun getLastCompletedReplyTimeFlow(): Flow<Map<String, Long>> = lastCompletedReplyTimeFlow

    override suspend fun listSessions(
        serverId: String,
        directory: String?,
        search: String?,
        cursor: String?,
        limit: Int
    ): List<Session> = emptyList()
}
