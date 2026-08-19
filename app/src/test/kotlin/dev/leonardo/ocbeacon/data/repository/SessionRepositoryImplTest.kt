package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionRepositoryImplTest {

    private lateinit var repo: SessionRepositoryImpl
    private lateinit var sessionApi: SessionApi
    private lateinit var messageApi: MessageApi
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var serverRepo: ServerDataStore
    private lateinit var sessionHandler: SessionEventHandler

    @Before
    fun setup() {
        sessionApi = mockk(relaxed = true)
        messageApi = mockk(relaxed = true)
        serverRepo = mockk(relaxed = true)
        sessionHandler = SessionEventHandler()
        val messageHandler = MessageEventHandler()
        val permissionHandler = PermissionEventHandler()
        val questionHandler = QuestionEventHandler()
        val miscHandler = MiscEventHandler()

        val sessionStateService = mockk<SessionStateService>(relaxed = true)
        val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
        eventDispatcher = EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            messagePartHandler = MessagePartHandler(messageHandler),
            messageUpdatedHandler = MessageUpdatedHandler(messageHandler),
            messageRemovedHandler = MessageRemovedHandler(messageHandler),
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler,
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            sessionStateService = sessionStateService,
            settingsDataStore = settingsDataStore,
            unreadBadgeService = UnreadBadgeService(settingsDataStore, CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore()),
            ownershipRegistry = StreamingOwnershipRegistry(),
            sessionRepoProvider = object : javax.inject.Provider<dev.leonardo.ocbeacon.domain.repository.SessionRepository> {
                override fun get() = io.mockk.mockk<dev.leonardo.ocbeacon.domain.repository.SessionRepository>(relaxed = true)
            },
            // #122 接线新增：自动批准（relaxed mock——既有用例不受影响）
            permissionAutoApprover = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover>(relaxed = true),
            chatRepoProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.domain.repository.ChatRepository>(relaxed = true) },
            // 堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessagePipelineProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline>(relaxed = true) },
            pendingMessageRepository = io.mockk.mockk(relaxed = true),
        )
        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
        repo = SessionRepositoryImpl(sessionApi, messageApi, eventDispatcher, serverRepo, mockk(relaxed = true))
    }

    private fun testSession(id: String) = Session(
        id = id, title = "Test $id",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    // ============ getSessionsFlow ============

    @Test
    fun `getSessionsFlow returns sessions for given server`() = runTest {
        sessionHandler.setSessions("server1", listOf(testSession("s1"), testSession("s2")))
        sessionHandler.setSessions("server2", listOf(testSession("s3")))

        val sessions = repo.getSessionsFlow("server1").first()
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.id in listOf("s1", "s2") })
    }

    @Test
    fun `getSessionsFlow returns empty for unknown server`() = runTest {
        sessionHandler.setSessions("server1", listOf(testSession("s1")))

        val sessions = repo.getSessionsFlow("unknown").first()
        assertTrue(sessions.isEmpty())
    }

    // ============ createSession ============

    @Test
    fun `createSession calls API and returns session`() = runTest {
        val newSession = testSession("new")
        coEvery { serverRepo.getServer("server1") } returns ServerConfig(
            id = "server1", url = "http://localhost:4096"
        )
        coEvery { sessionApi.createSession(any(), "Title", any(), any()) } returns newSession

        val result = repo.createSession("server1", CreateSessionOpts(title = "Title"))
        assertTrue(result.isSuccess)
        assertEquals("new", result.getOrThrow().id)
    }

    @Test
    fun `createSession returns failure when server not found`() = runTest {
        coEvery { serverRepo.getServer("unknown") } returns null

        val result = repo.createSession("unknown", CreateSessionOpts())
        assertTrue(result.isFailure)
    }

    // ============ deleteSession ============

    @Test
    fun `deleteSession delegates to API when server exists`() = runTest {
        coEvery { serverRepo.getServer("server1") } returns ServerConfig(
            id = "server1", url = "http://localhost:4096"
        )
        coEvery { sessionApi.deleteSession(any(), "s1") } returns true

        val result = repo.deleteSession("server1", "s1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `deleteSession returns failure when server not found`() = runTest {
        coEvery { serverRepo.getServer("noserver") } returns null

        val result = repo.deleteSession("noserver", "s1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `deleteSession propagates API failure`() = runTest {
        coEvery { serverRepo.getServer("server1") } returns ServerConfig(
            id = "server1", url = "http://localhost:4096"
        )
        coEvery { sessionApi.deleteSession(any(), "s1") } throws java.io.IOException("Connection reset")

        val result = repo.deleteSession("server1", "s1")
        assertTrue(result.isFailure)
    }
}
