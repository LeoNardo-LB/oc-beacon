package dev.leonardo.octether.ui.screens.chat

import dev.leonardo.octether.domain.repository.ToolSnapshotCache
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.octether.data.repository.ServerTerminalRegistry
import dev.leonardo.octether.data.repository.SessionStateService
import dev.leonardo.octether.service.SessionFocusHolder
import dev.leonardo.octether.service.AppNotificationManager
import io.ktor.client.HttpClient
import dev.leonardo.octether.data.api.SseClient
import dev.leonardo.octether.domain.model.AppSettings
import dev.leonardo.octether.domain.model.ProvidersResponse
import dev.leonardo.octether.domain.repository.ChatRepository
import dev.leonardo.octether.domain.repository.SessionRepository
import dev.leonardo.octether.domain.repository.SettingsRepository
import dev.leonardo.octether.domain.usecase.*
import dev.leonardo.octether.domain.tracker.TokenStatsTracker
import dev.leonardo.octether.ui.screens.sessions.SessionScrollSignal
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
class ChatViewModelSendTest {

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
    private val pendingPromptRepository = mockk<dev.leonardo.octether.data.repository.PendingPromptRepository>(relaxed = true)

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

        // Wire messagePaging.observeMessages to return empty messages
        every { messagePaging.observeMessages(any()) } returns flowOf(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createTestSession() = dev.leonardo.octether.domain.model.Session(
        id = "test-session",
        title = "Test Session",
        directory = "/test",
        time = dev.leonardo.octether.domain.model.Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createViewModel(): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to "test-server",
            "sessionId"  to "test-session"
        ))
        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
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
            toolCardResolver = dev.leonardo.octether.ui.screens.chat.tools.DefaultToolCardResolver(),
            chatRepository = mockk<ChatRepository>(relaxed = true).also {
                every { it.getAllPartsMap() } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, List<dev.leonardo.octether.domain.model.Part>>())
            },
            sessionRepository = mockk<SessionRepository>(relaxed = true).also {
                every { it.getSessionsFlow(any()) } returns flowOf(emptyList())
                every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
            },
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
     * uiState is backed by stateIn which needs an active subscriber to emit updates.
     * Without a subscriber, uiState.value returns the initial ChatUiState().
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.uiState.collect { /* keep subscription alive */ }
        }
    }

    @Test
    fun `pendingMessageIds cleared after successful send in V1`() = runTest {
        // P5-4: sendParts clears pendingId on success path (was only cleared in catch).
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } returns Unit

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        // After successful send, pendingId is cleared (P5-4 fix)
        val state = viewModel.uiState.value
        assertTrue(
            "Pending message should be cleared after successful send, got: ${state.pendingMessageIds}",
            state.pendingMessageIds.isEmpty()
        )
        collectJob.cancel()
    }

    @Test
    fun `optimistic message removed on failure`() = runTest {
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        // Pending should be cleared after failure
        val state = viewModel.uiState.value
        assertTrue(
            "Pending message should be removed on failure, got: ${state.pendingMessageIds}",
            state.pendingMessageIds.isEmpty()
        )
        collectJob.cancel()
    }

    @Test
    fun `restoredDraft is set on send failure in V1`() = runTest {
        // V1 sendParts() catches exceptions and restores draft to _restoredDraft.
        // Mock sendMessageUseCase.sendPrompt() to throw — this is what V1 calls.
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        // V1 sets restoredDraft on send failure so the user can retry
        assertNotNull(
            "V1 should set restoredDraft on send failure",
            viewModel.uiState.value.restoredDraft
        )
        assertEquals(
            "Hello world",
            viewModel.uiState.value.restoredDraft?.text
        )
        collectJob.cancel()
    }

    @Test
    fun `consumeRestoredDraft is safe when already null`() = runTest {
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        // restoredDraft starts as null (no undo/revert happened)
        assertNull(viewModel.uiState.value.restoredDraft)

        // Calling consume should not crash and stays null
        viewModel.consumeRestoredDraft()
        advanceUntilIdle()

        assertNull(
            "restoredDraft should remain null after consume when already null",
            viewModel.uiState.value.restoredDraft
        )
        collectJob.cancel()
    }
}
