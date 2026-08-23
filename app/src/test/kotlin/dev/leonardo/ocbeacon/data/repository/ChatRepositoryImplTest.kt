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
        val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
        eventDispatcher = EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler,
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore()),
            sessionStateRepository = sessionStateRepository,
            settingsDataStore = settingsDataStore,
            unreadBadgeService = UnreadBadgeService(settingsDataStore, CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
            ownershipRegistry = StreamingOwnershipRegistry(),
            sessionRepoProvider = object : javax.inject.Provider<dev.leonardo.ocbeacon.domain.repository.SessionRepository> {
                override fun get() = io.mockk.mockk<dev.leonardo.ocbeacon.domain.repository.SessionRepository>(relaxed = true)
            },
            // #122 接线新增：自动批准（relaxed mock——既有用例不受影响）
            permissionAutoApprover = permissionAutoApprover,
            chatRepoProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.domain.repository.ChatRepository>(relaxed = true) },
            // 堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessagePipelineProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline>(relaxed = true) },
            pendingMessageRepository = io.mockk.mockk(relaxed = true),
        )
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
        repo = ChatRepositoryImpl(messageApi, sessionApi, terminalApi, mockk(relaxed = true), providerApi, eventDispatcher, serverRepo, permissionAutoApprover, messageStore)
    }

    // ============ getMessagesFlow ============

    @Test
    fun `getMessagesFlow returns messages from dispatcher`() = runTest {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        messageHandler.setMessages("s1", listOf(MessageWithParts(msg, emptyList())))

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
}
