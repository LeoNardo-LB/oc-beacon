package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionReadSignal
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    private val draftRepository: DraftRepository = mockk(relaxed = true)
    private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
    private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
    private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
    private val chatRepository: ChatRepository = mockk(relaxed = true)
    private val tokenStatsTracker = TokenStatsTracker()
    private val sessionStateService: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

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

        every { draftRepository.getDraft(any()) } returns null

        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
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
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()

        // 将 messagePaging.observeMessages 接线为返回空消息列表
        every { messagePaging.observeMessages(any()) } returns flowOf(emptyList())

        every { chatRepository.getAllPartsMap() } returns MutableStateFlow(emptyMap<String, List<dev.leonardo.ocbeacon.domain.model.Part>>())
        every { chatRepository.getMessagesFlow(any()) } returns flowOf(emptyList())
        every { chatRepository.getActiveToolProgressForSession(any()) } returns flowOf(emptyList())
        // interactionState combine 依赖这三个源发射 —— relaxed mock 的 Flow 不发射会导致
        // stateIn(WhileSubscribed) 永不产生首发射，.value 恒为初始值
        every { chatRepository.getAllQuestionsFlow() } returns flowOf(emptyMap())
        every { chatRepository.getAllPermissionsFlow() } returns flowOf(emptyMap())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createTestSession() = dev.leonardo.ocbeacon.domain.model.Session(
        id = "test-session",
        title = "Test Session",
        directory = "/test",
        time = dev.leonardo.ocbeacon.domain.model.Session.Time(created = 1000L, updated = 2000L)
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
            draftRepository = draftRepository,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            settingsRepository = settingsRepository,
            terminalRegistry = terminalRegistry,
            toolCardResolver = dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver(),
            chatRepository = chatRepository,
            sessionRepository = mockk<SessionRepository>(relaxed = true).also {
                every { it.getSessionsFlow(any()) } returns flowOf(emptyList())
                every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
            },
            messagePaging = messagePaging,
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),

            sessionStateService = sessionStateService,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            sessionReadSignal = SessionReadSignal(),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            serverRepository = serverRepository,
        )
    }

    /**
     * uiState 由 stateIn 支撑，需要活跃订阅者才能发出更新。
     * 没有订阅者时，uiState.value 返回初始 ChatUiState()。
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.uiState.collect {         /* 保持订阅存活 */ }
        }
    }

    /** interactionState 是 stateIn(WhileSubscribed)，无订阅者时 value 恒为初始值。 */
    private fun kotlinx.coroutines.test.TestScope.subscribeToInteractionState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.interactionState.collect {         /* 保持订阅存活 */ }
        }
    }

    // ========== 悲观消息语义（Task 7） ==========

    @Test
    fun `isSending flips during send and clears after REST accepted`() = runTest {
        // coAnswers + delay 模拟 POST 受理中的网络窗口：isSending 保持 true 直到响应返回
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(1_000)
        }
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        val interactionJob = subscribeToInteractionState(viewModel)
        advanceUntilIdle()
        viewModel.sendMessage("Hello world")
        runCurrent()
        assertTrue(viewModel.interactionState.value.isSending)  // 发送中（POST 受理前）
        advanceUntilIdle()
        assertFalse(viewModel.interactionState.value.isSending) // 204 后恢复（可连续发送）
        collectJob.cancel()
        interactionJob.cancel()
    }

    @Test
    fun `send failure restores draft and reports error`() = runTest {
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        val interactionJob = subscribeToInteractionState(viewModel)
        advanceUntilIdle()
        viewModel.sendMessage("Hello world")
        advanceUntilIdle()
        assertEquals("Hello world", viewModel.uiState.value.restoredDraft?.text) // 草稿恢复
        assertNotNull(viewModel.interactionState.value.error) // 错误提示（snackbar 源）
        assertFalse(viewModel.interactionState.value.isSending) // finally 复位
        collectJob.cancel()
        interactionJob.cancel()
    }

    @Test
    fun `double send is ignored while sending`() = runTest {
        // sendPrompt 挂起期间 isSending=true，第二次 sendMessage 应被 isSendingValue 守卫拦截
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(1_000)
        }
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()
        viewModel.sendMessage("first")
        viewModel.sendMessage("second") // isSending 期间应被忽略
        advanceUntilIdle()
        coVerify(exactly = 1) { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) }
        collectJob.cancel()
    }

    // ========== 保留：草稿恢复与消费（悲观语义下） ==========

    @Test
    fun `restoredDraft is set on send failure in V1`() = runTest {
        // V1 sendParts() 捕获异常并将草稿恢复到 _restoredDraft。
        // 让 sendMessageUseCase.sendPrompt() 抛异常 —— 这正是 V1 调用的方法。
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        val interactionJob = subscribeToInteractionState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        // V1 在发送失败时设置 restoredDraft，以便用户重试，并上报错误
        assertNotNull(
            "V1 should set restoredDraft on send failure",
            viewModel.uiState.value.restoredDraft
        )
        assertEquals(
            "Hello world",
            viewModel.uiState.value.restoredDraft?.text
        )
        assertNotNull(viewModel.interactionState.value.error)
        collectJob.cancel()
        interactionJob.cancel()
    }

    @Test
    fun `consumeRestoredDraft is safe when already null`() = runTest {
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        // restoredDraft 初始为 null（未发生撤销/还原）
        assertNull(viewModel.uiState.value.restoredDraft)

        // 调用 consume 不应崩溃，且保持为 null
        viewModel.consumeRestoredDraft()
        advanceUntilIdle()

        assertNull(
            "restoredDraft should remain null after consume when already null",
            viewModel.uiState.value.restoredDraft
        )
        collectJob.cancel()
    }
}
