package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.data.repository.UnreadBadgeService

import android.util.Log
import app.cash.turbine.test
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.PermissionState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.StreamingOwnershipRegistry
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

/**
 * ChatViewModel 权限相关逻辑的纯 JVM 单元测试。
 *
 * 使用 [UnconfinedTestDispatcher] 使 viewModelScope 协程立即执行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPermissionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var terminalRegistry: ServerTerminalRegistry
    private lateinit var settingsRepository: SettingsRepository
    // UseCase mock
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var manageSessionUseCase: ManageSessionUseCase
    private lateinit var managePermissionUseCase: ManagePermissionUseCase
    private lateinit var selectModelUseCase: SelectModelUseCase
    private lateinit var manageAgentUseCase: ManageAgentUseCase
    private lateinit var manageTerminalUseCase: ManageTerminalUseCase
    private lateinit var draftRepository: DraftRepository
    private lateinit var shareExportUseCase: ShareExportUseCase
    private lateinit var undoRedoUseCase: UndoRedoUseCase
    private lateinit var messagePaging: MessagePaginationUseCase
    private val tokenStatsTracker = TokenStatsTracker()
    private val sessionStateService: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

    private val testSessionId = "session-123"
    private val testServerId = "server-1"
    private val testDirectory = "/home/user/project"

    @After
    fun tearDown() {
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val messageStore = MessageEventHandler()
        val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
        eventDispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            sessionStateService = sessionStateService,
            settingsDataStore = settingsDataStore,
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService>(relaxed = true),
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

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        // 每个测试创建全新的 mock，避免 stub 顺序问题
        terminalRegistry = mockk(relaxed = true)
        settingsRepository = mockk()

        // 创建 UseCase mock（全部 relaxed，因此不重要的方法无需 stub）
        sendMessageUseCase = mockk(relaxed = true)
        manageSessionUseCase = mockk(relaxed = true)
        managePermissionUseCase = mockk(relaxed = true)
        selectModelUseCase = mockk(relaxed = true)
        manageAgentUseCase = mockk(relaxed = true)
        manageTerminalUseCase = mockk(relaxed = true)
        draftRepository = mockk(relaxed = true)
        shareExportUseCase = mockk(relaxed = true)
        undoRedoUseCase = mockk(relaxed = true)
        messagePaging = mockk(relaxed = true)

        coEvery { draftRepository.getDraft(any()) } returns null

        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
        every { settingsRepository.defaultModel(any()) } returns flowOf(null)
        every { settingsRepository.getSettingsFlow() } returns flowOf(
            AppSettings(
                terminalFontSize = 13f,
                initialMessageCount = 50,
                chatFontSize = "medium",
                confirmBeforeSend = false,
                compactMessages = false,
                collapseTools = false,
                expandReasoning = false,
                hapticFeedback = true,
                keepScreenOn = false,
                compressImageAttachments = true,
                imageAttachmentMaxLongSide = 1440,
                imageAttachmentWebpQuality = 60,
            )
        )

        // init 块 stub —— 测试可覆盖的默认值
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()
        // 注意：此处不设置 listPendingPermissions —— 每个测试设置自己的 stub

        // 将 messagePaging.observeMessages 接线为委托给 eventDispatcher.messages
        every { messagePaging.observeMessages(any()) } answers {
            eventDispatcher.messages.map { msgs -> msgs[firstArg<String>()] ?: emptyList() }
        }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(
        sessionId: String = testSessionId,
        serverId: String = testServerId
    ): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to serverId,
            "sessionId"  to sessionId
        ))
        // ChatRepository mock：将状态操作委托给真实 EventDispatcher 以便验证
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        coEvery { chatRepo.listActiveSessions(any()) } returns kotlin.Result.success(emptyMap())
        every { chatRepo.setPermissions(any(), any()) } answers {
            val sid = firstArg<String>()
            val perms = secondArg<List<SseEvent.PermissionAsked>>()
            eventDispatcher.setPermissions(sid, perms)
        }
        every { chatRepo.removePermission(any()) } answers {
            eventDispatcher.removePermission(firstArg())
        }
        every { chatRepo.getPermissionsSnapshot() } answers {
            eventDispatcher.permissions.value
        }
        every { chatRepo.getQuestionsSnapshot() } answers {
            eventDispatcher.questions.value
        }
        every { chatRepo.getSessionsSnapshot() } answers {
            eventDispatcher.sessions.value
        }
        every { chatRepo.setMessages(any(), any()) } answers {
            eventDispatcher.setMessages(firstArg(), secondArg())
        }
        every { chatRepo.mergeMessages(any(), any()) } answers {
            eventDispatcher.mergeMessages(firstArg(), secondArg())
        }
        every { chatRepo.replaceMessages(any(), any()) } answers {
            eventDispatcher.replaceMessages(firstArg(), secondArg())
        }
        every { chatRepo.getPermissionsWithChildren(any(), any()) } answers {
            eventDispatcher.getPermissionsWithChildren(firstArg(), secondArg())
        }
        every { chatRepo.getQuestionsWithChildren(any(), any()) } answers {
            eventDispatcher.getQuestionsWithChildren(firstArg(), secondArg())
        }
        every { chatRepo.getParts(any()) } returns flowOf(emptyList())
        every { chatRepo.getAllPartsMap() } returns eventDispatcher.parts
        return ChatViewModel(
            savedStateHandle = savedState,
            sendMessageUseCase = sendMessageUseCase,
            manageSessionUseCase = manageSessionUseCase,
            managePermissionUseCase = managePermissionUseCase,
            selectModelUseCase = selectModelUseCase,
            manageAgentUseCase = manageAgentUseCase,
            manageTerminalUseCase = manageTerminalUseCase,
            draftRepository = draftRepository,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            settingsRepository = settingsRepository,
            terminalRegistry = terminalRegistry,
            toolCardResolver = dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver(),
            chatRepository = chatRepo,
            sessionRepository = mockk<SessionRepository>(relaxed = true).also {
                every { it.getSessionsFlow(any()) } returns eventDispatcher.sessions
                every { it.getSessionStatusesFlow(any()) } returns eventDispatcher.sessionStatuses
                every { it.getCurrentAgentFlow(any()) } returns eventDispatcher.currentAgent
                every { it.getCurrentModelFlow(any()) } returns eventDispatcher.currentModel
            },
            messagePaging = messagePaging,
            messageStore = mockk(relaxed = true),
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),

            sessionStateService = sessionStateService,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService>(relaxed = true),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            serverRepository = serverRepository,
            shellJobsStore = ShellJobsStore(),
            eventDispatcher = mockk(relaxed = true),
            // 堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessageRepository = mockk(relaxed = true),
            pendingMessagePipeline = mockk<dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline>(relaxed = true).also { mk ->
                // drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter
                // 会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起
                every { mk.drainingSessions } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
            },
        )
    }

    private fun createTestSession(
        id: String = testSessionId,
        directory: String = testDirectory
    ): Session = Session(
        id = id,
        title = "Test Session",
        directory = directory,
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createTestPermissionRequest(
        id: String = "perm-1",
        sessionId: String = testSessionId,
        permission: String = "bash",
        patterns: List<String> = listOf("/home/user/project"),
        metadata: Map<String, String>? = null,
        always: Boolean = false,
        tool: ToolRef? = null
    ): PermissionState = PermissionState(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        metadata = metadata,
        always = always,
        tool = tool
    )

    // ============================================================
    // 健全性检查：验证 init 块协程已执行
    // ============================================================

    @Test
    fun `init block executes — getSession API is called`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        createViewModel()
        coVerify { manageSessionUseCase.getSession(any(), testSessionId) }
    }

    @Test
    fun `init block executes — permissions API is called`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        createViewModel()
        coVerify { managePermissionUseCase.listPendingPermissions(any(), any()) }
    }

    @Test
    fun `EventDispatcher setPermissions works directly`() = runTest {
        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = testSessionId, permission = "bash")
        eventDispatcher.setPermissions(testSessionId, listOf(perm))
        assertEquals(1, eventDispatcher.permissions.value[testSessionId]?.size)
        assertEquals("p1", eventDispatcher.permissions.value[testSessionId]?.firstOrNull()?.id)
    }

    // ============================================================
    // 测试：loadPendingPermissions
    // ============================================================

    @Test
    fun `loadPendingPermissions maps and stores permission`() = runTest {
        val permRequest = createTestPermissionRequest(
            id = "perm-1",
            sessionId = testSessionId,
            permission = "bash",
            patterns = listOf("/home/user"),
            metadata = mapOf("key" to "value"),
            always = true
        )
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(permRequest)

        val vm = createViewModel()

        // 直接检查 EventDispatcher（真相来源）
        val reducerPerms = eventDispatcher.permissions.value
        assertEquals("EventDispatcher should have 1 permission for session, got: ${reducerPerms}",
            1, reducerPerms[testSessionId]?.size)
        assertEquals("perm-1", reducerPerms[testSessionId]?.firstOrNull()?.id)
        assertEquals("bash", reducerPerms[testSessionId]?.firstOrNull()?.permission)
        assertEquals(true, reducerPerms[testSessionId]?.firstOrNull()?.always)
        assertEquals(mapOf("key" to "value"), reducerPerms[testSessionId]?.firstOrNull()?.metadata)
    }

    @Test
    fun `loadPendingPermissions filters by session ID`() = runTest {
        val perm1 = createTestPermissionRequest(id = "p1", sessionId = testSessionId)
        val perm2 = createTestPermissionRequest(id = "p2", sessionId = "other-session")
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(perm1, perm2)

        val vm = createViewModel()

        val reducerPerms = eventDispatcher.permissions.value
        assertEquals(1, reducerPerms[testSessionId]?.size)
        assertEquals("p1", reducerPerms[testSessionId]?.firstOrNull()?.id)
        assertTrue(reducerPerms["other-session"].isNullOrEmpty())
    }

    @Test
    fun `loadPendingPermissions empty result — no permissions stored`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()

        val vm = createViewModel()

        assertTrue(eventDispatcher.permissions.value.isEmpty())
    }

    @Test
    fun `loadPendingPermissions maps metadata`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(
                id = "pm",
                metadata = mapOf(
                    "str" to "hello",
                    "num" to "42",
                    "bool" to "true"
                )
            )
        )

        createViewModel()

        val perm = eventDispatcher.permissions.value[testSessionId]?.firstOrNull()
        assertNotNull(perm)
        assertEquals("hello", perm?.metadata?.get("str"))
        assertEquals("42", perm?.metadata?.get("num"))
        assertEquals("true", perm?.metadata?.get("bool"))
    }

    @Test
    fun `loadPendingPermissions maps always field`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "p-no", always = false),
            createTestPermissionRequest(id = "p-yes", always = true)
        )

        createViewModel()

        val perms = eventDispatcher.permissions.value[testSessionId]
        assertEquals(2, perms?.size)
        assertFalse(perms?.first { it.id == "p-no" }?.always ?: true)
        assertTrue(perms?.first { it.id == "p-yes" }?.always ?: false)
    }

    @Test
    fun `loadPendingPermissions API exception does not crash`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } throws RuntimeException("err")

        createViewModel() // 不应抛异常

        assertTrue(eventDispatcher.permissions.value.isEmpty())
    }

    @Test
    fun `loadPendingPermissions maps tool ref`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pt", tool = ToolRef(messageId = "m1", callId = "c1"))
        )

        createViewModel()

        val perm = eventDispatcher.permissions.value[testSessionId]?.firstOrNull()
        assertNotNull(perm)
        assertEquals("m1", perm?.tool?.messageId)
        assertEquals("c1", perm?.tool?.callId)
    }

    // ============================================================
    // 测试：replyToPermission
    // ============================================================

    @Test
    fun `replyToPermission calls API and removes permission`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "perm-reply")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any()) } returns true

        val vm = createViewModel()
        assertEquals("Precondition: 1 permission loaded",
            1, eventDispatcher.permissions.value[testSessionId]?.size)

        vm.replyToPermission("perm-reply", "once")

        coVerify { managePermissionUseCase.replyToPermission(any(), any(), "perm-reply", "once", any()) }
        assertTrue(eventDispatcher.permissions.value[testSessionId].isNullOrEmpty())
    }

    @Test
    fun `replyToPermission with reply=always`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pa")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any()) } returns true

        val vm = createViewModel()

        vm.replyToPermission("pa", "always")

        coVerify { managePermissionUseCase.replyToPermission(any(), any(), "pa", "always", any()) }
        assertTrue(eventDispatcher.permissions.value[testSessionId].isNullOrEmpty())
    }

    @Test
    fun `replyToPermission with reply=reject`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pr")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any()) } returns true

        val vm = createViewModel()

        vm.replyToPermission("pr", "reject")

        coVerify { managePermissionUseCase.replyToPermission(any(), any(), "pr", "reject", any()) }
        assertTrue(eventDispatcher.permissions.value[testSessionId].isNullOrEmpty())
    }

    @Test
    fun `replyToPermission API false keeps card when server still pending`() = runTest {
        // 2026-08-17 根治（权限卡重弹）：失败不再无条件清卡——复核服务器
        // 仍 pending → 保留卡片（旧「失败也移除」正是重弹根因的一半）。
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pf")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any()) } returns false

        val vm = createViewModel()

        vm.replyToPermission("pf", "once")

        val perms = eventDispatcher.permissions.value[testSessionId]
        assertEquals(1, perms?.size)
        assertEquals("pf", perms?.firstOrNull()?.id)
    }

    @Test
    fun `replyToPermission API false removes card when server confirms gone`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any()) } returns false

        val vm = createViewModel()

        vm.replyToPermission("pf", "once")

        assertTrue(eventDispatcher.permissions.value[testSessionId].isNullOrEmpty())
    }

    @Test
    fun `replyToPermission spares other permissions`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "p1", permission = "bash"),
            createTestPermissionRequest(id = "p2", permission = "write")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), "p1", any(), any()) } returns true

        val vm = createViewModel()

        vm.replyToPermission("p1", "once")

        val perms = eventDispatcher.permissions.value[testSessionId]
        assertEquals(1, perms?.size)
        assertEquals("p2", perms?.firstOrNull()?.id)
        assertEquals("write", perms?.firstOrNull()?.permission)
    }

    @Test
    fun `replyToPermission API exception keeps card when server still pending`() = runTest {
        // 2026-08-17 根治：异常后复核服务器——仍 pending 保留卡片（用户重试）。
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pe")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any()) } throws RuntimeException("err")

        val vm = createViewModel()

        vm.replyToPermission("pe", "once")

        val perms = eventDispatcher.permissions.value[testSessionId]
        assertEquals(1, perms?.size)
    }

    // ============================================================
    // 测试：多会话
    // ============================================================

    @Test
    fun `multi-session — only current session permissions loaded into EventDispatcher`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "p1", sessionId = testSessionId),
            createTestPermissionRequest(id = "p2", sessionId = "session-456")
        )

        createViewModel()

        // 只存储当前会话的权限（按 sessionId 过滤）
        assertEquals(1, eventDispatcher.permissions.value[testSessionId]?.size)
        assertEquals("p1", eventDispatcher.permissions.value[testSessionId]?.firstOrNull()?.id)
        // session-456 未被加载，因为 loadPendingPermissions 只存储
        // 与 ViewModel 自身 sessionId 匹配的权限
        assertTrue(eventDispatcher.permissions.value["session-456"].isNullOrEmpty())
    }
}
