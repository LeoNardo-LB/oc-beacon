package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.data.repository.UnreadBadgeService

import android.util.Log
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.StreamingOwnershipRegistry
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionReadSignal
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
import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDeleteTest {

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
            messagePartHandler = MessagePartHandler(messageStore),
            messageUpdatedHandler = MessageUpdatedHandler(messageStore),
            messageRemovedHandler = MessageRemovedHandler(messageStore),
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            sessionStateService = sessionStateService,
            settingsDataStore = settingsDataStore,
            unreadBadgeService = UnreadBadgeService(settingsDataStore, CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore()),
            ownershipRegistry = StreamingOwnershipRegistry(),
            sessionRepoProvider = object : javax.inject.Provider<dev.leonardo.ocbeacon.domain.repository.SessionRepository> {
                override fun get() = io.mockk.mockk<dev.leonardo.ocbeacon.domain.repository.SessionRepository>(relaxed = true)
            },
        )
        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())

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

        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()

        // 将 messagePaging.observeMessages 接线为返回空消息列表
        every { messagePaging.observeMessages(any()) } returns flowOf(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `deleteMessage calls api and returns true on success`() = runTest {
        coEvery { manageSessionUseCase.deleteMessage(any(), testSessionId, "m1") } returns true

        val vm = createViewModel()
        var result = false
        vm.deleteMessage("m1") { result = it }

        assertTrue(result)
    }

    @Test
    fun `deleteMessage returns false on failure`() = runTest {
        coEvery { manageSessionUseCase.deleteMessage(any(), testSessionId, "m1") } returns false

        val vm = createViewModel()
        var result = true
        vm.deleteMessage("m1") { result = it }

        assertFalse(result)
    }

    @Test
    fun `deleteMessagePart calls api and returns true on success`() = runTest {
        coEvery { manageSessionUseCase.deleteMessagePart(any(), testSessionId, "m1", 2) } returns true

        val vm = createViewModel()
        var result = false
        vm.deleteMessagePart("m1", 2) { result = it }

        assertTrue(result)
    }

    @Test
    fun `deleteMessagePart returns false on failure`() = runTest {
        coEvery { manageSessionUseCase.deleteMessagePart(any(), testSessionId, "m1", 0) } returns false

        val vm = createViewModel()
        var result = true
        vm.deleteMessagePart("m1", 0) { result = it }

        assertFalse(result)
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
            chatRepository = mockk<ChatRepository>(relaxed = true).also { chatRepo ->
                coEvery { chatRepo.listActiveSessions(any()) } returns kotlin.Result.success(emptyMap())
                every { chatRepo.replaceMessages(any(), any()) } answers { eventDispatcher.replaceMessages(firstArg(), secondArg()) }
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

            sessionStateService = sessionStateService,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            sessionReadSignal = SessionReadSignal(),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            serverRepository = serverRepository,
            shellJobsStore = ShellJobsStore(),
            eventDispatcher = mockk(relaxed = true),
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

    // --- Task 10：onSessionUpdated 测试 ---

    @Test
    fun `onSessionUpdated refreshes messages for matching session`() = runTest {
        val messages = listOf(mockk<MessageWithParts>(relaxed = true))
        coEvery { manageSessionUseCase.listMessages(any(), testSessionId, 100) } returns messages

        val vm = createViewModel()
        vm.onSessionUpdated(createTestSession())

        coVerify { manageSessionUseCase.listMessages(any(), testSessionId, 100) }
    }

    @Test
    fun `onSessionUpdated ignores non-matching session`() = runTest {
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()

        val vm = createViewModel()
        // 对不匹配的会话不应抛异常
        vm.onSessionUpdated(createTestSession(id = "other-session"))
    }

    @Test
    fun `onSessionUpdated handles exception gracefully`() = runTest {
        coEvery { manageSessionUseCase.listMessages(any(), testSessionId, 100) } throws RuntimeException("network error")

        val vm = createViewModel()
        // 不应抛异常
        vm.onSessionUpdated(createTestSession())
    }
}
