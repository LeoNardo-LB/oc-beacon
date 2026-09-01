package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.data.repository.ShellJobsStore

import android.util.Log
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.StreamingOwnershipRegistry
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.Session
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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #276 走查 N1（C1 假成功）回归：revertMessage 在服务器拒绝（DSH 无 session.revert，
 * UnsupportedServerCapability）时必须回滚本地 revert 态——走查实证 setRevert 在网络
 * 调用前无条件置位且失败不清除：RevertBanner（标题文案恰为「消息已还原」）常驻 +
 * 消息列表按 revert 边界截断，用户读到「还原成功」而服务器什么都没做；随后 banner
 * redo 再失败显得「静默」。断言：失败 → onResult(false) + clearRevert；成功 → 不清。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRevertTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var terminalRegistry: ServerTerminalRegistry
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var manageSessionUseCase: ManageSessionUseCase
    private lateinit var managePermissionUseCase: ManagePermissionUseCase
    private lateinit var selectModelUseCase: SelectModelUseCase
    private lateinit var manageAgentUseCase: ManageAgentUseCase
    private lateinit var manageTerminalUseCase: ManageTerminalUseCase
    private lateinit var draftRepository: dev.leonardo.ocbeacon.domain.repository.DraftRepository
    private lateinit var shareExportUseCase: ShareExportUseCase
    private lateinit var undoRedoUseCase: UndoRedoUseCase
    private lateinit var messagePaging: MessagePaginationUseCase
    private lateinit var chatRepository: ChatRepository
    private val tokenStatsTracker = TokenStatsTracker()
    private val sessionStateRepository: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

    private val testSessionId = "session-revert"
    private val testServerId = "server-1"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val messageStore = MessageEventHandler()
        eventDispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            sessionStateRepository = sessionStateRepository,
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService>(relaxed = true),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), messageStore),
            ownershipRegistry = StreamingOwnershipRegistry(),
            permissionAutoApprover = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover>(relaxed = true),
            historySyncManagerProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.HistorySyncManager>(relaxed = true) },
            dshJobsHandler = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.handler.DshJobsHandler>(relaxed = true),
            dshQueueHandler = dev.leonardo.ocbeacon.data.repository.handler.DshQueueHandler(mockk(relaxed = true)),

        )
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        terminalRegistry = mockk(relaxed = true)
        settingsRepository = mockk()
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
        chatRepository = mockk(relaxed = true)

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
                autoExpandTools = false,
                expandReasoning = false,
                hapticFeedback = true,
                keepScreenOn = false,
                compressImageAttachments = true,
                imageAttachmentMaxLongSide = 1440,
                imageAttachmentWebpQuality = 60,
            )
        )

        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()
        every { messagePaging.observeMessages(any()) } returns flowOf(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** 服务器拒绝（DSH UnsupportedServerCapability 同型）→ 失败回调 + 本地 revert 态回滚。 */
    @Test
    fun `revertMessage rolls back local revert state on server rejection`() = runTest {
        coEvery { undoRedoUseCase.revertSession(any(), testSessionId, "msg-9") } throws
            RuntimeException("serverType=Dsh does not support session.revert")

        val vm = createViewModel()
        var ok = true
        vm.revertMessage("msg-9") { ok = it }

        assertFalse(ok)
        // 假成功根因：setRevert 在调用前置位，失败必须 clearRevert 回滚
        //（否则 RevertBanner「消息已还原」常驻 + 列表按边界截断 = 视觉假成功）
        verify(exactly = 1) { chatRepository.clearRevert(testSessionId) }
    }

    /** 成功路径：本地 revert 态保留（banner 由服务器语义持有），不清除。 */
    @Test
    fun `revertMessage keeps local revert state on success`() = runTest {
        coEvery { undoRedoUseCase.revertSession(any(), testSessionId, "msg-9") } returns Unit

        val vm = createViewModel()
        var ok = false
        vm.revertMessage("msg-9") { ok = it }

        assertTrue(ok)
        verify(exactly = 0) { chatRepository.clearRevert(any()) }
    }

    // #267：连接三态真源——显式 Connected 桩（relaxed 默认会产出 mock 实例，
    // != Connected → 既有发送/删除用例会被快速失败守卫误拦）
    private val sseConnectionManager = io.mockk.mockk<dev.leonardo.ocbeacon.service.SseConnectionManager>(relaxed = true).also {
        io.mockk.every { it.linkState(any()) } returns dev.leonardo.ocbeacon.service.ServerLinkState.Connected
        io.mockk.every { it.observeLinkState(any()) } returns kotlinx.coroutines.flow.flowOf(dev.leonardo.ocbeacon.service.ServerLinkState.Connected)
    }

    private fun createViewModel(sessionId: String = testSessionId): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to testServerId,
            "sessionId"  to sessionId
        ))
        return ChatViewModel(
            sseConnectionManager = sseConnectionManager,
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
            chatRepository = chatRepository.also { chatRepo ->
                coEvery { chatRepo.listActiveSessions(any()) } returns kotlin.Result.success(emptyMap())
                every { chatRepo.getParts(any()) } answers { eventDispatcher.parts.map { it[firstArg<String>()] ?: emptyList() } }
                every { chatRepo.getAllPartsMap() } returns eventDispatcher.parts
                every { chatRepo.getPermissionsSnapshot() } answers { eventDispatcher.permissions.value }
                every { chatRepo.getQuestionsSnapshot() } answers { eventDispatcher.questions.value }
                every { chatRepo.getSessionsSnapshot() } answers { eventDispatcher.sessions.value }
                every { chatRepo.getPermissionsWithChildren(any(), any()) } answers { eventDispatcher.getPermissionsWithChildren(firstArg(), secondArg()) }
                every { chatRepo.getQuestionsWithChildren(any(), any()) } answers { eventDispatcher.getQuestionsWithChildren(firstArg(), secondArg()) }
            },
            sessionRepository = mockk<SessionRepository>(relaxed = true).also { sessRepo ->
                every { sessRepo.getSessionsFlow(any()) } returns eventDispatcher.sessions
                every { sessRepo.getSessionStatusesFlow(any()) } returns eventDispatcher.sessionStatuses
                every { sessRepo.getCurrentAgentFlow(any()) } returns eventDispatcher.currentAgent
                every { sessRepo.getCurrentModelFlow(any()) } returns eventDispatcher.currentModel
            },
            messagePaging = messagePaging,
            messageStore = mockk(relaxed = true),
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),
            sessionStateRepository = sessionStateRepository,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService>(relaxed = true),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            serverRepository = serverRepository,
            shellJobsStore = ShellJobsStore(),
            eventDispatcher = mockk<EventDispatcher>(relaxed = true).also {
                io.mockk.every { it.commandsChanged } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
            },
            historySyncManager = mockk(relaxed = true),
            dshJobsStore = dev.leonardo.ocbeacon.data.repository.DshJobsStore(),
            dshQueueStore = dev.leonardo.ocbeacon.data.repository.DshQueueStore(),

        )
    }

    private fun createTestSession(
        id: String = testSessionId,
        directory: String = "/home/user/project"
    ): Session = Session(
        id = id,
        title = "Test Session",
        directory = directory,
        time = Session.Time(created = 1000L, updated = 2000L)
    )
}
