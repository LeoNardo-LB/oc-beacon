package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.data.api.SseClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStreamingTest {

    private val terminalRegistry: ServerTerminalRegistry = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    private val sendMessageUseCase: SendMessageUseCase = mockk()
    private val manageSessionUseCase: ManageSessionUseCase = mockk(relaxed = true)
    private val managePermissionUseCase: ManagePermissionUseCase = mockk(relaxed = true)
    private val selectModelUseCase: SelectModelUseCase = mockk(relaxed = true)
    private val manageAgentUseCase: ManageAgentUseCase = mockk(relaxed = true)
    private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
    private val draftUseCase: DraftUseCase = mockk(relaxed = true)
    private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
    private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
    private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
    private val tokenStatsTracker = TokenStatsTracker()
    private val sessionStateService: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val pendingPromptRepository = mockk<dev.leonardo.ocbeacon.data.repository.PendingPromptRepository>(relaxed = true)

    private val messagesFlow = MutableStateFlow<List<Message>>(emptyList())
    private val partsFlow = MutableStateFlow<Map<String, List<dev.leonardo.ocbeacon.domain.model.Part>>>(emptyMap())
    private lateinit var chatRepository: ChatRepository
    private lateinit var sessionRepository: SessionRepository

    @After
    fun tearDown() {
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        every { draftUseCase.getDraft(any()) } returns null

        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
        every { settingsRepository.getSettingsFlow() } returns flowOf(
            AppSettings(
                terminalFontSize = 13f,
                initialMessageCount = 50,
                chatFontSize = "medium",
                codeWordWrap = false,
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
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()

        // Wire messagePaging.observeMessages to our controllable flow
        every { messagePaging.observeMessages(any()) } returns messagesFlow

        chatRepository = mockk<ChatRepository>(relaxed = true).also {
            every { it.getMessagesFlow(any()) } returns messagesFlow
            every { it.getParts(any()) } answers {
                val sid = firstArg<String>()
                partsFlow.map { map: Map<String, List<dev.leonardo.ocbeacon.domain.model.Part>> ->
                    map[sid] ?: emptyList()
                }
            }
            every { it.getAllPartsMap() } returns partsFlow
            every { it.getActiveToolProgressForSession(any()) } returns flowOf(emptyList())
            every { it.setMessages(any(), any()) } answers {
                val sid = firstArg<String>()
                val msgs = secondArg<List<dev.leonardo.ocbeacon.domain.model.MessageWithParts>>()
                messagesFlow.value = msgs.map { m -> m.info }
                partsFlow.value = partsFlow.value + (sid to msgs.flatMap { m -> m.parts })
            }
            every { it.replaceMessages(any(), any()) } answers {
                val sid = firstArg<String>()
                val msgs = secondArg<List<dev.leonardo.ocbeacon.domain.model.MessageWithParts>>()
                messagesFlow.value = msgs.map { m -> m.info }
                partsFlow.value = partsFlow.value + (sid to msgs.flatMap { m -> m.parts })
            }
            every { it.getSessionsSnapshot() } returns emptyList()
            every { it.getPermissionsWithChildren(any(), any()) } returns emptyList()
            every { it.getQuestionsWithChildren(any(), any()) } returns emptyList()
        }

        sessionRepository = mockk<SessionRepository>(relaxed = true).also {
            every { it.getSessionsFlow(any()) } returns flowOf(listOf(createTestSession()))
            every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
            every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
            every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
            coEvery { it.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        }
        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createTestSession() = Session(
        id = "test-session",
        title = "Test Session",
        directory = "/test",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createTestUserMessage(id: String = "msg-1") = Message.User(
        id = id,
        sessionId = "test-session",
        role = "user",
        time = TimeInfo(created = System.currentTimeMillis())
    )

    /** Stub listMessages with a user message that has a text part (survives V1→V2 bridge). */
    private fun stubUserMessage(id: String = "msg-1") {
        val userMsg = createTestUserMessage(id)
        val textPart = dev.leonardo.ocbeacon.domain.model.Part.Text(
            id = "$id-text",
            sessionId = "test-session",
            messageId = id,
            text = "hello"
        )
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns listOf(
            dev.leonardo.ocbeacon.domain.model.MessageWithParts(info = userMsg, parts = listOf(textPart))
        )
    }

    private fun createViewModel(): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to "test-server",
            "sessionId"  to "test-session"
        ))
        return ChatViewModel(
            savedStateHandle = savedState,
            sendMessageUseCase = sendMessageUseCase,
            manageSessionUseCase = manageSessionUseCase,
            managePermissionUseCase = managePermissionUseCase,
            selectModelUseCase = selectModelUseCase,
            manageAgentUseCase = manageAgentUseCase,
            manageTerminalUseCase = manageTerminalUseCase,
            draftUseCase = draftUseCase,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            settingsRepository = settingsRepository,
            terminalRegistry = terminalRegistry,
            toolCardResolver = DefaultToolCardResolver(),
            chatRepository = chatRepository,
            sessionRepository = sessionRepository,
            messagePaging = messagePaging,
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),
            sseClient = mockk(relaxed = true),
            sessionStateService = sessionStateService,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            pendingPromptRepository = pendingPromptRepository
        )
    }

    /**
     * messageListState is backed by stateIn(WhileSubscribed) and needs an active subscriber.
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToMessageState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.messageListState.collect { /* keep subscription alive */ }
        }
    }

    // ========== Test 1: refreshSession does not set isLoading to true ==========

    @Test
    fun `refreshSession does not set isLoading to true`() = runTest {
        // Given: ViewModel with existing messages (via V1→V2 bridge)
        stubUserMessage("msg-1")
        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // Verify messages are present before refresh
        val beforeRefresh = vm.messageListState.value.messages
        assertTrue("Messages should exist before refresh", beforeRefresh.isNotEmpty())

        // When: refreshSession is called
        vm.refreshSession()
        advanceUntilIdle()

        // Then: messages should NOT be wiped (because refreshSession uses _isRefreshing, not _isLoading)
        val afterRefresh = vm.messageListState.value.messages
        assertTrue(
            "Messages should NOT be wiped during refresh, got ${afterRefresh.size} messages",
            afterRefresh.isNotEmpty()
        )

        collectJob.cancel()
    }

    // ========== Test 2: V1 setMessages replaces state on refresh ==========

    @Test
    fun `messageListState matches refresh result in V1`() = runTest {
        // Given: existing messages via initial load
        stubUserMessage("msg-1")

        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // Verify initial messages exist
        assertTrue(
            "Initial messages should exist",
            vm.messageListState.value.messages.isNotEmpty()
        )

        // When: REST refresh returns empty messages (e.g. server lag)
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        vm.refreshSession()
        advanceUntilIdle()

        // Then: V1 setMessages does full replace — messages are cleared
        val state = vm.messageListState.value
        assertTrue(
            "V1 setMessages replaces state, got ${state.messages.size} messages",
            state.messages.isEmpty()
        )

        collectJob.cancel()
    }

    // ========== Test 3: V1 refreshIfNeeded always triggers refresh ==========

    @Test
    fun `refreshIfNeeded triggers refresh in V1`() = runTest {
        // Given: ViewModel
        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // Clear mock state after init (init calls loadMessages → listMessages once)
        coVerify(atLeast = 1) { manageSessionUseCase.listMessages(any(), any(), any()) }
        clearMocks(manageSessionUseCase, answers = false)

        // When: refreshIfNeeded is called (V1 has no cooldown — always refreshes)
        vm.refreshIfNeeded()
        advanceUntilIdle()

        // Then: listMessages should be called (V1 delegates to refreshSession)
        coVerify(atLeast = 1) { manageSessionUseCase.listMessages(any(), any(), any()) }

        collectJob.cancel()
    }

    // ========== Test 4: loading guard only clears truly empty message lists ==========

    @Test
    fun `loading guard only clears truly empty message lists`() = runTest {
        // Given: exactly 1 message with text part (survives V1→V2 bridge)
        val userMsg = createTestUserMessage("msg-1")
        val textPart = dev.leonardo.ocbeacon.domain.model.Part.Text(
            id = "msg-1-text",
            sessionId = "test-session",
            messageId = "msg-1",
            text = "hello"
        )
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns listOf(
            dev.leonardo.ocbeacon.domain.model.MessageWithParts(info = userMsg, parts = listOf(textPart))
        )

        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // Then: messages should NOT be cleared despite size < 3
        // The loading guard uses: loading && sessionMessages.isEmpty()
        // With 1 message, sessionMessages is NOT empty, so messages are preserved
        val state = vm.messageListState.value
        assertTrue(
            "Messages should not be cleared when list has 1 message (isEmpty check, not size < 3), got ${state.messages.size}",
            state.messages.isNotEmpty()
        )

        collectJob.cancel()
    }

    // ========== Test 5: loadSession applies initialMessageCount from settings ==========

    @Test
    fun `loadSession applies initialMessageCount from settings as listMessages limit`() = runTest {
        // Given: settings mock returns initialMessageCount = 50 (see setup)
        val vm = createViewModel()
        advanceUntilIdle()

        // Then: listMessages must be called with limit = 50 (from AppSettings.initialMessageCount).
        // Before this fix, loadSession hardcoded limit=200 and loadMessages used currentMessageLimit=20,
        // so the user's "initial message count" setting was never consumed.
        coVerify(atLeast = 1) { manageSessionUseCase.listMessages(any(), any(), eq(50)) }
    }
}
