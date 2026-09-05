package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import dev.leonardo.ocbeacon.data.local.CachedSessionDao
import dev.leonardo.ocbeacon.data.local.CachedSessionEntity
import dev.leonardo.ocbeacon.data.local.SessionCacheStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    private lateinit var cachedDao: CachedSessionDao
    private lateinit var cachedJson: Json
    private lateinit var testScope: CoroutineScope

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

        val sessionStateRepository = mockk<SessionStateService>(relaxed = true)
        val unreadStateStore = mockk<UnreadStateStore>(relaxed = true)
        eventDispatcher = EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler,
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            sessionStateRepository = sessionStateRepository,
            unreadBadgeService = UnreadBadgeService(unreadStateStore, CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), messageHandler),
            ownershipRegistry = StreamingOwnershipRegistry(),
            // #122 接线新增：自动批准（relaxed mock——既有用例不受影响）
            permissionAutoApprover = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover>(relaxed = true),
            historySyncManagerProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.HistorySyncManager>(relaxed = true) },
            dshJobsHandler = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.handler.DshJobsHandler>(relaxed = true),
            dshQueueHandler = dev.leonardo.ocbeacon.data.repository.handler.DshQueueHandler(mockk(relaxed = true)),
            dshWorkspaceHandler = dev.leonardo.ocbeacon.data.repository.handler.DshWorkspaceHandler(
                dev.leonardo.ocbeacon.data.repository.DshWorkspaceStore(),
            ),

        )
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
        // #306：真 SessionCacheStore + mock DAO（顺带覆盖 encode/decode 链）
        cachedDao = mockk(relaxed = true)
        every { cachedDao.observeByServer(any()) } returns MutableStateFlow(emptyList<CachedSessionEntity>())
        cachedJson = Json { ignoreUnknownKeys = true; isLenient = true }
        testScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        repo = SessionRepositoryImpl(
            sessionApi, messageApi, eventDispatcher, serverRepo,
            SessionCacheStore(cachedDao, cachedJson), testScope,
        )
    }

    private fun testSession(id: String) = Session(
        id = id, title = "Test $id",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    // ============ getSessionsFlow ============

    private fun cachedEntity(id: String, serverId: String = "server1"): CachedSessionEntity =
        CachedSessionEntity(
            id = id, serverId = serverId, updatedAt = 100L,
            payload = cachedJson.encodeToString(Session.serializer(), testSession(id)),
        )

    // ---- #306：Room 缓存兜底（白屏根治）----

    @Test
    fun `getSessionsFlow falls back to room cache when server mapping cleared`() = runTest {
        // REST 基线曾落过内存（Unconfined scope 下缓存写也即时完成）
        repo.setSessions("server1", listOf(testSession("s1"), testSession("s2")))
        // 断连/服务销毁：clearForServer 移除映射 key → 内存态为空（旧实现白屏点）
        sessionHandler.clearForServer("server1")

        every { cachedDao.observeByServer("server1") } returns flowOf(
            listOf(cachedEntity("s1"), cachedEntity("s2"), cachedEntity("s3")),
        )
        val sessions = repo.getSessionsFlow("server1").first()
        // 兜底全量展示；陈旧条目（s3 已不在最新基线）待重连后 REST 基线校正
        assertEquals(listOf("s1", "s2", "s3"), sessions.map { it.id })
    }

    @Test
    fun `getSessionsFlow prefers memory over cache when mapping present`() = runTest {
        sessionHandler.setSessions("server1", listOf(testSession("s1")))
        every { cachedDao.observeByServer("server1") } returns flowOf(
            listOf(cachedEntity("s1"), cachedEntity("s2")),
        )
        val sessions = repo.getSessionsFlow("server1").first()
        // 内存权威：缓存不掺和内存态结果
        assertEquals(listOf("s1"), sessions.map { it.id })
    }

    @Test
    fun `setSessions writes cache with roundtrippable payload`() = runTest {
        repo.setSessions("server1", listOf(testSession("s1")))
        val slot = slot<List<CachedSessionEntity>>()
        coVerify { cachedDao.replaceForServer("server1", capture(slot)) }
        val entity = slot.captured.single()
        assertEquals("s1", entity.id)
        assertEquals("server1", entity.serverId)
        assertEquals(2000L, entity.updatedAt)
        // payload 无损还原（兜底显示的完整性前提）
        val decoded = cachedJson.decodeFromString<Session>(entity.payload)
        assertEquals(testSession("s1"), decoded)
    }

    @Test
    fun `getSessionsFlow skips malformed cached payload entries`() = runTest {
        every { cachedDao.observeByServer("server1") } returns flowOf(
            listOf(
                CachedSessionEntity(id = "bad", serverId = "server1", updatedAt = 1L, payload = "{not json"),
                cachedEntity("good"),
            ),
        )
        val sessions = repo.getSessionsFlow("server1").first()
        // 坏条目跳过，好条目照常——绝不整流失败
        assertEquals(listOf("good"), sessions.map { it.id })
    }

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
