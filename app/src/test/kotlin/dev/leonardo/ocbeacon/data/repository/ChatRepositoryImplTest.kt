package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatRepositoryImplTest {

    private lateinit var repo: ChatRepositoryImpl
    private lateinit var messageApi: MessageApi
    private lateinit var sessionApi: SessionApi
    private lateinit var terminalApi: TerminalApi
    private lateinit var providerApi: ProviderApi
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var serverRepo: ServerDataStore
    private lateinit var permissionAutoApprover: PermissionAutoApprover
    private lateinit var messageStore: MessageCacheRepository
    private lateinit var sessionHandler: SessionEventHandler
    private lateinit var messageHandler: MessageEventHandler
    private lateinit var permissionHandler: PermissionEventHandler
    private lateinit var questionHandler: QuestionEventHandler
    // #311 Task1：workspace 归档代理 + 快照流（真 store 断言流式投影）
    private lateinit var dshApiClient: dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
    private lateinit var dshWorkspaceStore: DshWorkspaceStore
    // #391：唯一路由 seam（私有能力端口）
    private lateinit var adapters: dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry

    @Before
    fun setup() {
        messageApi = mockk(relaxed = true)
        sessionApi = mockk(relaxed = true)
        terminalApi = mockk(relaxed = true)
        providerApi = mockk(relaxed = true)
        serverRepo = mockk(relaxed = true)
        permissionAutoApprover = mockk(relaxed = true)
        messageStore = mockk(relaxed = true)
        // 单元测试不接入 Room；种子化读到空 list（不触发 upsert），保持原测试语义
        every { messageStore.observeMessages(any()) } returns flowOf(emptyList())
        sessionHandler = SessionEventHandler()
        messageHandler = MessageEventHandler()
        permissionHandler = PermissionEventHandler()
        questionHandler = QuestionEventHandler()
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
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), messageHandler),
            sessionStateRepository = sessionStateRepository,
            unreadBadgeService = UnreadBadgeService(unreadStateStore, CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
            ownershipRegistry = StreamingOwnershipRegistry(),
            // #122 接线新增：自动批准（relaxed mock——既有用例不受影响）
            permissionAutoApprover = permissionAutoApprover,
            pendingInteractionStore = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingInteractionStore>(relaxed = true),
            historySyncManagerProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.HistorySyncManager>(relaxed = true) },
            dshJobsHandler = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.handler.DshJobsHandler>(relaxed = true),
            dshQueueHandler = dev.leonardo.ocbeacon.data.repository.handler.DshQueueHandler(mockk(relaxed = true)),
            dshWorkspaceHandler = dev.leonardo.ocbeacon.data.repository.handler.DshWorkspaceHandler(
                dev.leonardo.ocbeacon.data.repository.DshWorkspaceStore(),
            ),

        )
        // #362：spy 包裹真实 dispatcher——播种门控用 processEvent 交互断言（对既有用例透明）
        eventDispatcher = spyk(eventDispatcher)
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
        dshApiClient = mockk(relaxed = true)
        dshWorkspaceStore = DshWorkspaceStore()
        // #391：被测类只经注册表取端口——把测试自己的 mock 端口塞进注册表
        adapters = dev.leonardo.ocbeacon.testing.testAdapterRegistry(
            session = sessionApi,
            message = messageApi,
            provider = providerApi,
            terminal = terminalApi,
        )
        repo = ChatRepositoryImpl(eventDispatcher, serverRepo, permissionAutoApprover, messageStore, dshApiClient, dshWorkspaceStore, adapters)
    }

    // ============ getMessagesFlow ============

    @Test
    fun `getMessagesFlow returns messages from dispatcher`() = runTest {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        messageHandler.upsertMessages("s1", listOf(MessageWithParts(msg, emptyList())), MergeStrategy.SSE_PRIORITY)

        val messages = repo.getMessagesFlow("s1").first()
        assertEquals(1, messages.size)
        assertEquals("m1", messages[0].id)
    }

    @Test
    fun `getMessagesFlow returns empty for unknown session`() = runTest {
        val messages = repo.getMessagesFlow("unknown").first()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `getMessagesFlow seeds memory from Room cache when empty`() = runTest {
        // 冷启动场景：内存热视图空，Room 有缓存 → 种子化后消息立即可见
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        every { messageStore.observeMessages("s1") } returns flowOf(listOf(MessageWithParts(msg, emptyList())))

        val messages = repo.getMessagesFlow("s1").first()
        assertEquals(1, messages.size)
        assertEquals("m1", messages[0].id)
        // 种子化副作用：内存热视图被填充（后续订阅不再读 Room）
        assertEquals(1, eventDispatcher.messages.value["s1"]?.size)
    }

    // ============ getPermissionsFlow ============

    @Test
    fun `getPermissionsFlow maps events to PermissionState`() = runTest {
        val event = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "file-write",
            patterns = listOf("/tmp/*"),
            metadata = mapOf("path" to "/tmp/test"),
            always = false,
            tool = null
        )
        permissionHandler.setPermissions("s1", listOf(event))

        val permissions = repo.getPermissionsFlow("s1").first()
        assertEquals(1, permissions.size)
        assertEquals("p1", permissions[0].id)
        assertEquals("file-write", permissions[0].permission)
        assertEquals(listOf("/tmp/*"), permissions[0].patterns)
        assertEquals(mapOf("path" to "/tmp/test"), permissions[0].metadata)
    }

    @Test
    fun `getPermissionsFlow returns empty for unknown session`() = runTest {
        val permissions = repo.getPermissionsFlow("unknown").first()
        assertTrue(permissions.isEmpty())
    }

    // ============ getQuestionsFlow ============

    @Test
    fun `getQuestionsFlow maps events to QuestionState`() = runTest {
        val event = SseEvent.QuestionAsked(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    header = "Action",
                    question = "Proceed?",
                    options = listOf(
                        SseEvent.QuestionAsked.Option(label = "Yes", description = "Go ahead")
                    )
                )
            ),
            tool = null
        )
        questionHandler.setQuestions("s1", listOf(event))

        val questions = repo.getQuestionsFlow("s1").first()
        assertEquals(1, questions.size)
        assertEquals("q1", questions[0].id)
        assertEquals(1, questions[0].questions.size)
        assertEquals("Proceed?", questions[0].questions[0].question)
        assertEquals(1, questions[0].questions[0].options.size)
        assertEquals("Yes", questions[0].questions[0].options[0].label)
    }

    // ============ #362：echo 播种门控（busy+queue 队列行不上屏） ============

    private fun setupTrackedServerWithAdmission(admission: dev.leonardo.ocbeacon.data.api.message.PromptAdmission?) {
        sessionHandler.setSessions("server1", listOf(
            Session(id = "s1", title = "Test", time = Session.Time(created = 1000L, updated = 2000L))
        ))
        coEvery { serverRepo.getServer("server1") } returns ServerConfig(
            id = "server1", url = "http://localhost:4096"
        )
        coEvery {
            messageApi.promptAsync(any(), "s1", any(), any(), any(), any(), any(), any())
        } returns admission
    }

    @Test
    fun `promptAsync seeds transcript echo when seedTranscript true`() = runTest {
        setupTrackedServerWithAdmission(
            dev.leonardo.ocbeacon.data.api.message.PromptAdmission(id = "pending-r1", sessionId = "s1", text = "hello")
        )
        val result = repo.promptAsync(
            "server1", "s1", listOf(PromptPart(type = "text", text = "hello")),
            seedTranscript = true,
        )
        assertTrue(result.isSuccess)
        verify(exactly = 1) {
            eventDispatcher.processEvent(ofType<SseEvent.MessageUpdated>(), "server1")
        }
    }

    @Test
    fun `promptAsync skips echo seeding for queue row when seedTranscript false`() = runTest {
        setupTrackedServerWithAdmission(
            dev.leonardo.ocbeacon.data.api.message.PromptAdmission(id = "pending-r2", sessionId = "s1", text = "queued note")
        )
        val result = repo.promptAsync(
            "server1", "s1", listOf(PromptPart(type = "text", text = "queued note")),
            seedTranscript = false,
        )
        assertTrue(result.isSuccess)
        // 排队消息不进转录——唯一可见面是队列 UI（QueueSheet/角标），轮末派发后
        // durable user/message 才自然入列。
        verify(exactly = 0) {
            eventDispatcher.processEvent(ofType<SseEvent.MessageUpdated>(), any())
        }
    }

    @Test
    fun `promptAsync no admission means no seed regardless of flag`() = runTest {
        setupTrackedServerWithAdmission(null)
        val result = repo.promptAsync(
            "server1", "s1", listOf(PromptPart(type = "text", text = "v1 style")),
            seedTranscript = true,
        )
        assertTrue(result.isSuccess)
        verify(exactly = 0) {
            eventDispatcher.processEvent(ofType<SseEvent.MessageUpdated>(), any())
        }
    }

    // ============ sendMessage ============

    @Test
    fun `sendMessage returns failure when session not tracked`() = runTest {
        val result = repo.sendMessage("unknown", emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage calls api when session tracked`() = runTest {
        // Set up session tracking
        sessionHandler.setSessions("server1", listOf(
            Session(id = "s1", title = "Test", time = Session.Time(created = 1000L, updated = 2000L))
        ))
        coEvery { serverRepo.getServer("server1") } returns ServerConfig(
            id = "server1", url = "http://localhost:4096"
        )
        coEvery { messageApi.promptAsync(any(), "s1", any()) } returns null

        val textPart = Part.Text(id = "", sessionId = "s1", messageId = "", text = "hello")
        val result = repo.sendMessage("s1", listOf(textPart))
        assertTrue(result.isSuccess)
    }

    // ============ #311 Task1：DSH workspace 归档代理 ============

    @Test
    fun `archiveSession proxies to dsh api and returns new archived set for dsh server`() = runTest {
        coEvery { serverRepo.getServer("srv-dsh") } returns ServerConfig(
            id = "srv-dsh", url = "http://dsh.local", serverType = ServerType.Dsh,
        )
        coEvery { dshApiClient.archiveSession(any(), "s-9") } returns listOf("s-2", "s-9")
        val result = repo.archiveSession("srv-dsh", "s-9")
        assertEquals(listOf("s-2", "s-9"), result.getOrThrow())
    }

    @Test
    fun `archiveSession fails unsupported for non-dsh server`() = runTest {
        coEvery { serverRepo.getServer("srv-v1") } returns ServerConfig(
            id = "srv-v1", url = "http://v1.local",
        )
        val result = repo.archiveSession("srv-v1", "s-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability)
    }

    @Test
    fun `workspace snapshot flow maps store state and defaults empty for unknown server`() = runTest {
        // 未知服务器 → 恒空快照（非 DSH 无 workspace 帧的降级形态）
        val empty = repo.getWorkspaceSnapshotFlow("srv-none").first()
        assertTrue(empty.workspaces.isEmpty())
        assertTrue(empty.archivedSessionIds.isEmpty())
        // store baseline 后 → 流投影注册表 + 归档集合
        dshWorkspaceStore.applyBaseline(
            "srv-dsh",
            listOf(Workspace(workspaceId = "ws-1", path = "/w", title = "W", sessionIds = listOf("s-1"))),
            archivedSessionIds = listOf("s-9"),
        )
        val snapshot = repo.getWorkspaceSnapshotFlow("srv-dsh").first()
        assertEquals(listOf("ws-1"), snapshot.workspaces.map { it.workspaceId })
        assertEquals(listOf("s-9"), snapshot.archivedSessionIds)
    }
}
